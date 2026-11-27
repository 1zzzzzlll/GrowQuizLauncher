package dev.plume.quiz

import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.plume.DesktopExit
import dev.plume.MainActivity
import dev.plume.voice.Voice

/**
 * Opaque reminder modal shown when a parent-disabled app is launched or detected running.
 *
 * Uses an opaque window on purpose (unlike [QuizActivity]'s translucent theme): covering the
 * blocked app with an opaque activity immediately transitions that app through `onPause` -> `onStop`,
 * tearing down its video surface or game loop. Pressing **确定** dismisses the reminder and routes
 * cleanly to the home screen (never uncovering a blocked app that was running underneath).
 */
class BlockedAppActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())

    private var uiScale = 1f
    private var blockedPkg: String = ""
    private var blockedLabel: String = ""
    private var mustExitToHome = false
    private var dismissed = false
    private var ttsRetries = 0
    private var focusRequest: AudioFocusRequest? = null

    private lateinit var iconView: ImageView
    private lateinit var badgeView: TextView
    private lateinit var titleView: TextView
    private lateinit var messageView: TextView
    private lateinit var hintView: TextView
    private lateinit var confirmButton: Button

    private val speakRetryLoop = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed || dismissed) return
            val interactive = getSystemService(PowerManager::class.java)?.isInteractive ?: true
            if (!interactive || !isForeground) return
            if (Voice.isUnusable()) return
            if (!Voice.isReady()) {
                if (ttsRetries++ < TTS_RETRY_LIMIT) ui.postDelayed(this, TTS_RETRY_MS)
                return
            }
            val speech = AppBlockRules.speechMessage(blockedLabel, Voice.isBilingual())
            Voice.speak(speech, flush = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            ) { blockBack() }
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(createContent())
        immersive()
        applyIntent(intent, firstOpen = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        expectedIntentAt = 0L
        applyIntent(intent, firstOpen = false)
    }

    private fun applyIntent(src: Intent?, firstOpen: Boolean) {
        val newPkg = src?.getStringExtra(EXTRA_PKG).orEmpty().trim()
        val newLabel = src?.getStringExtra(EXTRA_LABEL).orEmpty().trim()
            .ifEmpty { if (newPkg.isNotEmpty()) AppBlock.resolveLabel(this, newPkg) else "" }
        val wasRunning = src?.getBooleanExtra(EXTRA_WAS_RUNNING, false) == true
        mustExitToHome = mustExitToHome || wasRunning

        val pkgChanged = firstOpen || newPkg != blockedPkg
        blockedPkg = newPkg
        blockedLabel = newLabel
        activePkg = newPkg
        dismissed = false

        getSystemService(NotificationManager::class.java)?.cancel(AppBlock.NOTIFICATION_ID)
        holdSoundAndStopMedia()
        Voice.warm(this)
        bindAppVisuals()

        confirmButton.requestFocus()
        ui.post { if (!isFinishing && !isDestroyed) confirmButton.requestFocus() }

        if (pkgChanged) {
            startVoiceReminder()
        }
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        launchInFlight = false
        activePkg = blockedPkg
        expectedIntentAt = 0L
        confirmButton.requestFocus()
        ui.post { if (!isFinishing && !isDestroyed) confirmButton.requestFocus() }
    }

    override fun onPause() {
        super.onPause()
        isForeground = false
        val now = SystemClock.elapsedRealtime()
        if (QuizLock.isExpectedSelfPause(expectedIntentAt, now)) return
        if (dismissed || isFinishing) {
            ui.removeCallbacks(speakRetryLoop)
            Voice.stop()
            return
        }
        // A blocked app's splash screen can spawn its main activity right over this reminder:
        // pull the reminder back to the front if a blocked app is what covered us.
        ui.postDelayed({
            if (!dismissed && !isFinishing && !isDestroyed && !isForeground) {
                AppBlock.enforceIfForeground(this@BlockedAppActivity)
            }
        }, 200L)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            immersive()
            confirmButton.requestFocus()
        }
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        if (isForeground) isForeground = false
        if (activePkg == blockedPkg) activePkg = null
        Voice.stop()
        releaseSoundFocus()
        super.onDestroy()
    }

    private fun createContent(): View {
        val dm = resources.displayMetrics
        val minDimDp = minOf(dm.widthPixels, dm.heightPixels) / dm.density
        uiScale = (minDimDp / 720f).coerceIn(0.62f, 1f)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(8, 16, 31))
            isFocusable = false
        }

        val cardMaxWidth = minOf((dm.widthPixels * 0.86f).toInt(), dp(600))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(40), dp(34), dp(40), dp(32))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.rgb(20, 35, 63))
                setStroke(dp(2), Color.rgb(66, 98, 148))
            }
            elevation = dp(16).toFloat()
        }

        val iconWrap = FrameLayout(this)
        iconView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        badgeView = TextView(this).apply {
            text = "🚫"
            textSize = 46f * uiScale
            gravity = Gravity.CENTER
        }
        iconWrap.addView(
            iconView,
            FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER),
        )
        iconWrap.addView(
            badgeView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )

        titleView = TextView(this).apply {
            text = AppBlockRules.dialogTitle()
            textSize = 30f * uiScale
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }

        messageView = TextView(this).apply {
            textSize = 20f * uiScale
            setTextColor(Color.rgb(220, 232, 250))
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
        }

        hintView = TextView(this).apply {
            text = DEFAULT_HINT
            textSize = 15f * uiScale
            setTextColor(Color.rgb(159, 179, 214))
            gravity = Gravity.CENTER
        }

        confirmButton = Button(this).apply {
            text = "确定"
            textSize = 21f * uiScale
            isAllCaps = false
            isFocusable = true
            isFocusableInTouchMode = true
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(26, 35, 50))
            minHeight = dp(58)
            background = confirmBackground()
            setOnClickListener { confirmAndClose() }
            setOnFocusChangeListener { v, focused ->
                v.animate()
                    .scaleX(if (focused) 1.03f else 1f)
                    .scaleY(if (focused) 1.03f else 1f)
                    .setDuration(120)
                    .start()
            }
        }

        card.addView(iconWrap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(
            titleView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(14)
            },
        )
        card.addView(
            messageView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(14)
            },
        )
        card.addView(
            hintView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            },
        )
        card.addView(
            confirmButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(26)
            },
        )

        root.addView(
            card,
            FrameLayout.LayoutParams(cardMaxWidth, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        return root
    }

    private fun bindAppVisuals() {
        titleView.text = AppBlockRules.dialogTitle()
        messageView.text = AppBlockRules.dialogMessage(blockedLabel)
        hintView.text = DEFAULT_HINT
        hintView.setTextColor(Color.rgb(159, 179, 214))

        val icon = if (blockedPkg.isNotEmpty()) {
            runCatching { packageManager.getApplicationIcon(blockedPkg) }.getOrNull()
        } else {
            null
        }
        if (icon != null) {
            iconView.setImageDrawable(icon)
            iconView.visibility = View.VISIBLE
            badgeView.textSize = 22f * uiScale
            (badgeView.layoutParams as? FrameLayout.LayoutParams)?.apply {
                gravity = Gravity.BOTTOM or Gravity.END
            }
        } else {
            iconView.setImageDrawable(null)
            iconView.visibility = View.GONE
            badgeView.textSize = 46f * uiScale
            (badgeView.layoutParams as? FrameLayout.LayoutParams)?.apply {
                gravity = Gravity.CENTER
            }
        }
    }

    private fun startVoiceReminder() {
        ui.removeCallbacks(speakRetryLoop)
        ttsRetries = 0
        speakRetryLoop.run()
    }

    private fun confirmAndClose() {
        if (dismissed) return
        dismissed = true
        ui.removeCallbacksAndMessages(null)
        Voice.stop()
        releaseSoundFocus()
        getSystemService(NotificationManager::class.java)?.cancel(AppBlock.NOTIFICATION_ID)

        val prefs = QuizPrefs(this)
        val quizOwed = QuizLock.sessionActive(
            quizActive = prefs.quizActive,
            restGuard = prefs.restGuard,
            restUntil = prefs.restUntil,
            now = System.currentTimeMillis(),
            restForced = prefs.restForced,
        )
        when {
            quizOwed -> {
                QuizActivity.markExpectedIntent()
                runCatching {
                    startActivity(
                        Intent(this, QuizActivity::class.java).addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                        ),
                    )
                }
            }
            mustExitToHome -> {
                justClosed = true
                if (!DesktopExit.exited(this) || !DesktopExit.forward(this)) {
                    runCatching {
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .setAction(Intent.ACTION_MAIN)
                                .addCategory(Intent.CATEGORY_HOME)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }
        }
        activePkg = null
        finish()
    }

    private fun blockBack() {
        hintView.text = "请点击「确定」关闭提醒弹窗"
        hintView.setTextColor(Color.rgb(255, 209, 102))
        confirmButton.requestFocus()
    }

    @Deprecated("Deprecated in Java")
    @android.annotation.SuppressLint("GestureBackNavigation")
    override fun onBackPressed() {
        blockBack()
    }

    private fun holdSoundAndStopMedia() {
        val am = getSystemService(AudioManager::class.java) ?: return
        if (focusRequest == null) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .build()
        }
        runCatching {
            am.requestAudioFocus(focusRequest!!)
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_STOP))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_STOP))
        }
    }

    private fun releaseSoundFocus() {
        val am = getSystemService(AudioManager::class.java) ?: return
        focusRequest?.let { runCatching { am.abandonAudioFocusRequest(it) } }
    }

    private fun confirmBackground() = StateListDrawable().apply {
        addState(
            intArrayOf(android.R.attr.state_focused),
            GradientDrawable().apply {
                setColor(Color.rgb(255, 221, 128))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(3), Color.WHITE)
            },
        )
        addState(
            intArrayOf(),
            GradientDrawable().apply {
                setColor(Color.rgb(255, 209, 102))
                cornerRadius = dp(16).toFloat()
            },
        )
    }

    private fun immersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            @Suppress("DEPRECATION")
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density * uiScale).toInt()

    companion object {
        const val EXTRA_PKG = "pkg"
        const val EXTRA_LABEL = "label"
        const val EXTRA_WAS_RUNNING = "was_running"

        private const val DEFAULT_HINT = "如需恢复使用，请由家长在手机管理后台解除禁用"
        private const val TTS_RETRY_MS = 250L
        private const val TTS_RETRY_LIMIT = 60

        @Volatile var isForeground = false
        @Volatile var launchInFlight = false
        @Volatile var activePkg: String? = null
        @Volatile var justClosed = false
        @Volatile private var expectedIntentAt = 0L

        fun markExpectedIntent() {
            expectedIntentAt = SystemClock.elapsedRealtime()
        }
    }
}

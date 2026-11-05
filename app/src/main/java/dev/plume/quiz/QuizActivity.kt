package dev.plume.quiz

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.plume.Qr

/** Full-screen, remote-first quiz. A wrong answer advances to a different question. */
class QuizActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var questionText: TextView
    private lateinit var progressText: TextView
    private lateinit var feedbackText: TextView
    private lateinit var answerButtons: List<Button>
    private var bank: QuestionBank? = null
    private var current: Question? = null
    private var solved = false
    private var changing = false
    private var lockActive = false
    private lateinit var answerGrid: GridLayout
    private var restPanel: LinearLayout? = null // admin address + QR, visible only while resting

    // GrowQuiz: the growth badge (rank, stage, points) sits above the question and updates after every answer.
    private var ledger: QuizLedger? = null
    private lateinit var rootLayout: FrameLayout
    private lateinit var levelBadge: TextView
    private lateinit var levelScore: TextView

    // GrowQuiz: answers cannot be blurted. The buttons respond only after MIN_THINK_MS of reading time.
    private var thinkReadyAt = 0L
    private var thinkLoop: Runnable? = null

    // GrowQuiz: too many wrong answers in a row mean a forced rest, so blind guessing never pays off.
    private var restLoop: Runnable? = null

    // GrowQuiz: while a question or a rest is up, a watchdog brings the quiz back to the front every
    // second if anything else stole it (Netflix-style remote keys, notifications, other apps).
    private var inForeground = false
    private val guardLoop: Runnable = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed || solved) return
            val interactive = getSystemService(android.os.PowerManager::class.java)?.isInteractive ?: true
            if (!inForeground && interactive) {
                try {
                    startActivity(
                        Intent(this@QuizActivity, QuizActivity::class.java).addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                        ),
                    )
                } catch (e: Exception) {
                    android.util.Log.w("GrowQuiz", "guard relaunch failed", e)
                }
            }
            ui.postDelayed(this, GUARD_POLL_MS)
        }
    }

    // GrowQuiz: the question is read aloud for children who cannot read yet, then repeated every 20 seconds.
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsNoted = false // the "no TTS engine" toast shows once
    private val speakLoop: Runnable = object : Runnable {
        override fun run() {
            speakQuestion()
            ui.postDelayed(this, READ_REPEAT_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(createContent())
        immersive()
        ledger = QuizLedger(applicationContext)
        refreshLevel()
        if (QuizDeviceOwner.configure(this)) {
            startLockTask()
            lockActive = true
        }
        Thread {
            val loaded = QuestionBank(applicationContext)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                bank = loaded
                showNextQuestion()
            }
        }.start()
        tts = TextToSpeech(applicationContext) { status ->
            val engine = tts
            if (status != TextToSpeech.SUCCESS || engine == null) {
                android.util.Log.w("GrowQuiz", "tts init failed: $status")
                runOnUiThread { ttsMissing() }
                return@TextToSpeech
            }
            // zh-CN first, then plain Chinese, then the device's own locale: an engine that only has
            // the latter still beats silence, and some engines miss the Chinese data until installed.
            val ok = listOf(Locale.SIMPLIFIED_CHINESE, Locale.CHINESE, Locale.getDefault())
                .any { l -> engine.setLanguage(l) >= TextToSpeech.LANG_AVAILABLE }
            ttsReady = ok
            android.util.Log.i("GrowQuiz", "tts ready=$ok lang=${engine.language}")
            if (!ok) runOnUiThread { ttsMissing() }
            // The bank may already be showing a question by the time the engine is up.
            runOnUiThread { if (current != null && !solved && !isFinishing) startReading() }
        }
    }

    /** No TTS engine (or no usable language) on this device: say so once, instead of reading nothing in silence. */
    private fun ttsMissing() {
        if (isFinishing || ttsNoted) return
        if (!QuizPrefs(this).voice) return // narration is off anyway: no engine toast would only confuse
        ttsNoted = true
        Toast.makeText(this, "本机没有可用的语音（TTS）引擎，无法朗读题目", Toast.LENGTH_LONG).show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (solved) {
            solved = false
            changing = false
            bank?.let { showNextQuestion() }
        }
    }

    override fun onResume() {
        super.onResume()
        inForeground = true
        ui.removeCallbacks(guardLoop) // one watchdog, started here, re-posted by itself
        ui.postDelayed(guardLoop, GUARD_POLL_MS)
    }

    override fun onPause() {
        super.onPause()
        inForeground = false
    }

    private fun createContent(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(16, 42, 67)) }
        rootLayout = root
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(52), dp(28), dp(52), dp(24))
        }
        val levelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(6))
        }
        levelBadge = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.rgb(16, 42, 67))
            setPadding(dp(16), dp(5), dp(16), dp(5))
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat() }
        }
        levelScore = text("积分 --", 15f, Color.rgb(184, 199, 217))
        levelRow.addView(levelBadge)
        levelRow.addView(levelScore, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(12)
        })
        progressText = text("成长问答 · 正在准备题目", 18f, Color.rgb(255, 209, 102)).apply {
            gravity = Gravity.CENTER
        }
        questionText = text("请稍候…", 32f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            minHeight = dp(118)
            maxLines = 4
        }
        feedbackText = text("用遥控器方向键选择，按确认键作答", 18f, Color.rgb(184, 199, 217)).apply {
            gravity = Gravity.CENTER
            minHeight = dp(48)
        }
        answerGrid = GridLayout(this).apply {
            columnCount = 2
            rowCount = 2
        }
        answerButtons = List(4) { index ->
            Button(this).apply {
                textSize = 21f
                setTextColor(Color.rgb(16, 42, 67))
                isAllCaps = false
                gravity = Gravity.CENTER
                minHeight = dp(88)
                background = answerBackground()
                setOnClickListener { answer(index) }
                setOnFocusChangeListener { view, focused ->
                    view.animate().scaleX(if (focused) 1.025f else 1f).scaleY(if (focused) 1.025f else 1f).setDuration(100).start()
                }
                answerGrid.addView(this, GridLayout.LayoutParams().apply {
                    width = 0
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(dp(10), dp(10), dp(10), dp(10))
                })
            }
        }
        // GrowQuiz: while the child rests, the parents see where to go to lift the rest early.
        restPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            addView(text("家长打开手机管理后台，可提前解除休息：", 16f, Color.rgb(184, 199, 217)))
            val adminUrl = dev.plume.web.WebAdminServer.url()
            if (dev.plume.web.WebAdminServer.hasLanUrl()) {
                addView(ImageView(this@QuizActivity).apply {
                    try { setImageBitmap(Qr.bitmap(adminUrl, dp(140))) } catch (e: Exception) {}
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(10)
                })
            }
            addView(text(adminUrl, 18f, Color.rgb(255, 209, 102)).apply {
                setPadding(0, dp(10), 0, 0)
            })
        }
        content.addView(levelRow)
        content.addView(progressText)
        content.addView(questionText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.85f))
        content.addView(restPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(answerGrid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.35f))
        content.addView(feedbackText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return root
    }

    private fun showNextQuestion() {
        if (restRemaining() > 0L) {
            showRestScreen()
            return
        }
        val quiz = bank ?: return
        changing = false
        answerGrid.visibility = View.VISIBLE
        restPanel?.visibility = View.GONE
        val prefs = QuizPrefs(this)
        val next = quiz.next(prefs.age, prefs.categories, current?.id)
        current = next
        val tag = if (prefs.verifyPending) "验证题 · " else ""
        progressText.text = "$tag${next.age} 岁 · ${next.category} · 答对后即可返回"
        questionText.text = next.prompt
        feedbackText.setTextColor(Color.rgb(184, 199, 217))
        answerButtons.forEachIndexed { index, button ->
            button.text = "${'A' + index}  ${next.options[index]}"
            button.isEnabled = true
            button.backgroundTintList = null
        }
        answerButtons.first().requestFocus()
        startThinkWindow()
        startReading()
    }

    /**
     * The buttons stay focusable for the remote, but [answer] ignores presses until the configured
     * think time has passed. A countdown in the feedback line tells the child why.
     */
    private fun startThinkWindow() {
        thinkLoop?.let { ui.removeCallbacks(it) }
        val seconds = QuizPrefs(this).thinkSeconds
        thinkReadyAt = System.currentTimeMillis() + seconds * 1_000L
        if (seconds <= 0) {
            feedbackText.text = "请选择正确答案"
            return
        }
        val loop = object : Runnable {
            override fun run() {
                if (isFinishing || solved) return
                val left = thinkReadyAt - System.currentTimeMillis()
                if (left <= 0L) {
                    if (!changing) feedbackText.text = "请选择正确答案"
                    return
                }
                feedbackText.text = "请认真听题和思考，${(left + 999) / 1000} 秒后可以作答"
                ui.postDelayed(this, 500L)
            }
        }
        thinkLoop = loop
        loop.run()
    }

    /** Milliseconds of enforced rest still owed; 0 when none. Side effect: clears an expired deadline. */
    private fun restRemaining(): Long {
        val prefs = QuizPrefs(this)
        if (!prefs.restGuard) return 0L // the parents switched the enforced rest off: never gate on it
        val until = prefs.restUntil
        if (until <= 0L) return 0L
        val left = until - System.currentTimeMillis()
        if (left <= 0L) {
            prefs.restUntil = 0L
            return 0L
        }
        return left
    }

    /** Full-screen rest: no question, no buttons, a countdown, and no way out except the parents' console. */
    private fun showRestScreen() {
        changing = true
        stopReading()
        thinkLoop?.let { ui.removeCallbacks(it) }
        answerGrid.visibility = View.GONE
        restPanel?.visibility = View.VISIBLE
        progressText.text = "休息一下"
        if (QuizPrefs(this).voice && ttsReady) {
            try { tts?.speak("连续答错啦，休息一会再来答题", TextToSpeech.QUEUE_FLUSH, null, "quiz_rest") } catch (e: Exception) {}
        }
        val loop = object : Runnable {
            override fun run() {
                val left = restRemaining()
                if (left <= 0L) {
                    if (!isFinishing) showNextQuestion()
                    return
                }
                val totalSec = (left + 999) / 1000
                questionText.text = "连续答错啦，休息 ${totalSec / 60} 分 ${totalSec % 60} 秒后再答题"
                feedbackText.text = "休息期间不能作答，休息结束自动回到答题"
                feedbackText.setTextColor(Color.rgb(184, 199, 217))
                ui.postDelayed(this, 1_000L)
            }
        }
        restLoop = loop
        loop.run()
    }

    /** Reads the current question and its options aloud, then keeps repeating every 20 seconds. */
    private fun startReading() {
        stopReading()
        if (!QuizPrefs(this).voice) return // the parents turned narration off in the web console
        speakLoop.run()
    }

    private fun stopReading() {
        ui.removeCallbacks(speakLoop)
        try { tts?.stop() } catch (e: Exception) {}
    }

    private fun speakQuestion() {
        val q = current ?: return
        val engine = tts ?: return
        if (!ttsReady) return
        val text = buildString {
            append("题目：").append(q.prompt)
            q.options.forEachIndexed { i, option -> append("。选项").append('A' + i).append("：").append(option) }
        }
        try { engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "quiz_question") } catch (e: Exception) {}
    }

    private fun answer(index: Int) {
        val question = current ?: return
        if (changing || solved) return
        val now = System.currentTimeMillis()
        if (now < thinkReadyAt) {
            // The think window is still closed: no penalty, just a nudge to slow down.
            feedbackText.text = "再想一想，${(thinkReadyAt - now + 999) / 1000} 秒后可以作答"
            feedbackText.setTextColor(Color.rgb(255, 209, 102))
            return
        }
        changing = true
        stopReading() // the child answered; the next question (or the exit) restarts narration as needed
        thinkLoop?.let { ui.removeCallbacks(it) }
        answerButtons.forEach { it.isEnabled = false }
        val prefs = QuizPrefs(this)
        val snap = QuizRules.isSnapAnswer(now, thinkReadyAt)
        val correct = QuizRules.isCorrect(question, index)
        // Every graded answer goes into the growth ledger: score, totals, and the per-question wrong memory.
        val recorded = ledger?.record(question, correct, snap, nowMs = now)
        if (recorded != null) refreshLevel(recorded.score)
        if (correct) {
            bank?.registerCorrect(question.id) // mastered questions retire themselves after enough correct answers
            val stageUp = recorded != null && recorded.tierAfter > recorded.tierBefore
            if (stageUp) celebrateLevelUp(recorded)
            if (!prefs.verifyPending && QuizRules.needsVerification(prefs.snapWrongs, snap)) {
                // Two snap wrongs then a snap correct smells like a lucky guess: prove it with one more question.
                prefs.verifyPending = true
                answerButtons[index].backgroundTintList = ColorStateList.valueOf(Color.rgb(46, 158, 107))
                feedbackText.text = "答得真快！再来一道题验证一下"
                feedbackText.setTextColor(Color.rgb(255, 209, 102))
                ui.postDelayed({ if (!isFinishing) { changing = false; showNextQuestion() } }, 1_300L)
                return
            }
            solved = true
            answerButtons[index].backgroundTintList = ColorStateList.valueOf(Color.rgb(46, 158, 107))
            feedbackText.text = "回答正确！${question.explanation}"
            feedbackText.setTextColor(Color.rgb(160, 235, 190))
            // A stage-up celebration needs the screen: hold it a little longer than the usual farewell.
            ui.postDelayed({ finishAfterCorrect() }, if (stageUp) 3_200L else 1_300L)
        } else {
            if (prefs.verifyPending) {
                // Failed the verification: the snap correct answer was a guess after all. Count it as 3 in a row.
                prefs.verifyPending = false
                prefs.wrongStreak = QuizRules.REST_STREAK_START
                prefs.snapWrongs = 0
            } else {
                prefs.wrongStreak = prefs.wrongStreak + 1
                prefs.snapWrongs = if (snap) prefs.snapWrongs + 1 else 0
            }
            answerButtons[index].backgroundTintList = ColorStateList.valueOf(Color.rgb(217, 83, 79))
            val restMinutes = if (prefs.restGuard) QuizRules.restMinutes(prefs.wrongStreak) else 0
            if (restMinutes > 0) {
                prefs.restUntil = now + restMinutes * 60_000L
                feedbackText.text = "连续答错 ${prefs.wrongStreak} 题，休息 $restMinutes 分钟后再来"
            } else {
                feedbackText.text = "这题不对，换一题再试试"
            }
            feedbackText.setTextColor(Color.rgb(255, 180, 170))
            ui.postDelayed({ if (!isFinishing) showNextQuestion() }, if (restMinutes > 0) 2_000L else 750L)
        }
    }

    private fun finishAfterCorrect() {
        justFinished = true // MainActivity skips the admin prompt right after a quiz
        QuizPrefs(this).apply {
            wrongStreak = 0 // a finished session proves real work: every anti-guess counter resets
            snapWrongs = 0
            verifyPending = false
        }
        if (lockActive) {
            stopLockTask()
            lockActive = false
        }
        finish()
    }

    /** Puts the current rank, stage and points on the badge; the badge color follows the rank. */
    private fun refreshLevel(score: Int = ledger?.score() ?: 0) {
        val tier = QuizRules.tierIndex(score)
        val rank = tier / QuizRules.STAGES_PER_RANK
        (levelBadge.background as? GradientDrawable)?.setColor(QuizRules.RANK_COLORS[rank])
        levelBadge.text = "${QuizRules.RANK_ICONS[rank]} ${QuizRules.tierName(tier)}"
        levelScore.text = if (tier >= QuizRules.TIER_COUNT - 1) {
            "积分 $score · 已是最高等级"
        } else {
            val next = tier + 1
            "积分 $score · 距${QuizRules.tierName(next)}还差 ${QuizRules.tierFloor(next) - score} 分"
        }
    }

    /**
     * A stage-up earns a full-screen party: the rank icon bounces in, stars burst outward, the new
     * stage name shines in the rank color, and the voice engine congratulates the child.
     */
    private fun celebrateLevelUp(result: QuizLedger.Result) {
        val tier = result.tierAfter
        val rank = tier / QuizRules.STAGES_PER_RANK
        val color = QuizRules.RANK_COLORS[rank]
        val overlay = FrameLayout(this).apply { setBackgroundColor(0xCC0A1526.toInt()) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val icon = TextView(this).apply {
            text = QuizRules.RANK_ICONS[rank]
            textSize = 64f
            gravity = Gravity.CENTER
        }
        val title = TextView(this).apply {
            text = "升级啦！"
            textSize = 40f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val stage = TextView(this).apply {
            text = QuizRules.tierName(tier)
            textSize = 34f
            setTextColor(color)
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        box.addView(icon)
        box.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        box.addView(stage)
        box.addView(
            text("积分 +${result.delta} · 继续加油！", 18f, Color.rgb(184, 199, 217)),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) },
        )
        overlay.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // The star burst: ten sparks fly outward from the center on fixed angles, so it looks the
        // same every time and stays jitter-free on slow TV hardware.
        for (i in 0 until 10) {
            val star = TextView(this).apply {
                text = "✦"
                textSize = if (i % 2 == 0) 26f else 20f
                setTextColor(if (i % 2 == 0) color else Color.rgb(255, 209, 102))
            }
            overlay.addView(star, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            val angle = Math.toRadians((i * 36).toDouble())
            val distance = dp(150) - (i % 3) * dp(25)
            star.animate()
                .translationX((Math.cos(angle) * distance).toFloat())
                .translationY((Math.sin(angle) * distance).toFloat())
                .alpha(0f)
                .setDuration(1_400L)
                .setStartDelay(250L)
                .start()
        }
        icon.scaleX = 0.2f
        icon.scaleY = 0.2f
        icon.animate().scaleX(1f).scaleY(1f).setDuration(600L).start()
        rootLayout.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        ui.postDelayed({
            (overlay.parent as? ViewGroup)?.removeView(overlay)
        }, 2_600L)
        if (QuizPrefs(this).voice && ttsReady) {
            try {
                tts?.speak("恭喜你，升级到${QuizRules.tierName(tier)}，继续加油！", TextToSpeech.QUEUE_FLUSH, null, "quiz_levelup")
            } catch (e: Exception) {
            }
        }
    }

    // TV remotes still dispatch the back key; gesture-back migration would require AndroidX.
    @android.annotation.SuppressLint("GestureBackNavigation")
    override fun onBackPressed() {
        feedbackText.text = if (restRemaining() > 0L) "休息结束后，答对一题才能退出" else "答对一题后才能退出"
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Best-effort recovery for ordinary installations; Device Owner uses real lock-task enforcement.
        if (!solved && !lockActive) {
            ui.postDelayed({
                if (!solved && !isFinishing) {
                    startActivity(Intent(this, QuizActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                }
            }, 250L)
        }
    }

    private fun immersive() {
        if (Build.VERSION.SDK_INT >= 30) {
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        stopReading()
        try { tts?.shutdown() } catch (e: Exception) {}
        tts = null
        super.onDestroy()
    }

    private fun text(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    private fun answerBackground() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), rounded(Color.rgb(255, 248, 231), Color.rgb(255, 209, 102), 4))
        addState(intArrayOf(), rounded(Color.WHITE, Color.rgb(184, 199, 217), 2))
    }

    private fun rounded(fill: Int, stroke: Int, width: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(18).toFloat()
        setStroke(dp(width), stroke)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val READ_REPEAT_MS = 20_000L // after the first reading, repeat question + options every 20 s
        private const val GUARD_POLL_MS = 1_000L // while a quiz or rest is up, check the foreground every second

        /** Set when a quiz is completed; MainActivity reads (and clears) it to skip the admin prompt then. */
        @Volatile var justFinished = false
    }
}

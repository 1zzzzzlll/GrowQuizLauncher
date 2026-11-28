package dev.plume.quiz

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import dev.plume.voice.Voice

/**
 * Coordinates pausing any playing video/audio when [QuizActivity] opens and automatically resuming
 * it once the quiz (or parent-timed rest) finishes.
 *
 * Lives on the application main looper rather than inside [QuizActivity]'s view handler so the
 * post-`finish()` resume verification still runs after [QuizActivity.onDestroy] clears its own UI
 * callbacks and after the underlying player activity has transitioned back to `onResume`.
 */
object QuizMediaControl {
    private const val RECHECK_PAUSE_MS = 180L
    private const val TOGGLE_PAUSE_MS = 380L
    private const val RESUME_STEP0_MS = 240L
    private const val RESUME_STEP1_MS = 620L
    private const val RESUME_STEP2_MS = 950L
    private const val FOCUS_RECLAIM_DELAY_MS = 150L
    private const val GUARD_THROTTLE_MS = 900L

    private val main = Handler(Looper.getMainLooper())

    /** True while [QuizActivity] owns transient exclusive media focus for an active session. */
    @Volatile var holdingFocus: Boolean = false
        private set

    private var focusRequest: AudioFocusRequest? = null
    private var sessionGen: Int = 0
    private var hadActivePlayback: Boolean = false
    private var interruptedPkg: String? = null
    private var lastPauseAttemptAt: Long = 0L

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> {
                // A background video finishing its buffer or advancing an episode under our
                // translucent quiz window can steal focus back; reclaim it while the quiz is up.
                if (holdingFocus && QuizActivity.isForeground && !QuizActivity.sessionSolved) {
                    val gen = sessionGen
                    main.postDelayed({
                        val ctx = appCtx ?: return@postDelayed
                        if (gen == sessionGen && holdingFocus && QuizActivity.isForeground && !QuizActivity.sessionSolved) {
                            hadActivePlayback = true
                            requestFocusAndPauseNow(ctx, gen)
                        }
                    }, FOCUS_RECLAIM_DELAY_MS)
                }
            }
        }
    }

    @Volatile private var appCtx: Context? = null

    /**
     * Takes exclusive transient media focus and pauses any active player. Safe to call in
     * `onCreate`, `onNewIntent`, and `onResume`.
     */
    fun acquireAndPause(context: Context, targetPkg: String?) {
        val app = context.applicationContext
        appCtx = app
        if (!targetPkg.isNullOrBlank()) {
            interruptedPkg = targetPkg
        }
        val am = app.getSystemService(AudioManager::class.java) ?: return
        if (isExternalMediaPlaying(am)) {
            hadActivePlayback = true
        }
        val gen = ++sessionGen
        requestFocusAndPauseNow(app, gen)
    }

    /**
     * Periodic check from [QuizActivity]'s 1-second foreground guard: catches players that started
     * outputting sound *after* the quiz opened (for instance after buffering or an ad transition).
     */
    fun ensurePausedWhileActive(context: Context) {
        val app = context.applicationContext
        appCtx = app
        if (!QuizActivity.isForeground || QuizActivity.sessionSolved) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPauseAttemptAt < GUARD_THROTTLE_MS) return
        val am = app.getSystemService(AudioManager::class.java) ?: return
        if (!holdingFocus || isExternalMediaPlaying(am)) {
            if (isExternalMediaPlaying(am)) hadActivePlayback = true
            val gen = ++sessionGen
            requestFocusAndPauseNow(app, gen)
        }
    }

    /**
     * Releases audio focus *before* resuming playback, then verifies after the underlying player
     * returns to `onResume` that playback actually restarted.
     */
    fun releaseAndResume(context: Context) {
        val app = context.applicationContext
        appCtx = app
        val wasPlaying = hadActivePlayback
        val pkg = interruptedPkg
        hadActivePlayback = false
        interruptedPkg = null
        holdingFocus = false
        val gen = ++sessionGen

        val am = app.getSystemService(AudioManager::class.java) ?: return
        focusRequest?.let { req -> runCatching { am.abandonAudioFocusRequest(req) } }

        if (!MediaPlaybackRules.shouldAttemptResume(wasPlaying, pkg)) return

        // Step 0 (+240 ms): QuizActivity.finish() has completed and the covered player is resumed.
        // Players that auto-resume on AUDIOFOCUS_GAIN (ExoPlayer/Media3) are already playing here
        // and are left untouched; others receive explicit KEYCODE_MEDIA_PLAY.
        main.postDelayed({
            if (!canResume(gen)) return@postDelayed
            val playingNow = isExternalMediaPlaying(am)
            val key0 = MediaPlaybackRules.resumeKeyToSend(step = 0, externalPlayingNow = playingNow, verifiedWasPlaying = wasPlaying)
            if (key0 != null) {
                dispatchGlobalMediaKey(am, key0)
                dispatchTargetedMediaButton(app, pkg, key0)
            }
            if (!playingNow) {
                // Step 1 (+620 ms): if explicit MEDIA_PLAY was ignored (e.g. MediaSessionCompat with
                // ACTION_PLAY_PAUSE only) and we verified active audio before the quiz, toggle once.
                main.postDelayed({
                    if (!canResume(gen)) return@postDelayed
                    val playingAfterPlay = isExternalMediaPlaying(am)
                    val key1 = MediaPlaybackRules.resumeKeyToSend(
                        step = 1,
                        externalPlayingNow = playingAfterPlay,
                        verifiedWasPlaying = wasPlaying,
                    )
                    if (key1 != null) {
                        dispatchGlobalMediaKey(am, key1)
                        if (!pkg.isNullOrBlank()) {
                            main.postDelayed({
                                if (!canResume(gen)) return@postDelayed
                                if (!isExternalMediaPlaying(am)) {
                                    dispatchTargetedMediaButton(app, pkg, key1)
                                }
                            }, RESUME_STEP2_MS - RESUME_STEP1_MS)
                        }
                    }
                }, RESUME_STEP1_MS - RESUME_STEP0_MS)
            }
        }, RESUME_STEP0_MS)
    }

    /**
     * Releases focus without resuming playback when [QuizActivity] is destroyed unexpectedly while
     * the question is still unanswered. No-op when [releaseAndResume] already ran.
     */
    fun abandonOnly(context: Context) {
        if (!holdingFocus) return
        holdingFocus = false
        hadActivePlayback = false
        interruptedPkg = null
        sessionGen++
        val am = context.applicationContext.getSystemService(AudioManager::class.java) ?: return
        focusRequest?.let { req -> runCatching { am.abandonAudioFocusRequest(req) } }
    }

    private fun canResume(gen: Int): Boolean =
        gen == sessionGen && !holdingFocus && !QuizActivity.isForeground && !BlockedAppActivity.isForeground

    private fun requestFocusAndPauseNow(app: Context, gen: Int) {
        val am = app.getSystemService(AudioManager::class.java) ?: return
        lastPauseAttemptAt = SystemClock.elapsedRealtime()
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(focusListener, main)
            .build()
            .also { focusRequest = it }

        val granted = runCatching { am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED }
            .getOrDefault(false)
        holdingFocus = true
        dispatchGlobalMediaKey(am, KeyEvent.KEYCODE_MEDIA_PAUSE)
        dispatchTargetedMediaButton(app, interruptedPkg, KeyEvent.KEYCODE_MEDIA_PAUSE)
        android.util.Log.i(
            "GrowQuiz",
            "quiz media hold focus=$granted hadActive=$hadActivePlayback target=${interruptedPkg.orEmpty()}",
        )

        // Follow-up check 1 (+180 ms): re-send explicit pause once the underlying activity finishes onPause.
        main.postDelayed({
            if (gen != sessionGen || !holdingFocus || !QuizActivity.isForeground || QuizActivity.sessionSolved) {
                return@postDelayed
            }
            if (isExternalMediaPlaying(am)) {
                hadActivePlayback = true
                runCatching { am.requestAudioFocus(req) }
                dispatchGlobalMediaKey(am, KeyEvent.KEYCODE_MEDIA_PAUSE)
                dispatchTargetedMediaButton(app, interruptedPkg, KeyEvent.KEYCODE_MEDIA_PAUSE)
            }
        }, RECHECK_PAUSE_MS)

        // Follow-up check 2 (+380 ms): if external media is *still* actively playing, its MediaSession
        // ignores ACTION_PAUSE and only handles ACTION_PLAY_PAUSE — send a single toggle to pause it.
        main.postDelayed({
            if (gen != sessionGen || !holdingFocus) return@postDelayed
            val stillPlaying = isExternalMediaPlaying(am)
            if (MediaPlaybackRules.shouldSendTogglePause(
                    stillPlayingAfterPauseKey = stillPlaying,
                    quizForeground = QuizActivity.isForeground,
                    solved = QuizActivity.sessionSolved,
                )) {
                hadActivePlayback = true
                dispatchGlobalMediaKey(am, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            }
        }, TOGGLE_PAUSE_MS)
    }

    internal fun isExternalMediaPlaying(am: AudioManager): Boolean {
        val ownSpeech = Voice.isSpeaking()
        val configs = runCatching { am.activePlaybackConfigurations }.getOrDefault(emptyList())
        val snapshots = configs.map { cfg ->
            MediaPlaybackRules.PlayerSnapshot(
                usage = cfg.audioAttributes.usage,
                contentType = cfg.audioAttributes.contentType,
            )
        }
        if (MediaPlaybackRules.countExternalMediaPlayers(snapshots, ownSpeech) > 0) {
            return true
        }
        return !ownSpeech && configs.isEmpty() && runCatching { am.isMusicActive }.getOrDefault(false)
    }

    private fun dispatchGlobalMediaKey(am: AudioManager, keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        runCatching {
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        }
    }

    private fun dispatchTargetedMediaButton(context: Context, pkg: String?, keyCode: Int) {
        val clean = pkg?.trim().orEmpty()
        if (clean.isEmpty() || clean == context.packageName) return
        runCatching {
            val pm = context.packageManager
            val receivers = pm.queryBroadcastReceivers(Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(clean), 0)
            if (receivers.isEmpty()) return
            val now = SystemClock.uptimeMillis()
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
            val up = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0)
            for (ri in receivers) {
                val ai = ri.activityInfo ?: continue
                if (!ai.exported) continue
                val cmp = ComponentName(ai.packageName, ai.name)
                context.sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(cmp).putExtra(Intent.EXTRA_KEY_EVENT, down))
                context.sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(cmp).putExtra(Intent.EXTRA_KEY_EVENT, up))
            }
        }
    }
}

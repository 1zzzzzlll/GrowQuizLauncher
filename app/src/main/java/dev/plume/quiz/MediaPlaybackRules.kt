package dev.plume.quiz

import android.media.AudioAttributes
import android.view.KeyEvent

/**
 * Pure-JVM rules for pausing background media when [QuizActivity] opens and resuming it cleanly
 * once the quiz finishes.
 *
 * Kept free of Android runtime calls so every playback-detection, toggle-safety, and focus-sharing
 * rule is directly unit-tested.
 */
object MediaPlaybackRules {

    data class PlayerSnapshot(
        val usage: Int,
        val contentType: Int,
    )

    /**
     * Counts currently active external media/game players from `AudioManager.activePlaybackConfigurations`,
     * excluding our own speech player when [ownSpeechActive] is true.
     *
     * On Android 8.0+ (`minSdk = 26`), `AudioManager.getActivePlaybackConfigurations()` returns only
     * players in `PLAYER_STATE_STARTED` for non-privileged callers, making it a reliable signal even
     * on TV firmwares where `AudioManager.isMusicActive` reports `false` during hardware playback.
     */
    fun countExternalMediaPlayers(
        activePlayers: List<PlayerSnapshot>,
        ownSpeechActive: Boolean,
    ): Int {
        var speechAllowance = if (ownSpeechActive) 1 else 0
        var external = 0
        for (p in activePlayers) {
            if (!isMediaOrGameUsage(p.usage)) continue
            if (speechAllowance > 0 && p.contentType == AudioAttributes.CONTENT_TYPE_SPEECH) {
                speechAllowance--
                continue
            }
            external++
        }
        return external
    }

    fun isMediaOrGameUsage(usage: Int): Boolean = when (usage) {
        AudioAttributes.USAGE_MEDIA,
        AudioAttributes.USAGE_GAME,
        AudioAttributes.USAGE_UNKNOWN,
        -> true
        else -> false
    }

    /**
     * Whether our own TTS engines (`CloudTts`, `EmbeddedTts`) should skip requesting and abandoning
     * per-utterance `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` because a foreground modal (`QuizActivity`
     * or `BlockedAppActivity`) already holds exclusive `AUDIOFOCUS_GAIN_TRANSIENT` for the whole
     * session.
     *
     * Abandoning a second `USAGE_MEDIA` focus request mid-quiz on firmwares that deduplicate focus
     * by package name would release focus early and let the video underneath start playing again.
     */
    fun shouldSkipUtteranceAudioFocus(
        quizHoldingFocus: Boolean,
        blockedAlertForeground: Boolean,
    ): Boolean = quizHoldingFocus || blockedAlertForeground

    /**
     * Whether to send `KEYCODE_MEDIA_PLAY_PAUSE` as a fallback when pausing: safe only while the
     * quiz is actively on screen and `AudioPlaybackConfiguration` / `isMusicActive` confirms an
     * external player is *still* outputting audio after `AUDIOFOCUS_GAIN_TRANSIENT` and explicit
     * `KEYCODE_MEDIA_PAUSE` were both ignored (e.g. a `MediaSessionCompat` that declares only
     * `ACTION_PLAY_PAUSE` without `ACTION_PAUSE`).
     */
    fun shouldSendTogglePause(
        stillPlayingAfterPauseKey: Boolean,
        quizForeground: Boolean,
        solved: Boolean,
    ): Boolean = quizForeground && !solved && stillPlayingAfterPauseKey

    /**
     * Whether finishing a quiz should attempt to resume playback. A quiz started from the home
     * screen with no active playback must not start a stale background music/video session.
     */
    fun shouldAttemptResume(
        hadActivePlayback: Boolean,
        interruptedPkg: String?,
    ): Boolean = hadActivePlayback || !interruptedPkg.isNullOrBlank()

    /**
     * Decides which media key (if any) to dispatch at post-finish [step] (`0` = initial check after
     * abandoning audio focus and resuming the player window; `1` = follow-up check if still silent):
     * - Returns `null` immediately whenever [externalPlayingNow] is already `true` (e.g. ExoPlayer
     *   auto-resumed on `AUDIOFOCUS_GAIN`), so we never toggle an already-playing video back to pause.
     * - At step `0`: sends idempotent `KEYCODE_MEDIA_PLAY` (never pauses an active player).
     * - At step `1`: sends `KEYCODE_MEDIA_PLAY_PAUSE` *only* when [verifiedWasPlaying] is `true`
     *   (we positively observed active audio before pausing it, and `KEYCODE_MEDIA_PLAY` was ignored
     *   because the player's `MediaSession` only declares `ACTION_PLAY_PAUSE`).
     */
    fun resumeKeyToSend(
        step: Int,
        externalPlayingNow: Boolean,
        verifiedWasPlaying: Boolean,
    ): Int? {
        if (externalPlayingNow) return null
        return when (step) {
            0 -> KeyEvent.KEYCODE_MEDIA_PLAY
            1 -> if (verifiedWasPlaying) KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE else null
            else -> null
        }
    }
}

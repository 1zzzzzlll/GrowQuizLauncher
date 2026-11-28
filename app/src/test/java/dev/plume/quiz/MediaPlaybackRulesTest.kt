package dev.plume.quiz

import android.media.AudioAttributes
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPlaybackRulesTest {

    @Test
    fun `countExternalMediaPlayers ignores own speech player while detecting underlying video`() {
        val ownTts = MediaPlaybackRules.PlayerSnapshot(
            usage = AudioAttributes.USAGE_MEDIA,
            contentType = AudioAttributes.CONTENT_TYPE_SPEECH,
        )
        val videoMovie = MediaPlaybackRules.PlayerSnapshot(
            usage = AudioAttributes.USAGE_MEDIA,
            contentType = AudioAttributes.CONTENT_TYPE_MOVIE,
        )
        val videoUnknown = MediaPlaybackRules.PlayerSnapshot(
            usage = AudioAttributes.USAGE_MEDIA,
            contentType = AudioAttributes.CONTENT_TYPE_UNKNOWN,
        )
        val sonification = MediaPlaybackRules.PlayerSnapshot(
            usage = AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
            contentType = AudioAttributes.CONTENT_TYPE_SONIFICATION,
        )

        // Only our own TTS speaking + UI click sound -> 0 external players.
        assertEquals(0, MediaPlaybackRules.countExternalMediaPlayers(listOf(ownTts, sonification), ownSpeechActive = true))

        // Our TTS speaking AND background video still outputting sound -> 1 external player detected!
        assertEquals(1, MediaPlaybackRules.countExternalMediaPlayers(listOf(ownTts, videoMovie), ownSpeechActive = true))
        assertEquals(1, MediaPlaybackRules.countExternalMediaPlayers(listOf(ownTts, videoUnknown), ownSpeechActive = true))

        // When our TTS is idle, even a speech-tagged third-party podcast/audiobook counts as external media.
        assertEquals(1, MediaPlaybackRules.countExternalMediaPlayers(listOf(ownTts), ownSpeechActive = false))
    }

    @Test
    fun `tts skips per-utterance focus churn while quiz or blocked-app modal holds session focus`() {
        assertTrue(MediaPlaybackRules.shouldSkipUtteranceAudioFocus(quizHoldingFocus = true, blockedAlertForeground = false))
        assertTrue(MediaPlaybackRules.shouldSkipUtteranceAudioFocus(quizHoldingFocus = false, blockedAlertForeground = true))
        assertFalse(MediaPlaybackRules.shouldSkipUtteranceAudioFocus(quizHoldingFocus = false, blockedAlertForeground = false))
    }

    @Test
    fun `toggle pause is sent only when quiz is active in foreground and audio is still playing`() {
        assertTrue(MediaPlaybackRules.shouldSendTogglePause(stillPlayingAfterPauseKey = true, quizForeground = true, solved = false))
        assertFalse(MediaPlaybackRules.shouldSendTogglePause(stillPlayingAfterPauseKey = false, quizForeground = true, solved = false))
        assertFalse(MediaPlaybackRules.shouldSendTogglePause(stillPlayingAfterPauseKey = true, quizForeground = false, solved = false))
        assertFalse(MediaPlaybackRules.shouldSendTogglePause(stillPlayingAfterPauseKey = true, quizForeground = true, solved = true))
    }

    @Test
    fun `resume runs when quiz covered an app or interrupted active audio, never from idle desktop`() {
        assertTrue(MediaPlaybackRules.shouldAttemptResume(hadActivePlayback = true, interruptedPkg = null))
        assertTrue(MediaPlaybackRules.shouldAttemptResume(hadActivePlayback = false, interruptedPkg = "com.xiaodianshi.tv.yst"))
        assertFalse(MediaPlaybackRules.shouldAttemptResume(hadActivePlayback = false, interruptedPkg = null))
        assertFalse(MediaPlaybackRules.shouldAttemptResume(hadActivePlayback = false, interruptedPkg = "  "))
    }

    @Test
    fun `resumeKeyToSend leaves auto-resumed ExoPlayer alone and escalates to toggle only when verified`() {
        // Player already auto-resumed via AUDIOFOCUS_GAIN -> send nothing!
        assertNull(MediaPlaybackRules.resumeKeyToSend(step = 0, externalPlayingNow = true, verifiedWasPlaying = true))
        assertNull(MediaPlaybackRules.resumeKeyToSend(step = 1, externalPlayingNow = true, verifiedWasPlaying = true))

        // Still paused at step 0 -> send idempotent KEYCODE_MEDIA_PLAY.
        assertEquals(
            KeyEvent.KEYCODE_MEDIA_PLAY,
            MediaPlaybackRules.resumeKeyToSend(step = 0, externalPlayingNow = false, verifiedWasPlaying = false),
        )

        // Still paused at step 1 -> toggle ONLY when active playback was positively verified before quiz.
        assertEquals(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            MediaPlaybackRules.resumeKeyToSend(step = 1, externalPlayingNow = false, verifiedWasPlaying = true),
        )
        assertNull(
            MediaPlaybackRules.resumeKeyToSend(step = 1, externalPlayingNow = false, verifiedWasPlaying = false),
        )
    }
}

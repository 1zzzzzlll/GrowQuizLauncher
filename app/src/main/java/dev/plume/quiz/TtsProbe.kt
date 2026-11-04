package dev.plume.quiz

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * One TTS engine kept for the whole process, so the web admin can tell whether this TV can read
 * questions aloud — Chinese-brand TVs often have no Google TTS; some have iFlytek or nothing at all.
 */
object TtsProbe {
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var state = "initializing"

    fun start(context: Context) {
        if (tts != null) return
        val app = context.applicationContext
        tts = TextToSpeech(app) { status ->
            val engine = tts
            if (status != TextToSpeech.SUCCESS || engine == null) {
                state = "no-engine"
                return@TextToSpeech
            }
            // zh-CN first, then plain Chinese, then the device's own locale: any engine beats silence.
            val ok = listOf(Locale.SIMPLIFIED_CHINESE, Locale.CHINESE, Locale.getDefault())
                .any { l -> engine.setLanguage(l) >= TextToSpeech.LANG_AVAILABLE }
            state = if (ok) "ok" else "no-language"
            android.util.Log.i("GrowQuiz", "tts probe: $state")
        }
    }

    /** Reads a sentence aloud (the web console's test button); false when it could not be spoken. */
    fun speak(text: String): Boolean {
        val engine = tts ?: return false
        return try {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "probe") == TextToSpeech.SUCCESS
        } catch (e: Exception) {
            false
        }
    }

    fun describe(): String = when (state) {
        "ok" -> "available"
        "no-engine" -> "no-engine"
        "no-language" -> "no-language"
        else -> "initializing"
    }
}

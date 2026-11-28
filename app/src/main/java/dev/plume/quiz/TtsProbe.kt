package dev.plume.quiz

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import dev.plume.voice.Voice

/**
 * The parents' console asks two things about speech: can this TV read aloud, and does it work right now.
 * Which voice answers — the TV's own engine or the one bundled with the app — is decided inside [Voice],
 * so the console and the quiz page never disagree.
 */
object TtsProbe {

    /** Starts the voice (no-op when the desktop already started it at start-up). */
    fun start(context: Context) = Voice.warm(context)

    /** Reads a sentence aloud (the console's test button); false when it could not be spoken. */
    fun speak(text: String): Boolean {
        dev.plume.voice.CloudTts.resetCooldown()
        return Voice.speak(text, flush = true)
    }

    /** "embedded" / "available" / "no-engine" / "no-language" / "initializing", the values the console knows. */
    fun describe(): String = Voice.describe()

    /**
     * The speech engines installed on this TV, by package, empty when there is none. This is the same
     * query [TextToSpeech] makes before it starts an engine, so an empty answer here means the -1 it
     * returns is "no engine", not "broken engine". Needs the TTS intents in the manifest's `<queries>`
     * on Android 11 and later, or the system hides the engines and this answer comes back empty.
     */
    fun engineNames(context: Context): List<String> = try {
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        context.packageManager.queryIntentServices(intent, 0)
            .mapNotNull { it.serviceInfo?.packageName }
            .distinct().sorted()
    } catch (_: Exception) {
        emptyList()
    }
}

package dev.plume.quiz

import android.content.Context

/**
 * The parents' console asks two things about speech: can this TV read aloud, and does it work right now.
 * The engine itself lives in [QuizVoice], so the TV loads one model instead of two — a second engine
 * would double the memory and the startup cost on a TV.
 */
object TtsProbe {

    /** Starts the shared engine (no-op when [QuizVoice] already started it at desktop start). */
    fun start(context: Context) = QuizVoice.warm(context)

    /** Reads a sentence aloud (the console's test button); false when it could not be spoken. */
    fun speak(text: String): Boolean = QuizVoice.speak(text, flush = true)

    /** "available" / "no-engine" / "no-language" / "initializing", the values the console knows. */
    fun describe(): String = when {
        QuizVoice.failureReason() != null -> QuizVoice.failureReason()!!
        QuizVoice.isReady() -> "available"
        else -> "initializing"
    }
}

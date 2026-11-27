package dev.plume.voice

import android.content.Context
import dev.plume.quiz.QuizPrefs
import dev.plume.quiz.QuizVoice

/**
 * The one place a page asks for a sentence to be spoken.
 *
 * Two voices can answer. The TV's own engine ([QuizVoice]) needs nothing installed but is missing on
 * many set-top boxes, and on others its Chinese is poor. The bundled voice ([EmbeddedTts]) sounds the
 * same everywhere and needs no permission, but costs a 32 MB model, so it only speaks after a parent
 * loads it. This object decides which one answers, once per sentence: a loaded pack wins on the TV
 * that has one, and it takes over by itself on a TV whose engine turns out to be unusable — a child
 * should never sit in front of a silent question because a setting was left alone.
 *
 * Kept thin on purpose: the pages keep calling one speak/stop pair, and the difference between the two
 * voices does not leak into the quiz.
 */
object Voice {
    @Volatile private var app: Context? = null

    /** Called when a page comes up: start the chosen voice (skip system TTS when bundled voice is active). */
    fun warm(context: Context) {
        val app = context.applicationContext
        this.app = app
        // A TV with a bundled voice pack enabled has no reason to initialize Android's missing
        // system TTS on every cold start — doing so logged false tts_missing(status=-1) every boot.
        if (!usesEmbedded()) QuizVoice.warm(app)
    }

    /**
     * Whether the next sentence goes through the bundled voice. Also starts that voice when it is the
     * one that should answer, so a pack loaded while the app runs needs no restart.
     */
    fun usesEmbedded(): Boolean {
        val app = app ?: return false
        val pack = PackStore.ready(app) ?: return false
        if (EmbeddedTts.reason() != null) return false // this device could not load it: engine only
        if (QuizPrefs(app).embeddedVoice) {
            EmbeddedTts.prepare(app, pack)
            return true
        }
        // No engine, or one without Chinese: the pack already on disk is better than silence.
        if (!QuizVoice.isUnusable()) return false
        EmbeddedTts.prepare(app, pack)
        return true
    }

    /** True once the chosen voice can speak; false while the model is still loading. */
    fun isReady(): Boolean = if (usesEmbedded()) EmbeddedTts.isReady() else QuizVoice.isReady()

    /** No voice at all: the caller says so once instead of reading nothing. Never true while a pack loads. */
    fun isUnusable(): Boolean = if (usesEmbedded()) false else QuizVoice.isUnusable()

    /**
     * Speaks [text]. With [flush] the current sentence stops and this one starts at once, which is what a
     * new question needs; without it the text queues behind what is being said.
     */
    fun speak(text: String, flush: Boolean = true): Boolean {
        val app = app ?: return QuizVoice.speak(text, flush)
        return if (usesEmbedded()) EmbeddedTts.speak(app, text, flush) else QuizVoice.speak(text, flush)
    }

    /**
     * Speaks a question: [first] is the stem, [rest] the options. The bundled voice takes both as one
     * text — it synthesises everything before the first sound anyway, so two calls would only double
     * the wait and put an unnatural pause in the middle. A system engine streams: there the options
     * queue behind the stem and the voice starts earlier.
     */
    fun speakParts(first: String, rest: String) {
        val app = app ?: run {
            QuizVoice.speak(first, flush = true)
            if (rest.isNotBlank()) QuizVoice.speak(rest, flush = false)
            return
        }
        if (usesEmbedded()) {
            EmbeddedTts.speak(app, if (rest.isBlank()) first else "$first。$rest", flush = true)
        } else {
            QuizVoice.speak(first, flush = true)
            if (rest.isNotBlank()) QuizVoice.speak(rest, flush = false)
        }
    }

    /**
     * Prepares a question that is not on screen yet. A sentence takes seconds to synthesise on this
     * hardware, so the next question is computed while the child is still reading the current one;
     * by the time it appears its clip is on disk and the voice starts at once. A system engine is
     * fast enough that there is nothing to prepare.
     */
    fun warmParts(first: String, rest: String) {
        val app = app ?: return
        if (!usesEmbedded()) return
        EmbeddedTts.warmClip(app, if (rest.isBlank()) first else "$first。$rest")
    }

    fun stop() {
        QuizVoice.stop()
        EmbeddedTts.stop()
    }

    /**
     * What the parents' console shows: "embedded" (bundled voice), "available" (the TV's engine),
     * "initializing" while either is still starting, or why there is nothing.
     */
    fun describe(): String = when {
        usesEmbedded() && EmbeddedTts.isReady() -> "embedded"
        usesEmbedded() -> "initializing"
        else -> QuizVoice.failureReason() ?: if (QuizVoice.isReady()) "available" else "initializing"
    }

    /** A one-line state for the environment check, in words a parent reads on the TV. */
    fun detail(): String = when (describe()) {
        "embedded" -> "内置语音"
        "available" -> "电视自带引擎"
        "initializing" -> "加载中"
        "no-language" -> "缺中文语音"
        else -> "未安装"
    }

    /** Switches the voice, e.g. from the console, and starts it at once. */
    fun preferEmbedded(context: Context, on: Boolean) {
        QuizPrefs(context.applicationContext).embeddedVoice = on
        val app = context.applicationContext
        this.app = app
        if (on) {
            PackStore.ready(app)?.let { EmbeddedTts.prepare(app, it) }
        } else {
            EmbeddedTts.stop()
            QuizVoice.warm(app)
        }
    }
}

package dev.plume.voice

import android.content.Context
import dev.plume.quiz.QuizPrefs
import dev.plume.quiz.QuizVoice
import dev.plume.quiz.SpeechText

/**
 * The one place a page asks for a sentence to be spoken.
 *
 * Three tiers answer in order:
 * 1. [CloudTts] — 24 kHz studio neural voice cached on our server and locally on disk (~0.3 s,
 *    bilingual, natural prosody). Active whenever the server is configured and reachable.
 * 2. [EmbeddedTts] — the downloaded on-device neural pack, taking over automatically when offline
 *    or when cloud speech is disabled.
 * 3. [QuizVoice] — the TV's own Android speech engine, when neither neural voice is available.
 */
object Voice {
    @Volatile private var app: Context? = null

    /** Called when a page comes up: warm offline fallback if installed; skip system TTS when neural voice is active. */
    fun warm(context: Context) {
        val app = context.applicationContext
        this.app = app
        // If an offline pack is already on disk, keep it warm in RAM so an internet drop falls back with zero wait.
        val pack = PackStore.ready(app)
        if (pack != null && EmbeddedTts.reason() == null && (QuizPrefs(app).embeddedVoice || usesCloud())) {
            EmbeddedTts.prepare(app, pack)
        }
        if (!usesCloud() && !usesEmbedded()) QuizVoice.warm(app)
    }

    /** Whether the next sentence goes through the 24 kHz cloud neural voice. */
    fun usesCloud(): Boolean {
        val app = app ?: return false
        return CloudTts.isConfigured() && QuizPrefs(app).cloudVoice && !CloudTts.isCoolingDown()
    }

    /** True when the active engine pronounces English words and option letters A..D natively. */
    fun isBilingual(): Boolean = usesCloud()

    /**
     * Whether the next offline sentence goes through the bundled voice pack. Also starts that voice
     * when it is the one that should answer, so a pack loaded while the app runs needs no restart.
     */
    fun usesEmbedded(): Boolean {
        if (usesCloud()) return false
        return canFallbackEmbedded()
    }

    private fun canFallbackEmbedded(): Boolean {
        val app = app ?: return false
        val pack = PackStore.ready(app) ?: return false
        if (EmbeddedTts.reason() != null) return false
        if (QuizPrefs(app).embeddedVoice || CloudTts.isCoolingDown() || QuizVoice.isUnusable()) {
            EmbeddedTts.prepare(app, pack)
            return true
        }
        return false
    }

    /** True once the chosen voice can speak; false while an offline model is still loading. */
    fun isReady(): Boolean = when {
        usesCloud() -> true
        usesEmbedded() -> EmbeddedTts.isReady()
        else -> QuizVoice.isReady()
    }

    /** No voice at all: the caller says so once instead of reading nothing. Never true while a pack loads. */
    fun isUnusable(): Boolean = when {
        usesCloud() -> false
        usesEmbedded() -> false
        else -> QuizVoice.isUnusable()
    }

    /** When a sentence last started playing out of the speaker (cloud, bundled offline, or system TTS). */
    fun playedAt(): Long = maxOf(CloudTts.playedAt(), EmbeddedTts.playedAt(), QuizVoice.playedAt())

    /** True while any of our speech engines is actively outputting audio. */
    fun isSpeaking(): Boolean = CloudTts.isSpeaking() || EmbeddedTts.isSpeaking() || QuizVoice.isSpeaking()

    /**
     * Speaks [text]. When [CloudTts] fails mid-request (e.g. Wi-Fi dropped), falls back immediately
     * to [EmbeddedTts] or [QuizVoice] so the child still hears the sentence. [onDone] runs once on a
     * background thread after playback completes (or fails after being queued), and is dropped if
     * [stop] or a later flushing utterance cancels playback first.
     */
    fun speak(text: String, flush: Boolean = true, onDone: (() -> Unit)? = null): Boolean {
        val doneOnce = onDone?.let { cb ->
            val called = java.util.concurrent.atomic.AtomicBoolean(false)
            ({ if (called.compareAndSet(false, true)) cb() })
        }
        val app = app ?: return QuizVoice.speak(text, flush, doneOnce)
        if (usesCloud()) {
            val ok = CloudTts.speak(
                context = app,
                text = text,
                flush = flush,
                onFallback = {
                    if (!speakOfflineFallback(app, text, flush = true, onDone = doneOnce)) {
                        doneOnce?.invoke()
                    }
                },
                onDone = doneOnce,
            )
            if (ok) return true
        }
        return speakOfflineFallback(app, text, flush, doneOnce)
    }

    /**
     * Speaks a question: [first] is the stem, [rest] the options.
     * Cloud TTS fetches [first] and [rest] in parallel so the stem plays within ~0.3–1.0 s;
     * Embedded TTS joins them cleanly and streams clause-by-clause via callback.
     */
    fun speakParts(first: String, rest: String) {
        val app = app ?: run {
            QuizVoice.speak(first, flush = true)
            if (rest.isNotBlank()) QuizVoice.speak(rest, flush = false)
            return
        }
        if (usesCloud()) {
            val ok = CloudTts.speakParts(app, first, rest) {
                speakPartsOfflineFallback(app, first, rest)
            }
            if (ok) return
        }
        speakPartsOfflineFallback(app, first, rest)
    }

    private fun speakOfflineFallback(
        app: Context,
        text: String,
        flush: Boolean,
        onDone: (() -> Unit)? = null,
    ): Boolean {
        if (canFallbackEmbedded() && EmbeddedTts.isReady()) {
            return EmbeddedTts.speak(app, text, flush, onDone)
        }
        QuizVoice.warm(app)
        return QuizVoice.speak(text, flush, onDone)
    }

    private fun speakPartsOfflineFallback(app: Context, first: String, rest: String) {
        if (canFallbackEmbedded() && EmbeddedTts.isReady()) {
            EmbeddedTts.speak(app, SpeechText.joinParts(first, rest), flush = true)
            return
        }
        QuizVoice.warm(app)
        QuizVoice.speak(first, flush = true)
        if (rest.isNotBlank()) QuizVoice.speak(rest, flush = false)
    }

    /** Pre-fetches or pre-synthesises a question that is not on screen yet. */
    fun warmParts(first: String, rest: String) {
        val app = app ?: return
        if (usesCloud()) {
            CloudTts.warmParts(app, first, rest)
            return
        }
        if (usesEmbedded()) {
            EmbeddedTts.warmClip(app, SpeechText.joinParts(first, rest))
        }
    }

    /** Pre-caches a single sentence (such as the current question's explanation) in the background. */
    fun warmClip(text: String, offlineWhenIdle: Boolean = false) {
        if (text.isBlank()) return
        val app = app ?: return
        if (usesCloud()) {
            CloudTts.warmParts(app, text, "")
            return
        }
        if (offlineWhenIdle && usesEmbedded()) {
            EmbeddedTts.warmClip(app, text)
        }
    }

    fun stop() {
        CloudTts.stop()
        QuizVoice.stop()
        EmbeddedTts.stop()
    }

    /**
     * What the parents' console shows: "cloud" (server neural voice), "embedded" (offline pack),
     * "available" (the TV's engine), "initializing" while starting, or why there is nothing.
     */
    fun describe(): String = when {
        usesCloud() -> "cloud"
        usesEmbedded() && EmbeddedTts.isReady() -> "embedded"
        usesEmbedded() -> "initializing"
        else -> QuizVoice.failureReason() ?: if (QuizVoice.isReady()) "available" else "initializing"
    }

    /** A one-line state for the environment check, in words a parent reads on the TV. */
    fun detail(): String = when (describe()) {
        "cloud" -> "云端语音"
        "embedded" -> "内置语音"
        "available" -> "电视自带引擎"
        "initializing" -> "加载中"
        "no-language" -> "缺中文语音"
        else -> "未安装"
    }

    /** Switches the offline voice preference from the console or TV settings. */
    fun preferEmbedded(context: Context, on: Boolean) {
        val app = context.applicationContext
        this.app = app
        QuizPrefs(app).embeddedVoice = on
        if (on) {
            PackStore.ready(app)?.let { EmbeddedTts.prepare(app, it) }
        } else {
            EmbeddedTts.stop()
            if (!usesCloud()) QuizVoice.warm(app)
        }
    }

    /** Enables or disables the cloud neural voice (e.g. from the console). */
    fun preferCloud(context: Context, on: Boolean) {
        val app = context.applicationContext
        this.app = app
        QuizPrefs(app).cloudVoice = on
        if (!on) {
            CloudTts.stop()
            if (!usesEmbedded()) QuizVoice.warm(app)
        }
    }
}

package dev.plume.quiz

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * One text-to-speech engine for the whole app, started as soon as the desktop appears.
 *
 * A Chinese engine loads its model when the TextToSpeech object is created, and on TV hardware that
 * takes seconds. Creating the engine inside the quiz page meant the question was on screen long before
 * the voice was ready, and every visit paid the cost again; started once at desktop start, the engine
 * is warm by the time a question appears, and the next question speaks at once.
 */
object QuizVoice {
    private var appCtx: Context? = null
    private var engine: TextToSpeech? = null
    private var lastEngineName: String? = null
    private var starting = false
    @Volatile private var ready = false
    /** "no-engine" or "no-language" once the engine is known to be unusable; null while it works. */
    @Volatile private var failure: String? = null
    private var autoRetries = 0
    private var lastFailElapsedMs = 0L
    private var utterance = 0
    private var rate = 0.85f
    @Volatile private var playedAt = 0L
    private val callbacks = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()

    /** Counts engine creations, so a callback from a replaced engine is ignored (see [start]). */
    private var generation = 0

    /** Starts the engine, or retries a transient failure when an engine is installed. Safe to call often. */
    @Synchronized
    fun warm(context: Context) {
        val app = context.applicationContext
        appCtx = app
        if (ready || starting) return
        if (engine != null) {
            val engines = TtsDiagnosis.names(TtsProbe.engineNames(app))
            val now = SystemClock.elapsedRealtime()
            if (!TtsDiagnosis.shouldRetryInit(failure, engines.isNotEmpty(), autoRetries, lastFailElapsedMs, now)) return
            autoRetries++
            failure = null
            runCatching { engine?.shutdown() }
            engine = null
        }
        start(app, null)
    }

    /**
     * Builds the engine. [engineName] null means "the system default"; a non-null value asks one
     * engine by package, which is the retry path for a TV that has an engine installed but no
     * default set (see [TtsDiagnosis.explicitEngine]).
     *
     * [generation] makes the retry safe: an engine that answers during its own constructor must not
     * leave the field pointing at the instance that just failed.
     */
    @Synchronized
    private fun start(app: Context, engineName: String?) {
        starting = true
        lastEngineName = engineName
        val gen = ++generation
        val listener = TextToSpeech.OnInitListener { status ->
            if (gen == generation) onInit(app, engineName, status)
        }
        val created = if (engineName == null) TextToSpeech(app, listener)
        else TextToSpeech(app, listener, engineName)
        if (gen == generation) engine = created else runCatching { created.shutdown() }
    }

    @Synchronized
    private fun onInit(app: Context, engineName: String?, status: Int) {
        starting = false
        if (status != TextToSpeech.SUCCESS) {
            // Most TVs here have no engine at all, and the constructor answers the same -1 for
            // that as for an engine that is installed but will not start. The list of engines
            // on board tells the two apart; see TtsDiagnosis.
            val engines = TtsDiagnosis.names(TtsProbe.engineNames(app))
            val retry = TtsDiagnosis.explicitEngine(engineName, engines)
            if (retry != null) {
                android.util.Log.w("GrowQuiz", "tts default engine failed ($status); asking $retry")
                runCatching { engine?.shutdown() }
                engine = null
                start(app, retry)
                return
            }
            android.util.Log.w("GrowQuiz", "tts init failed: $status engines=${engines.joinToString(",")}")
            failure = "no-engine"
            lastFailElapsedMs = SystemClock.elapsedRealtime()
            dev.plume.DiagLog.event(TtsDiagnosis.typeFor(engines), TtsDiagnosis.msg(status, engines))
            return
        }
        configure()
    }

    @Synchronized
    private fun configure() {
        val e = engine ?: return
        if (ready) return
        // zh-CN first, then plain Chinese, then the device's own locale: an engine that only has the
        // latter still beats silence, and some engines miss the Chinese data until installed.
        val ok = listOf(Locale.SIMPLIFIED_CHINESE, Locale.CHINESE, Locale.getDefault())
            .any { l -> e.setLanguage(l) >= TextToSpeech.LANG_AVAILABLE }
        if (!ok) {
            android.util.Log.w("GrowQuiz", "tts has no usable language")
            failure = "no-language"
            lastFailElapsedMs = SystemClock.elapsedRealtime()
            dev.plume.DiagLog.event("tts_no_language")
            return
        }
        // A little slower than default: at full speed the four options run into each other.
        e.setSpeechRate(rate)
        e.setPitch(1f)
        failure = null
        autoRetries = 0
        ready = true
        watchSynthesis(e) // synthesis failures after the queue accepted the text land in the report
    }

    /** True once the engine can speak. Configures it here if the init callback arrived before the field did. */
    fun isReady(): Boolean {
        if (ready) return true
        if (failure == null && engine != null) configure()
        return ready
    }

    /** No engine, or one without a Chinese voice: the caller says so once instead of reading nothing. */
    fun isUnusable(): Boolean = failure != null

    /** "no-engine" / "no-language" for the parents' console, null while the engine is fine. */
    fun failureReason(): String? = failure

    fun playedAt(): Long = playedAt

    /**
     * Speaks [text]. With [flush] the current sentence stops and this one starts at once, which is what a
     * new question needs; without it the text waits behind what is already being said, so the stem and
     * the options can be sent together and the voice begins before the rest is synthesised.
     */
    fun speak(text: String, flush: Boolean = true, onDone: (() -> Unit)? = null): Boolean {
        if (text.isBlank() || !isReady()) return false
        val queue = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val id = "quiz_${utterance++}"
        if (flush) callbacks.clear()
        if (onDone != null) callbacks[id] = onDone
        return try {
            val ok = engine?.speak(text, queue, null, id) == TextToSpeech.SUCCESS
            if (ok) {
                playedAt = SystemClock.elapsedRealtime()
            } else {
                callbacks.remove(id)
                dev.plume.DiagLog.event("tts_speak_fail", "queue rejected")
            }
            ok
        } catch (e: Exception) {
            callbacks.remove(id)
            android.util.Log.w("GrowQuiz", "tts speak failed", e)
            dev.plume.DiagLog.event("tts_speak_fail", e.message ?: e.javaClass.simpleName)
            false
        }
    }

    /** Rebuilds the engine after a dropped TTS service connection (e.g. Samsung SMT code=-5). */
    @Synchronized
    private fun reconnectAfterError(errorCode: Int) {
        if (!TtsDiagnosis.isReconnectableError(errorCode)) return
        val app = appCtx ?: return
        val now = SystemClock.elapsedRealtime()
        if (autoRetries >= TtsDiagnosis.MAX_AUTO_RETRIES) return
        if (lastFailElapsedMs > 0L && now - lastFailElapsedMs < TtsDiagnosis.RETRY_GAP_MS) return
        autoRetries++
        lastFailElapsedMs = now
        ready = false
        runCatching { engine?.shutdown() }
        engine = null
        start(app, lastEngineName)
    }

    /**
     * GrowQuiz: an engine takes the text and then fails in the synthesis, which [speak] cannot see —
     * the report needs the utterance callback for that. Installed once, when the engine is up.
     */
    private fun watchSynthesis(e: TextToSpeech) {
        try {
            e.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onError(utteranceId: String?) {
                    dev.plume.DiagLog.event("tts_speak_fail", "utterance $utteranceId")
                    utteranceId?.let { callbacks.remove(it)?.invoke() }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?, errorCode: Int) {
                    dev.plume.DiagLog.event("tts_speak_fail", "utterance $utteranceId code=$errorCode")
                    reconnectAfterError(errorCode)
                    utteranceId?.let { callbacks.remove(it)?.invoke() }
                }

                override fun onStart(utteranceId: String?) {
                    playedAt = SystemClock.elapsedRealtime()
                }

                override fun onDone(utteranceId: String?) {
                    utteranceId?.let { callbacks.remove(it)?.invoke() }
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    utteranceId?.let { callbacks.remove(it) }
                }
            })
        } catch (_: Exception) {
        }
    }

    fun stop() {
        callbacks.clear()
        try { engine?.stop() } catch (e: Exception) {}
    }
}

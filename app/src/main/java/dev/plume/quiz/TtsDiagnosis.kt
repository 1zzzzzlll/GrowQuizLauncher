package dev.plume.quiz

/**
 * Which report event an unusable speech engine deserves.
 *
 * `TextToSpeech` answers the same error (-1) for a TV with no engine at all and for an engine that
 * is installed but will not start, so the event name alone has to carry the difference: most TVs in
 * China ship with no engine, and one such TV per report must not look like a broken engine on every
 * set. The engine list decides. Kept free of Android classes so it can be tested off the TV.
 */
object TtsDiagnosis {
    /** No engine on board: the parents must install one. */
    const val MISSING = "tts_missing"

    /** An engine answers, but it will not start: its data or version is wrong. */
    const val INIT_FAILED = "tts_init_fail"

    /** The engine package names, trimmed, without duplicates, sorted, so reports compare by eye. */
    fun names(raw: List<String?>): List<String> =
        raw.mapNotNull { it?.trim() }.filter { it.isNotEmpty() }.distinct().sorted()

    /** The event type for an init failure, decided by what the TV has installed. */
    fun typeFor(engines: List<String>): String = if (engines.isEmpty()) MISSING else INIT_FAILED

    /**
     * The engine to ask by name after a failed start, or null when asking again would only repeat it.
     *
     * A set-top box in the field listed the sherpa engine and still answered -1 on every start: the
     * engine was installed but none was set as the system default (`tts_default_synth` empty). The
     * constructor without a package name only consults that default, so naming the installed engine
     * is the difference between a read-aloud question and a silent screen for a five-year-old. The
     * second attempt is never retried, or a broken engine would loop.
     */
    fun explicitEngine(engineName: String?, engines: List<String>): String? =
        if (engineName == null) engines.firstOrNull() else null

    /** The message that goes with [typeFor]: the status, plus the engines when there are any. */
    fun msg(status: Int, engines: List<String>): String =
        if (engines.isEmpty()) "status=$status" else "status=$status engines=${engines.joinToString(",")}"

    /** Minimum wait before retrying a transient TTS failure (boot-time no-language or service drop). */
    const val RETRY_GAP_MS = 30_000L

    /** Cap automatic rebuilds per process so a permanently broken engine cannot spin forever. */
    const val MAX_AUTO_RETRIES = 3

    /**
     * Whether a failed system TTS engine is worth initializing again.
     *
     * Field reports on UNP-SJA8 (Sherpa) and Samsung SM-T860 (Samsung SMT) showed setLanguage()
     * failing right after boot (boot=18..90 s) while the engine worked fine later — keeping the
     * permanent failure left the whole session silent for up to 148 quizzes. Never retries when
     * no engine is installed at all.
     */
    fun shouldRetryInit(
        failure: String?,
        enginesAvailable: Boolean,
        retries: Int,
        lastFailElapsedMs: Long,
        nowElapsedMs: Long,
    ): Boolean {
        if (failure == null || !enginesAvailable) return false
        if (retries >= MAX_AUTO_RETRIES) return false
        if (lastFailElapsedMs > 0L && nowElapsedMs - lastFailElapsedMs < RETRY_GAP_MS) return false
        return true
    }

    /** Utterance error codes where tearing down and rebinding the TTS service can recover speech. */
    fun isReconnectableError(errorCode: Int): Boolean =
        errorCode == -5 /* TextToSpeech.ERROR_SERVICE */ || errorCode == -9 /* ERROR_NOT_INSTALLED_YET */
}

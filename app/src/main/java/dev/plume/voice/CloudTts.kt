package dev.plume.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.SystemClock
import dev.plume.BuildConfig
import dev.plume.DiagLog
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

/**
 * Cloud neural speech (24 kHz Microsoft XiaoyiNeural hosted and cached behind our update server)
 * with a 40 MB local MP3 cache and instant fallback to the on-device voice when offline.
 *
 * Stem and options are fetched separately so the stem plays within ~0.3–1.0 s while the options
 * download in parallel on [prefetchPool]. Any clip already on disk plays offline in ~20 ms.
 */
object CloudTts {
    private const val CLIP_CACHE_BYTES = 40L * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 4_500
    private const val READ_TIMEOUT_MS = 12_000
    private const val PREFETCH_CONNECT_TIMEOUT_MS = 4_000
    private const val PREFETCH_READ_TIMEOUT_MS = 12_000
    private const val COOLDOWN_MS = 12_000L
    private const val RETRY_PAUSE_MS = 120L
    private const val SPEAKING_TAIL_MS = 450L
    private const val MIN_VALID_MP3_BYTES = 200L
    private const val VOICE_NAME = "xiaoyi"
    private const val PREFS = "voice_cloud_meta"
    private const val KEY_PROFILE = "profile"

    private class Say(
        val text: String,
        val gen: Int,
        val onFallback: (() -> Unit)?,
        val onDone: (() -> Unit)? = null,
    )

    private val queue = LinkedBlockingQueue<Say>()
    // Single-threaded so current options finish before explanation or next-question prefetches start,
    // avoiding 3-5 simultaneous WebSocket bursts that starve the foreground stem on residential Wi-Fi.
    private val prefetchPool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cloud-tts-prefetch").apply { isDaemon = true }
    }

    @Volatile private var app: Context? = null
    @Volatile private var gen = 0
    @Volatile private var playedAt = 0L
    @Volatile private var cooldownUntil = 0L
    @Volatile private var speakingUntil = 0L
    @Volatile private var activePlayer: MediaPlayer? = null
    private var worker: Thread? = null

    fun isConfigured(): Boolean =
        BuildConfig.UPDATE_BASE.isNotBlank() && BuildConfig.REPORT_TOKEN.isNotBlank()

    fun isCoolingDown(): Boolean = isCoolingDownAt(cooldownUntil, SystemClock.elapsedRealtime())

    internal fun isCoolingDownAt(untilMs: Long, nowMs: Long): Boolean =
        untilMs > 0L && nowMs < untilMs

    internal fun shouldTripCooldown(background: Boolean): Boolean = !background

    internal fun maxAttempts(background: Boolean): Int = if (background) 1 else 2

    internal fun hasValidCachedFile(file: File): Boolean =
        file.isFile && file.length() > MIN_VALID_MP3_BYTES

    /** Clears any transient network cooldown (e.g. when a parent explicitly taps the console test button). */
    fun resetCooldown() {
        cooldownUntil = 0L
    }

    fun hasCached(context: Context, text: String): Boolean =
        text.isNotBlank() && hasValidCachedFile(clipFile(context.applicationContext, text))

    fun hasCachedParts(context: Context, first: String, rest: String): Boolean =
        hasCached(context, first) && (rest.isBlank() || hasCached(context, rest))

    fun playedAt(): Long = playedAt

    fun isSpeaking(): Boolean =
        activePlayer != null || SystemClock.elapsedRealtime() < speakingUntil

    fun speak(
        context: Context,
        text: String,
        flush: Boolean,
        onFallback: (() -> Unit)? = null,
        onDone: (() -> Unit)? = null,
    ): Boolean {
        if (text.isBlank() || !isConfigured()) return false
        val appCtx = context.applicationContext
        app = appCtx
        startWorker()
        if (flush) stop()
        queue.offer(Say(text, gen, onFallback, onDone))
        return true
    }

    /**
     * Speaks [first] (the stem) immediately and downloads [rest] (the options) in parallel while
     * [first] is being spoken, so there is no network gap between the question and its options.
     */
    fun speakParts(context: Context, first: String, rest: String, onFallback: () -> Unit): Boolean {
        if (first.isBlank() || !isConfigured()) return false
        val appCtx = context.applicationContext
        app = appCtx
        startWorker()
        stop()
        val mine = gen
        if (rest.isNotBlank()) {
            prefetchPool.execute {
                if (mine == gen) runCatching { ensureCached(appCtx, rest, background = true, expectedGen = mine) }
            }
        }
        queue.offer(Say(first, mine, onFallback))
        if (rest.isNotBlank()) {
            queue.offer(Say(rest, mine, onFallback = null))
        }
        return true
    }

    /** Pre-downloads the next question into the local MP3 cache while the current one is on screen. */
    fun warmParts(context: Context, first: String, rest: String) {
        if (!isConfigured() || isCoolingDown()) return
        val appCtx = context.applicationContext
        app = appCtx
        prefetchPool.execute {
            if (first.isNotBlank()) runCatching { ensureCached(appCtx, first, background = true) }
            if (rest.isNotBlank()) runCatching { ensureCached(appCtx, rest, background = true) }
        }
    }

    fun stop() {
        gen++
        speakingUntil = 0L
        queue.clear()
        runCatching { activePlayer?.stop() }
    }

    private fun startWorker() {
        synchronized(this) {
            if (worker != null) return
            worker = Thread {
                while (true) {
                    val say = try {
                        queue.take()
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                    if (say.gen != gen) continue
                    try {
                        process(say)
                    } catch (e: Throwable) {
                        android.util.Log.w("GrowQuiz", "cloud tts worker error", e)
                        if (say.gen == gen) say.onDone?.invoke()
                    }
                }
            }.apply {
                name = "cloud-tts"
                isDaemon = true
                start()
            }
        }
    }

    private fun process(say: Say) {
        val context = app ?: run {
            if (say.gen == gen) say.onDone?.invoke()
            return
        }
        if (say.gen != gen) return
        val mp3 = ensureCached(context, say.text, background = false, expectedGen = say.gen)
        if (mp3 == null) {
            if (say.gen == gen) {
                // Drop any queued second half of this question and hand the whole reading to offline fallback.
                gen++
                queue.clear()
                if (say.onFallback != null) say.onFallback.invoke() else say.onDone?.invoke()
            }
            return
        }
        if (say.gen != gen) return
        if (playMp3(context, mp3, say.gen, say.onFallback) && say.gen == gen) {
            say.onDone?.invoke()
        }
    }

    private fun ensureCached(
        context: Context,
        text: String,
        background: Boolean,
        expectedGen: Int = -1,
    ): File? {
        val file = clipFile(context, text)
        if (hasValidCachedFile(file)) {
            runCatching { file.setLastModified(System.currentTimeMillis()) }
            return file
        }
        if (isCoolingDown()) return null
        return synchronized(text.intern()) {
            if (hasValidCachedFile(file)) return@synchronized file
            fetchToCache(context, text, file, background = background, expectedGen = expectedGen)
        }
    }

    private fun fetchToCache(
        context: Context,
        text: String,
        target: File,
        background: Boolean,
        expectedGen: Int,
    ): File? {
        val url = VoicePacks.cloudSpeakUrl(BuildConfig.UPDATE_BASE)
        val payload = JSONObject()
            .put("text", text)
            .put("voice", VOICE_NAME)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val attempts = maxAttempts(background)
        var lastError: Exception? = null

        for (attempt in 0 until attempts) {
            if (expectedGen >= 0 && expectedGen != gen) return null
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = if (background) PREFETCH_CONNECT_TIMEOUT_MS else CONNECT_TIMEOUT_MS
                    readTimeout = if (background) PREFETCH_READ_TIMEOUT_MS else READ_TIMEOUT_MS
                    doOutput = true
                    setFixedLengthStreamingMode(payload.size)
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("X-Report-Token", BuildConfig.REPORT_TOKEN)
                    // After an 8+ minute quiz interval, residential NAT routers silently drop idle
                    // keep-alive mappings; forcing Connection: close on retry dials a fresh socket.
                    if (attempt > 0) setRequestProperty("Connection", "close")
                }
                conn.outputStream.use { it.write(payload) }
                val code = conn.responseCode
                if (code != 200) throw java.io.IOException("http $code")
                val profile = conn.getHeaderField("X-TTS-Profile")?.trim().orEmpty()
                val bytes = conn.inputStream.use { it.readBytes() }
                if (bytes.size <= MIN_VALID_MP3_BYTES) throw java.io.IOException("short mp3 ${bytes.size}")
                if (profile.isNotEmpty()) syncProfile(context, profile)
                val tmp = File(target.parentFile, "${target.name}.${System.nanoTime()}.tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) {
                    target.writeBytes(bytes)
                    tmp.delete()
                }
                cooldownUntil = 0L
                prune(context)
                return target
            } catch (e: Exception) {
                lastError = e
                // 4xx (e.g. bad token/request) will not succeed on immediate retry.
                val nonRetryable = e.message?.startsWith("http 4") == true
                if (attempt + 1 < attempts && !nonRetryable && (expectedGen < 0 || expectedGen == gen)) {
                    runCatching { Thread.sleep(RETRY_PAUSE_MS) }
                } else {
                    break
                }
            } finally {
                conn?.disconnect()
            }
        }

        if (shouldTripCooldown(background) && (expectedGen < 0 || expectedGen == gen)) {
            val now = SystemClock.elapsedRealtime()
            val wasCooling = isCoolingDownAt(cooldownUntil, now)
            cooldownUntil = now + COOLDOWN_MS
            if (!wasCooling && lastError != null) {
                android.util.Log.w("GrowQuiz", "cloud tts unavailable, falling back offline", lastError)
                DiagLog.event("tts_cloud_fail", lastError.message ?: lastError.javaClass.simpleName)
            }
        }
        return null
    }

    /** Returns true when playback finished on its own; false when cancelled or handed to [onFallback]. */
    private fun playMp3(context: Context, file: File, mine: Int, onFallback: (() -> Unit)?): Boolean {
        val manager = context.getSystemService(AudioManager::class.java)
        val requestOwnFocus = !dev.plume.quiz.MediaPlaybackRules.shouldSkipUtteranceAudioFocus(
            quizHoldingFocus = dev.plume.quiz.QuizMediaControl.holdingFocus,
            blockedAlertForeground = dev.plume.quiz.BlockedAppActivity.isForeground,
        )
        val focus = if (requestOwnFocus) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .build()
        } else null
        if (focus != null) manager?.let { runCatching { it.requestAudioFocus(focus) } }
        val done = CountDownLatch(1)
        val player = MediaPlayer()
        var failed = false
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            FileInputStream(file).use { fis ->
                player.setDataSource(fis.fd)
            }
            player.setVolume(1.0f, 1.0f)
            player.setOnCompletionListener { done.countDown() }
            player.setOnErrorListener { _, _, _ ->
                failed = true
                done.countDown()
                true
            }
            player.prepare()
            if (mine != gen) return false
            activePlayer = player
            player.start()
            playedAt = SystemClock.elapsedRealtime()
            val maxWaitMs = (player.duration.coerceIn(500, 45_000) + 1_500).toLong()
            val deadline = SystemClock.elapsedRealtime() + maxWaitMs
            while (mine == gen && SystemClock.elapsedRealtime() < deadline) {
                if (done.await(40, TimeUnit.MILLISECONDS)) break
            }
            if (mine != gen) return false
            if (failed) {
                runCatching { file.delete() } // corrupted file: evict and fall back
                if (onFallback != null) {
                    onFallback.invoke()
                    return false
                }
            }
            return true
        } catch (e: Exception) {
            android.util.Log.w("GrowQuiz", "cloud mp3 play failed", e)
            DiagLog.event("tts_cloud_play_fail", e.message ?: e.javaClass.simpleName)
            if (mine == gen && onFallback != null) {
                onFallback.invoke()
                return false
            }
            return mine == gen
        } finally {
            if (mine == gen) speakingUntil = SystemClock.elapsedRealtime() + SPEAKING_TAIL_MS
            runCatching { player.stop() }
            runCatching { player.release() }
            if (activePlayer === player) activePlayer = null
            if (focus != null) manager?.let { runCatching { it.abandonAudioFocusRequest(focus) } }
        }
    }

    /** When the server changes voice or speed, evict cached clips from the previous profile. */
    private fun syncProfile(context: Context, profile: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prev = prefs.getString(KEY_PROFILE, null)
        if (prev != null && prev != profile) {
            clips(context).listFiles()?.forEach { runCatching { it.delete() } }
        }
        if (prev != profile) prefs.edit().putString(KEY_PROFILE, profile).apply()
    }

    private fun clips(context: Context): File = File(context.filesDir, "voice_cloud").apply { mkdirs() }

    private fun clipFile(context: Context, text: String): File =
        File(clips(context), VoicePacks.cloudClipName(text, VOICE_NAME))

    private fun prune(context: Context) {
        val files = clips(context).listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= CLIP_CACHE_BYTES) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= CLIP_CACHE_BYTES) break
            total -= file.length()
            file.delete()
        }
    }
}

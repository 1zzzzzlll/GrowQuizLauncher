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
    private const val CONNECT_TIMEOUT_MS = 3_000
    private const val READ_TIMEOUT_MS = 7_500
    private const val COOLDOWN_MS = 45_000L
    private const val VOICE_NAME = "xiaoyi"
    private const val PREFS = "voice_cloud_meta"
    private const val KEY_PROFILE = "profile"

    private class Say(
        val text: String,
        val gen: Int,
        val onFallback: (() -> Unit)?,
    )

    private val queue = LinkedBlockingQueue<Say>()
    private val prefetchPool = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "cloud-tts-prefetch").apply { isDaemon = true }
    }

    @Volatile private var app: Context? = null
    @Volatile private var gen = 0
    @Volatile private var playedAt = 0L
    @Volatile private var cooldownUntil = 0L
    @Volatile private var activePlayer: MediaPlayer? = null
    private var worker: Thread? = null

    fun isConfigured(): Boolean =
        BuildConfig.UPDATE_BASE.isNotBlank() && BuildConfig.REPORT_TOKEN.isNotBlank()

    fun isCoolingDown(): Boolean {
        val until = cooldownUntil
        return until > 0L && SystemClock.elapsedRealtime() < until
    }

    fun playedAt(): Long = playedAt

    fun speak(context: Context, text: String, flush: Boolean, onFallback: (() -> Unit)? = null): Boolean {
        if (text.isBlank() || !isConfigured()) return false
        val appCtx = context.applicationContext
        app = appCtx
        startWorker()
        if (flush) stop()
        queue.offer(Say(text, gen, onFallback))
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
                if (mine == gen) runCatching { ensureCached(appCtx, rest) }
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
            if (first.isNotBlank()) runCatching { ensureCached(appCtx, first) }
            if (rest.isNotBlank()) runCatching { ensureCached(appCtx, rest) }
        }
    }

    fun stop() {
        gen++
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
        val context = app ?: return
        if (say.gen != gen) return
        val mp3 = ensureCached(context, say.text)
        if (mp3 == null) {
            if (say.gen == gen) {
                // Drop any queued second half of this question and hand the whole reading to offline fallback.
                gen++
                queue.clear()
                say.onFallback?.invoke()
            }
            return
        }
        if (say.gen != gen) return
        playMp3(context, mp3, say.gen, say.onFallback)
    }

    private fun ensureCached(context: Context, text: String): File? {
        val file = clipFile(context, text)
        if (file.isFile && file.length() > 200L) {
            runCatching { file.setLastModified(System.currentTimeMillis()) }
            return file
        }
        if (isCoolingDown()) return null
        return synchronized(text.intern()) {
            if (file.isFile && file.length() > 200L) return@synchronized file
            fetchToCache(context, text, file)
        }
    }

    private fun fetchToCache(context: Context, text: String, target: File): File? {
        val url = VoicePacks.cloudSpeakUrl(BuildConfig.UPDATE_BASE)
        var conn: HttpURLConnection? = null
        try {
            val payload = JSONObject()
                .put("text", text)
                .put("voice", VOICE_NAME)
                .toString()
                .toByteArray(Charsets.UTF_8)
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setFixedLengthStreamingMode(payload.size)
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("X-Report-Token", BuildConfig.REPORT_TOKEN)
            }
            conn.outputStream.use { it.write(payload) }
            val code = conn.responseCode
            if (code != 200) throw java.io.IOException("http $code")
            val profile = conn.getHeaderField("X-TTS-Profile")?.trim().orEmpty()
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.size <= 200) throw java.io.IOException("short mp3 ${bytes.size}")
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
            val now = SystemClock.elapsedRealtime()
            val wasCooling = isCoolingDown()
            cooldownUntil = now + COOLDOWN_MS
            if (!wasCooling) {
                android.util.Log.w("GrowQuiz", "cloud tts unavailable, falling back offline", e)
                DiagLog.event("tts_cloud_fail", e.message ?: e.javaClass.simpleName)
            }
            return null
        } finally {
            conn?.disconnect()
        }
    }

    private fun playMp3(context: Context, file: File, mine: Int, onFallback: (() -> Unit)?) {
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        runCatching { manager.requestAudioFocus(focus) }
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
            if (mine != gen) return
            activePlayer = player
            player.start()
            playedAt = SystemClock.elapsedRealtime()
            val maxWaitMs = (player.duration.coerceIn(500, 45_000) + 1_500).toLong()
            val deadline = SystemClock.elapsedRealtime() + maxWaitMs
            while (mine == gen && SystemClock.elapsedRealtime() < deadline) {
                if (done.await(40, TimeUnit.MILLISECONDS)) break
            }
            if (failed && mine == gen) {
                runCatching { file.delete() } // corrupted file: evict and fall back
                onFallback?.invoke()
            }
        } catch (e: Exception) {
            android.util.Log.w("GrowQuiz", "cloud mp3 play failed", e)
            DiagLog.event("tts_cloud_play_fail", e.message ?: e.javaClass.simpleName)
            if (mine == gen) onFallback?.invoke()
        } finally {
            if (activePlayer === player) activePlayer = null
            runCatching { player.stop() }
            runCatching { player.release() }
            runCatching { manager.abandonAudioFocusRequest(focus) }
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

package dev.plume.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import dev.plume.DiagLog
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * The bundled offline voice: a Chinese neural model that runs in this process as the offline fallback
 * (or primary voice when no cloud server is configured), so a TV with no system speech engine and no
 * internet still reads the question aloud.
 *
 * Streams clauses to [AudioTrack] as each clause finishes via [OfflineTts.generateWithCallback], so
 * the stem starts playing before the options finish computing and returning 0 aborts synthesis as soon
 * as the child answers. Every generated waveform is RMS-normalised and soft-limited before playback.
 */
/**
 * sherpa-onnx's JNI (`generateWithCallbackImpl`) looks up `invoke([F)Ljava/lang/Integer;` via
 * `GetMethodID` on the callback's runtime class. Kotlin 2.x indy lambdas only emit
 * `invoke(Object)Object`, and a non-null `Int` return type emits primitive `invoke([F)I` — both
 * abort the process with `NoSuchMethodError` in JNI. Boxed `Int?` produces the exact signature.
 */
internal class SherpaCallback(
    private val onSamples: (FloatArray) -> Int,
) : Function1<FloatArray, Int?> {
    override fun invoke(samples: FloatArray): Int? = onSamples(samples)
}

object EmbeddedTts {
    private const val CLIP_CACHE_BYTES = 40L * 1024 * 1024
    private const val CHUNK = 2048
    private const val SPEED = 1.08f

    private class Say(val text: String, val gen: Int, val play: Boolean, val onDone: (() -> Unit)? = null)

    private val queue = LinkedBlockingQueue<Say>()

    @Volatile private var engine: OfflineTts? = null
    @Volatile private var sampleRate = 22_050
    @Volatile private var loading = false
    @Volatile private var failure: String? = null
    @Volatile private var speaker = 0
    @Volatile private var app: Context? = null
    /** Bumped by [stop] and by a flushing [speak]: playback and callback loops compare it and drop out. */
    @Volatile private var gen = 0
    /** When a sentence last reached the speaker: the console's test button waits for this to move. */
    @Volatile private var playedAt = 0L
    @Volatile private var speaking = false

    private var worker: Thread? = null

    fun isReady(): Boolean = engine != null

    fun isLoading(): Boolean = loading

    fun reason(): String? = failure

    fun playedAt(): Long = playedAt

    fun isSpeaking(): Boolean = speaking

    @Synchronized
    fun prepare(context: Context, pack: VoicePack) {
        if (engine != null || loading || failure != null) return
        val dir = PackStore.dir(context.applicationContext, pack.id)
        if (!VoicePacks.hasModelOnDisk(dir)) {
            failure = "no-files"
            return
        }
        app = context.applicationContext
        speaker = pack.speaker
        loading = true
        startWorker()
        Thread {
            val began = SystemClock.elapsedRealtime()
            try {
                val lexicon = File(dir, VoicePacks.LEXICON).absolutePath
                val tokens = File(dir, VoicePacks.TOKENS).absolutePath
                val dataDir = File(dir, "espeak-ng-data").takeIf { it.isDirectory }?.absolutePath.orEmpty()
                val ruleFsts = VoicePacks.RULE_FSTS
                    .map { File(dir, it) }
                    .filter { it.isFile }
                    .joinToString(",") { it.absolutePath }

                val matchaFile = File(dir, VoicePacks.MATCHA_MODEL)
                val vocoderFile = File(dir, VoicePacks.VOCODER)
                val modelCfg = if (matchaFile.isFile && vocoderFile.isFile) {
                    OfflineTtsModelConfig(
                        matcha = OfflineTtsMatchaModelConfig(
                            acousticModel = matchaFile.absolutePath,
                            vocoder = vocoderFile.absolutePath,
                            lexicon = lexicon,
                            tokens = tokens,
                            dataDir = dataDir,
                        ),
                        numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
                        debug = false,
                        provider = "cpu",
                    )
                } else {
                    OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = File(dir, VoicePacks.MODEL).absolutePath,
                            lexicon = lexicon,
                            tokens = tokens,
                            dataDir = dataDir,
                            noiseScale = 0.55f,
                            noiseScaleW = 0.65f,
                        ),
                        numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
                        debug = false,
                        provider = "cpu",
                    )
                }
                val config = OfflineTtsConfig(
                    model = modelCfg,
                    ruleFsts = ruleFsts,
                    maxNumSentences = 2,
                    silenceScale = 0.35f,
                )
                val built = OfflineTts(assetManager = null, config = config)
                sampleRate = built.sampleRate()
                engine = built
                DiagLog.event("tts_local_ready", "sr=$sampleRate ms=${SystemClock.elapsedRealtime() - began}")
            } catch (e: Throwable) {
                failure = e.message ?: e.javaClass.simpleName
                DiagLog.event("tts_local_fail", "${e.javaClass.simpleName} ${e.message.orEmpty()}".trim())
                app?.let { dev.plume.quiz.QuizVoice.warm(it) }
            } finally {
                loading = false
            }
        }.start()
    }

    fun speak(context: Context, text: String, flush: Boolean, onDone: (() -> Unit)? = null): Boolean {
        if (text.isBlank() || engine == null) return false
        app = context.applicationContext
        if (flush) {
            gen++
            queue.clear()
        }
        queue.offer(Say(text, gen, play = true, onDone = onDone))
        return true
    }

    fun warmClip(context: Context, text: String) {
        if (text.isBlank() || engine == null) return
        app = context.applicationContext
        if (clipFile(context.applicationContext, text).isFile) return
        queue.offer(Say(text, gen, play = false))
    }

    fun stop() {
        gen++
        queue.clear()
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
                        say(say)
                    } catch (e: Throwable) {
                        android.util.Log.w("GrowQuiz", "voice synth failed", e)
                        DiagLog.event("tts_local_speak_fail", e.message ?: e.javaClass.simpleName)
                        if (say.play && say.gen == gen) say.onDone?.invoke()
                    }
                }
            }.apply {
                name = "voice-tts"
                priority = Thread.NORM_PRIORITY - 1
                isDaemon = true
                start()
            }
        }
    }

    private fun say(say: Say) {
        val context = app
        val engine = engine
        if (context == null || engine == null) {
            if (say.play && say.gen == gen) say.onDone?.invoke()
            return
        }
        if (say.gen != gen) return
        val cached = cachedClip(context, say.text)
        if (cached != null) {
            if (say.play && say.gen == gen) {
                playBuffer(context, cached, say.gen)
                if (say.gen == gen) say.onDone?.invoke()
            }
            return
        }
        if (!say.play) {
            val audio = engine.generate(say.text, speaker, SPEED)
            if (say.gen != gen) return
            val data = normalizeToPcm(audio.samples)
            if (data.isNotEmpty()) runCatching { writeClip(context, say.text, data) }
            return
        }
        streamAndCache(context, engine, say.text, say.gen)
        if (say.gen == gen) say.onDone?.invoke()
    }

    /**
     * Synthesises [text] clause-by-clause via [OfflineTts.generateWithCallback], streaming each clause
     * to [AudioTrack] immediately so the child hears the stem while the options are still computing.
     */
    private fun streamAndCache(context: Context, engine: OfflineTts, text: String, mine: Int) {
        val manager = context.getSystemService(AudioManager::class.java)
        val requestOwnFocus = !dev.plume.quiz.MediaPlaybackRules.shouldSkipUtteranceAudioFocus(
            quizHoldingFocus = dev.plume.quiz.QuizMediaControl.holdingFocus,
            blockedAlertForeground = dev.plume.quiz.BlockedAppActivity.isForeground,
        )
        val focus = if (requestOwnFocus) focusRequest() else null
        if (focus != null) manager?.let { runCatching { it.requestAudioFocus(focus) } }
        var track: AudioTrack? = null
        val collected = ArrayList<ShortArray>(4)
        var totalFrames = 0
        var interrupted = false
        val began = SystemClock.elapsedRealtime()
        var firstSoundMs = -1L
        try {
            track = openTrack()
            speaking = true
            track.play()
            @Suppress("UNCHECKED_CAST")
            val callback = SherpaCallback { chunk ->
                if (mine != gen) {
                    interrupted = true
                    return@SherpaCallback 0
                }
                val pcm = normalizeToPcm(chunk)
                if (pcm.isEmpty()) return@SherpaCallback 1
                collected += pcm
                if (firstSoundMs < 0L) {
                    firstSoundMs = SystemClock.elapsedRealtime() - began
                    playedAt = SystemClock.elapsedRealtime()
                }
                var at = 0
                while (at < pcm.size && mine == gen) {
                    val n = track.write(pcm, at, minOf(CHUNK, pcm.size - at))
                    if (n <= 0) break
                    at += n
                    totalFrames += n
                }
                if (mine != gen) {
                    interrupted = true
                    0
                } else {
                    1
                }
            } as (FloatArray) -> Int
            engine.generateWithCallback(text, speaker, SPEED, callback)
            if (firstSoundMs > 2500) DiagLog.event("tts_local_slow", "chars=${text.length} ms=$firstSoundMs")
            if (!interrupted && totalFrames > 0) {
                val full = ShortArray(collected.sumOf { it.size })
                var pos = 0
                for (part in collected) {
                    part.copyInto(full, pos)
                    pos += part.size
                }
                runCatching { writeClip(context, text, full) }
                waitDrain(track, totalFrames, mine)
            }
        } catch (e: Exception) {
            android.util.Log.w("GrowQuiz", "voice stream failed (sr=$sampleRate)", e)
            DiagLog.event("tts_local_play_fail", e.message ?: e.javaClass.simpleName)
        } finally {
            speaking = false
            runCatching { track?.stop() }
            runCatching { track?.release() }
            if (focus != null) manager?.let { runCatching { it.abandonAudioFocusRequest(focus) } }
        }
    }

    private fun playBuffer(context: Context, pcm: ShortArray, mine: Int) {
        val manager = context.getSystemService(AudioManager::class.java)
        val requestOwnFocus = !dev.plume.quiz.MediaPlaybackRules.shouldSkipUtteranceAudioFocus(
            quizHoldingFocus = dev.plume.quiz.QuizMediaControl.holdingFocus,
            blockedAlertForeground = dev.plume.quiz.BlockedAppActivity.isForeground,
        )
        val focus = if (requestOwnFocus) focusRequest() else null
        if (focus != null) manager?.let { runCatching { it.requestAudioFocus(focus) } }
        var track: AudioTrack? = null
        try {
            track = openTrack()
            speaking = true
            track.play()
            playedAt = SystemClock.elapsedRealtime()
            var at = 0
            while (at < pcm.size && mine == gen) {
                val n = track.write(pcm, at, minOf(CHUNK, pcm.size - at))
                if (n <= 0) break
                at += n
            }
            waitDrain(track, pcm.size, mine)
        } catch (e: Exception) {
            android.util.Log.w("GrowQuiz", "voice play failed (sr=$sampleRate frames=${pcm.size})", e)
            DiagLog.event("tts_local_play_fail", e.message ?: e.javaClass.simpleName)
        } finally {
            speaking = false
            runCatching { track?.stop() }
            runCatching { track?.release() }
            if (focus != null) manager?.let { runCatching { it.abandonAudioFocusRequest(focus) } }
        }
    }

    private fun waitDrain(track: AudioTrack, frames: Int, mine: Int) {
        val deadline = SystemClock.elapsedRealtime() + 1_000L + frames * 1000L / sampleRate
        while (mine == gen && track.playbackHeadPosition < frames && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(20)
        }
    }

    private fun focusRequest(): AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()

    private fun openTrack(): AudioTrack {
        val buffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(CHUNK * 2)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(buffer)
            .build()
    }

    /**
     * Normalises raw model samples (which often peak at 0.15..0.30 in AISHELL-3) to ~-14 dBFS active
     * RMS with a soft-knee limiter above 0.84, matching TV media loudness without clipping.
     */
    internal fun normalizeToPcm(samples: FloatArray): ShortArray {
        if (samples.isEmpty()) return ShortArray(0)
        var peak = 0f
        var activeSq = 0.0
        var activeCount = 0
        for (s in samples) {
            if (!s.isFinite()) continue
            val a = abs(s)
            if (a > peak) peak = a
            if (a > 0.008f) {
                activeSq += (s * s).toDouble()
                activeCount++
            }
        }
        if (peak <= 1e-5f) return ShortArray(samples.size)
        val rms = if (activeCount > 0) sqrt(activeSq / activeCount).toFloat() else peak
        val gain = (if (activeCount > 64) 0.20f / rms.coerceAtLeast(0.01f) else 0.88f / peak)
            .coerceIn(1.0f, 6.0f)
        val knee = 0.84f
        val headroom = 0.14f
        val out = ShortArray(samples.size)
        for (i in samples.indices) {
            val raw = samples[i]
            if (!raw.isFinite()) continue
            var v = raw * gain
            val a = abs(v)
            if (a > knee) {
                val limited = knee + headroom * tanh((a - knee) / headroom)
                v = if (v >= 0f) limited else -limited
            }
            out[i] = (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
        return out
    }

    // ---------- clip cache ----------

    private fun clips(context: Context): File = File(context.filesDir, "voice_clips").apply { mkdirs() }

    private fun clipFile(context: Context, text: String): File =
        File(clips(context), VoicePacks.clipName(text, speaker, SPEED))

    private fun cachedClip(context: Context, text: String): ShortArray? {
        val file = clipFile(context, text)
        if (!file.isFile || file.length() < 2) return null
        return try {
            val bytes = file.readBytes()
            val pcm = ShortArray(bytes.size / 2)
            for (i in pcm.indices) {
                val lo = bytes[i * 2].toInt() and 0xff
                val hi = bytes[i * 2 + 1].toInt()
                pcm[i] = ((hi shl 8) or lo).toShort()
            }
            file.setLastModified(System.currentTimeMillis())
            pcm
        } catch (_: Exception) {
            null
        }
    }

    private fun writeClip(context: Context, text: String, pcm: ShortArray) {
        val file = clipFile(context, text)
        val bytes = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            val v = pcm[i].toInt()
            bytes[i * 2] = (v and 0xff).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
        }
        file.writeBytes(bytes)
        prune(context)
    }

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

    @Synchronized
    fun release() {
        gen++
        queue.clear()
        failure = null
        runCatching { engine?.release() }
        engine = null
    }
}

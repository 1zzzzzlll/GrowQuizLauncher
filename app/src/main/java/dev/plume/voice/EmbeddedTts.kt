package dev.plume.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import dev.plume.DiagLog
import java.io.File
import java.util.concurrent.LinkedBlockingQueue

/**
 * The bundled voice: a Chinese neural model that runs in this process, so a TV with no speech engine
 * at all still reads the question aloud. Nothing has to be installed and no system setting has to be
 * granted — the two things that stop a set-top box from ever speaking (see docs/VOICE.md).
 *
 * A TV is a small computer, and this one is honest about it: the model is 8 kHz and needs seconds per
 * sentence. So the model is loaded once on a worker thread and kept, a whole question goes to the
 * model as one text instead of a stream of pieces ([Voice.speakParts]), and every sentence is kept as
 * raw PCM on disk — the second reading of the same question costs nothing.
 *
 * Synthesis cannot be interrupted inside the model; the answer to "stop talking" is to drop the queue
 * and start the next sentence as soon as the model returns. Playback itself stops within one chunk.
 */
object EmbeddedTts {
    private const val CLIP_CACHE_BYTES = 40L * 1024 * 1024
    private const val CHUNK = 2048

    /**
     * Speech rate. A pack may carry its own, but 0.9 sounded sluggish on a real quiz and 1.0 still a
     * little slow (the parent's words both times); a child reads along faster than either.
     */
    private const val SPEED = 1.12f

    private class Say(val text: String, val gen: Int, val play: Boolean)

    private val queue = LinkedBlockingQueue<Say>()

    @Volatile private var engine: OfflineTts? = null
    @Volatile private var sampleRate = 22_050
    @Volatile private var loading = false
    @Volatile private var failure: String? = null
    @Volatile private var speaker = 0
    @Volatile private var app: Context? = null
    /** Bumped by [stop] and by a flushing [speak]: playback loops compare it and drop out. */
    @Volatile private var gen = 0
    /** When a sentence last reached the speaker: the console's test button waits for this to move. */
    @Volatile private var playedAt = 0L

    private var worker: Thread? = null

    fun isReady(): Boolean = engine != null

    fun isLoading(): Boolean = loading

    /** "no-files" / "no-native" / "load-failed ..." once the model is known to be unusable here. */
    fun reason(): String? = failure

    /** The time a sentence last started playing, so a caller can tell "queued" from "heard". */
    fun playedAt(): Long = playedAt

    /**
     * Loads the model for [pack], once. Safe from any thread: a second call while loading returns at
     * once, and a failed load is not retried until the next process start (a model that does not fit
     * in RAM will not fit the second time either).
     */
    @Synchronized
    fun prepare(context: Context, pack: VoicePack) {
        if (engine != null || loading || failure != null) return
        val dir = PackStore.dir(context.applicationContext, pack.id)
        if (!File(dir, VoicePacks.MODEL).isFile) {
            failure = "no-files"
            return
        }
        app = context.applicationContext
        speaker = pack.speaker
        loading = true
        startWorker()
        Thread {
            val began = System.currentTimeMillis()
            try {
                val vits = OfflineTtsVitsModelConfig(
                    model = File(dir, VoicePacks.MODEL).absolutePath,
                    lexicon = File(dir, VoicePacks.LEXICON).absolutePath,
                    tokens = File(dir, VoicePacks.TOKENS).absolutePath,
                )
                // Two threads: a TV box has four small cores, and one more shortens a sentence without
                // starving the page that draws it.
                val config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(vits = vits, numThreads = 2, debug = false, provider = "cpu"),
                )
                val built = OfflineTts(assetManager = null, config = config)
                sampleRate = built.sampleRate()
                engine = built
                DiagLog.event("tts_local_ready", "sr=$sampleRate ms=${System.currentTimeMillis() - began}")
            } catch (e: Throwable) {
                // UnsatisfiedLinkError included: this APK carries no library for an x86 box, and the
                // report is the only way to learn that a device family needs one.
                failure = e.message ?: e.javaClass.simpleName
                DiagLog.event("tts_local_fail", "${e.javaClass.simpleName} ${e.message.orEmpty()}".trim())
            } finally {
                loading = false
            }
        }.start()
    }

    /**
     * Speaks [text]. With [flush] whatever is speaking stops and this sentence starts as soon as the
     * model has it, which is what a new question needs; without it the text waits its turn.
     */
    fun speak(context: Context, text: String, flush: Boolean): Boolean {
        if (text.isBlank() || engine == null) return false
        app = context.applicationContext
        if (flush) {
            gen++
            queue.clear()
        }
        queue.offer(Say(text, gen, play = true))
        return true
    }

    /**
     * Synthesises [text] into the clip cache without speaking it. A question takes seconds to compute
     * here; doing that while the child is still reading the previous one is what lets the next
     * question start at once.
     */
    fun warmClip(context: Context, text: String) {
        if (text.isBlank() || engine == null) return
        app = context.applicationContext
        if (clipFile(context.applicationContext, text).isFile) return
        queue.offer(Say(text, gen, play = false))
    }

    /** Stops the voice now: the page is closing, or narration was switched off. */
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
                    }
                }
            }.apply {
                name = "voice-tts"
                priority = Thread.NORM_PRIORITY - 1 // the page that draws the question comes first
                isDaemon = true
                start()
            }
        }
    }

    private fun say(say: Say) {
        val context = app ?: return
        val engine = engine ?: return
        if (say.gen != gen) return
        val pcm = cachedClip(context, say.text) ?: run {
            val began = System.currentTimeMillis()
            val audio = engine.generate(say.text, speaker, SPEED)
            val data = toPcm(audio.samples)
            if (data.isEmpty()) return
            // A synthesis that took longer than the audio lasts is worth knowing about: it is the
            // difference between a TV that reads the question and one that talks over the next one.
            val ms = System.currentTimeMillis() - began
            if (ms > 2500) DiagLog.event("tts_local_slow", "chars=${say.text.length} ms=$ms")
            runCatching { writeClip(context, say.text, data) }
            data
        }
        if (!say.play || say.gen != gen) return
        play(context, pcm)
    }

    /** Plays 16-bit mono PCM at the model's own rate, and returns when it ends or when [gen] moved on. */
    private fun play(context: Context, pcm: ShortArray) {
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        runCatching { manager.requestAudioFocus(request) }
        val mine = gen
        var track: AudioTrack? = null
        try {
            val buffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                .coerceAtLeast(CHUNK * 2)
            track = AudioTrack.Builder()
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
            track.play()
            playedAt = System.currentTimeMillis()
            var at = 0
            while (at < pcm.size && mine == gen) {
                val n = track.write(pcm, at, minOf(CHUNK, pcm.size - at))
                if (n <= 0) break
                at += n
            }
            // A stream write returns when the samples are buffered, not when they are heard. Stopping
            // here cut the last syllable of every sentence; wait for the head to reach the end instead,
            // and give up at once when a newer sentence arrived ([gen] moved).
            val deadline = System.currentTimeMillis() + 1_000L + pcm.size * 1000L / sampleRate
            while (mine == gen && track.playbackHeadPosition < pcm.size && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
        } catch (e: Exception) {
            android.util.Log.w("GrowQuiz", "voice play failed (sr=$sampleRate frames=${pcm.size})", e)
            DiagLog.event("tts_local_play_fail", e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { track?.stop() }
            runCatching { track?.release() }
            runCatching { manager.abandonAudioFocusRequest(request) }
        }
    }

    private fun toPcm(samples: FloatArray): ShortArray {
        val out = ShortArray(samples.size)
        for (i in samples.indices) {
            val v = samples[i].coerceIn(-1f, 1f)
            out[i] = (v * 32767f).toInt().toShort()
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
            file.setLastModified(System.currentTimeMillis()) // keeps a clip the quiz reuses from being pruned
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

    /** Keeps the cache under [CLIP_CACHE_BYTES], dropping the least recently spoken clips. */
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

    /** Frees the model when the app is done with it, or when another pack replaces it. */
    @Synchronized
    fun release() {
        gen++
        queue.clear()
        runCatching { engine?.release() }
        engine = null
    }
}

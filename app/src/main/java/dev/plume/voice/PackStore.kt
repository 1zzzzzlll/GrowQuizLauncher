package dev.plume.voice

import android.content.Context
import dev.plume.BuildConfig
import dev.plume.DiagLog
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Owns the voice pack on the device: what the server offers, what is on disk, and the download that
 * takes one to the other.
 *
 * The pack is a 32 MB zip of model files, so the download must survive a re-run: a partial file is
 * kept as `<id>.part` and the next attempt asks the server for the rest (`Range`), which matters on a
 * TV whose Wi-Fi drops during a five-minute download. Nothing is loaded until the zip matches the
 * manifest's SHA-256 and holds the three files the engine needs; only then is the old pack replaced,
 * so a failed download leaves a working voice in place.
 *
 * No page holds on to this: [state] is a snapshot and the screens poll it while a download runs.
 */
object PackStore {
    private const val PREFS = "voice_pack"
    private const val KEY_ID = "id"
    private const val KEY_NAME = "name"
    private const val KEY_SPEAKER = "speaker"
    private const val KEY_SPEED = "speed"
    private const val KEY_BYTES = "bytes"

    /** What the download is doing right now; [NONE] means no pack was ever installed on this device. */
    enum class Stage { NONE, DOWNLOADING, UNPACKING, READY, FAILED }

    /** [progress] is 0..100 and only means something while [stage] is DOWNLOADING or UNPACKING. */
    data class State(val stage: Stage = Stage.NONE, val progress: Int = 0, val note: String = "")

    @Volatile private var state = State()
    @Volatile private var busy = false

    fun state(): State = state

    /** The pack this device can speak with, or null when none is installed. */
    fun ready(context: Context): VoicePack? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getString(KEY_ID, null) ?: return null
        if (!File(dir(context, id), VoicePacks.MODEL).isFile) return null
        return VoicePack(
            id = id,
            name = prefs.getString(KEY_NAME, "") ?: "",
            url = "",
            bytes = prefs.getLong(KEY_BYTES, 0L),
            sha256 = "",
            speaker = prefs.getInt(KEY_SPEAKER, 0),
            speed = prefs.getFloat(KEY_SPEED, 0.9f),
        )
    }

    fun dir(context: Context): File = File(context.filesDir, "voice")

    fun dir(context: Context, id: String): File = File(dir(context), id)

    /** The packs the server lists, newest first as the server orders them. Runs on a worker thread: it hits the network. */
    fun offered(context: Context): List<VoicePack> {
        // The packs sit next to latest.json on the update host, so one setting reaches both.
        val base = BuildConfig.UPDATE_BASE
        if (base.isBlank()) return emptyList()
        val json = try {
            val conn = (URL(VoicePacks.manifestUrl(base)).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            DiagLog.event("voice_manifest_fail", e.message ?: e.javaClass.simpleName)
            return emptyList()
        }
        return parse(base, json)
    }

    /** The manifest, turned into packs. A malformed entry is skipped instead of taking the whole list down. */
    fun parse(base: String, json: String): List<VoicePack> {
        val packs = ArrayList<VoicePack>()
        try {
            val array = JSONObject(json).optJSONArray("packs") ?: return emptyList()
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("id").trim()
                val url = o.optString("url").trim()
                val sha = o.optString("sha256").trim()
                if (id.isEmpty() || url.isEmpty() || sha.isEmpty()) continue
                packs += VoicePack(
                    id = id,
                    name = o.optString("name").trim().ifEmpty { id },
                    url = VoicePacks.absolute(base, url),
                    bytes = o.optLong("bytes", 0L),
                    sha256 = sha,
                    speaker = o.optInt("speaker", 0),
                    speed = o.optDouble("speed", 0.9).toFloat(),
                )
            }
        } catch (_: Exception) {
            return emptyList()
        }
        return packs
    }

    /**
     * Downloads and installs [pack], on a worker thread. Safe to call again: a second call while one
     * runs is ignored, and a later call resumes a partial file instead of starting over.
     */
    @Synchronized
    fun install(context: Context, pack: VoicePack) {
        if (busy) return
        busy = true
        val app = context.applicationContext
        Thread {
            try {
                run(app, pack)
            } finally {
                busy = false
            }
        }.start()
    }

    private fun run(app: Context, pack: VoicePack) {
        val root = dir(app)
        root.mkdirs()
        val part = File(root, "${pack.id}.part")
        val zip = File(root, "${pack.id}.zip")
        zip.delete()
        try {
            state = State(Stage.DOWNLOADING, 0)
            download(pack, part) { done, total ->
                state = State(Stage.DOWNLOADING, if (total > 0) (done * 100 / total).toInt() else 0)
            }
            val got = VoicePacks.sha256(part)
            if (got == null || !got.equals(pack.sha256, ignoreCase = true)) {
                part.delete()
                return fail("sha256 ${got ?: "unreadable"}")
            }
            state = State(Stage.UNPACKING, 0)
            part.renameTo(zip)
            val tmp = File(root, "${pack.id}.tmp")
            tmp.deleteRecursively()
            tmp.mkdirs()
            val files = unzip(zip, tmp)
            if (!VoicePacks.complete(files)) {
                tmp.deleteRecursively()
                zip.delete()
                return fail("incomplete ${files.joinToString(",")}")
            }
            val target = dir(app, pack.id)
            // Replace only after the new pack is on disk and complete: a failure above leaves the old voice.
            target.deleteRecursively()
            if (!tmp.renameTo(target)) {
                tmp.deleteRecursively()
                zip.delete()
                return fail("rename")
            }
            zip.delete()
            // Older packs are dead weight once one is installed.
            root.listFiles()?.forEach { if (it.isDirectory && it.name != pack.id) it.deleteRecursively() }
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_ID, pack.id)
                .putString(KEY_NAME, pack.name)
                .putInt(KEY_SPEAKER, pack.speaker)
                .putFloat(KEY_SPEED, pack.speed)
                .putLong(KEY_BYTES, pack.bytes)
                .apply()
            // A pack on disk is the voice this device asked for: switch to it here, not in whichever
            // page started the download — a pack started from the phone, or from a page the parent
            // left, must still be the voice the next question speaks with.
            dev.plume.quiz.QuizPrefs(app).embeddedVoice = true
            // A pack replaces the model under the running engine: dropping it first is what makes the
            // new voice speak now instead of at the next process start.
            EmbeddedTts.release()
            EmbeddedTts.prepare(app, pack)
            state = State(Stage.READY, 100)
            DiagLog.event("voice_pack_ready", "id=${pack.id} bytes=${pack.bytes}")
        } catch (e: Exception) {
            runCatching { zip.delete() }
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun fail(why: String) {
        state = State(Stage.FAILED, 0, why)
        DiagLog.event("voice_pack_fail", why)
    }

    /** Downloads to [part], asking for the bytes already on disk. Calls [onProgress] with the running size. */
    private fun download(pack: VoicePack, part: File, onProgress: (Long, Long) -> Unit) {
        var have = if (part.isFile) part.length() else 0L
        if (have > pack.bytes) { // a different pack, or a corrupted partial file
            part.delete()
            have = 0L
        }
        val conn = (URL(pack.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            val code = conn.responseCode
            // 206 is the rest of the file, 200 means the server ignored the range and starts over.
            if (code != 206 && code != 200) throw java.io.IOException("http $code")
            if (code == 200) have = 0L
            val total = if (pack.bytes > 0) pack.bytes else have + conn.contentLengthLong.coerceAtLeast(0)
            conn.inputStream.use { input ->
                java.io.FileOutputStream(part, have > 0).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var done = have
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        done += read
                        onProgress(done, total)
                    }
                    out.flush()
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Writes the zip out under [target]; returns the file names it wrote. Entries outside are refused. */
    private fun unzip(zip: File, target: File): Set<String> {
        val files = HashSet<String>()
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val name = entry.name
                if (!VoicePacks.safeEntry(name)) throw java.io.IOException("bad entry $name")
                val out = File(target, name)
                if (entry.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                out.outputStream().buffered().use { input.copyTo(it) }
                files += name
            }
        }
        return files
    }

    /** Frees the space: the next question falls back to the system engine until a pack is loaded again. */
    fun remove(context: Context) {
        dir(context).deleteRecursively()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        state = State()
        DiagLog.event("voice_pack_removed")
    }
}

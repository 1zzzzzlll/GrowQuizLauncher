package dev.plume.voice

import java.io.File
import java.net.URI
import java.security.MessageDigest

/**
 * One downloadable voice pack: a neural Chinese model the app speaks with on its own, with no system
 * speech engine installed. The app ships the engine (see docs/VOICE.md); the model comes from our
 * server the first time a device needs it, because 32 MB inside the APK would be paid by every TV at
 * every update.
 *
 * [sha256] is what makes a download trustworthy: the pack lands in a directory the app loads native
 * code data from, and a half-written model must never be tried (a broken load costs a minute and a
 * crash on a TV). [speaker] picks one voice when the model holds several.
 *
 * Kept free of Android types so the file maths can be tested off the TV.
 */
data class VoicePack(
    val id: String,
    val name: String,
    val url: String,
    val bytes: Long,
    val sha256: String,
    val speaker: Int = 0,
    val speed: Float = 0.9f,
) {
    /** "31 MB": what the parent is about to spend before the download starts. */
    val sizeText: String get() = VoicePacks.sizeText(bytes)
}

object VoicePacks {

    /** Required files for VITS or Matcha packs; [LEXICON] and [TOKENS] are common to both. */
    const val MODEL = "model.onnx"
    const val MATCHA_MODEL = "model-steps-3.onnx"
    const val VOCODER = "vocoder.onnx"
    const val LEXICON = "lexicon.txt"
    const val TOKENS = "tokens.txt"

    /** Optional polyphone and text-normalization FSTs, applied in order when present in the pack. */
    val RULE_FSTS = listOf(
        "phone.fst", "date.fst", "number.fst", "new_heteronym.fst",
        "phone-zh.fst", "date-zh.fst", "number-zh.fst",
    )

    /** Where the manifest with the packs sits under the update host: a sibling of latest.json. */
    fun manifestUrl(base: String): String = base.trimEnd('/') + "/tts/voice.json"

    /** Where the server-side neural TTS endpoint lives under the update host. */
    fun cloudSpeakUrl(base: String): String = base.trimEnd('/') + "/tts/speak"

    /**
     * The absolute address of a pack. The manifest may hold a full URL (a mirror) or a path relative
     * to the update host, which keeps one manifest readable in a browser.
     */
    fun absolute(base: String, url: String): String {
        val trimmed = url.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        val root = base.trimEnd('/')
        if (trimmed.startsWith("/")) return runCatching {
            val u = URI(root)
            "${u.scheme}://${u.authority}$trimmed"
        }.getOrDefault(root + trimmed)
        return "$root/$trimmed"
    }

    /** A size a parent understands, never more than two significant parts: "31 MB", "840 KB". */
    fun sizeText(bytes: Long): String = when {
        bytes >= 1_000_000 -> "${(bytes + 500_000) / 1_000_000} MB"
        bytes >= 1000 -> "${(bytes + 500) / 1000} KB"
        else -> "$bytes B"
    }

    /** Lowercase hex SHA-256 of a file, or null when it cannot be read. Streamed: a pack never fits in memory twice. */
    fun sha256(file: File): String? = try {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                md.update(buffer, 0, read)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        null
    }

    /**
     * Whether a zip entry name may be written under the pack directory. A pack comes from our server,
     * but the check costs nothing and a name like `../../shared_prefs/x.xml` would let a broken or
     * swapped file write outside the pack.
     */
    fun safeEntry(name: String): Boolean {
        if (name.isBlank()) return false
        if (name.startsWith("/") || name.startsWith("\\")) return false
        if (name.contains("..")) return false
        if (name.contains(':')) return false
        return true
    }

    /** True when the unpacked files hold either a complete VITS pack or a complete Matcha pack. */
    fun complete(files: Set<String>): Boolean {
        val hasLexAndTokens = files.contains(LEXICON) && files.contains(TOKENS)
        val hasVits = files.contains(MODEL)
        val hasMatcha = files.contains(MATCHA_MODEL) && files.contains(VOCODER)
        return hasLexAndTokens && (hasVits || hasMatcha)
    }

    /** True when [dir] holds a complete unpacked voice pack on disk. */
    fun hasModelOnDisk(dir: File): Boolean {
        if (!File(dir, LEXICON).isFile || !File(dir, TOKENS).isFile) return false
        return File(dir, MODEL).isFile || (File(dir, MATCHA_MODEL).isFile && File(dir, VOCODER).isFile)
    }

    /**
     * The cache file for one spoken sentence. Version-prefixed ("v2|") so pre-normalization quiet
     * PCM clips are never reused after upgrading.
     */
    fun clipName(text: String, speaker: Int, speed: Float): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("v2|$text|$speaker|$speed".toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) } + ".pcm"
    }

    /** Cache file name for one cloud-synthesised MP3 clip. */
    fun cloudClipName(text: String, voice: String = "xiaoyi"): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("cloud_v2|$voice|$text".toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) } + ".mp3"
    }

    /** True when the device can run native code of this ABI at all: a pack for a 32-bit phone is useless on a 64-bit-only one. */
    fun runsOn(abis: List<String>, wanted: String): Boolean = abis.any { it.equals(wanted, ignoreCase = true) }
}

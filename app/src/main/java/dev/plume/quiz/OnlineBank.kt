package dev.plume.quiz

import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Pure-JVM rules for the online textbook question bank: manifest parsing, URL resolution,
 * SHA-256 verification, randomized background schedule intervals, and shard selection.
 */
data class OnlineShard(
    val age: Int,
    val url: String,
    val sha256: String,
    val count: Int = 0,
    val bytes: Long = 0L,
)

data class OnlineManifest(
    val version: String,
    val semester: String,
    val teachingWeek: Int,
    val totalWeeks: Int,
    val progressPct: Int,
    val shards: List<OnlineShard>,
)

object OnlineBanks {
    /** Minimum and maximum startup jitter on the first sync (8–35 s) vs routine runs (20–180 s). */
    const val FIRST_DELAY_MIN_MS = 8_000L
    const val FIRST_DELAY_MAX_MS = 35_000L
    const val START_DELAY_MIN_MS = 20_000L
    const val START_DELAY_MAX_MS = 180_000L

    /** Randomized interval between background online bank checks: 3 to 8 hours. */
    const val SYNC_INTERVAL_MIN_MS = 3L * 60 * 60 * 1000
    const val SYNC_INTERVAL_MAX_MS = 8L * 60 * 60 * 1000

    /** Maximum shard downloads in a single routine background pass (forced sync pulls all). */
    const val MAX_SHARDS_PER_PASS = 4

    /** Hard cap on a single shard file (4 MB) so a misconfigured server never fills internal storage. */
    const val MAX_SHARD_BYTES = 4L * 1024 * 1024

    /** Sibling of latest.json and tts/voice.json under the update host. */
    fun manifestUrl(base: String): String = base.trimEnd('/') + "/questions/manifest.json"

    /** Resolves relative manifest paths against [base] while leaving full mirror URLs untouched. */
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

    /** Safe local filename for an age shard; never trusts server-supplied filenames on disk. */
    fun shardFileName(age: Int): String = "age_${age.coerceIn(5, 15)}.json"

    /** Randomized initial delay so devices never hit the server at the exact same second on boot. */
    fun initialDelayMs(neverSynced: Boolean, roll: Double = Random.Default.nextDouble()): Long {
        val clamped = roll.coerceIn(0.0, 1.0)
        val (lo, hi) = if (neverSynced) FIRST_DELAY_MIN_MS to FIRST_DELAY_MAX_MS else START_DELAY_MIN_MS to START_DELAY_MAX_MS
        return lo + ((hi - lo) * clamped).toLong()
    }

    /** Randomized delay until the next background check (3–8 hours). */
    fun nextIntervalMs(roll: Double = Random.Default.nextDouble()): Long {
        val clamped = roll.coerceIn(0.0, 1.0)
        return SYNC_INTERVAL_MIN_MS + ((SYNC_INTERVAL_MAX_MS - SYNC_INTERVAL_MIN_MS) * clamped).toLong()
    }

    /** Parses manifest.json; returns null when the payload is missing or malformed. */
    fun parseManifest(base: String, raw: String): OnlineManifest? = try {
        val root = JSONObject(raw)
        val arr = root.optJSONArray("shards") ?: return null
        val shards = ArrayList<OnlineShard>(arr.length())
        val seenAges = HashSet<Int>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val age = o.optInt("age", -1)
            val url = o.optString("url").trim()
            val sha = o.optString("sha256").trim().lowercase()
            if (age !in 5..15 || url.isEmpty() || sha.length != 64 || !seenAges.add(age)) continue
            shards += OnlineShard(
                age = age,
                url = absolute(base, url),
                sha256 = sha,
                count = o.optInt("count", 0).coerceAtLeast(0),
                bytes = o.optLong("bytes", 0L).coerceAtLeast(0L),
            )
        }
        OnlineManifest(
            version = root.optString("version").trim(),
            semester = root.optString("semester").trim(),
            teachingWeek = root.optInt("teachingWeek", 0).coerceAtLeast(0),
            totalWeeks = root.optInt("totalWeeks", 18).coerceAtLeast(1),
            progressPct = root.optInt("progressPct", 0).coerceIn(0, 100),
            shards = shards,
        )
    } catch (_: Exception) {
        null
    }

    /**
     * Picks outdated shards to pull: the child's active [preferredAge] always goes first when outdated,
     * followed by a randomly shuffled slice of the remaining outdated ages.
     */
    fun shardsToSync(
        shards: List<OnlineShard>,
        localShas: Map<Int, String>,
        preferredAge: Int,
        forceAll: Boolean = false,
        rng: Random = Random.Default,
    ): List<OnlineShard> {
        val outdated = shards.filter { !localShas[it.age].equals(it.sha256, ignoreCase = true) }
        if (outdated.isEmpty()) return emptyList()
        val primary = outdated.filter { it.age == preferredAge }
        val rest = outdated.filter { it.age != preferredAge }.shuffled(rng)
        val ordered = primary + rest
        return if (forceAll) ordered else ordered.take(MAX_SHARDS_PER_PASS)
    }

    /** Parses valid questions from an online shard JSON text; malformed rows are skipped safely. */
    fun parseShardQuestions(raw: String, expectedAge: Int): List<Question>? = try {
        val array = JSONObject(raw).optJSONArray("questions") ?: return null
        val out = ArrayList<Question>(array.length())
        val ids = HashSet<String>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id").trim()
            val age = item.optInt("age", expectedAge)
            val category = item.optString("category").trim()
            val prompt = item.optString("question").trim()
            val optsJson = item.optJSONArray("options") ?: continue
            if (id.isEmpty() || age != expectedAge || age !in 5..15 || category.isEmpty() || prompt.isEmpty()) continue
            if (optsJson.length() != 4) continue
            val options = List(4) { optsJson.optString(it).trim() }
            if (options.any { it.isEmpty() } || options.toSet().size != 4) continue
            val answerIndex = item.optInt("answerIndex", -1)
            if (answerIndex !in 0..3) continue
            if (!ids.add(id)) continue
            out += Question(
                id = id,
                age = age,
                category = category,
                prompt = prompt,
                options = options,
                answerIndex = answerIndex,
                explanation = item.optString("explanation").trim(),
                custom = false,
                online = true,
            )
        }
        out
    } catch (_: Exception) {
        null
    }

    /** Lowercase hex SHA-256 of [file], or null on I/O failure. */
    fun sha256(file: File): String? = try {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        null
    }
}

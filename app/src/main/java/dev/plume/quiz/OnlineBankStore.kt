package dev.plume.quiz

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.plume.BuildConfig
import dev.plume.DiagLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Pulls online textbook question shards in the background at randomized intervals.
 *
 * Files live in `filesDir/online_questions/age_<N>.json` and are swapped atomically only after
 * both SHA-256 and JSON schema validation pass, so a dropped Wi-Fi connection never breaks a quiz.
 */
object OnlineBankStore {
    private const val PREFS = "online_question_bank"
    private const val KEY_NEXT_AT = "next_sync_at"
    private const val KEY_LAST_OK_AT = "last_sync_ok_at"
    private const val KEY_VERSION = "version"
    private const val KEY_SEMESTER = "semester"
    private const val KEY_WEEK = "teaching_week"
    private const val KEY_PCT = "progress_pct"
    private const val KEY_SHA_PREFIX = "sha_age_"
    private const val KEY_COUNT_PREFIX = "cnt_age_"

    data class Status(
        val syncing: Boolean,
        val lastSyncAt: Long,
        val version: String,
        val semester: String,
        val teachingWeek: Int,
        val progressPct: Int,
        val totalCount: Int,
        val ageCount: Int,
        val lastError: String,
    )

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "online-bank-sync").apply { isDaemon = true }
    }

    private val revisionCounter = AtomicLong(0L)
    @Volatile private var started = false
    @Volatile private var syncing = false
    @Volatile private var pendingTask: ScheduledFuture<*>? = null
    @Volatile private var lastError = ""

    /** Increments whenever an online shard on disk changes so [QuestionBank] reloads lazily. */
    fun revision(): Long = revisionCounter.get()

    fun dir(context: Context): File = File(context.filesDir, "online_questions")

    fun shardFile(context: Context, age: Int): File =
        File(dir(context), OnlineBanks.shardFileName(age))

    /** Snapshot for the web admin console. */
    fun status(context: Context, currentAge: Int = QuizPrefs(context).age): Status {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var total = 0
        for (a in 5..15) total += prefs.getInt(KEY_COUNT_PREFIX + a, 0)
        return Status(
            syncing = syncing,
            lastSyncAt = prefs.getLong(KEY_LAST_OK_AT, 0L),
            version = prefs.getString(KEY_VERSION, "") ?: "",
            semester = prefs.getString(KEY_SEMESTER, "") ?: "",
            teachingWeek = prefs.getInt(KEY_WEEK, 0),
            progressPct = prefs.getInt(KEY_PCT, 0),
            totalCount = total,
            ageCount = prefs.getInt(KEY_COUNT_PREFIX + currentAge.coerceIn(5, 15), 0),
            lastError = lastError,
        )
    }

    /**
     * Starts the background random sync loop once per process, and nudges a check when a wakeup
     * (alarm / watchdog) arrives past the scheduled deadline.
     */
    @Synchronized
    fun ensureStarted(context: Context) {
        if (BuildConfig.UPDATE_BASE.isBlank()) return
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val neverSynced = prefs.getLong(KEY_LAST_OK_AT, 0L) == 0L
        val nextAt = prefs.getLong(KEY_NEXT_AT, 0L)
        if (!started) {
            started = true
            val jitter = OnlineBanks.initialDelayMs(neverSynced)
            val wait = if (nextAt <= now) jitter else maxOf(jitter, nextAt - now)
            scheduleIn(app, wait, force = false)
        } else if (nextAt in 1..now && !syncing) {
            // Doze may have paused the background timer while the TV slept; wake it with small jitter.
            scheduleIn(app, OnlineBanks.initialDelayMs(neverSynced = false), force = false)
        }
    }

    /** Manual one-shot sync triggered by parents from the web admin console. */
    @Synchronized
    fun syncNow(context: Context): Boolean {
        if (BuildConfig.UPDATE_BASE.isBlank()) return false
        if (syncing) return false
        scheduleIn(context.applicationContext, 0L, force = true)
        return true
    }

    @Synchronized
    private fun scheduleIn(app: Context, delayMs: Long, force: Boolean) {
        if (force) pendingTask?.cancel(false)
        pendingTask = scheduler.schedule(
            { runSync(app, force) },
            delayMs.coerceAtLeast(0L),
            TimeUnit.MILLISECONDS,
        )
    }

    private fun runSync(app: Context, force: Boolean) {
        val base = BuildConfig.UPDATE_BASE
        if (base.isBlank() || syncing) return
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!online(app)) {
            lastError = if (force) "当前网络未连接" else lastError
            // Retry sooner (15–45 min) when offline so a TV whose Wi-Fi connects late still catches up.
            val retryMs = (15L + kotlin.random.Random.nextLong(30L)) * 60_000L
            prefs.edit().putLong(KEY_NEXT_AT, System.currentTimeMillis() + retryMs).apply()
            scheduleIn(app, retryMs, force = false)
            return
        }
        syncing = true
        try {
            val rawManifest = fetchText(OnlineBanks.manifestUrl(base))
            val manifest = OnlineBanks.parseManifest(base, rawManifest)
                ?: throw java.io.IOException("bad manifest")
            val root = dir(app).apply { mkdirs() }
            val localShas = HashMap<Int, String>()
            for (age in 5..15) {
                val file = File(root, OnlineBanks.shardFileName(age))
                val recorded = prefs.getString(KEY_SHA_PREFIX + age, null)
                if (file.isFile && !recorded.isNullOrEmpty()) localShas[age] = recorded
            }
            val preferredAge = QuizPrefs(app).age
            val targets = OnlineBanks.shardsToSync(manifest.shards, localShas, preferredAge, forceAll = force)
            var updatedShards = 0
            for (shard in targets) {
                if (downloadShard(root, shard)) {
                    val parsed = OnlineBanks.parseShardQuestions(
                        File(root, OnlineBanks.shardFileName(shard.age)).readText(Charsets.UTF_8),
                        shard.age,
                    )
                    prefs.edit()
                        .putString(KEY_SHA_PREFIX + shard.age, shard.sha256)
                        .putInt(KEY_COUNT_PREFIX + shard.age, parsed?.size ?: shard.count)
                        .apply()
                    updatedShards++
                }
            }
            val now = System.currentTimeMillis()
            // If there are still remaining un-synced shards from this manifest, schedule a shorter follow-up;
            // otherwise wait a full random 3–8 hour window.
            val allCaughtUp = manifest.shards.all { s ->
                val f = File(root, OnlineBanks.shardFileName(s.age))
                f.isFile && prefs.getString(KEY_SHA_PREFIX + s.age, null).equals(s.sha256, ignoreCase = true)
            }
            val nextInterval = if (allCaughtUp) {
                OnlineBanks.nextIntervalMs()
            } else {
                (10L + kotlin.random.Random.nextLong(20L)) * 60_000L
            }
            prefs.edit()
                .putLong(KEY_LAST_OK_AT, now)
                .putLong(KEY_NEXT_AT, now + nextInterval)
                .putString(KEY_VERSION, manifest.version)
                .putString(KEY_SEMESTER, manifest.semester)
                .putInt(KEY_WEEK, manifest.teachingWeek)
                .putInt(KEY_PCT, manifest.progressPct)
                .apply()
            lastError = ""
            if (updatedShards > 0) {
                revisionCounter.incrementAndGet()
                DiagLog.event(
                    "online_bank_updated",
                    manifest.version,
                    mapOf("shards" to updatedShards.toString(), "week" to manifest.teachingWeek.toString()),
                )
            }
            scheduleIn(app, nextInterval, force = false)
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            DiagLog.event("online_bank_fail", lastError)
            val retryMs = (30L + kotlin.random.Random.nextLong(60L)) * 60_000L
            prefs.edit().putLong(KEY_NEXT_AT, System.currentTimeMillis() + retryMs).apply()
            scheduleIn(app, retryMs, force = false)
        } finally {
            syncing = false
        }
    }

    private fun downloadShard(root: File, shard: OnlineShard): Boolean {
        val tmp = File(root, OnlineBanks.shardFileName(shard.age) + ".part")
        val dst = File(root, OnlineBanks.shardFileName(shard.age))
        tmp.delete()
        val conn = (URL(shard.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 25_000
            setRequestProperty("User-Agent", "GrowQuiz-OnlineBank")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw java.io.IOException("http $code")
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > OnlineBanks.MAX_SHARD_BYTES) throw java.io.IOException("shard too large")
                        out.write(buf, 0, n)
                    }
                }
            }
            val got = OnlineBanks.sha256(tmp)
            if (got == null || !got.equals(shard.sha256, ignoreCase = true)) {
                tmp.delete()
                throw java.io.IOException("sha256 mismatch age=${shard.age}")
            }
            val parsed = OnlineBanks.parseShardQuestions(tmp.readText(Charsets.UTF_8), shard.age)
            if (parsed == null) {
                tmp.delete()
                throw java.io.IOException("invalid shard json age=${shard.age}")
            }
            if (!tmp.renameTo(dst)) {
                dst.writeBytes(tmp.readBytes())
                tmp.delete()
            }
            return true
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchText(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "GrowQuiz-OnlineBank")
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) throw java.io.IOException("http $code")
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun online(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

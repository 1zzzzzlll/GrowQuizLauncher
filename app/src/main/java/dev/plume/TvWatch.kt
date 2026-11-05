package dev.plume

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Counts how long the child watches TV. HomeOverride reports every foreground window change; when
 * the foreground app is a TV player or a video app, the time since the previous event accrues to
 * that day's bucket in files/tv_time.json. A 30-second tick keeps the clock running while a player
 * stays up without raising window events, and each single gap is capped so a switched-off screen
 * never counts. Needs the accessibility service (the "主屏幕键" switch), which is also what the
 * home cover needs; without it nothing is recorded, and nothing breaks.
 */
object TvWatch {
    /** The TV's own input players (from HomeWatch) plus the OTT video apps common on China TVs. */
    private val WATCH_APPS = setOf(
        // TV input players
        "com.tcl.tv", "com.xiaomi.mitv.tvplayer", "com.skyworth.tv", "com.hisilicon.tvui",
        // OTT video clients
        "com.ktcp.video",        // 腾讯视频 TV 版（云视听·极光）
        "com.gitv.video",        // 爱奇艺 TV 版（银河奇异果）
        "tv.danmaku.bili",       // 哔哩哔哩
        "tv.danmaku.bili.tv",    // 哔哩哔哩 TV 版
        "com.hunantv.imgo.activity", // 芒果 TV TV 版
        "com.cibn.ott",          // CIBN 视频
    )

    private const val MAX_GAP_MS = 5 * 60_000L // one unobserved stretch of up to 5 min still counts
    private const val TICK_MS = 30_000L
    private const val PERSIST_EVERY_MS = 60_000L
    private const val KEEP_DAYS = 400

    private val handler = Handler(Looper.getMainLooper())
    private val dayKey = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private var lastPkg: String? = null
    private var lastAt = 0L
    private var lastPersist = 0L
    private val today = HashMap<String, Long>() // app -> ms, for the day being watched right now
    private var todayKey = ""
    private var ticking = false

    private val tick = object : Runnable {
        override fun run() {
            ticking = false
            val context = HomeOverride.running ?: return
            note(context, lastPkg ?: return) // re-note the same app: accrues the tick, re-schedules
        }
    }

    /** True when the package is one whose screen time counts as watching TV. */
    fun isWatchApp(pkg: String): Boolean = pkg in WATCH_APPS

    /**
     * Called by HomeOverride on every window change, and by the tick while a player stays up.
     * Accrues the elapsed time of the previous foreground app first, then remembers the new one.
     */
    fun note(context: Context, pkg: String) {
        val now = System.currentTimeMillis()
        if (lastAt > 0L) accrue(context, lastPkg, lastAt, now)
        lastPkg = pkg
        lastAt = now
        if (isWatchApp(pkg)) {
            if (!ticking) {
                ticking = true
                handler.postDelayed(tick, TICK_MS)
            }
        } else {
            handler.removeCallbacks(tick)
            ticking = false
        }
    }

    /** Adds the capped gap to the day bucket, switching buckets at midnight. */
    private fun accrue(context: Context, pkg: String?, from: Long, to: Long) {
        if (pkg == null || !isWatchApp(pkg)) return
        val delta = (to - from).coerceIn(0L, MAX_GAP_MS)
        if (delta <= 0L) return
        val key = java.time.Instant.ofEpochMilli(from).atZone(ZoneId.systemDefault()).toLocalDate().format(dayKey)
        if (key != todayKey) {
            persist(context) // the previous day goes to disk before the new bucket starts
            todayKey = key
            today.clear()
        }
        today[pkg] = (today[pkg] ?: 0L) + delta
        if (now() - lastPersist >= PERSIST_EVERY_MS) persist(context)
    }

    /** Writes today's bucket into tv_time.json, keeping only the newest days. */
    @Synchronized
    private fun persist(context: Context) {
        try {
            if (todayKey.isNotEmpty() && today.isNotEmpty()) {
                val file = file(context)
                val root = if (file.exists()) {
                    JSONObject(file.readText(Charsets.UTF_8))
                } else {
                    JSONObject()
                }
                val days = root.optJSONObject("days") ?: JSONObject().also { root.put("days", it) }
                val day = JSONObject()
                for ((app, ms) in today) day.put(app, ms)
                days.put(todayKey, day)
                val keys = days.keys().asSequence().toMutableList()
                if (keys.size > KEEP_DAYS) {
                    keys.sorted().take(keys.size - KEEP_DAYS).forEach { days.remove(it) }
                }
                val tmp = File(context.filesDir, "tv_time.json.tmp")
                tmp.writeText(root.toString(), Charsets.UTF_8)
                if (!tmp.renameTo(file)) {
                    file.writeText(root.toString(), Charsets.UTF_8)
                    tmp.delete()
                }
            }
            lastPersist = now()
        } catch (_: Exception) {
            // tracking must never disturb watching TV: a failed write is retried on the next flush
        }
    }

    /** Milliseconds watched on each recorded day: "2026-11-05" -> total ms. */
    fun minutesByDay(context: Context): Map<String, Long> {
        val result = HashMap<String, Long>()
        try {
            val file = file(context)
            if (!file.exists()) return emptyMap()
            val days = JSONObject(file.readText(Charsets.UTF_8)).optJSONObject("days") ?: return emptyMap()
            for (key in days.keys()) {
                val day = days.optJSONObject(key) ?: continue
                var total = 0L
                for (app in day.keys()) total += day.optLong(app)
                result[key] = total
            }
        } catch (_: Exception) {
        }
        return result
    }

    /** Flushes the current bucket; HomeOverride calls this from onUnbind/onDestroy. */
    fun flush(context: Context) {
        handler.removeCallbacks(tick)
        ticking = false
        lastAt = 0L
        persist(context)
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, "tv_time.json")

    private fun now() = System.currentTimeMillis()
}

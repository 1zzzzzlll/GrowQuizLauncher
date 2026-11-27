package dev.plume.quiz

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioManager
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.LruCache
import android.view.KeyEvent
import dev.plume.DiagLog
import dev.plume.HomeOverride
import dev.plume.HomeWatch
import dev.plume.SettingsApps
import dev.plume.Vendor
import dev.plume.preferTvApps
import dev.plume.voice.Voice
import java.io.ByteArrayOutputStream

/**
 * Manages parental software blocking: lists deployed non-system apps, persists the blocked package
 * set with an O(1) in-memory mirror for the accessibility window watcher, and closes any blocked
 * app as soon as it enters the foreground (or the moment a parent disables it while it is running).
 */
object AppBlock {
    private const val PREFS = "grow_quiz_app_block"
    private const val KEY_BLOCKED = "blocked_pkgs"
    private const val KEY_HIT_COUNT = "hit_count"
    private const val KEY_LAST_HIT_AT = "last_hit_at"
    private const val KEY_LAST_HIT_NAME = "last_hit_name"
    private const val CHANNEL = "app_block_alert"
    const val NOTIFICATION_ID = 11

    private const val FG_MAX_AGE_MS = 6 * 60 * 60 * 1000L

    @Volatile private var cachedBlocked: Set<String>? = null
    @Volatile private var lastFgPkg: String? = null
    @Volatile private var lastFgElapsed: Long = 0L
    @Volatile private var lastLaunchElapsed: Long = 0L

    private val iconPngCache = object : LruCache<String, ByteArray>(384 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Returns the current set of blocked package names; cached in memory after the first read. */
    fun blockedPackages(context: Context): Set<String> {
        cachedBlocked?.let { return it }
        synchronized(this) {
            cachedBlocked?.let { return it }
            val loaded = prefs(context).getStringSet(KEY_BLOCKED, emptySet()).orEmpty().toSet()
            cachedBlocked = loaded
            return loaded
        }
    }

    /** Fast check run on every window change and app launch. */
    fun isBlocked(context: Context, pkg: String?): Boolean =
        AppBlockRules.shouldIntercept(pkg, context.packageName, blockedPackages(context))

    fun hitCount(context: Context): Int = prefs(context).getInt(KEY_HIT_COUNT, 0)

    fun lastHitAt(context: Context): Long = prefs(context).getLong(KEY_LAST_HIT_AT, 0L)

    fun lastHitName(context: Context): String = prefs(context).getString(KEY_LAST_HIT_NAME, "").orEmpty()

    /** Updates the blocked state of [pkgs] and returns the updated full blocked set. */
    fun setBlocked(context: Context, pkgs: Collection<String>, blocked: Boolean): Set<String> {
        val app = context.applicationContext
        val protected = protectedPackages(app)
        val cleanTargets = AppBlockRules.sanitizeBlockedTargets(pkgs, app.packageName, protected)
        val updated = synchronized(this) {
            val current = blockedPackages(app)
            val next = if (blocked) {
                AppBlockRules.sanitizeBlockedTargets(current + cleanTargets, app.packageName, protected)
            } else {
                current - pkgs.map { it.trim() }.toSet()
            }
            prefs(app).edit().putStringSet(KEY_BLOCKED, next).apply()
            cachedBlocked = next
            next
        }
        if (blocked && cleanTargets.isNotEmpty()) {
            warmSpeechForPackages(app, cleanTargets)
        }
        return updated
    }

    /** Lists all deployed non-system software on this device with each item's current blocked flag. */
    fun listNonSystemApps(context: Context): List<AppBlockRules.ListedApp> {
        val app = context.applicationContext
        val pm = app.packageManager
        val homePkgs = queryHomePackages(app)
        val settingsPkgs = querySettingsPackages(app)
        val ttsPkgs = queryTtsPackages(app)

        fun queryLauncher(category: String) = runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
        }.getOrDefault(emptyList())

        val launcherEntries = preferTvApps(
            queryLauncher(Intent.CATEGORY_LEANBACK_LAUNCHER),
            queryLauncher(Intent.CATEGORY_LAUNCHER),
        ) { it.activityInfo.packageName }

        val candidates = ArrayList<AppBlockRules.Candidate>(launcherEntries.size + 8)
        val seen = HashSet<String>(launcherEntries.size)

        for (ri in launcherEntries) {
            val ai = ri.activityInfo?.applicationInfo ?: continue
            val pkg = ai.packageName
            if (!seen.add(pkg)) continue
            val label = runCatching { ri.loadLabel(pm).toString().trim() }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?: runCatching { pm.getApplicationLabel(ai).toString().trim() }.getOrDefault(pkg)
            val version = runCatching { pm.getPackageInfo(pkg, 0).versionName.orEmpty() }.getOrDefault("")
            candidates += AppBlockRules.Candidate(
                pkg = pkg,
                label = label,
                version = version,
                isSystem = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                isUpdatedSystem = ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0,
                hasLauncher = true,
                isHome = pkg in homePkgs,
                isSettings = pkg in settingsPkgs,
                isTtsEngine = pkg in ttsPkgs,
            )
        }

        // Also surface any already-blocked package whose launcher entry is currently hidden/missing.
        val blocked = blockedPackages(app)
        for (pkg in blocked) {
            if (pkg in seen) continue
            val ai = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: continue
            val label = runCatching { pm.getApplicationLabel(ai).toString().trim() }.getOrDefault(pkg)
            val version = runCatching { pm.getPackageInfo(pkg, 0).versionName.orEmpty() }.getOrDefault("")
            candidates += AppBlockRules.Candidate(
                pkg = pkg,
                label = label,
                version = version,
                isSystem = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                isUpdatedSystem = ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0,
                hasLauncher = pm.getLeanbackLaunchIntentForPackage(pkg) != null || pm.getLaunchIntentForPackage(pkg) != null,
                isHome = pkg in homePkgs,
                isSettings = pkg in settingsPkgs,
                isTtsEngine = pkg in ttsPkgs,
            )
        }

        return AppBlockRules.selectApps(candidates, blocked, app.packageName)
    }

    /** Count of currently installed non-system apps that are marked disabled. */
    fun blockedInstalledCount(context: Context): Int =
        listNonSystemApps(context).count { it.blocked }

    /** Renders a small PNG icon for the web console's app list; null if the package is gone. */
    fun iconPng(context: Context, pkg: String, sizePx: Int = 72): ByteArray? {
        val clean = pkg.trim()
        if (clean.isEmpty()) return null
        val cacheKey = "$clean@$sizePx"
        iconPngCache.get(cacheKey)?.let { return it }
        val pm = context.applicationContext.packageManager
        val drawable = runCatching {
            pm.getLeanbackLaunchIntentForPackage(clean)?.let { pm.getActivityIcon(it) }
                ?: pm.getLaunchIntentForPackage(clean)?.let { pm.getActivityIcon(it) }
                ?: pm.getApplicationIcon(clean)
        }.getOrNull() ?: return null
        return runCatching {
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(canvas)
            val out = ByteArrayOutputStream(2048)
            bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
            bmp.recycle()
            out.toByteArray().also { bytes ->
                if (bytes.isNotEmpty()) iconPngCache.put(cacheKey, bytes)
            }
        }.getOrNull()
    }

    /** Evicts a cached icon when a package is updated or removed. */
    fun invalidateIcon(pkg: String?) {
        val clean = pkg?.trim().orEmpty()
        if (clean.isEmpty()) return
        iconPngCache.remove("$clean@72")
    }

    /** Called by [HomeOverride] on every window change to remember which app is on screen. */
    fun noteForeground(context: Context, pkg: String?) {
        if (!AppBlockRules.tracksAsForegroundApp(pkg, context.packageName)) return
        lastFgPkg = pkg?.trim()
        lastFgElapsed = SystemClock.elapsedRealtime()
    }

    /** Called when our own desktop comes to the front: no third-party app is in front anymore. */
    fun noteHomeShown() {
        lastFgPkg = null
        lastFgElapsed = 0L
    }

    /**
     * Checks whether a newly disabled app is on screen right now and closes it immediately.
     * Called on the main thread right after a parent updates the blocked list in the web console.
     */
    fun enforceIfForeground(context: Context): Boolean {
        val app = context.applicationContext
        val live = runCatching {
            HomeOverride.running?.rootInActiveWindow?.packageName?.toString()
        }.getOrNull()?.takeIf { AppBlockRules.tracksAsForegroundApp(it, app.packageName) }
        val tracked = lastFgPkg?.takeIf {
            lastFgElapsed > 0L && SystemClock.elapsedRealtime() - lastFgElapsed in 0..FG_MAX_AGE_MS
        }
        val candidate = live ?: tracked ?: return false
        if (!isBlocked(app, candidate)) return false
        intercept(app, candidate, wasRunning = true)
        return true
    }

    /**
     * Stops a blocked app's media, brings up the opaque reminder dialog (which forces the blocked
     * app into `onStop` immediately), and triggers voice narration.
     */
    fun intercept(context: Context, pkg: String, wasRunning: Boolean) {
        val app = context.applicationContext
        val cleanPkg = pkg.trim()
        if (!isBlocked(app, cleanPkg)) return

        val nowElapsed = SystemClock.elapsedRealtime()
        if (!AppBlockRules.shouldRelaunchAlert(
                alertForeground = BlockedAppActivity.isForeground,
                alertInFlight = BlockedAppActivity.launchInFlight,
                activeAlertPkg = BlockedAppActivity.activePkg,
                targetPkg = cleanPkg,
                lastLaunchElapsedMs = lastLaunchElapsed,
                nowElapsedMs = nowElapsed,
            )) {
            return
        }
        lastLaunchElapsed = nowElapsed
        BlockedAppActivity.activePkg = cleanPkg
        BlockedAppActivity.launchInFlight = true
        if (lastFgPkg == cleanPkg) {
            lastFgPkg = null
            lastFgElapsed = 0L
        }

        val label = resolveLabel(app, cleanPkg)
        stopMediaNow(app)
        Voice.warm(app)

        val p = prefs(app)
        val hits = p.getInt(KEY_HIT_COUNT, 0) + 1
        p.edit()
            .putInt(KEY_HIT_COUNT, hits)
            .putLong(KEY_LAST_HIT_AT, System.currentTimeMillis())
            .putString(KEY_LAST_HIT_NAME, label.ifBlank { cleanPkg })
            .apply()
        DiagLog.event(
            "app_blocked",
            cleanPkg,
            mapOf("running" to if (wasRunning) "1" else "0"),
        )

        val intent = Intent(app, BlockedAppActivity::class.java)
            .putExtra(BlockedAppActivity.EXTRA_PKG, cleanPkg)
            .putExtra(BlockedAppActivity.EXTRA_LABEL, label)
            .putExtra(BlockedAppActivity.EXTRA_WAS_RUNNING, wasRunning)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            )
        BlockedAppActivity.markExpectedIntent()
        try {
            app.startActivity(intent)
        } catch (e: Exception) {
            DiagLog.event("app_block_popup_fail", e.message ?: e.javaClass.simpleName)
        }
        if (wasRunning && !BlockedAppActivity.isForeground) {
            notifyFullScreenFallback(app, cleanPkg, label, wasRunning)
        }
    }

    fun resolveLabel(context: Context, pkg: String): String = runCatching {
        val pm = context.packageManager
        val ai = pm.getApplicationInfo(pkg, 0)
        pm.getApplicationLabel(ai).toString().trim()
    }.getOrDefault("")

    private fun stopMediaNow(context: Context) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        runCatching {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_STOP))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_STOP))
        }
    }

    private fun notifyFullScreenFallback(context: Context, pkg: String, label: String, wasRunning: Boolean) {
        try {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "软件禁用提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "监听到已禁用软件运行时弹出提醒"
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
            val open = PendingIntent.getActivity(
                context,
                11,
                Intent(context, BlockedAppActivity::class.java)
                    .putExtra(BlockedAppActivity.EXTRA_PKG, pkg)
                    .putExtra(BlockedAppActivity.EXTRA_LABEL, label)
                    .putExtra(BlockedAppActivity.EXTRA_WAS_RUNNING, wasRunning)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_delete)
                .setContentTitle(AppBlockRules.dialogTitle())
                .setContentText(AppBlockRules.dialogMessage(label))
                .setCategory(Notification.CATEGORY_ALARM)
                .setContentIntent(open)
                .setFullScreenIntent(open, true)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIFICATION_ID, notification)
        } catch (_: Exception) {
        }
    }

    private fun warmSpeechForPackages(context: Context, pkgs: Set<String>) {
        Thread({
            runCatching {
                Voice.warm(context)
                val bilingual = Voice.isBilingual()
                for (pkg in pkgs.take(6)) {
                    val label = resolveLabel(context, pkg)
                    Voice.warmClip(AppBlockRules.speechMessage(label, bilingual), offlineWhenIdle = true)
                }
            }
        }, "app-block-voice-warm").apply { isDaemon = true }.start()
    }

    private fun protectedPackages(context: Context): Set<String> =
        queryHomePackages(context) + querySettingsPackages(context) + queryTtsPackages(context)

    private fun queryHomePackages(context: Context): Set<String> {
        val pm = context.packageManager
        val found = runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .mapNotNull { it.activityInfo?.packageName }
        }.getOrDefault(emptyList())
        return (found + HomeWatch.FIRMWARE_HOMES.keys + HomeWatch.INPUT_PLAYERS +
            setOf(HomeWatch.GOOGLE_TV, HomeWatch.MITV, HomeWatch.MITV_ATV)).toSet()
    }

    private fun querySettingsPackages(context: Context): Set<String> {
        val pm = context.packageManager
        val handlers = listOf(Settings.ACTION_SETTINGS, Settings.ACTION_WIFI_SETTINGS).flatMap { action ->
            runCatching {
                pm.queryIntentActivities(Intent(action), 0).mapNotNull { it.activityInfo?.packageName }
            }.getOrDefault(emptyList())
        }
        return (handlers + SettingsApps.vendorFallbackPackages(Vendor.current)).toSet()
    }

    private fun queryTtsPackages(context: Context): Set<String> {
        val pm = context.packageManager
        val byService = runCatching {
            pm.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
                .mapNotNull { it.serviceInfo?.packageName }
        }.getOrDefault(emptyList())
        val byCheck = runCatching {
            pm.queryIntentActivities(Intent(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA), 0)
                .mapNotNull { it.activityInfo?.packageName }
        }.getOrDefault(emptyList())
        return (byService + byCheck + TtsProbe.engineNames(context)).toSet()
    }
}

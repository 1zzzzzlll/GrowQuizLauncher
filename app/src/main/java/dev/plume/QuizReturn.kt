package dev.plume

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings

/**
 * The video the quiz covered, remembered so the page can hand it back.
 *
 * A question takes the screen in its own task. When the page finishes, the system shows whatever
 * task is under it again — the player, in the normal case. After a long question that no longer
 * holds: the TV's own memory manager kills a backgrounded player on this hardware, and a page that
 * was ended by the system leaves nothing to return to. The child then lands on the desktop instead
 * of the video they were watching.
 *
 * So the app remembers the last thing that was in front (the accessibility service sees every
 * window change), and [QuizActivity][dev.plume.quiz.QuizActivity] captures it when it opens. The
 * desktop asks for it on the resume that follows a finished quiz: by then it is known that the
 * desktop is what the child sees, which is exactly the case that needs the video started again.
 */
object QuizReturn {
    private const val PREFS = "quiz_return"
    private const val KEY_PKG = "last_pkg"
    private const val KEY_AT = "last_at"
    private const val KEY_BOOT = "last_boot"
    private const val KEY_DESKTOP_AT = "desktop_at"
    private const val KEY_DESKTOP_BOOT = "desktop_boot"
    private const val KEY_TARGET = "return_pkg"

    /** A player left behind longer ago than this is not "the video the quiz interrupted". */
    const val MAX_AGE_MS = 6 * 60 * 60 * 1000L

    /** Windows that are never a video: the system's own UI never plays anything. */
    private val NOT_A_PLAYER = setOf("android", "com.android.systemui", "com.android.settings")

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun bootCount(context: Context): Int =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0) }
            .getOrDefault(0)

    /**
     * Records the app a window just opened. Called by [HomeOverride] for every foreground change,
     * and by the desktop when it starts an app (the fallback for TVs without the accessibility
     * service). Our own package is skipped: the quiz page must not overwrite what it covered.
     *
     * Uses monotonic [SystemClock.elapsedRealtime] paired with [Settings.Global.BOOT_COUNT] so an
     * early-boot NTP clock jump cannot turn a 10-second-old app into a 685-day-old record.
     */
    fun note(context: Context, pkg: String?) {
        if (pkg.isNullOrBlank()) return
        if (pkg == context.packageName || pkg in NOT_A_PLAYER) return
        if (dev.plume.quiz.AppBlock.isBlocked(context, pkg)) return
        prefs(context).edit()
            .putString(KEY_PKG, pkg)
            .putLong(KEY_AT, SystemClock.elapsedRealtime())
            .putInt(KEY_BOOT, bootCount(context))
            .apply()
    }

    /** The desktop came to the front: from here on nothing was interrupted. */
    fun desktopShown(context: Context) {
        prefs(context).edit()
            .putLong(KEY_DESKTOP_AT, SystemClock.elapsedRealtime())
            .putInt(KEY_DESKTOP_BOOT, bootCount(context))
            .apply()
    }

    /**
     * Remembers what the quiz is covering. Returns the package for the report, or null when the
     * quiz opened over the desktop (nothing to hand back) or the record is too old.
     */
    fun capture(context: Context): String? {
        val p = prefs(context)
        val boot = bootCount(context)
        val now = SystemClock.elapsedRealtime()
        val last = p.getString(KEY_PKG, null)
        val lastBoot = p.getInt(KEY_BOOT, -1)
        val lastAt = p.getLong(KEY_AT, 0L)
        val desktopBoot = p.getInt(KEY_DESKTOP_BOOT, -1)
        val desktopAt = p.getLong(KEY_DESKTOP_AT, 0L)
        val target = pick(last, lastAt, lastBoot, desktopAt, desktopBoot, now, boot, MAX_AGE_MS)
            ?.takeIf {
                it != context.packageName &&
                    !dev.plume.quiz.AppBlock.isBlocked(context, it) &&
                    launchIntent(context, it) != null &&
                    !isHome(context, it)
            }
        p.edit().apply {
            if (target == null) remove(KEY_TARGET) else putString(KEY_TARGET, target)
        }.apply()
        val ageMin = if (lastBoot == boot && lastAt in 1..now) ((now - lastAt) / 60_000L).toString() else "-1"
        DiagLog.event(
            "quiz_return_capture",
            target ?: "",
            mapOf("last" to (last ?: ""), "ageMin" to ageMin),
        )
        return target
    }

    /**
     * The package captured by the quiz that just ended, cleared so one quiz returns at most once.
     * Null when this quiz covered the desktop or did not capture anything.
     */
    fun take(context: Context): String? {
        val p = prefs(context)
        val pkg = p.getString(KEY_TARGET, null)
        if (pkg != null) p.edit().remove(KEY_TARGET).apply()
        return pkg
    }

    /** Brings [pkg] back as it was: an app that is still alive keeps its task, and its video. */
    fun launch(context: Context, pkg: String): Boolean {
        if (dev.plume.quiz.AppBlock.isBlocked(context, pkg)) return false
        val intent = launchIntent(context, pkg) ?: return false
        // NEW_TASK only: no CLEAR_TOP and no RESET_TASK_IF_NEEDED, so a player that is still in
        // memory is brought forward where it stopped instead of being rebuilt from its start page.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            DiagLog.event("quiz_return_fail", pkg, mapOf("why" to (e.message ?: e.javaClass.simpleName)))
            false
        }
    }

    /** Called by the desktop when it resumes because a quiz just finished. */
    fun resume(context: Context) {
        val pkg = take(context) ?: return
        val ok = launch(context, pkg)
        DiagLog.event("quiz_return", pkg, mapOf("ok" to if (ok) "1" else "0"))
    }

    private fun launchIntent(context: Context, pkg: String): Intent? =
        context.packageManager.getLeanbackLaunchIntentForPackage(pkg)
            ?: context.packageManager.getLaunchIntentForPackage(pkg)

    /** A home screen is not something to hand back: it is where the desktop already is. */
    private fun isHome(context: Context, pkg: String): Boolean = try {
        context.packageManager
            .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .any { it.activityInfo?.packageName == pkg }
    } catch (e: Exception) {
        false
    }

    /**
     * Which app to hand back, or null. The desktop being in front more recently than the app means
     * the child was on the home screen when the question came up, and nothing was interrupted.
     * Kept off Android so the rule can be tested.
     */
    fun pick(appPkg: String?, appAt: Long, desktopAt: Long, now: Long, maxAgeMs: Long): String? {
        if (appPkg.isNullOrBlank()) return null
        if (appAt <= 0L || now < appAt) return null
        if (desktopAt >= appAt) return null
        if (now - appAt > maxAgeMs) return null
        return appPkg
    }

    /** Boot-aware variant for monotonic timestamps: records from an earlier boot are discarded. */
    fun pick(
        appPkg: String?,
        appAt: Long,
        appBoot: Int,
        desktopAt: Long,
        desktopBoot: Int,
        now: Long,
        currentBoot: Int,
        maxAgeMs: Long,
    ): String? = pick(
        appPkg = appPkg,
        appAt = if (appBoot == currentBoot) appAt else 0L,
        desktopAt = if (desktopBoot == currentBoot) desktopAt else 0L,
        now = now,
        maxAgeMs = maxAgeMs,
    )
}

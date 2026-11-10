package dev.plume

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * The desktop to hand the TV back to. The launcher the system resolves Home to wins — that is the one
 * the family had before Plume. Then come the firmware launchers that answer no query at all (a TCL set
 * starts its own by component, see [HomeWatch.FIRMWARE_HOMES]); they count when they are installed.
 * A listed home screen is the last resort. Kept free of Android classes so it can be tested off a TV.
 */
fun pickStockHome(found: List<HomeEntry>, resolved: String?, self: String, installed: Set<String>): HomeEntry? {
    val usable = found.filter { it.pkg != self }.filterNot { HomeWatch.isPlatformStubHome(it.pkg, it.cls) }
    usable.firstOrNull { it.pkg == resolved }?.let { return it }
    usable.firstOrNull { it.pkg in HomeWatch.FIRMWARE_HOMES }?.let { return it }
    HomeWatch.FIRMWARE_HOMES.entries.firstOrNull { it.key != self && it.key in installed }
        ?.let { return HomeEntry(it.key, it.value.first(), null) }
    return usable.firstOrNull { it.pkg !in HomeWatch.INPUT_PLAYERS }
}

/**
 * "Exit the launcher": Plume stops taking the Home key, and the TV's own desktop comes back. The parents
 * ask for it from Plume settings (behind the PIN) or from the phone console — e.g. while guests use the TV.
 *
 * The flag matters as much as the switch-off: on a TV where Plume *is* the default home app, Android
 * starts Plume again at the next Home press, so [forward] passes that press on to the stock launcher.
 * Nothing else stops: the quiz keeps its schedule, and the parents still turn that off from the console.
 */
object DesktopExit {
    private const val PREFS = "home"
    private const val KEY_OFF = "home_off" // the Home key switch in Plume settings, as before
    private const val KEY_EXITED = "home_exited" // the parents exited on purpose
    private const val WRITE_SECURE = "android.permission.WRITE_SECURE_SETTINGS"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True while Plume stays out of the way: Home goes to the TV's own desktop. */
    fun exited(c: Context) = prefs(c).getBoolean(KEY_EXITED, false)

    /** Leaves the launcher. False when no stock desktop can be found (the caller then says so). */
    fun exit(c: Context): Boolean {
        prefs(c).edit().putBoolean(KEY_OFF, true).putBoolean(KEY_EXITED, true).apply()
        HomeOverride.running?.disableSelf()
        dropService(c)
        val opened = openStockHome(c)
        DiagLog.event(if (opened) "launcher_exit" else "launcher_exit_fail")
        return opened
    }

    /**
     * Takes the launcher back: the flag goes, and the Home key service is enabled again where the adb
     * permission allows the write. False means the service is still off — the parent must switch it on
     * in Android's Accessibility settings, which only the user can do.
     */
    fun resume(c: Context): Boolean {
        prefs(c).edit().remove(KEY_EXITED).remove(KEY_OFF).apply()
        val me = ComponentName(c, HomeOverride::class.java)
        val on = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val listed = on.split(':').any { ComponentName.unflattenFromString(it) == me }
        if (c.checkSelfPermission(WRITE_SECURE) == PackageManager.PERMISSION_GRANTED) {
            if (!listed) {
                Settings.Secure.putString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    if (on.isEmpty()) me.flattenToString() else "$on:${me.flattenToString()}")
                Settings.Secure.putInt(c.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }
        } else if (!listed) {
            DiagLog.event("launcher_resume_fail", "no-write-secure")
            return false
        }
        DiagLog.event("launcher_resume")
        return true
    }

    /** A Home press reached Plume while the parents had exited: pass it on to the TV's own desktop. */
    fun forward(c: Context): Boolean = openStockHome(c)

    /** The component to open, or null when this firmware hides every launcher from a third-party query. */
    fun stockHome(c: Context): ComponentName? {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val found = runCatching {
            c.packageManager.queryIntentActivities(home, 0).map {
                val a = it.activityInfo
                HomeEntry(a.packageName, a.name, a.targetActivity)
            }
        }.getOrDefault(emptyList())
        val resolved = runCatching {
            c.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        }.getOrNull()
        val installed = c.packageManager.getInstalledApplications(0).map { it.packageName }.toSet()
        val pick = pickStockHome(found, resolved, c.packageName, installed) ?: return null
        return ComponentName(pick.pkg, pick.cls)
    }

    private fun openStockHome(c: Context): Boolean {
        val target = stockHome(c) ?: return false
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setComponent(target)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            c.startActivity(intent)
            true
        } catch (e: Exception) {
            // A launcher that is not exported, or a background start the system refuses: try the plain
            // Home intent, which the firmware's own default answers on most sets.
            try {
                c.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (second: Exception) {
                false
            }
        }
    }

    /** Removes the Home key service from the enabled list, so the system stops binding it. */
    private fun dropService(c: Context) {
        if (c.checkSelfPermission(WRITE_SECURE) != PackageManager.PERMISSION_GRANTED) return
        val me = ComponentName(c, HomeOverride::class.java)
        val on = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        if (on.split(':').none { ComponentName.unflattenFromString(it) == me }) return
        Settings.Secure.putString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            on.split(':').filter { it.isNotEmpty() && ComponentName.unflattenFromString(it) != me }.joinToString(":"))
    }
}

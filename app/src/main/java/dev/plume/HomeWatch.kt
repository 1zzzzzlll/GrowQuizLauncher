package dev.plume

/** One activity that answers the Home intent, as the system lists it. */
class HomeEntry(val pkg: String, val cls: String, val target: String?)

/**
 * The classes that the Google TV launcher declares for the Home intent: its home screens. The launcher draws
 * other windows too (notices, sign-in, the dashboard). An alias is listed under its own name but its window
 * carries the real class, so both count.
 */
fun googleTvHomes(found: List<HomeEntry>): Set<String> =
    found.filter { it.pkg == HomeWatch.GOOGLE_TV }.flatMap { listOfNotNull(it.cls, it.target) }.toSet()

/**
 * The home screens of every other launcher the system lists for Home, by package. This is what makes the
 * cover work on TVs whose vendor resets or withholds the home role (MIUI TV hands it back to its own launcher
 * on every boot): whatever the system still calls a home screen gets covered. TV players that declare Home
 * are left out, unless the system really resolves Home to them (TCL 85G60E lists com.tcl.tv as a Home, and
 * every input switch would otherwise be taken back).
 */
fun stockHomes(found: List<HomeEntry>, resolved: String?): Map<String, Set<String>> =
    found.filter { it.pkg != HomeWatch.PLUME }
        .filter { it.pkg !in HomeWatch.INPUT_PLAYERS || it.pkg == resolved }
        .groupBy { it.pkg }
        .mapValues { (_, entries) -> entries.flatMap { listOfNotNull(it.cls, it.target) }.toSet() }

/**
 * Decides, for each window that opens, whether Plume should cover the TV's own home screen. Fire OS never
 * hands the Home key to an accessibility service, so there this is the only way Home reaches Plume. MIUI TV
 * hands the home role back to its own launcher on every boot, so the same cover puts Plume back after a restart.
 * [googleHomes] gives the home screens of the Google TV launcher (see [googleTvHomes]); while it is empty the
 * launcher's windows all count, except the dashboard. [stockHomes] gives every other launcher's home screens
 * (see [stockHomes]); [FIRMWARE_HOMES] adds the ones a firmware starts by component, hidden from any query.
 * Kept free of Android classes so it can be tested off the TV.
 */
class HomeWatch(
    private val fire: Boolean,
    private val googleHomes: () -> Set<String>,
    private val stockHomes: () -> Map<String, Set<String>> = { emptyMap() },
) {
    private var settingsMayFollow = false
    private var lastJump = -JUMP_GAP_MS
    private var waitingPkg: String? = null

    /**
     * [KEEP]: leave any jump still waiting. Null: drop it. Otherwise: go home after this many ms.
     */
    fun opened(pkg: String, cls: String?, now: Long): Long? {
        // System UI events do not show that the user left Home; a different launcher screen does.
        if (waitingPkg != null && ((pkg == waitingPkg && isHome(pkg, cls)) ||
                pkg == PLUME || pkg == "android" || pkg == "com.android.systemui")) return KEEP
        waitingPkg = null
        if (!isHome(pkg, cls)) {
            // Fire OS shows its home on the way into (and out of) Amazon Settings, then Settings on top.
            settingsMayFollow = pkg == PLUME || pkg.startsWith(AMAZON_SETTINGS)
            return null
        }
        if (now - lastJump < JUMP_GAP_MS) return KEEP // one Home press sends several of these
        lastJump = now
        if (!settingsMayFollow || !fire) return 0
        waitingPkg = pkg
        return SETTINGS_WAIT_MS
    }

    fun jumped() { waitingPkg = null }

    private fun isHome(pkg: String, cls: String?): Boolean = cls != null && when (pkg) {
        GOOGLE_TV -> googleHomes().let { homes -> if (homes.isEmpty()) !cls.contains("dashboard", ignoreCase = true) else cls in homes } // holding Home opens the dashboard
        AMAZON -> cls.startsWith("$AMAZON.ui.HomeActivity") // HomeActivity_vNext on current builds
        OLD_AMAZON -> cls == "$OLD_AMAZON.Launcher"
        // MIUI TV's PatchWall: MainActivityUserMode on current builds, MainActivity/HomeActivity on older
        // ones; BootActivity is the cover it puts up some 20 s into a boot, so covering it skips the wait.
        MITV -> cls.contains("MainActivity") || cls.contains("HomeActivity") || cls.endsWith(".BootActivity")
        else -> cls in stockHomes()[pkg].orEmpty() || cls in FIRMWARE_HOMES[pkg].orEmpty()
    }

    companion object {
        internal const val PLUME = "dev.plume.growquiz"
        const val GOOGLE_TV = "com.google.android.apps.tv.launcherx"
        private const val AMAZON = "com.amazon.tv.launcher"
        private const val OLD_AMAZON = "com.amazon.firelauncher"
        const val MITV = "com.mitv.tvhome"
        /** PatchWall on Xiaomi's Android TV firmware (international sets) is its own package. */
        const val MITV_ATV = "com.mitv.tvhome.atv"
        private const val AMAZON_SETTINGS = "com.amazon.tv.settings"
        private const val JUMP_GAP_MS = 600L
        const val SETTINGS_WAIT_MS = 550L
        const val KEEP = -1L

        /** Where TV inputs play; they declare Home on some firmwares but are no launcher (see [stockHomes]). */
        internal val INPUT_PLAYERS = setOf("com.tcl.tv", "com.xiaomi.mitv.tvplayer", "com.skyworth.tv", "com.hisilicon.tvui")

        private const val VENDOR_HOME_PREFIX = "android.intent.category.HOME."
        private const val VENDOR_HOME_SUFFIX = "_HOME"

        /**
         * Vendor home categories a firmware picks its launcher by: Coocaa's android.intent.category.HOME.CC,
         * smartdevice.intent.category.LITE_HOME on others. Android's own SECONDARY_HOME is for other displays
         * and comes with CATEGORY_HOME anyway. Nor an automatic test's "home": CVTE's factory test declares
         * CVTE_AT_HOME, and covering it would switch the TV into factory mode.
         */
        internal fun isVendorHomeCategory(category: String): Boolean =
            category.startsWith(VENDOR_HOME_PREFIX) ||
                (category.endsWith(VENDOR_HOME_SUFFIX) && category != "android.intent.category.SECONDARY_HOME" &&
                    !category.endsWith("_AT_HOME"))

        /**
         * Launcher screens a firmware starts by component, hidden from Home queries: they declare no Home
         * filter, or the firmware keeps Home candidates out of a third-party caller's query. Each entry is
         * verified on a real set (see the aerial-tv project notes, which use the same list).
         */
        internal val FIRMWARE_HOMES = mapOf(
            // Skyworth-made Panasonic (Tianci, Android 6): started from /system/pcfg's DEFAULT_HOMEPAGE.
            "com.skyworth.panasonichome" to setOf("com.skyworth.panasonichome.MainActivity"),
            // Coocaa's AI launcher, started by com.tianci.system's SLauncher on the Home key and at power-on.
            "com.coocaa.aigc.home" to setOf("com.coocaa.aigc.home.home.rootpage.HomeActivity"),
            // What Tianci shows as its home, named only in persist.service.homepage.cls.
            "com.tianci.movieplatform" to setOf("com.coocaa.homepage.vast.HomePageActivity"),
            // Skyworth E900V21F (China Mobile box): the firmware filters Home candidates by signature.
            "com.mylejia.launcher" to setOf("com.mylejia.launcher.activity.DesktopActivity"),
            // TCL's China launcher; on some sets a Home query from a third party returns nothing at all.
            "com.tcl.cyberui" to setOf("com.tcl.cyberui.MainActivity"),
            // Hisense VIDAA's 聚好看; note it raises no window change on some firmwares, so the cover
            // can miss it there (usage-stats polling is how other projects close that gap).
            "com.jamdeo.tv.vod" to setOf("com.ju.main.launcher.LauncherActivity"),
            // Xiaomi's office/elderly mode home (the standard mode's home is PatchWall itself).
            "com.xiaomi.eetv.litehome" to setOf("com.xiaomi.eetv.litehome.Launcher"),
            // PatchWall on Xiaomi's Android TV firmware declares no Home; the Mi key starts it by component.
            MITV_ATV to setOf("$MITV_ATV.app.PatchWallActivity"),
            // LeShi (LeTV) boxes: Home is a trampoline, this is the screen it opens.
            "com.stv.deskplatform" to setOf("com.stv.deskplatform.ui.activity.Launcher"),
            // Tencent 极光 boxes: Home is LauncherMainActivity, the screen shown is this one.
            "com.ktcp.launcher" to setOf("com.ktcp.launcher.activity.LauncherHomeSingleTaskActivity"),
            // Hisense VIDAA's voice launcher, a Home with negative priority the firmware really starts.
            "com.keylab.speech.view.vidaa" to setOf("com.ju.secondscreen.HomeActivity"),
        )
    }
}

package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeWatchTest {
    private val amazon = "com.amazon.tv.launcher"
    private val amazonHome = "com.amazon.tv.launcher.ui.HomeActivity_vNext"
    private val starter = "com.amazon.firehomestarter"
    private val gtv = "com.google.android.apps.tv.launcherx"
    private val gtvHome = "$gtv.home.HomeActivity"
    private val gtvKids = "$gtv.home.VanillaModeHomeActivity"
    private val oldFire = "com.amazon.firelauncher"
    private fun watch(
        fire: Boolean = true,
        homes: Set<String> = setOf(gtvHome, gtvKids),
        stock: Map<String, Set<String>> = emptyMap(),
    ) = HomeWatch(fire, { homes }, { stock })

    @Test fun amazonHomeFromAnAppJumpsAtOnce() {
        val w = watch()
        assertNull(w.opened("com.netflix.ninja", "Player", 1_000))
        assertEquals(0L, w.opened(amazon, amazonHome, 2_000))
    }

    @Test fun onePressJumpsOnce() {
        val w = watch()
        assertEquals(0L, w.opened(amazon, amazonHome, 1_000))
        assertEquals(HomeWatch.KEEP, w.opened(amazon, amazonHome, 1_200))
        assertEquals(0L, w.opened(amazon, amazonHome, 2_000))
    }

    // Another window of the same press must not drop the jump that waits for Settings to go away.
    @Test fun aSecondHomeWindowOfThePressKeepsTheWaitingJump() {
        val w = watch()
        w.opened("com.amazon.tv.settings.v2", "Settings", 1_000)
        assertEquals(HomeWatch.SETTINGS_WAIT_MS, w.opened(amazon, amazonHome, 2_000))
        assertEquals(HomeWatch.KEEP, w.opened(amazon, "$amazon.ui.HomeActivity", 2_100))
    }

    @Test fun theStarterOfTheAmazonHomeIsNotAHomeWindow() {
        val w = watch()
        assertNull(w.opened("com.netflix.ninja", "Player", 1_000))
        assertNull(w.opened(starter, "$starter.HomeStarterActivity", 2_000))
        assertEquals(0L, w.opened(amazon, amazonHome, 2_050))
    }

    @Test fun otherAmazonLauncherScreensAreLeftAlone() {
        assertNull(watch().opened(amazon, "com.amazon.tv.launcher.ui.QuickSettingsActivity", 1_000))
    }

    @Test fun everyHomeActivityOfTheAmazonLauncherCounts() {
        assertEquals(0L, watch().opened(amazon, "$amazon.ui.HomeActivity", 1_000))
        assertEquals(0L, watch().opened(amazon, "$amazon.ui.HomeActivity_v2", 1_000))
    }

    @Test fun waitsWhenSettingsMayFollow() {
        val w = watch()
        w.opened("com.amazon.tv.settings.v2", "Settings", 1_000)
        assertEquals(HomeWatch.SETTINGS_WAIT_MS, w.opened(amazon, amazonHome, 2_000))
        w.jumped()
        w.opened("dev.plume.growquiz", "dev.plume.MainActivity", 3_000)
        assertEquals(HomeWatch.SETTINGS_WAIT_MS, w.opened(amazon, amazonHome, 4_000))
    }

    @Test fun theWaitIsOnlyForFireTv() {
        val w = watch(fire = false)
        w.opened("dev.plume.growquiz", "dev.plume.MainActivity", 1_000)
        assertEquals(0L, w.opened(gtv, gtvHome, 2_000))
        w.opened("dev.plume.growquiz", "dev.plume.MainActivity", 3_000)
        assertEquals(0L, w.opened(amazon, amazonHome, 4_000))
    }

    @Test fun olderFireLauncher() {
        assertEquals(0L, watch().opened(oldFire, "$oldFire.Launcher", 1_000))
    }

    @Test fun googleTvHomeJumpsAtOnce() {
        val w = watch(fire = false)
        w.opened("dev.plume.growquiz", "dev.plume.MainActivity", 2_000)
        assertEquals(0L, w.opened(gtv, gtvHome, 3_000))
        assertEquals(0L, w.opened(gtv, gtvKids, 4_000))
    }

    // #150: the Google TV launcher also draws notices and sign-in screens, and Plume took each for the home screen.
    @Test fun otherGoogleTvLauncherWindowsAreLeftAlone() {
        val w = watch(fire = false)
        for ((i, cls) in listOf(
            "$gtv.networkreauth.NetworkReauthActivity",
            "$gtv.dashboard.DashboardActivity",
            "$gtv.gemini.chat.ChatActivity",
            "android.widget.FrameLayout", // a notice drawn by the launcher
        ).withIndex()) assertNull(cls, w.opened(gtv, cls, 1_000L + i * 1_000))
    }

    // The system gave no list: keep the rule that was there before, so Home on Google TV still works.
    @Test fun withoutAListTheGoogleTvLauncherCountsExceptItsDashboard() {
        val w = watch(fire = false, homes = emptySet())
        assertEquals(0L, w.opened(gtv, gtvHome, 1_000))
        assertNull(w.opened(gtv, "$gtv.dashboard.DashboardActivity", 3_000))
    }

    // MIUI TV hands the home role back to com.mitv.tvhome on every boot, so its home screen gets covered.
    @Test fun xiaomiHomeJumpsAtOnce() {
        val w = watch(fire = false)
        w.opened("dev.plume.growquiz", "dev.plume.MainActivity", 1_000)
        assertEquals(0L, w.opened("com.mitv.tvhome", "com.mitv.tvhome.MainActivityUserMode", 2_000))
    }

    @Test fun theOlderXiaomiHomeCounts() {
        assertEquals(0L, watch(fire = false).opened("com.mitv.tvhome", "com.mitv.tvhome.HomeActivity", 1_000))
    }

    // PatchWall's boot cover comes up some 20 s into a boot; covering it skips the wait after a restart.
    @Test fun theXiaomiBootCoverCounts() {
        assertEquals(0L, watch(fire = false).opened("com.mitv.tvhome", "com.mitv.tvhome.BootActivity", 1_000))
    }

    @Test fun otherXiaomiLauncherWindowsAreLeftAlone() {
        val w = watch(fire = false)
        assertNull(w.opened("com.mitv.tvhome", "com.mitv.tvhome.settings.SettingsActivity", 1_000))
        assertNull(w.opened("com.mitv.tvhome", "android.widget.FrameLayout", 2_000))
    }

    // Domestic launchers a firmware starts by component, hidden from any Home query (see FIRMWARE_HOMES).
    @Test fun firmwareHomesJumpAtOnce() {
        val w = watch(fire = false)
        for ((i, home) in listOf(
            "com.tcl.cyberui" to "com.tcl.cyberui.MainActivity",
            "com.jamdeo.tv.vod" to "com.ju.main.launcher.LauncherActivity",
            "com.coocaa.aigc.home" to "com.coocaa.aigc.home.home.rootpage.HomeActivity",
            "com.tianci.movieplatform" to "com.coocaa.homepage.vast.HomePageActivity",
            "com.mitv.tvhome.atv" to "com.mitv.tvhome.atv.app.PatchWallActivity",
            "com.stv.deskplatform" to "com.stv.deskplatform.ui.activity.Launcher",
            "com.ktcp.launcher" to "com.ktcp.launcher.activity.LauncherHomeSingleTaskActivity",
        ).withIndex()) {
            assertEquals(home.first, 0L, w.opened(home.first, home.second, 1_000L + i * 1_000))
        }
    }

    @Test fun aScreenNotListedAsAFirmwareHomeIsLeftAlone() {
        assertNull(watch(fire = false).opened("com.tcl.cyberui", "com.tcl.cyberui.settings.SettingsActivity", 1_000))
    }

    // Home screens the system lists, on a TV whose vendor hid nothing: the query alone is enough.
    @Test fun aStockHomeFromTheSystemListJumpsAtOnce() {
        val w = watch(fire = false, stock = mapOf("com.skyworth.officehomepage" to setOf("com.skyworth.officehomepage.HomeActivity")))
        w.opened("dev.plume.growquiz", "dev.plume.MainActivity", 1_000)
        assertEquals(0L, w.opened("com.skyworth.officehomepage", "com.skyworth.officehomepage.HomeActivity", 2_000))
    }

    @Test fun aStockHomeWindowOfAnotherClassIsLeftAlone() {
        val w = watch(fire = false, stock = mapOf("com.tcl.cyberui" to setOf("com.tcl.cyberui.MainActivity")))
        assertNull(w.opened("com.tcl.cyberui", "com.tcl.cyberui.OtherActivity", 1_000))
    }

    @Test fun theStockHomesAreGroupedByPackageWithoutPlume() {
        val found = listOf(
            entry(gtv, gtvHome),
            entry("dev.plume.growquiz", "dev.plume.MainActivity"),
            entry("com.ktcp.launcher", "com.ktcp.launcher.LauncherMainActivity", target = "com.ktcp.launcher.activity.LauncherHomeSingleTaskActivity"),
        )
        assertEquals(
            mapOf(
                gtv to setOf(gtvHome),
                "com.ktcp.launcher" to setOf("com.ktcp.launcher.LauncherMainActivity", "com.ktcp.launcher.activity.LauncherHomeSingleTaskActivity"),
            ),
            stockHomes(found, resolved = "dev.plume.growquiz"),
        )
    }

    // TCL 85G60E lists com.tcl.tv as a Home next to its launcher; the cover must not take back every input switch.
    @Test fun aTvPlayerCountsOnlyWhereItIsTheResolvedHome() {
        val player = entry("com.tcl.tv", "com.tcl.tv.TVActivity")
        assertEquals(emptyMap<String, Set<String>>(), stockHomes(listOf(player), resolved = "com.tcl.cyberui"))
        assertEquals(mapOf("com.tcl.tv" to setOf("com.tcl.tv.TVActivity")), stockHomes(listOf(player), resolved = "com.tcl.tv"))
    }

    @Test fun vendorHomeCategories() {
        assert(HomeWatch.isVendorHomeCategory("android.intent.category.HOME.CC"))
        assert(HomeWatch.isVendorHomeCategory("smartdevice.intent.category.LITE_HOME"))
        assert(!HomeWatch.isVendorHomeCategory("android.intent.category.SECONDARY_HOME"))
        assert(!HomeWatch.isVendorHomeCategory("com.cvte.factory.at.CVTE_AT_HOME"))
        assert(!HomeWatch.isVendorHomeCategory("android.intent.category.LAUNCHER"))
    }

    @Test fun aWindowWithoutAClassIsLeftAlone() {
        assertNull(watch().opened(gtv, null, 1_000))
        assertNull(watch().opened(amazon, null, 1_000))
    }

    @Test fun aLauncherThatIsNotInTheListIsLeftAlone() {
        assertNull(watch().opened("com.spocky.projengmenu", "com.spocky.projengmenu.ui.home.MainActivity", 1_000))
    }

    private fun entry(pkg: String, cls: String, target: String? = null) = HomeEntry(pkg, cls, target)

    @Test fun theGoogleTvHomesAreWhatTheLauncherDeclaresForHome() {
        val found = listOf(
            entry(gtv, gtvHome),
            entry(gtv, gtvKids),
            entry(amazon, amazonHome),
            entry("dev.plume.growquiz", "dev.plume.MainActivity"),
        )
        assertEquals(setOf(gtvHome, gtvKids), googleTvHomes(found))
    }

    @Test fun anAliasCountsUnderItsOwnNameAndItsRealClass() {
        val found = listOf(entry(gtv, "$gtv.HomeAlias", target = "$gtv.home.RealHome"))
        assertEquals(setOf("$gtv.HomeAlias", "$gtv.home.RealHome"), googleTvHomes(found))
    }

    @Test fun noGoogleTvLauncherGivesAnEmptySet() {
        assertEquals(emptySet<String>(), googleTvHomes(listOf(entry(amazon, amazonHome))))
    }

    // A second Home window must leave the jump waiting when Home is pressed from Plume.
    @Test fun moreWindowsOfTheSamePressKeepTheWaitingJump() {
        val w = watch()
        w.opened("dev.plume.growquiz", "dev.plume.MainActivity", 1_000)
        assertEquals(HomeWatch.SETTINGS_WAIT_MS, w.opened(amazon, amazonHome, 2_000))
        assertEquals(HomeWatch.KEEP, w.opened(amazon, amazonHome, 2_100))
        assertEquals(HomeWatch.KEEP, w.opened("com.android.systemui", "Panel", 2_200))
        assertEquals(HomeWatch.KEEP, w.opened("dev.plume.growquiz", "dev.plume.MainActivity", 2_300))
    }

    @Test fun anotherAppOpeningDropsTheWaitingJump() { // Settings on top of Amazon's home
        val w = watch()
        w.opened("dev.plume.growquiz", "dev.plume.MainActivity", 1_000)
        assertEquals(HomeWatch.SETTINGS_WAIT_MS, w.opened(amazon, amazonHome, 2_000))
        assertNull(w.opened("com.amazon.tv.settings.v2", "Settings", 2_200))
    }

    @Test fun waitingJumpSurvivesTheGapUntilItRuns() {
        val w = watch()
        w.opened("dev.plume.growquiz", "dev.plume.MainActivity", 1_000)
        assertEquals(HomeWatch.SETTINGS_WAIT_MS, w.opened(amazon, amazonHome, 2_000))
        assertEquals(HomeWatch.KEEP, w.opened(amazon, amazonHome, 2_000 + HomeWatch.SETTINGS_WAIT_MS))
        assertEquals(HomeWatch.KEEP, w.opened(amazon, amazonHome, 2_575))
        w.jumped()
        assertEquals(HomeWatch.SETTINGS_WAIT_MS, w.opened(amazon, amazonHome, 3_000))
    }

    @Test fun anotherAmazonLauncherScreenDropsTheWaitingJump() {
        val w = watch()
        w.opened("dev.plume.growquiz", "dev.plume.MainActivity", 1_000)
        assertEquals(HomeWatch.SETTINGS_WAIT_MS, w.opened(amazon, amazonHome, 2_000))
        assertNull(w.opened(amazon, "com.amazon.tv.launcher.ui.QuickSettingsActivity", 2_200))
    }

    @Test fun androidWindowDoesNotDropTheWaitingJump() {
        val w = watch()
        w.opened("dev.plume.growquiz", "dev.plume.MainActivity", 1_000)
        assertEquals(HomeWatch.SETTINGS_WAIT_MS, w.opened(amazon, amazonHome, 2_000))
        assertEquals(HomeWatch.KEEP, w.opened("android", "Dialog", 2_200))
        assertEquals(HomeWatch.KEEP, w.opened(amazon, amazonHome, 2_300))
    }

    @Test fun bootCatchUpCoversKnownHomePackages() { // the window class is unknown right after a boot
        val w = watch(stock = mapOf("com.tcl.cyberui" to setOf("com.tcl.cyberui.MainActivity")))
        org.junit.Assert.assertTrue(w.coversPackage(amazon))
        org.junit.Assert.assertTrue(w.coversPackage(oldFire))
        org.junit.Assert.assertTrue(w.coversPackage(gtv))
        org.junit.Assert.assertTrue(w.coversPackage(HomeWatch.MITV))
        org.junit.Assert.assertTrue(w.coversPackage("com.tcl.cyberui")) // from the system Home query
        org.junit.Assert.assertTrue(w.coversPackage("com.ktcp.launcher")) // from the firmware table
    }

    @Test fun bootCatchUpLeavesOtherAppsAlone() {
        val w = watch()
        org.junit.Assert.assertFalse(w.coversPackage("dev.plume.growquiz"))
        org.junit.Assert.assertFalse(w.coversPackage("com.netflix.ninja"))
        org.junit.Assert.assertFalse(w.coversPackage("com.example.launcher3")) // not in any home list
    }
}

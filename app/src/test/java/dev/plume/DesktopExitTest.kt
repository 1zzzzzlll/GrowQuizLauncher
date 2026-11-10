package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which desktop the TV gets back when the parents exit the launcher. */
class DesktopExitTest {
    private val self = "dev.plume.growquiz"
    private fun entry(pkg: String, cls: String = "$pkg.MainActivity", target: String? = null) = HomeEntry(pkg, cls, target)

    @Test fun theDesktopTheSystemResolvesTodayWins() {
        val found = listOf(entry("com.vendor.other"), entry("com.mitv.tvhome"))
        val pick = pickStockHome(found, "com.vendor.other", self, emptySet())
        assertEquals("com.vendor.other", pick?.pkg)
    }

    @Test fun plumeIsNeverTheDesktopToGoBackTo() {
        val found = listOf(entry(self), entry("com.mitv.tvhome"))
        assertEquals("com.mitv.tvhome", pickStockHome(found, self, self, emptySet())?.pkg)
    }

    @Test fun thePlatformBootStubIsNotADesktop() {
        // Settings' FallbackHome answers Home on every build, and no one ever picked it.
        val found = listOf(entry("com.android.settings", "com.android.settings.FallbackHome"), entry("com.miui.home"))
        val pick = pickStockHome(found, "com.android.settings", self, emptySet())
        assertEquals("com.miui.home", pick?.pkg)
    }

    @Test fun aFirmwareLauncherThatAnswersNoQueryStillWorks() {
        // TCL starts com.tcl.cyberui by component; a third-party Home query returns nothing for it.
        val pick = pickStockHome(emptyList(), null, self, setOf("com.tcl.cyberui"))
        assertEquals("com.tcl.cyberui", pick?.pkg)
        assertEquals("com.tcl.cyberui.MainActivity", pick?.cls)
    }

    @Test fun anInstalledFirmwareLauncherIsPreferredOverAnUnknownHome() {
        val found = listOf(entry("com.example.something"))
        val pick = pickStockHome(found, null, self, setOf("com.tcl.cyberui", "com.example.something"))
        assertEquals("com.tcl.cyberui", pick?.pkg)
    }

    @Test fun aListedHomeScreenIsTheLastResort() {
        val pick = pickStockHome(listOf(entry("com.example.launcher")), null, self, emptySet())
        assertEquals("com.example.launcher", pick?.pkg)
    }

    @Test fun aTvInputThatDeclaresHomeIsNotADesktop() {
        assertNull(pickStockHome(listOf(entry("com.tcl.tv")), null, self, emptySet()))
    }

    @Test fun nothingUsableMeansNoExitTarget() {
        assertNull(pickStockHome(emptyList(), null, self, emptySet()))
        assertNull(pickStockHome(listOf(entry(self)), self, self, emptySet()))
    }
}

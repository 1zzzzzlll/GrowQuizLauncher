package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Test

class TopBarTest {
    private val known = listOf("wifi", "sources", "apps", "system", "plume")

    @Test fun nothingSavedShowsTheDefault() {
        assertEquals(known, TopBar.parse(null, known, known, listOf("plume")))
    }

    @Test fun aSavedListKeepsItsOrderAndDropsWhatIsUnknownOrRepeated() {
        assertEquals(listOf("apps", "wifi", "plume"), TopBar.parse("apps,wifi,gone,apps,plume", known, known, listOf("plume")))
    }

    @Test fun plumeSettingsIsNeverMissing() {
        assertEquals(listOf("wifi", "plume"), TopBar.parse("wifi", known, known, listOf("plume")))
        assertEquals(listOf("plume"), TopBar.parse("", known, known, listOf("plume"))) // the bar is never empty
    }

    @Test fun anIconSlidesPastTheOnesOnScreen() {
        val order = listOf("wifi", "sources", "apps", "plume")
        val shown = listOf("wifi", "apps", "plume") // no input: Sources is not on screen
        assertEquals(listOf("wifi", "sources", "plume", "apps"), TopBar.slide(order, shown, "apps", 1))
        assertEquals(listOf("apps", "sources", "wifi", "plume"), TopBar.slide(order, shown, "apps", -1))
    }

    @Test fun anIconAtAnEndStaysThere() {
        val order = listOf("wifi", "apps", "plume")
        assertEquals(order, TopBar.slide(order, order, "wifi", -1))
        assertEquals(order, TopBar.slide(order, order, "plume", 1))
        assertEquals(order, TopBar.slide(order, order, "system", 1)) // not on the bar
    }

    @Test fun anAddedIconLandsRightAfterTheOneYouHeld() {
        val order = listOf("wifi", "apps", "plume")
        assertEquals(listOf("wifi", "system", "apps", "plume"), TopBar.addAfter(order, "system", "wifi"))
        assertEquals(listOf("wifi", "apps", "plume", "system"), TopBar.addAfter(order, "system", "plume"))
        assertEquals(listOf("wifi", "apps", "plume", "system"), TopBar.addAfter(order, "system", "missing"))
    }

    @Test fun aDefaultCanLeaveAnIconOut() { // Power is not on the bar until someone adds it
        val all = known + "power"
        assertEquals(known, TopBar.parse(null, all, all - "power", listOf("plume")))
        assertEquals(known + "power", TopBar.parse("wifi,sources,apps,system,plume,power", all, all - "power", listOf("plume")))
    }

    @Test fun aBarSavedBeforeAnUpdateGainsTheNewRequiredIcon() { // 环境检测 landed after people had saved bars
        assertEquals(listOf("apps", "plume", "env"), TopBar.parse("apps,plume", known, known, listOf("plume", "env")))
        assertEquals(listOf("apps", "plume", "env"), TopBar.parse("apps,plume,env", known + "env", known, listOf("plume", "env")))
    }
}

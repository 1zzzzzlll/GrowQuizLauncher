package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Test

class VendorTest {

    @Test
    fun `tcl is matched from brand or manufacturer`() {
        assertEquals(Vendor.TCL, Vendor.identify("TCL", "TCL"))
        assertEquals(Vendor.TCL, Vendor.identify("tcl_mt5879_cn", null))
        assertEquals(Vendor.TCL, Vendor.identify(null, "TCL Technology"))
    }

    @Test
    fun `ffalcon stays apart from tcl`() {
        assertEquals(Vendor.FFALCON, Vendor.identify("FFalcon", "TCL"))
    }

    @Test
    fun `xiaomi covers redmi`() {
        assertEquals(Vendor.XIAOMI, Vendor.identify("Xiaomi", "Xiaomi"))
        assertEquals(Vendor.XIAOMI, Vendor.identify("Redmi", "Xiaomi"))
    }

    @Test
    fun `domestic makers`() {
        assertEquals(Vendor.HISENSE, Vendor.identify("Hisense", "Hisense"))
        assertEquals(Vendor.HISENSE, Vendor.identify("VIDAA", null))
        assertEquals(Vendor.SKYWORTH, Vendor.identify("Skyworth", null))
        assertEquals(Vendor.SKYWORTH, Vendor.identify("coocaa", null))
        assertEquals(Vendor.KONKA, Vendor.identify("KONKA", null))
        assertEquals(Vendor.CHANGHONG, Vendor.identify("CHANGHONG", null))
        assertEquals(Vendor.HAIER, Vendor.identify("Haier", null))
        assertEquals(Vendor.XGIMI, Vendor.identify("XGIMI", "XGIMI"))
    }

    @Test
    fun `global makers`() {
        assertEquals(Vendor.HUAWEI, Vendor.identify("HUAWEI", null))
        assertEquals(Vendor.HUAWEI, Vendor.identify("HONOR", null))
        assertEquals(Vendor.AMAZON, Vendor.identify("Amazon", "Amazon"))
        assertEquals(Vendor.SONY, Vendor.identify("Sony", "Sony"))
        assertEquals(Vendor.PHILIPS, Vendor.identify("Philips", "TPV"))
    }

    @Test
    fun `unknown maker falls to other`() {
        assertEquals(Vendor.OTHER, Vendor.identify("acme", "acme"))
        assertEquals(Vendor.OTHER, Vendor.identify(null, null))
    }

    @Test
    fun `dead accessibility revive is gated to verified brands only`() {
        org.junit.Assert.assertTrue(Vendor.revivesDeadA11y(Vendor.TCL))
        org.junit.Assert.assertTrue(Vendor.revivesDeadA11y(Vendor.KONKA))
        org.junit.Assert.assertFalse(Vendor.revivesDeadA11y(Vendor.XIAOMI))
        org.junit.Assert.assertFalse(Vendor.revivesDeadA11y(Vendor.HUAWEI))
        org.junit.Assert.assertFalse(Vendor.revivesDeadA11y(Vendor.SKYWORTH))
        org.junit.Assert.assertFalse(Vendor.revivesDeadA11y(Vendor.OTHER))
    }
}

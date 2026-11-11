package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceFormTest {

    @Test
    fun `the television hardware flag wins`() {
        assertEquals(DeviceForm.TV, DeviceForm.of(leanback = false, television = true, touchscreen = true, smallestWidthDp = 400))
    }

    @Test
    fun `leanback says tv`() {
        assertEquals(DeviceForm.TV, DeviceForm.of(leanback = true, television = false, touchscreen = false, smallestWidthDp = 960))
    }

    // A set that declares neither is not a phone: a phone-only rule must not reach a TV.
    @Test
    fun `no television flag and no touch screen is unknown`() {
        assertEquals(DeviceForm.UNKNOWN, DeviceForm.of(leanback = false, television = false, touchscreen = false, smallestWidthDp = 960))
    }

    @Test
    fun `a touch screen splits phone from tablet at 600 dp`() {
        assertEquals(DeviceForm.PHONE, DeviceForm.of(leanback = false, television = false, touchscreen = true, smallestWidthDp = 599))
        assertEquals(DeviceForm.TABLET, DeviceForm.of(leanback = false, television = false, touchscreen = true, smallestWidthDp = 600))
    }

    // The three devices that reported on 2026-11-10.
    @Test
    fun `the reporting devices are read as they are`() {
        assertEquals(DeviceForm.PHONE, DeviceForm.of(leanback = false, television = false, touchscreen = true, smallestWidthDp = 411)) // Redmi light
        assertEquals(DeviceForm.PHONE, DeviceForm.of(leanback = false, television = false, touchscreen = true, smallestWidthDp = 384)) // OnePlus PLZ110
        assertEquals(DeviceForm.PHONE, DeviceForm.of(leanback = false, television = false, touchscreen = true, smallestWidthDp = 384)) // samsung SM-S9180
    }

    // TCL ak30a5 (Android 9, 1920x1080, reported 2026-11-11): a set that declares no television
    // feature at all. The running UI mode is the only thing left that says what it is.
    @Test
    fun `the television ui mode says tv when both feature flags are missing`() {
        assertEquals(DeviceForm.TV, DeviceForm.of(leanback = false, television = false, touchscreen = false, smallestWidthDp = 960, uiModeTv = true))
    }

    // A touch panel that runs the television UI mode is a television, like a set that declares the
    // television hardware itself: the television signal outranks the touch screen, as it always has.
    @Test
    fun `the television ui mode outranks a touch screen`() {
        assertEquals(DeviceForm.TV, DeviceForm.of(leanback = false, television = false, touchscreen = true, smallestWidthDp = 960, uiModeTv = true))
    }

    // Nothing new may turn a set that reports neither into a phone.
    @Test
    fun `no signal at all stays unknown`() {
        assertEquals(DeviceForm.UNKNOWN, DeviceForm.of(leanback = false, television = false, touchscreen = false, smallestWidthDp = 960, uiModeTv = false))
    }
}

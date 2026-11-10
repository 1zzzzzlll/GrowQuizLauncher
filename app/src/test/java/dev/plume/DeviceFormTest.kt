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
}

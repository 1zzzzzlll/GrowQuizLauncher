package dev.plume

import dev.plume.EnvCheck.Key
import dev.plume.EnvCheck.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvCheckTest {

    private fun items(
        sdk: Int = 34,
        form: String = DeviceForm.TV,
        overlay: Boolean = true,
        exactAlarm: Boolean = true,
        batteryFree: Boolean = true,
        notifications: Boolean = true,
        fullScreen: Boolean = true,
        a11yEnabled: Boolean = true,
        a11yBound: Boolean = true,
        snapBack: Boolean = true,
        snapOkCount: Int = 3,
        tts: String = "available",
        ttsWanted: Boolean = true,
    ) = EnvCheck.items(
        sdk, form, overlay, exactAlarm, batteryFree, notifications, fullScreen,
        a11yEnabled, a11yBound, snapBack, snapOkCount, tts, ttsWanted,
    )

    @Test fun everythingGrantedPasses() {
        val list = items()
        assertEquals(8, list.size)
        assertTrue(list.all { it.state == State.PASS })
        assertEquals(0, EnvCheck.openCount(list))
        assertTrue(list.all { it.fix.isEmpty() })
    }

    @Test fun aPlatformTooOldNeverShowsWhatItCannotGrant() {
        val keys = items(sdk = 30).map { it.key }
        assertFalse(Key.EXACT_ALARM in keys)
        assertFalse(Key.NOTIFICATION in keys)
        assertFalse(Key.FULL_SCREEN in keys)
        assertEquals(listOf(Key.OVERLAY, Key.BATTERY, Key.HOME_SERVICE, Key.SNAP_BACK, Key.TTS), keys)
    }

    @Test fun aFailedCheckOnATvLeadsWithTheCommand() {
        val list = items(overlay = false)
        val overlay = list.first { it.key == Key.OVERLAY }
        assertEquals(State.FAIL, overlay.state)
        assertTrue(overlay.fix.isNotEmpty())
        assertEquals(listOf("adb shell appops set dev.plume.growquiz SYSTEM_ALERT_WINDOW allow"), overlay.adb)
        // Snap-back cannot work without the overlay grant, so it fails along with it.
        assertEquals(State.FAIL, list.first { it.key == Key.SNAP_BACK }.state)
        assertEquals(2, EnvCheck.openCount(list))
    }

    @Test fun aPhoneGetsTheSettingsPathInsteadOfCommands() {
        val list = items(form = DeviceForm.PHONE, overlay = false, batteryFree = false, a11yEnabled = false, a11yBound = false)
        // Every one of these has a settings page on a phone, so no row sends the parent to a computer.
        assertTrue(list.filter { it.state == State.FAIL }.all { it.adb.isEmpty() })
        assertTrue(list.first { it.key == Key.OVERLAY }.fix.contains("设置"))
        // A tablet reads the same way; an unknown form stays on the TV path, which never needs a page.
        assertTrue(items(form = DeviceForm.TABLET, overlay = false).first { it.key == Key.OVERLAY }.adb.isEmpty())
        assertTrue(items(form = DeviceForm.UNKNOWN, overlay = false).first { it.key == Key.OVERLAY }.adb.isNotEmpty())
    }

    @Test fun theHomeServiceHasThreeStates() {
        assertEquals(State.FAIL, items(a11yEnabled = false, a11yBound = false).first { it.key == Key.HOME_SERVICE }.state)
        assertEquals(State.WARN, items(a11yEnabled = true, a11yBound = false).first { it.key == Key.HOME_SERVICE }.state)
        assertEquals(State.PASS, items(a11yEnabled = true, a11yBound = true).first { it.key == Key.HOME_SERVICE }.state)
        // Only the un-enabled state sends the parent to a computer.
        assertEquals(2, items(a11yEnabled = false, a11yBound = false).first { it.key == Key.HOME_SERVICE }.adb.size)
    }

    @Test fun snapBackWaitsForEvidence() {
        assertEquals(State.WARN, items(snapOkCount = 0).first { it.key == Key.SNAP_BACK }.state)
        assertEquals(State.FAIL, items(snapBack = false).first { it.key == Key.SNAP_BACK }.state)
    }

    @Test fun speechLeavesTheListWhenReadAloudIsOff() {
        assertFalse(Key.TTS in items(ttsWanted = false).map { it.key })
        assertEquals(State.WARN, items(tts = "initializing").first { it.key == Key.TTS }.state)
        assertEquals(State.FAIL, items(tts = "no-engine").first { it.key == Key.TTS }.state)
    }
}

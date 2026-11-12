package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsAppsTest {

    @Test
    fun `an empty set has no settings app to report`() {
        assertEquals(emptyList<String>(), SettingsApps.merge(emptyList(), emptyList()))
    }

    // A Huawei vision screen, an XGIMI projector and TCL's ak30a5 each opened no Settings page:
    // handlers empty, and nothing named "settings" installed.
    @Test
    fun `a firmware without any settings app reports empty`() {
        assertEquals(
            emptyList<String>(),
            SettingsApps.merge(listOf("com.tcl.cyberui", "dev.plume.growquiz"), emptyList()),
        )
    }

    @Test
    fun `installed packages whose name says settings are listed`() {
        assertEquals(
            listOf("com.android.settings", "com.tcl.tv.settings"),
            SettingsApps.merge(
                listOf("com.tcl.tv.settings", "com.tcl.cyberui", "com.android.settings"),
                emptyList(),
            ),
        )
    }

    @Test
    fun `the name match ignores case`() {
        assertEquals(
            listOf("com.vendor.SettingsTv"),
            SettingsApps.merge(listOf("com.vendor.SettingsTv"), emptyList()),
        )
    }

    // A handler proves the page opens even when its package name does not say "settings".
    @Test
    fun `a settings handler is kept whatever its package is named`() {
        assertEquals(
            listOf("com.android.settings", "com.vendor.controlcenter"),
            SettingsApps.merge(
                listOf("com.android.settings"),
                listOf("com.vendor.controlcenter"),
            ),
        )
    }

    @Test
    fun `a package found both ways appears once, and the list is sorted`() {
        assertEquals(
            listOf("com.android.settings", "com.android.tv.settings"),
            SettingsApps.merge(
                listOf("com.android.tv.settings", "com.android.settings"),
                listOf("com.android.settings", "com.android.tv.settings", "com.android.settings"),
            ),
        )
    }
}

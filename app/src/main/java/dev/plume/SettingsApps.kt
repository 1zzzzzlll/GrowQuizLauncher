package dev.plume

/**
 * Which package on a set is its Settings app, asked two ways at once: the packages that answer
 * the Settings intents the launcher itself opens, and the installed packages whose name says
 * "settings". Three TV firmwares so far open neither the top-level Settings page nor the Wi-Fi
 * page (a Huawei vision screen, an XGIMI projector, TCL's ak30a5 — each logged settings_open_fail).
 * The launcher catches the failure and says so, but a report could not tell whether the set ships
 * a Settings app under a package name of its own. This list is that evidence; a vendor fallback
 * can only be written once a real package name is known. Kept free of Android classes so it can
 * be tested off the TV.
 */
object SettingsApps {

    /**
     * [installed]: every installed package name the caller could list. [handlers]: every package
     * that answered one of the Settings intents the launcher opens. The answer is both, merged:
     * a handler proves the page opens, an installed "settings" package that answers nothing is
     * the candidate a vendor-specific fallback would try.
     */
    fun merge(installed: List<String>, handlers: List<String>): List<String> =
        (installed.filter { it.contains("settings", ignoreCase = true) } + handlers)
            .distinct().sorted()
}

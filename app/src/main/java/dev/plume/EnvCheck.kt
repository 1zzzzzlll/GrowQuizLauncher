package dev.plume

/**
 * The environment checks behind the "环境检测" button, on the TV's top bar and in the parents'
 * console. One list serves both: the TV shows it as a pop-up whose failed rows open the matching
 * system page, the console shows it with adb commands a computer can run instead.
 *
 * Kept off Android so the rules can be tested: the caller reads the device and passes plain values.
 * A check the platform cannot grant (exact alarms before Android 12, the notification permission
 * before 13, full-screen intents before 14) is left out, so the list never shows an item that
 * cannot be fixed — the same rule [dev.plume.quiz.QuizLock.missing] follows.
 *
 * The fix text follows the device [form] (see [DeviceForm]). A TV often ships no settings page at
 * all (a Huawei vision screen had none), so there the adb command is the fix and the settings path
 * the fallback. A phone or tablet has every page, so it gets the ordinary settings path, and only
 * what no page can grant keeps its command.
 */
object EnvCheck {

    /** The applicationId, pinned here so the adb text the console shows is covered by tests. */
    const val PKG = "dev.plume.growquiz"

    enum class Key { OVERLAY, EXACT_ALARM, BATTERY, NOTIFICATION, FULL_SCREEN, HOME_SERVICE, SNAP_BACK, TTS }

    enum class State { PASS, WARN, FAIL }

    /**
     * One row of the list. [detail] is the short status word next to the name; [fix] tells the
     * parent what to do and is empty when the check passes; [adb] holds the commands a computer
     * can run instead, empty when this device's form is better served by its settings pages.
     */
    data class Item(
        val key: Key,
        val name: String,
        val state: State,
        val detail: String,
        val fix: String,
        val adb: List<String> = emptyList(),
    )

    val ADB_OVERLAY = listOf("adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow")
    val ADB_EXACT_ALARM = listOf("adb shell pm grant $PKG android.permission.SCHEDULE_EXACT_ALARM")
    val ADB_BATTERY = listOf("adb shell dumpsys deviceidle whitelist +$PKG")
    val ADB_NOTIFICATION = listOf("adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS")
    val ADB_FULL_SCREEN = listOf("adb shell appops set $PKG USE_FULL_SCREEN_INTENT allow")
    val ADB_HOME_SERVICE = listOf(
        "adb shell settings put secure enabled_accessibility_services $PKG/dev.plume.HomeOverride",
        "adb shell settings put secure accessibility_enabled 1",
    )

    /**
     * The checks in the order both screens show them.
     *
     * [form] is a [DeviceForm] value; "unknown" reads as TV, because firmware that declares nothing
     * is a TV that hid it, and a TV's fix must not depend on a settings page that may not exist.
     * [tts] is Voice.describe() ("embedded" / "available" / "initializing" / "no-engine" /
     * "no-language"); [pack] is the built-in voice download ("", "ready", "failed", "unpacking",
     * "downloading:42"); [ttsWanted] is the read-aloud switch — with it off the voice is nobody's
     * problem and the row stays out. [snapOkCount] is the launcher's count of real take-backs, the
     * evidence that snap-back works rather than merely being switched on.
     */
    fun items(
        sdk: Int,
        form: String,
        overlay: Boolean,
        exactAlarm: Boolean,
        batteryFree: Boolean,
        notifications: Boolean,
        fullScreen: Boolean,
        a11yEnabled: Boolean,
        a11yBound: Boolean,
        snapBack: Boolean,
        snapOkCount: Int,
        tts: String,
        ttsWanted: Boolean,
        pack: String = "",
        popupBlocked: Boolean = false,
    ): List<Item> {
        val tv = form != DeviceForm.PHONE && form != DeviceForm.TABLET
        // TV: the adb commands are the fix, so every row carries them; phone: the settings page is.
        fun grant(key: Key, name: String, ok: Boolean, okDetail: String, badDetail: String, reason: String, path: String, adb: List<String>) =
            Item(
                key, name,
                if (ok) State.PASS else State.FAIL,
                if (ok) okDetail else badDetail,
                if (ok) "" else reason + " " + if (tv) {
                    "用电脑连电视执行下方命令即可（也可试试点本行打开电视的设置页，部分电视没有该页面）。"
                } else {
                    "在「$path」里允许本应用；点本行可直接打开。"
                },
                if (ok || !tv) emptyList() else adb,
            )
        val out = ArrayList<Item>(8)
        out += grant(
            Key.OVERLAY, "悬浮窗权限", overlay, "已允许", "未允许",
            if (popupBlocked) "已检测到定时弹题被系统拦截！没有它，看视频时到点弹不出答题页，切走后也回不来。"
            else "没有它，看视频时到点可能弹不出答题页，切走后也回不来。",
            "设置 → 应用 → 特殊应用权限 → 显示在其他应用上层", ADB_OVERLAY,
        )
        if (sdk >= 31) out += grant(
            Key.EXACT_ALARM, "闹钟和提醒", exactAlarm, "已允许", "未允许",
            "没有它，答题时间可能被系统推迟几分钟。",
            "设置 → 应用 → 特殊权限 → 闹钟和提醒", ADB_EXACT_ALARM,
        )
        out += grant(
            Key.BATTERY, "电池不受限制", batteryFree, "不限制", "受限制",
            "系统会在后台冻结本应用，到点不弹题。",
            "设置 → 应用 → 宝贝成长桌面 → 电池里选「不限制」", ADB_BATTERY,
        )
        if (sdk >= 33) out += grant(
            Key.NOTIFICATION, "通知权限", notifications, "已允许", "未允许",
            "弹题的全屏提醒靠通知兜底。",
            "设置 → 应用 → 宝贝成长桌面 → 通知", ADB_NOTIFICATION,
        )
        if (sdk >= 34) out += grant(
            Key.FULL_SCREEN, "全屏提醒", fullScreen, "已允许", "未允许",
            "到点直接弹出答题页靠它。",
            "设置 → 应用 → 宝贝成长桌面 → 全屏提醒", ADB_FULL_SCREEN,
        )
        out += when {
            a11yEnabled && a11yBound -> Item(Key.HOME_SERVICE, "主屏幕键服务", State.PASS, "正常工作", "")
            a11yEnabled -> Item(
                Key.HOME_SERVICE, "主屏幕键服务", State.WARN, "已开启但未生效",
                "服务已开启但系统没有绑定。在设备上打开一次「宝贝成长桌面」即可恢复；若反复出现请联系开发者。",
            )
            else -> {
                val fix = "不开启时，遥控器的一键影视键、厂商桌面抢屏都无法拦截。" + if (tv) {
                    "用电脑连电视执行下方两条命令即可（也可试试点本行打开电视的无障碍设置）。TCL/雷鸟设备代开后如未生效，在电视上打开一次本应用即可绑定。"
                } else {
                    "在「设置 → 无障碍」里开启「宝贝成长桌面（主屏幕键）」服务；点本行可直接打开。"
                }
                Item(Key.HOME_SERVICE, "主屏幕键服务", State.FAIL, "未开启", fix, if (tv) ADB_HOME_SERVICE else emptyList())
            }
        }
        out += when {
            !snapBack -> Item(
                Key.SNAP_BACK, "切走弹回", State.FAIL, "已关闭",
                "在管理控制台（手机浏览器打开电视地址）「设置 → 功能开关」里打开「切走后自动弹回」。",
            )
            !overlay -> Item(
                Key.SNAP_BACK, "切走弹回", State.FAIL, "缺少悬浮窗权限",
                "先补上面的「悬浮窗权限」，弹回才能生效。",
            )
            snapOkCount == 0 -> Item(
                Key.SNAP_BACK, "切走弹回", State.WARN, "已开启，还没拦截过",
                "下次答题时切到别的应用试一次，弹回成功后这里会记下次数。",
            )
            else -> Item(Key.SNAP_BACK, "切走弹回", State.PASS, "正常工作，已拦截 $snapOkCount 次", "")
        }
        // No settings page installs an engine on any form, so this row keeps its command everywhere.
        // The pack is different: it is downloaded and enabled from inside the app, which is the only
        // route that works on a TV with no settings page and no install permission (see docs/VOICE.md).
        if (ttsWanted) out += when (tts) {
            "available" -> Item(Key.TTS, "语音朗读引擎", State.PASS, "电视自带引擎", "")
            "embedded" -> Item(Key.TTS, "语音朗读引擎", State.PASS, "内置语音", "")
            "initializing" -> Item(
                Key.TTS, "语音朗读引擎", State.WARN, "加载中",
                "语音正在加载，稍等片刻再进来看一眼。",
            )
            else -> when {
                pack == "ready" -> Item(
                    Key.TTS, "语音朗读引擎", State.WARN, "内置语音未启用",
                    "内置语音已下载但没有启用。点本行启用，读题立刻用它。",
                )
                pack.startsWith("downloading:") -> Item(
                    Key.TTS, "语音朗读引擎", State.WARN, "正在下载 ${pack.removePrefix("downloading:")}%",
                    "内置语音正在下载，下完自动启用，不用留在这个页面。",
                )
                pack == "unpacking" -> Item(
                    Key.TTS, "语音朗读引擎", State.WARN, "正在安装",
                    "内置语音正在解压，马上就好。",
                )
                pack == "failed" -> Item(
                    Key.TTS, "语音朗读引擎", State.FAIL, "下载失败",
                    "上次下载没有完成。点本行重新下载内置语音（约 28 MB）；网络不好时会接着上次的进度继续。",
                )
                else -> Item(
                    Key.TTS, "语音朗读引擎", State.FAIL, if (tts == "no-language") "缺中文语音" else "未安装",
                    "读题需要中文语音。点本行下载内置语音（约 28 MB），不用安装任何软件，下完自动启用；" +
                        "也可以继续用电脑 adb 装一个语音引擎 APP。",
                )
            }
        }
        return out
    }

    /** How many rows are not PASS: the headline number both screens show. */
    fun openCount(items: List<Item>): Int = items.count { it.state != State.PASS }
}

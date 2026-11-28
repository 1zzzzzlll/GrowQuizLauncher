package dev.plume.quiz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppBlockRulesTest {

    private val self = "dev.plume.growquiz"

    @Test
    fun `selectable list includes preinstalled system apps and system games while excluding critical OS components`() {
        val candidates = listOf(
            // Third-party & updated system apps
            AppBlockRules.Candidate(pkg = "com.xiaodianshi.tv.yst", label = "云视听小电视", version = "1.8.8", isSystem = false),
            AppBlockRules.Candidate(pkg = "com.ktcp.video", label = "云视听极光", version = "16.1", isSystem = true, isUpdatedSystem = true),
            // Pre-installed system browser, system game bar (no plain LAUNCHER!), H5 game container, built-in VOD
            AppBlockRules.Candidate(pkg = "com.tcl.browser", label = "浏览器", version = "3.0", isSystem = true, isUpdatedSystem = false),
            AppBlockRules.Candidate(
                pkg = "com.tcl.gamebar",
                label = "游戏工具栏",
                version = "4.10",
                isSystem = true,
                isUpdatedSystem = false,
                isGame = true,
                hasLauncher = false,
                hasExportedActivity = true,
            ),
            AppBlockRules.Candidate(
                pkg = "com.tcl.walleve",
                label = "小T",
                version = "6.24",
                isSystem = true,
                isUpdatedSystem = true,
                isGame = AppBlockRules.looksLikeGame("com.tcl.walleve", "小T", listOf("com.tcl.walleve.tools.H5GameActivity")),
            ),
            AppBlockRules.Candidate(
                pkg = "com.tcl.vod",
                label = "企鹅影院",
                version = "8.9",
                isSystem = true,
                isUpdatedSystem = false,
                hasLauncher = false,
                hasExportedActivity = true,
            ),
            // Critical system / hardware / role components that MUST be excluded
            AppBlockRules.Candidate(pkg = "com.tcl.tv", label = "电视输入", isSystem = true, isUpdatedSystem = false),
            AppBlockRules.Candidate(pkg = self, label = "宝贝成长桌面", isSystem = false),
            AppBlockRules.Candidate(pkg = "com.tcl.cyberui", label = "TCL桌面", isSystem = true, isHome = true),
            AppBlockRules.Candidate(pkg = "com.tcl.settings", label = "系统设置", isSystem = true, isSettings = true),
            AppBlockRules.Candidate(pkg = "org.k2fsa.sherpa.onnx.tts.engine", label = "语音引擎", isSystem = false, isTtsEngine = true),
            AppBlockRules.Candidate(pkg = "com.sohu.inputmethod.sogou.tv", label = "搜狗输入法", isSystem = true, isInputMethod = true),
            AppBlockRules.Candidate(pkg = "com.google.android.webview", label = "WebView", isSystem = true, isUpdatedSystem = true),
            AppBlockRules.Candidate(pkg = "com.android.bluetooth", label = "蓝牙", isSystem = true),
            AppBlockRules.Candidate(pkg = "com.android.providers.tv", label = "TV存储", isSystem = true, isPersistent = true),
            AppBlockRules.Candidate(pkg = "com.tcl.autopair", label = "遥控器配对", isSystem = true, isPersistent = true),
            AppBlockRules.Candidate(pkg = "com.tcl.initsetup", label = "开机引导", isSystem = true),
            AppBlockRules.Candidate(pkg = "com.tcl.factory.view", label = "工厂菜单", isSystem = true),
            AppBlockRules.Candidate(pkg = "com.tcl.versionUpdateApp", label = "系统升级", isSystem = true, isUpdatedSystem = true),
            AppBlockRules.Candidate(pkg = "com.tcl.ttvs", label = "com.tcl.ttvs", isSystem = true, hasLauncher = false, hasExportedActivity = true),
            AppBlockRules.Candidate(pkg = "com.helper.codec", label = "解码插件", isSystem = false, hasLauncher = false, hasExportedActivity = false),
            AppBlockRules.Candidate(pkg = "hdmi:hw1", label = "HDMI 1", isSystem = false),
        )

        val listed = AppBlockRules.selectApps(
            candidates = candidates,
            blocked = setOf("com.xiaodianshi.tv.yst", "com.tcl.gamebar"),
            selfPkg = self,
        )

        val byPkg = listed.associateBy { it.pkg }
        assertEquals(
            setOf(
                "com.xiaodianshi.tv.yst",
                "com.ktcp.video",
                "com.tcl.browser",
                "com.tcl.gamebar",
                "com.tcl.walleve",
                "com.tcl.vod",
            ),
            byPkg.keys,
        )
        assertTrue(byPkg.getValue("com.xiaodianshi.tv.yst").blocked)
        assertTrue(byPkg.getValue("com.tcl.gamebar").blocked)
        assertTrue(byPkg.getValue("com.tcl.gamebar").isGame)
        assertTrue(byPkg.getValue("com.tcl.gamebar").isSystem)
        assertTrue(byPkg.getValue("com.tcl.walleve").isGame)
        assertFalse(byPkg.getValue("com.ktcp.video").blocked)
    }

    @Test
    fun `looksLikeGame recognises package names, Chinese game titles, and embedded H5GameActivity classes`() {
        assertTrue(AppBlockRules.looksLikeGame("com.tcl.gamebar", "工具栏"))
        assertTrue(AppBlockRules.looksLikeGame("com.vendor.cloud", "咪咕快游"))
        assertTrue(AppBlockRules.looksLikeGame("com.vendor.portal", "小T", listOf("com.tcl.walleve.tools.H5GameActivity")))
        assertFalse(AppBlockRules.looksLikeGame("com.android.inputdevices", "手柄驱动"))
        assertFalse(AppBlockRules.looksLikeGame("com.tcl.common.weather", "天气"))
    }

    @Test
    fun `allowlisted user browsers under com_android remain selectable while AOSP plumbing is protected`() {
        val chrome = AppBlockRules.Candidate(pkg = "com.android.chrome", label = "Chrome", isSystem = true)
        val htmlViewer = AppBlockRules.Candidate(pkg = "com.android.htmlviewer", label = "HTML查看器", isSystem = true)
        assertTrue(AppBlockRules.isSelectableApp(chrome, self))
        assertFalse(AppBlockRules.isSelectableApp(htmlViewer, self))
    }

    @Test
    fun `selectApps deduplicates packages and keeps alphabetical order stable across state toggles`() {
        val candidates = listOf(
            AppBlockRules.Candidate(pkg = "com.b.game", label = "植物大战僵尸", isSystem = false),
            AppBlockRules.Candidate(pkg = "com.a.video", label = "", isSystem = false),
            AppBlockRules.Candidate(pkg = "com.a.video", label = "爱奇艺TV", isSystem = false),
        )

        val before = AppBlockRules.selectApps(candidates, emptySet(), self)
        val after = AppBlockRules.selectApps(candidates, setOf("com.b.game"), self)

        assertEquals(listOf("com.a.video", "com.b.game"), before.map { it.pkg })
        assertEquals(listOf("com.a.video", "com.b.game"), after.map { it.pkg })
        assertEquals("爱奇艺TV", before.first().label)
        assertTrue(after.last().blocked)
        assertTrue(after.last().isGame)
    }

    @Test
    fun `sanitizeBlockedTargets strips self, core system packages, tv inputs, hdmi and dynamic protected sets`() {
        val cleaned = AppBlockRules.sanitizeBlockedTargets(
            requested = listOf(
                " com.xiaodianshi.tv.yst ",
                "com.tcl.gamebar",
                self,
                "com.android.systemui",
                "com.android.settings",
                "com.tcl.tv",
                "com.tcl.cyberui",
                "hdmi:1",
                "",
            ),
            selfPkg = self,
            protectedPkgs = setOf("com.tcl.cyberui", "com.sohu.inputmethod.sogou.tv"),
        )
        assertEquals(setOf("com.xiaodianshi.tv.yst", "com.tcl.gamebar"), cleaned)
    }

    @Test
    fun `shouldIntercept matches blocked controllable packages and never intercepts self or core OS`() {
        val blocked = setOf("com.xiaodianshi.tv.yst", "com.tcl.walleve", self, "com.android.settings", "com.tcl.tv")
        assertTrue(AppBlockRules.shouldIntercept("com.xiaodianshi.tv.yst", self, blocked))
        assertTrue(AppBlockRules.shouldIntercept("com.tcl.walleve", self, blocked))
        assertFalse(AppBlockRules.shouldIntercept("com.ktcp.video", self, blocked))
        assertFalse(AppBlockRules.shouldIntercept(self, self, blocked))
        assertFalse(AppBlockRules.shouldIntercept("com.android.settings", self, blocked))
        assertFalse(AppBlockRules.shouldIntercept("com.tcl.tv", self, blocked))
        assertFalse(AppBlockRules.shouldIntercept(null, self, blocked))
    }

    @Test
    fun `foreground tracking ignores transient volume or permission overlays`() {
        assertTrue(AppBlockRules.tracksAsForegroundApp("com.xiaodianshi.tv.yst", self))
        assertFalse(AppBlockRules.tracksAsForegroundApp("com.android.systemui", self))
        assertFalse(AppBlockRules.tracksAsForegroundApp("android", self))
        assertFalse(AppBlockRules.tracksAsForegroundApp(self, self))
    }

    @Test
    fun `shouldRelaunchAlert debounces in-flight start but catches splash-to-main cover immediately`() {
        assertFalse(
            AppBlockRules.shouldRelaunchAlert(
                alertForeground = true,
                alertInFlight = false,
                activeAlertPkg = "com.ktcp.video",
                targetPkg = "com.ktcp.video",
                lastLaunchElapsedMs = 1_000L,
                nowElapsedMs = 1_200L,
            ),
        )
        assertFalse(
            AppBlockRules.shouldRelaunchAlert(
                alertForeground = false,
                alertInFlight = true,
                activeAlertPkg = "com.ktcp.video",
                targetPkg = "com.ktcp.video",
                lastLaunchElapsedMs = 1_000L,
                nowElapsedMs = 1_200L,
            ),
        )
        assertTrue(
            AppBlockRules.shouldRelaunchAlert(
                alertForeground = false,
                alertInFlight = false,
                activeAlertPkg = "com.ktcp.video",
                targetPkg = "com.ktcp.video",
                lastLaunchElapsedMs = 1_000L,
                nowElapsedMs = 1_250L,
            ),
        )
        assertTrue(
            AppBlockRules.shouldRelaunchAlert(
                alertForeground = true,
                alertInFlight = false,
                activeAlertPkg = "com.ktcp.video",
                targetPkg = "com.xiaodianshi.tv.yst",
                lastLaunchElapsedMs = 1_000L,
                nowElapsedMs = 1_100L,
            ),
        )
    }

    @Test
    fun `dialog and speech messages adapt to Chinese, English and raw package names`() {
        assertEquals(
            "「云视听小电视」已被家长禁用，已自动关闭该软件。",
            AppBlockRules.dialogMessage("云视听小电视"),
        )
        assertEquals(
            "当前软件已被家长禁用，已自动关闭。",
            AppBlockRules.dialogMessage("com.xiaodianshi.tv.yst"),
        )
        assertEquals(
            "提醒你，云视听小电视已被家长禁用，已自动关闭，请点击确定关闭提醒。",
            AppBlockRules.speechMessage("云视听小电视", bilingual = false),
        )
        assertEquals(
            "提醒你，Kodi已被家长禁用，已自动关闭，请点击确定关闭提醒。",
            AppBlockRules.speechMessage("Kodi", bilingual = true),
        )
        assertEquals(
            "提醒你，该软件已被家长禁用，已自动关闭，请点击确定关闭提醒。",
            AppBlockRules.speechMessage("Kodi", bilingual = false),
        )
    }
}

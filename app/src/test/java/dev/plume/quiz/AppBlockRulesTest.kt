package dev.plume.quiz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppBlockRulesTest {

    private val self = "dev.plume.growquiz"

    @Test
    fun `selectable list keeps deployed non-system apps and excludes system or essential components`() {
        val candidates = listOf(
            AppBlockRules.Candidate(pkg = "com.xiaodianshi.tv.yst", label = "云视听小电视", version = "1.6.3", isSystem = false),
            AppBlockRules.Candidate(pkg = "com.ktcp.video", label = "云视听极光", version = "16.1", isSystem = true, isUpdatedSystem = true),
            AppBlockRules.Candidate(pkg = "com.tcl.tv", label = "电视输入", isSystem = true, isUpdatedSystem = false),
            AppBlockRules.Candidate(pkg = self, label = "宝贝成长桌面", isSystem = false),
            AppBlockRules.Candidate(pkg = "com.tcl.cyberui", label = "TCL桌面", isSystem = false, isHome = true),
            AppBlockRules.Candidate(pkg = "com.tcl.settings", label = "系统设置", isSystem = false, isSettings = true),
            AppBlockRules.Candidate(pkg = "org.k2fsa.sherpa.onnx.tts.engine", label = "语音引擎", isSystem = false, isTtsEngine = true),
            AppBlockRules.Candidate(pkg = "com.google.android.webview", label = "WebView", isSystem = true, isUpdatedSystem = true),
            AppBlockRules.Candidate(pkg = "com.helper.codec", label = "解码插件", isSystem = false, hasLauncher = false),
            AppBlockRules.Candidate(pkg = "hdmi:hw1", label = "HDMI 1", isSystem = false),
        )

        val listed = AppBlockRules.selectApps(
            candidates = candidates,
            blocked = setOf("com.xiaodianshi.tv.yst"),
            selfPkg = self,
        )

        assertEquals(2, listed.size)
        val byPkg = listed.associateBy { it.pkg }
        assertTrue(byPkg.getValue("com.xiaodianshi.tv.yst").blocked)
        assertFalse(byPkg.getValue("com.ktcp.video").blocked)
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
    }

    @Test
    fun `sanitizeBlockedTargets strips self, core system packages, hdmi and dynamic protected sets`() {
        val cleaned = AppBlockRules.sanitizeBlockedTargets(
            requested = listOf(
                " com.xiaodianshi.tv.yst ",
                self,
                "com.android.systemui",
                "com.android.settings",
                "com.tcl.cyberui",
                "hdmi:1",
                "",
            ),
            selfPkg = self,
            protectedPkgs = setOf("com.tcl.cyberui"),
        )
        assertEquals(setOf("com.xiaodianshi.tv.yst"), cleaned)
    }

    @Test
    fun `shouldIntercept matches only blocked third-party packages`() {
        val blocked = setOf("com.xiaodianshi.tv.yst", self, "com.android.settings")
        assertTrue(AppBlockRules.shouldIntercept("com.xiaodianshi.tv.yst", self, blocked))
        assertFalse(AppBlockRules.shouldIntercept("com.ktcp.video", self, blocked))
        assertFalse(AppBlockRules.shouldIntercept(self, self, blocked))
        assertFalse(AppBlockRules.shouldIntercept("com.android.settings", self, blocked))
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
        // Already visibly showing the reminder for this exact package -> do not restart speech.
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
        // Initial launch still in flight within 350 ms -> wait for it to land.
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
        // Reminder had resumed (alertInFlight = false), then a second activity of the blocked app covered it 250 ms later -> relaunch immediately!
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
        // Different blocked package opened while showing the first -> update immediately.
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
        // Pure English name: spoken by name on bilingual cloud TTS, generic noun on Chinese-only offline TTS.
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

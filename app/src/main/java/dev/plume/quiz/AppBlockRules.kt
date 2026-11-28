package dev.plume.quiz

/**
 * Pure-JVM rules for controllable software discovery, critical-system protection, runtime
 * interception, and reminder speech.
 *
 * Kept free of Android framework classes so every filtering, protection, game-detection, debounce,
 * and voice-text edge case is covered directly by unit tests.
 */
object AppBlockRules {

    data class Candidate(
        val pkg: String,
        val label: String,
        val version: String = "",
        val isSystem: Boolean,
        val isUpdatedSystem: Boolean = false,
        val isPersistent: Boolean = false,
        val isGame: Boolean = false,
        val hasLauncher: Boolean = true,
        val hasExportedActivity: Boolean = hasLauncher,
        val isHome: Boolean = false,
        val isSettings: Boolean = false,
        val isTtsEngine: Boolean = false,
        val isInputMethod: Boolean = false,
    )

    data class ListedApp(
        val pkg: String,
        val label: String,
        val version: String,
        val blocked: Boolean,
        val isSystem: Boolean = false,
        val isGame: Boolean = false,
    )

    /**
     * Core OS, installer, permission, connectivity, and runtime packages that must never be offered
     * for blocking or intercepted, even if updated via an app store (`FLAG_UPDATED_SYSTEM_APP`).
     */
    val CORE_PROTECTED_PKGS = setOf(
        "android",
        "com.tvos",
        "com.android.systemui",
        "com.android.settings",
        "com.android.tv.settings",
        "com.android.settings.intelligence",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.android.webview",
        "com.android.webview",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.incallui",
        "com.android.emergency",
        "com.android.shell",
        "com.android.bluetooth",
        "com.android.nfc",
    )

    /**
     * User-facing content apps under `com.android.*` that are NOT OS plumbing and may be disabled
     * by parents (browsers, app stores).
     */
    private val ALLOWLISTED_ANDROID_USER_PKGS = setOf(
        "com.android.chrome",
        "com.android.browser",
        "com.android.vending",
    )

    private val GOOGLE_SYSTEM_PREFIXES = listOf(
        "com.google.android.ext.",
        "com.google.android.networkstack",
        "com.google.android.captiveportallogin",
        "com.google.android.modulemetadata",
        "com.google.android.providers.",
        "com.google.android.tungsten.",
        "com.google.android.tvrecommendations",
        "com.google.android.backdrop",
        "com.google.android.marvin.",
        "com.google.android.partnersetup",
        "com.google.android.onetimeinitializer",
        "com.google.android.configupdater",
        "com.google.android.syncadapters.",
        "com.google.android.tts",
        "com.google.android.gms.",
    )

    private val SOC_HARDWARE_PREFIXES = listOf(
        "com.mediatek.",
        "com.qualcomm.",
        "com.qti.",
        "org.codeaurora.",
        "com.hisilicon.",
        "com.amlogic.",
        "com.realtek.",
        "com.mstar.",
        "com.dolby.",
        "com.dts.",
    )

    /**
     * Vendor firmware, hardware pairing, factory calibration, OOBE setup, and OTA daemon tokens.
     * Matched only against pre-installed system packages (`isSystem == true`) that are not games,
     * keeping Bluetooth remotes (`bt_rcu`, `autopair`), TV input switches, and OTA updaters safe.
     */
    private val VENDOR_CRITICAL_TOKENS = listOf(
        "initsetup",
        "setupwizard",
        "oobewizard",
        "factory",
        "workmode",
        "systemui",
        "systemserver",
        "bt_rcu",
        "autopair",
        "rcuservice",
        "micmanager",
        "speakerservice",
        "versionupdate",
        "updateperipheral",
        "systemupdate",
        ".upgrade",
        "otaupdate",
        "useragreement",
        "logkit",
        "bootad",
        "shortcutmenu",
        "startandroidservice",
        "recognize.service",
        "inputpreference",
        "keychain",
        "certinstaller",
        "packageinstaller",
        "permissioncontroller",
    )

    private val GAME_PKG_TOKENS = listOf(
        "game",
        "xiaoyouxi",
        "migugame",
        "startcloud",
        "arcade",
        "mahjong",
        "poker",
        "chess",
        "retroarch",
        "ppsspp",
    )

    private val GAME_ACTIVITY_TOKENS = listOf(
        "gameactivity",
        "h5game",
        "minigame",
        "cloudgame",
        "gamecenter",
        "gamebar",
    )

    private val GAME_LABEL_WORDS = listOf(
        "游戏",
        "云游",
        "快游",
        "电玩",
        "棋牌",
        "斗地主",
        "麻将",
        "街机",
    )

    /**
     * System overlays (volume bar, permission dialog) whose brief window events must not overwrite
     * the underlying app tracked as currently running in front.
     */
    val TRANSIENT_OVERLAY_PKGS = setOf(
        "android",
        "com.android.systemui",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
    )

    private const val HDMI_PREFIX = "hdmi:"
    private const val MAX_SPEECH_NAME_LEN = 20

    /**
     * Heuristic game detector supplementing `ApplicationInfo.CATEGORY_GAME` and `FLAG_IS_GAME`.
     *
     * Catches pre-installed TV game centres (`com.tcl.gamebar`), cloud gaming apps, and vendor
     * voice-assistant / portal containers that embed H5 game activities (such as
     * `com.tcl.walleve/.tools.H5GameActivity`).
     */
    fun looksLikeGame(
        pkg: String,
        label: String = "",
        activityNames: Iterable<String> = emptyList(),
    ): Boolean {
        val lowerPkg = pkg.trim().lowercase()
        if (lowerPkg.isNotEmpty() && !lowerPkg.contains("gamepad") && !lowerPkg.contains("inputdevice")) {
            if (GAME_PKG_TOKENS.any { lowerPkg.contains(it) }) return true
        }
        val cleanLabel = label.trim()
        if (cleanLabel.isNotEmpty() && GAME_LABEL_WORDS.any { cleanLabel.contains(it) }) {
            return true
        }
        for (act in activityNames) {
            val lowerAct = act.lowercase()
            if (GAME_ACTIVITY_TOKENS.any { lowerAct.contains(it) }) return true
        }
        return false
    }

    /**
     * Whether [pkg] is an essential OS / hardware / firmware component that must never be offered
     * for blocking, so disabling apps in the console can never break boot, remotes, networking, or
     * system dialogs.
     */
    fun isCriticalSystemPackage(
        pkg: String,
        isSystem: Boolean,
        isPersistent: Boolean = false,
        isGame: Boolean = false,
    ): Boolean {
        val clean = pkg.trim()
        if (clean.isEmpty()) return true
        if (clean in CORE_PROTECTED_PKGS) return true
        if (clean in dev.plume.HomeWatch.INPUT_PLAYERS) return true
        if (!isSystem) return false
        if (isPersistent) return true

        val lower = clean.lowercase()
        if (lower == "android" || lower.startsWith("android.") || lower.startsWith("com.tvos.")) return true
        if (lower.startsWith("com.android.") && clean !in ALLOWLISTED_ANDROID_USER_PKGS) return true
        if (GOOGLE_SYSTEM_PREFIXES.any { lower.startsWith(it) }) return true
        if (SOC_HARDWARE_PREFIXES.any { lower.startsWith(it) }) return true
        if (lower.contains(".overlay") || lower.endsWith("overlay") ||
            lower.contains(".providers.") || lower.contains(".cts.")
        ) {
            return true
        }
        if (!isGame && VENDOR_CRITICAL_TOKENS.any { lower.contains(it) }) return true
        return false
    }

    /**
     * Whether [c] should be listed in the console so parents can disable it.
     *
     * Lists all third-party apps as well as pre-installed system apps and system games (whether or
     * not they have received a user-space update or declare a standard `MAIN+LAUNCHER` icon), while
     * excluding critical OS components, Home launchers, Settings, IME keyboards, TTS engines, and
     * headless background plugins.
     */
    fun isSelectableApp(c: Candidate, selfPkg: String): Boolean {
        val pkg = c.pkg.trim()
        if (pkg.isEmpty() || pkg == selfPkg || pkg.startsWith(HDMI_PREFIX)) return false
        if (c.isHome || c.isSettings || c.isTtsEngine || c.isInputMethod) return false
        val game = c.isGame || looksLikeGame(pkg, c.label)
        if (isCriticalSystemPackage(pkg, isSystem = c.isSystem, isPersistent = c.isPersistent, isGame = game)) {
            return false
        }
        if (c.hasLauncher || game) return true
        if (!c.hasExportedActivity) return false
        return !c.isSystem || cleanDisplayName(c.label).isNotEmpty()
    }

    /** Kept as an alias for callers/tests referencing the earlier name. */
    fun isSelectableNonSystemApp(c: Candidate, selfPkg: String): Boolean = isSelectableApp(c, selfPkg)

    /**
     * Filters, deduplicates, and sorts [candidates] into the list shown in the web admin console.
     * Sorted deterministically by display name so toggling an app's state in the console never
     * makes the row jump away from under the parent's finger.
     */
    fun selectApps(
        candidates: List<Candidate>,
        blocked: Set<String>,
        selfPkg: String,
    ): List<ListedApp> {
        val byPkg = LinkedHashMap<String, Candidate>()
        for (c in candidates) {
            val pkg = c.pkg.trim()
            if (pkg.isEmpty()) continue
            val existing = byPkg[pkg]
            if (existing == null || (existing.label.isBlank() && c.label.isNotBlank())) {
                byPkg[pkg] = c.copy(pkg = pkg)
            } else if (c.isGame && !existing.isGame) {
                byPkg[pkg] = existing.copy(isGame = true)
            }
        }
        val collator = java.text.Collator.getInstance(java.util.Locale.SIMPLIFIED_CHINESE)
        return byPkg.values
            .filter { c ->
                val game = c.isGame || looksLikeGame(c.pkg, c.label)
                isSelectableApp(c, selfPkg) ||
                    (c.pkg in blocked && c.pkg != selfPkg &&
                        !isCriticalSystemPackage(c.pkg, c.isSystem, c.isPersistent, game) &&
                        !c.isHome && !c.isSettings && !c.isTtsEngine && !c.isInputMethod)
            }
            .map { c ->
                val game = c.isGame || looksLikeGame(c.pkg, c.label)
                ListedApp(
                    pkg = c.pkg,
                    label = c.label.trim().ifEmpty { c.pkg },
                    version = c.version.trim(),
                    blocked = c.pkg in blocked,
                    isSystem = c.isSystem,
                    isGame = game,
                )
            }
            .sortedWith { a, b ->
                val cmp = collator.compare(a.label, b.label)
                if (cmp != 0) cmp else a.pkg.compareTo(b.pkg)
            }
    }

    /** Filters out protected or invalid package names before saving to storage. */
    fun sanitizeBlockedTargets(
        requested: Iterable<String>,
        selfPkg: String,
        protectedPkgs: Set<String> = emptySet(),
    ): Set<String> = requested
        .map { it.trim() }
        .filter { pkg ->
            pkg.isNotEmpty() &&
                pkg != selfPkg &&
                !pkg.startsWith(HDMI_PREFIX) &&
                pkg !in CORE_PROTECTED_PKGS &&
                pkg !in dev.plume.HomeWatch.INPUT_PLAYERS &&
                pkg !in protectedPkgs
        }
        .toSet()

    /** Whether a window/launch of [pkg] must be stopped and replaced by the blocked-app reminder. */
    fun shouldIntercept(pkg: String?, selfPkg: String, blocked: Set<String>): Boolean {
        val target = pkg?.trim().orEmpty()
        if (target.isEmpty() || target == selfPkg || target.startsWith(HDMI_PREFIX)) return false
        if (target in CORE_PROTECTED_PKGS || target in dev.plume.HomeWatch.INPUT_PLAYERS) return false
        return target in blocked
    }

    /** Whether [pkg] represents a real foreground app rather than a transient system overlay. */
    fun tracksAsForegroundApp(pkg: String?, selfPkg: String): Boolean {
        val target = pkg?.trim().orEmpty()
        return target.isNotEmpty() && target != selfPkg && target !in TRANSIENT_OVERLAY_PKGS
    }

    /**
     * Prevents duplicate window events while the initial reminder intent is still on its way to
     * screen, without ignoring a splash-to-main second activity that covers an already-shown
     * reminder a few hundred milliseconds later.
     */
    fun shouldRelaunchAlert(
        alertForeground: Boolean,
        alertInFlight: Boolean,
        activeAlertPkg: String?,
        targetPkg: String,
        lastLaunchElapsedMs: Long,
        nowElapsedMs: Long,
        inFlightWindowMs: Long = 350L,
    ): Boolean {
        if (alertForeground && activeAlertPkg == targetPkg) return false
        if (alertInFlight && activeAlertPkg == targetPkg && lastLaunchElapsedMs > 0L &&
            nowElapsedMs - lastLaunchElapsedMs in 0 until inFlightWindowMs
        ) {
            return false
        }
        return true
    }

    fun dialogTitle(): String = "该软件已被禁用"

    fun dialogMessage(appName: String?): String {
        val clean = cleanDisplayName(appName)
        return if (clean.isNotEmpty()) {
            "「$clean」已被家长禁用，已自动关闭该软件。"
        } else {
            "当前软件已被家长禁用，已自动关闭。"
        }
    }

    /**
     * Builds the voice prompt read aloud when a disabled app is caught.
     *
     * Offline Chinese-only TTS models (`zh-aishell3`) skip pure Latin tokens when `bilingual` is
     * false, so pure ASCII names fall back to "该软件" offline while reading the real name on
     * bilingual cloud TTS or whenever the label contains Chinese characters.
     */
    fun speechMessage(appName: String?, bilingual: Boolean): String {
        val clean = cleanSpeechName(appName)
        val useName = clean.isNotEmpty() && (containsChinese(clean) || bilingual)
        return if (useName) {
            "提醒你，${clean}已被家长禁用，已自动关闭，请点击确定关闭提醒。"
        } else {
            "提醒你，该软件已被家长禁用，已自动关闭，请点击确定关闭提醒。"
        }
    }

    internal fun cleanDisplayName(raw: String?): String {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || looksLikePackageName(s)) return ""
        return s.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
    }

    internal fun cleanSpeechName(raw: String?): String {
        val base = cleanDisplayName(raw)
        if (base.isEmpty()) return ""
        val cleaned = base
            .replace(Regex("[「」『』【】\\[\\]()（）<>《》·•|/_\\\\]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (cleaned.length > MAX_SPEECH_NAME_LEN) cleaned.take(MAX_SPEECH_NAME_LEN).trim() else cleaned
    }

    internal fun containsChinese(text: String): Boolean =
        text.any { ch -> Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN }

    private fun looksLikePackageName(text: String): Boolean =
        text.contains('.') && text.none { it.isWhitespace() || Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
}

package dev.plume.quiz

/**
 * Pure-JVM rules for non-system software discovery, runtime interception, and reminder speech.
 *
 * Kept free of Android framework classes so every filtering, protection, debounce, and voice-text
 * edge case is covered directly by unit tests.
 */
object AppBlockRules {

    data class Candidate(
        val pkg: String,
        val label: String,
        val version: String = "",
        val isSystem: Boolean,
        val isUpdatedSystem: Boolean = false,
        val hasLauncher: Boolean = true,
        val isHome: Boolean = false,
        val isSettings: Boolean = false,
        val isTtsEngine: Boolean = false,
    )

    data class ListedApp(
        val pkg: String,
        val label: String,
        val version: String,
        val blocked: Boolean,
    )

    /**
     * Platform and installer packages that must never be offered for blocking or intercepted,
     * even if updated via an app store (`FLAG_UPDATED_SYSTEM_APP`).
     */
    val CORE_PROTECTED_PKGS = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.tv.settings",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.vending",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.android.webview",
        "com.android.webview",
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
     * Whether [c] is a deployed non-system app that parents can choose to disable in the console.
     *
     * Matches the launcher's own non-system definition (user-installed apps, plus pre-installed
     * apps that carry a user-space update), while excluding our own package, system Settings,
     * Home launchers, TTS speech engines, and non-launchable helper packages.
     */
    fun isSelectableNonSystemApp(c: Candidate, selfPkg: String): Boolean {
        val pkg = c.pkg.trim()
        if (pkg.isEmpty() || pkg == selfPkg || pkg.startsWith(HDMI_PREFIX)) return false
        if (pkg in CORE_PROTECTED_PKGS) return false
        if (c.isHome || c.isSettings || c.isTtsEngine) return false
        if (!c.hasLauncher) return false
        return !c.isSystem || c.isUpdatedSystem
    }

    /**
     * Filters, deduplicates, and sorts [candidates] into the list shown in the web admin console.
     * Sorted deterministically by display name (case-insensitive) so toggling an app's state in the
     * console never makes the row jump away from under the parent's finger.
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
            }
        }
        val collator = java.text.Collator.getInstance(java.util.Locale.SIMPLIFIED_CHINESE)
        return byPkg.values
            .filter { c ->
                isSelectableNonSystemApp(c, selfPkg) ||
                    (c.pkg in blocked && c.pkg != selfPkg && c.pkg !in CORE_PROTECTED_PKGS &&
                        !c.isHome && !c.isSettings && !c.isTtsEngine)
            }
            .map { c ->
                ListedApp(
                    pkg = c.pkg,
                    label = c.label.trim().ifEmpty { c.pkg },
                    version = c.version.trim(),
                    blocked = c.pkg in blocked,
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
                pkg !in protectedPkgs
        }
        .toSet()

    /** Whether a window/launch of [pkg] must be stopped and replaced by the blocked-app reminder. */
    fun shouldIntercept(pkg: String?, selfPkg: String, blocked: Set<String>): Boolean {
        val target = pkg?.trim().orEmpty()
        if (target.isEmpty() || target == selfPkg || target.startsWith(HDMI_PREFIX)) return false
        if (target in CORE_PROTECTED_PKGS) return false
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

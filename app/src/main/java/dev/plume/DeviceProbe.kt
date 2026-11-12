package dev.plume

import android.app.AlarmManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.speech.tts.TextToSpeech
import org.json.JSONObject

/**
 * A snapshot of everything a compatibility report needs to reproduce a problem on a TV we do not
 * own: the build fingerprint, the Android level, which of our guards the system actually granted
 * (accessibility bind, overlay, exact alarms, secure-settings), the speech engines on board and
 * the screen it draws on. Collected fresh for every report — a vendor update can change any of it.
 */
object DeviceProbe {

    fun collect(context: Context): JSONObject {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, 0)
        val dm = context.resources.displayMetrics
        val quiz = dev.plume.quiz.QuizPrefs(context)
        return JSONObject()
            .put("vendor", Vendor.current)
            .put("form", deviceForm(context))
            .put("brand", Build.BRAND)
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("device", Build.DEVICE)
            .put("product", Build.PRODUCT)
            .put("hardware", Build.HARDWARE)
            .put("fingerprint", Build.FINGERPRINT)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("release", Build.VERSION.RELEASE)
            .put("abis", Build.SUPPORTED_ABIS.joinToString(","))
            .put("appVersion", info.versionName)
            .put("appVersionCode", info.longVersionCode)
            .put("screen", "${dm.widthPixels}x${dm.heightPixels}")
            .put("densityDpi", dm.densityDpi)
            .put("ttsEngines", ttsEngines(context))
            .put("ttsEngineList", ttsEngineList(context))
            .put("ttsDefault", Settings.Secure.getString(context.contentResolver, "tts_default_synth") ?: "")
            .put("a11yEnabled", a11yEnabled(context))
            .put("a11yBound", HomeOverride.running != null)
            .put("overlay", Settings.canDrawOverlays(context))
            .put("exactAlarm", exactAlarm(context))
            .put("secureSettings", secureSettings(context))
            .put("deviceOwner", deviceOwner(context))
            .put("defaultHome", defaultHome(context))
            .put("homeCandidates", homeCandidates(context).joinToString(","))
            .put("homeStubs", homeStubs(context).joinToString(","))
            // GrowQuiz: which package is this set's Settings app, if any. Three TV firmwares so
            // far open no Settings page at all (settings_open_fail); this field is the evidence
            // a vendor-specific fallback would need before it may exist (see [SettingsApps]).
            .put("settingsApps", settingsApps(context).joinToString(","))
            // GrowQuiz: one apk runs on TVs, phones and tablets. These four fields say which, so a
            // report from a phone can be read as a phone's report (see [DeviceForm]).
            .put("leanback", pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK))
            .put("television", pm.hasSystemFeature("android.hardware.type.television"))
            .put("touchscreen", pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN))
            // GrowQuiz: the third television answer, kept as evidence on its own. Some set firmwares
            // declare neither feature flag and still run in the television UI mode (TCL ak30a5).
            .put("uiModeTv", uiModeTv(context))
            // GrowQuiz: the quiz schedule as the app itself sees it, so the server can compare "when
            // the alarm should have fired" with the events that say when it did. A timezone comes
            // along: a wrong clock is its own class of "the alarm never fired" reports.
            .put("quizEnabled", quiz.enabled)
            .put("quizNextAt", quiz.nextTriggerAt)
            .put("quizIntervalMin", quiz.intervalMinutes)
            .put("timezone", java.util.TimeZone.getDefault().id)
            .put("webAdminPort", dev.plume.web.WebAdminServer.port)
    }

    /** Speech engines that answer the standard TTS intent, comma separated ("" = none on board). */
    private fun ttsEngines(context: Context): String {
        val intent = Intent(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA)
        return context.packageManager.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }.distinct().sorted().joinToString(",")
    }

    /**
     * The engines the speech API itself lists ("" = none). This is the list [TextToSpeech] starts an
     * engine from, so it is the one that decides whether a failed init means "no engine" or "broken
     * engine"; [ttsEngines] is the older check and answers differently on some sets.
     */
    private fun ttsEngineList(context: Context): String =
        dev.plume.quiz.TtsProbe.engineNames(context).joinToString(",")

    /** Whether our accessibility service sits in the system's enabled list (bound or not). */
    fun a11yEnabled(context: Context): Boolean {
        val on = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val me = ComponentName(context, HomeOverride::class.java)
        return on.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun exactAlarm(context: Context): Boolean {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return false
        return Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()
    }

    private fun secureSettings(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    private fun deviceOwner(context: Context): Boolean =
        context.getSystemService(DevicePolicyManager::class.java)?.isDeviceOwnerApp(context.packageName) == true

    private fun defaultHome(context: Context): String {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName ?: ""
    }

    /** The form factor string the report, the console and the env check all share (see [DeviceForm]). */
    fun form(context: Context): String = deviceForm(context)

    private fun deviceForm(context: Context): String {
        val pm = context.packageManager
        return DeviceForm.of(
            leanback = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK),
            television = pm.hasSystemFeature("android.hardware.type.television"),
            touchscreen = pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN),
            smallestWidthDp = context.resources.configuration.smallestScreenWidthDp,
            uiModeTv = uiModeTv(context),
        )
    }

    /** Whether the running configuration is the television UI mode, which a feature list may not say. */
    private fun uiModeTv(context: Context): Boolean =
        (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION

    /**
     * Every package that answers Home and could be a launcher, except ours, comma separated.
     * [defaultHome] answers "android" when no launcher holds the role (the system's own resolver would
     * show a chooser), and on those sets — Hisense VIDAA, for one — this list is the only way to learn
     * which launcher the firmware starts by component, which is what a cover for that brand needs.
     *
     * The platform's boot stub is not a launcher and is left out (see [HomeWatch.isPlatformStubHome]);
     * it answers Home on most builds, so without this every device reported the same extra package and
     * the list stopped telling a real launcher from a stub. [homeStubs] keeps what was left out.
     */
    private fun homeCandidates(context: Context): List<String> = homeAnswers(context)
        .filterNot { HomeWatch.isPlatformStubHome(it.activityInfo.packageName, it.activityInfo.name) }
        .map { it.activityInfo.packageName }
        .distinct().sorted()

    /** The Home answers left out of [homeCandidates], as `package/class`: evidence of the stub itself. */
    private fun homeStubs(context: Context): List<String> = homeAnswers(context)
        .filter { HomeWatch.isPlatformStubHome(it.activityInfo.packageName, it.activityInfo.name) }
        .map { "${it.activityInfo.packageName}/${it.activityInfo.name}" }
        .distinct().sorted()

    private fun homeAnswers(context: Context) =
        context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0,
        ).filter { it.activityInfo.packageName != context.packageName }

    /**
     * The packages [SettingsApps.merge] reports: handlers of the two Settings intents the
     * launcher opens (MainActivity's settings button and its Wi-Fi button), plus every installed
     * package whose name says "settings". The installed list is what Android 11's package
     * visibility leaves us — system Settings apps stay visible through the queries declared in
     * the manifest. Never throws: a probe that broke collection would hide the very report it
     * rides on.
     */
    private fun settingsApps(context: Context): List<String> = runCatching {
        val pm = context.packageManager
        val handlers = listOf(Settings.ACTION_SETTINGS, Settings.ACTION_WIFI_SETTINGS)
            .flatMap { pm.queryIntentActivities(Intent(it), 0) }
            .map { it.activityInfo.packageName }
        val installed = pm.getInstalledPackages(0).map { it.packageName }
        SettingsApps.merge(installed, handlers)
    }.getOrDefault(emptyList())
}

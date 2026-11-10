package dev.plume.quiz

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import dev.plume.DeviceProbe

/**
 * Reads, on the device, which of the permissions the quiz needs are still open (see [QuizLock]).
 * The on-device settings page shows them one by one; the parent console shows the same list as text,
 * so a parent on a phone can see what is missing before walking to the device.
 */
object QuizReady {

    fun missing(context: Context): List<QuizLock.Need> = QuizLock.missing(
        sdk = Build.VERSION.SDK_INT,
        overlay = Settings.canDrawOverlays(context),
        exactAlarm = DeviceProbe.exactAlarm(context),
        batteryFree = batteryFree(context),
        notifications = notifications(context),
        fullScreen = fullScreen(context),
    )

    /** The open needs as short stable keys, for the console's JSON. */
    fun missingKeys(context: Context): List<String> = missing(context).map { it.name.lowercase() }

    fun batteryFree(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: false

    fun notifications(context: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun fullScreen(context: Context): Boolean = Build.VERSION.SDK_INT < 34 ||
        context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true
}

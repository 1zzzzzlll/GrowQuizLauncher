package dev.plume.quiz

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

object QuizDeviceOwner {
    fun admin(context: Context) = ComponentName(context, QuizDeviceAdminReceiver::class.java)

    fun isOwner(context: Context): Boolean =
        context.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(context.packageName)

    /** Make both the launcher and its quiz activity eligible for system lock-task mode. */
    fun configure(context: Context): Boolean {
        if (!isOwner(context)) return false
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        dpm.setLockTaskPackages(admin(context), arrayOf(context.packageName))
        return true
    }
}

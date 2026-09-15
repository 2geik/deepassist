package com.deepassist.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

object PermissionsHelper {

    val runtimePermissions: Array<String>
        get() {
            val perms = mutableListOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.WRITE_CONTACTS,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_SMS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= 33) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            return perms.toTypedArray()
        }

    fun hasMicrophone(context: Context): Boolean = has(context, Manifest.permission.RECORD_AUDIO)

    fun hasContacts(context: Context): Boolean = has(context, Manifest.permission.READ_CONTACTS)

    fun hasCallPhone(context: Context): Boolean = has(context, Manifest.permission.CALL_PHONE)

    fun hasReadSms(context: Context): Boolean = has(context, Manifest.permission.READ_SMS)

    fun hasSendSms(context: Context): Boolean = has(context, Manifest.permission.SEND_SMS)

    fun hasWriteContacts(context: Context): Boolean = has(context, Manifest.permission.WRITE_CONTACTS)

    fun hasCallLog(context: Context): Boolean = has(context, Manifest.permission.READ_CALL_LOG)

    fun hasCamera(context: Context): Boolean = has(context, Manifest.permission.CAMERA)

    fun hasLocation(context: Context): Boolean =
        has(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            has(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasWriteSettings(context: Context): Boolean = Settings.System.canWrite(context)

    fun canUseFullScreenIntent(context: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.canUseFullScreenIntent()
        }.getOrDefault(true)

    fun hasAllRuntimePermissions(context: Context): Boolean =
        runtimePermissions.all { has(context, it) }

    fun hasOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun isAccessibilityEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(context.packageName)
    }

    fun isNotificationListenerEnabled(context: Context): Boolean =
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun has(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

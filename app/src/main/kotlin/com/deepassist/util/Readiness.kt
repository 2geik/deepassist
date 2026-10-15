package com.deepassist.util

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.deepassist.data.SecureStore
import com.deepassist.service.AccessibilitySvc

/** Everything the assistant needs to work, as short Turkish labels of what is missing. */
object Readiness {

    fun problems(context: Context): List<String> {
        val store = SecureStore(context)
        val list = mutableListOf<String>()
        if (!store.hasOpenAiKey() || !store.hasDeepSeekKey()) list += "API anahtarları bu derlemede yok"
        if (!PermissionsHelper.hasAllRuntimePermissions(context)) list += "Temel izinler (mikrofon, rehber, kamera...)"
        if (!PermissionsHelper.hasOverlay(context)) list += "Ekran üstü gösterim izni"
        when {
            !PermissionsHelper.isAccessibilityEnabled(context) -> list += "Erişilebilirlik servisi kapalı"
            AccessibilitySvc.isStalled(context) -> list += "Erişilebilirlik servisi durmuş, kapatıp açın"
        }
        if (!PermissionsHelper.isNotificationListenerEnabled(context)) list += "Bildirim erişimi"
        if (!PermissionsHelper.isIgnoringBatteryOptimizations(context)) list += "Pil optimizasyonu açık"
        if (Build.VERSION.SDK_INT >= 34 && !PermissionsHelper.canUseFullScreenIntent(context)) {
            list += "Tam ekran bildirim izni"
        }
        if (isDefaultAssistant(context) == false) list += "Varsayılan asistan değil"
        return list
    }

    /** null when Android doesn't let us read it. */
    private fun isDefaultAssistant(context: Context): Boolean? = runCatching {
        Settings.Secure.getString(context.contentResolver, "assistant")
            ?.contains(context.packageName)
    }.getOrNull()
}

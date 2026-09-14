package com.deepassist.tools

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import com.deepassist.data.ToolProperty
import com.deepassist.data.ToolResult
import com.google.gson.JsonObject

class RingerModeTool : Tool() {

    override val name = "set_ringer_mode"
    override val description =
        "Telefonu sessiz, titreşim veya normal zil moduna alır. Alarmları etkilemez."
    override val parameters = mapOf(
        "mode" to ToolProperty(
            type = "string",
            description = "silent (sessiz), vibrate (titreşim), normal",
            enum = listOf("silent", "vibrate", "normal")
        )
    )
    override val required = listOf("mode")

    override suspend fun execute(args: JsonObject): ToolResult {
        val (mode, done) = when (args.optString("mode")?.lowercase()) {
            "silent" -> AudioManager.RINGER_MODE_SILENT to "Telefon sessize alındı."
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE to "Telefon titreşime alındı."
            "normal" -> AudioManager.RINGER_MODE_NORMAL to "Telefon normal moda alındı."
            else -> return ToolResult(false, "", error = "Geçersiz mod.")
        }
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Toggling in/out of silent touches Do Not Disturb, which needs policy access.
        val applied = try {
            am.ringerMode = mode
            am.ringerMode == mode
        } catch (e: SecurityException) {
            false
        }
        if (applied) return ToolResult(true, done)

        if (!nm.isNotificationPolicyAccessGranted) {
            launchFromAssistant(context, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            return ToolResult(
                false, "",
                error = "Zil modunu değiştirmek için Rahatsız Etmeyin erişimi gerekiyor. Ayar ekranını açtım, deepAssist'e izin vermen gerekiyor."
            )
        }
        return ToolResult(false, "", error = "Zil modu değiştirilemedi.")
    }
}

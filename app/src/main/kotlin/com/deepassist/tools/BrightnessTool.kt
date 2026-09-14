package com.deepassist.tools

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.deepassist.data.ToolProperty
import com.deepassist.data.ToolResult
import com.google.gson.JsonObject

class BrightnessTool : Tool() {

    override val name = "set_brightness"
    override val description =
        "Ekran parlaklığını yüzde olarak ayarlar veya otomatik/manuel parlaklık moduna geçirir."
    override val parameters = mapOf(
        "level" to ToolProperty(
            type = "integer",
            description = "Parlaklık yüzdesi 0-100. Verilirse mod manuele alınır."
        ),
        "mode" to ToolProperty(
            type = "string",
            description = "auto (otomatik) veya manual (manuel)",
            enum = listOf("auto", "manual")
        )
    )
    override val required = emptyList<String>()

    override suspend fun execute(args: JsonObject): ToolResult {
        val level = args.optInt("level")
        val mode = args.optString("mode")?.lowercase()
        if (level == null && mode == null) {
            return ToolResult(false, "", error = "Parlaklık seviyesi veya modu belirtilmedi.")
        }

        if (!Settings.System.canWrite(context)) {
            launchFromAssistant(
                context,
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
            )
            return ToolResult(
                false, "",
                error = "Parlaklığı değiştirmek için sistem ayarlarını değiştirme izni gerekiyor. Ayar ekranını açtım, deepAssist için izni açman gerekiyor."
            )
        }

        val cr = context.contentResolver
        return try {
            if (level != null) {
                val percent = level.coerceIn(0, 100)
                Settings.System.putInt(
                    cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                Settings.System.putInt(
                    cr, Settings.System.SCREEN_BRIGHTNESS,
                    (percent * 255 / 100).coerceAtLeast(1)
                )
                ToolResult(true, "Parlaklık yüzde $percent.")
            } else {
                val auto = mode == "auto"
                Settings.System.putInt(
                    cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    if (auto) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                    else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                ToolResult(true, if (auto) "Otomatik parlaklık açık." else "Manuel parlaklık açık.")
            }
        } catch (e: SecurityException) {
            ToolResult(false, "", error = "Parlaklık değiştirilemedi.")
        }
    }
}

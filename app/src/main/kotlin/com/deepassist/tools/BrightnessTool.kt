package com.deepassist.tools

import android.content.ContentResolver
import android.provider.Settings
import android.util.Log
import com.deepassist.data.ToolProperty
import com.deepassist.data.ToolResult
import com.deepassist.util.PermissionsHelper
import com.google.gson.JsonObject
import java.util.Locale

class BrightnessTool : Tool() {

    override val name = "set_brightness"
    override val description =
        "Ekran parlaklığını ayarlar. Yüzde olarak (0-100) seviye verebilir veya otomatik/manuel mod arasında geçiş yapabilir."
    override val parameters = mapOf(
        "level" to ToolProperty(
            type = "integer",
            description = "Parlaklık seviyesi yüzdesi (0-100). Örneğin 70 = yüzde 70 parlaklık."
        ),
        "mode" to ToolProperty(
            type = "string",
            description = "Parlaklık modu: auto veya manual.",
            enum = listOf("auto", "manual")
        )
    )
    override val required = emptyList<String>()

    override fun dynamicThinkingPhrase(args: JsonObject): String? {
        if (args.has("level")) {
            return "Ekran parlaklığını yüzde ${args.optInt("level") ?: 0} yapıyorum..."
        }
        return when (args.optString("mode")) {
            "auto" -> "Otomatik parlaklığa geçiyorum..."
            "manual" -> "Manuel parlaklığa geçiyorum..."
            else -> "Parlaklığı ayarlıyorum..."
        }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        if (!PermissionsHelper.hasWriteSettings(context)) {
            return ToolResult(
                false, "",
                error = "Sistem ayarlarını değiştirme izni verilmemiş. Bu izni açmak için telefon ayarlarına gidin:\n" +
                    "Ayarlar > Uygulamalar > Özel erişim > Sistem ayarlarını değiştir > deepAssist > İzin ver"
            )
        }
        val cr = context.contentResolver
        val mode = args.optString("mode")

        if (mode == null) {
            val level = args.optInt("level")
            if (level != null) {
                val value = level.coerceIn(1, 100) * 255 / 100
                try {
                    Settings.System.putInt(
                        cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                    )
                } catch (_: Exception) {
                }
                return try {
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, value)
                    cr.notifyChange(Settings.System.CONTENT_URI, null)
                    ToolResult(true, "Ekran parlaklığı yüzde ${readBrightness(cr) * 100 / 255} seviyesine ayarlandı.")
                } catch (e: Exception) {
                    Log.e(TAG, "Set brightness failed", e)
                    ToolResult(false, "", error = "Parlaklık ayarlanamadı: ${e.message}")
                }
            }

            val currentMode = try {
                Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE)
            } catch (_: Exception) {
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            }
            return try {
                val modeStr = if (currentMode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) "otomatik" else "manuel"
                ToolResult(true, "Ekran parlaklığı $modeStr modunda, yüzde ${readBrightness(cr) * 100 / 255} seviyesinde.")
            } catch (_: Exception) {
                ToolResult(false, "", error = "Parlaklık okunamadı.")
            }
        }

        return try {
            when (mode.lowercase(Locale.ROOT)) {
                "auto" -> {
                    Settings.System.putInt(
                        cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                    )
                    cr.notifyChange(Settings.System.CONTENT_URI, null)
                    ToolResult(true, "Otomatik parlaklığa geçildi.")
                }
                "manual" -> {
                    Settings.System.putInt(
                        cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                    )
                    cr.notifyChange(Settings.System.CONTENT_URI, null)
                    ToolResult(true, "Manuel parlaklığa geçildi. Şu anki parlaklık yüzde ${readBrightness(cr) * 100 / 255}.")
                }
                else -> ToolResult(false, "", error = "Geçersiz mod: $mode. auto veya manual olmalı.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Set mode failed", e)
            ToolResult(false, "", error = "Parlaklık modu ayarlanamadı: ${e.message}")
        }
    }

    private fun readBrightness(cr: ContentResolver): Int = try {
        Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
    } catch (_: Exception) {
        128
    }

    companion object {
        private const val TAG = "BrightnessTool"
    }
}

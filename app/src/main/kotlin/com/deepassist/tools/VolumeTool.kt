package com.deepassist.tools

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.deepassist.data.ToolProperty
import com.deepassist.data.ToolResult
import com.google.gson.JsonObject
import java.util.Locale

class VolumeTool : Tool() {

    override val name = "set_volume"
    override val description =
        "Ses seviyesini ayarlar: müzik, zil sesi, bildirim, alarm veya arama sesi. " +
            "Yüzde olarak (0-100) mutlak değer verebilir veya göreceli ayar (yükselt, azalt, sustur, sesi aç, tam ses) yapabilir."
    override val parameters = mapOf(
        "stream" to ToolProperty(
            type = "string",
            description = "Hangi sesin ayarlanacağı",
            enum = listOf("music", "ring", "notification", "alarm", "call")
        ),
        "level" to ToolProperty(
            type = "integer",
            description = "Ses seviyesi yüzdesi (0-100). Örn: 50 = yüzde 50. Göreceli ayar yapılacaksa boş bırakılır."
        ),
        "adjustment" to ToolProperty(
            type = "string",
            description = "Göreceli ses ayarı. level ile birlikte kullanılmaz.",
            enum = listOf("up", "down", "mute", "unmute", "max")
        )
    )
    override val required = listOf("stream")

    override fun dynamicThinkingPhrase(args: JsonObject): String? {
        val stream = streamLabel(args.optString("stream"))
        if (args.has("level")) {
            return "$stream sesini yüzde ${args.optInt("level") ?: 0}'ye ayarlıyorum..."
        }
        return when (args.optString("adjustment")) {
            "unmute" -> "$stream sesini açıyorum..."
            "up" -> "$stream sesini yükseltiyorum..."
            "max" -> "$stream sesini tamamen açıyorum..."
            "down" -> "$stream sesini azaltıyorum..."
            "mute" -> "$stream sesini susturuyorum..."
            else -> "Ses seviyesini ayarlıyorum..."
        }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val streamKey = args.optString("stream")
            ?: return ToolResult(false, "", error = "Hangi sesin ayarlanacağı (stream) belirtilmedi.")
        val streamType = resolveStream(streamKey)
            ?: return ToolResult(
                false, "",
                error = "Geçersiz ses türü: $streamKey. music, ring, notification, alarm veya call olmalı."
            )
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audioManager.getStreamMaxVolume(streamType)
        val label = streamLabel(streamKey)

        val level = args.optInt("level")
        if (level != null) {
            return try {
                val target = (maxVol * level.coerceIn(0, 100) / 100f).toInt().coerceAtLeast(0)
                audioManager.setStreamVolume(streamType, target, 0)
                val pct = audioManager.getStreamVolume(streamType) * 100 / maxVol
                ToolResult(true, "$label sesi yüzde $pct seviyesine ayarlandı.")
            } catch (e: SecurityException) {
                Log.e(TAG, "setStreamVolume denied", e)
                ToolResult(false, "", error = "Ses ayarı reddedildi. Bazı cihazlarda bu işlem için özel izin gerekebilir.")
            } catch (e: Exception) {
                Log.e(TAG, "setStreamVolume failed", e)
                ToolResult(false, "", error = "Ses ayarlanamadı: ${e.message}")
            }
        }

        val adjustment = args.optString("adjustment")
        if (adjustment == null) {
            val currentPct = audioManager.getStreamVolume(streamType) * 100 / maxVol
            return ToolResult(true, "$label sesi şu an yüzde $currentPct seviyesinde.")
        }

        return try {
            when (adjustment) {
                "unmute" -> {
                    audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_UNMUTE, 0)
                    ToolResult(true, "$label sesi açıldı (yüzde ${currentPct(audioManager, streamType, maxVol)}).")
                }
                "up" -> {
                    audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_RAISE, 0)
                    ToolResult(true, "$label sesi yükseltildi (yüzde ${currentPct(audioManager, streamType, maxVol)}).")
                }
                "max" -> {
                    audioManager.setStreamVolume(streamType, maxVol, 0)
                    ToolResult(true, "$label sesi tamamen açıldı.")
                }
                "down" -> {
                    audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_LOWER, 0)
                    ToolResult(true, "$label sesi azaltıldı (yüzde ${currentPct(audioManager, streamType, maxVol)}).")
                }
                "mute" -> {
                    audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_MUTE, 0)
                    ToolResult(true, "$label sesi susturuldu.")
                }
                else -> ToolResult(
                    false, "",
                    error = "Geçersiz ses ayarı: $adjustment. up, down, mute, unmute veya max olmalı."
                )
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "adjustStreamVolume denied", e)
            ToolResult(false, "", error = "Ses ayarı reddedildi. Lütfen Rahatsız Etme izinlerini kontrol edin.")
        } catch (e: Exception) {
            Log.e(TAG, "adjustStreamVolume failed", e)
            ToolResult(false, "", error = "Ses ayarlanamadı: ${e.message}")
        }
    }

    private fun currentPct(audioManager: AudioManager, streamType: Int, maxVol: Int): Int =
        audioManager.getStreamVolume(streamType) * 100 / maxVol

    private fun resolveStream(key: String?): Int? = when (key?.lowercase(Locale.ROOT)) {
        "call" -> AudioManager.STREAM_VOICE_CALL
        "ring" -> AudioManager.STREAM_RING
        "alarm" -> AudioManager.STREAM_ALARM
        "music" -> AudioManager.STREAM_MUSIC
        "notification" -> AudioManager.STREAM_NOTIFICATION
        else -> null
    }

    private fun streamLabel(key: String?): String = when (key?.lowercase(Locale.ROOT)) {
        "call" -> "Arama"
        "ring" -> "Zil"
        "alarm" -> "Alarm"
        "music" -> "Müzik"
        "notification" -> "Bildirim"
        else -> "Ses"
    }

    companion object {
        private const val TAG = "VolumeTool"
    }
}

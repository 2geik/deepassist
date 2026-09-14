package com.deepassist.tools

import android.content.Context
import android.media.AudioManager
import com.deepassist.data.ToolProperty
import com.deepassist.data.ToolResult
import com.google.gson.JsonObject
import kotlin.math.roundToInt

class VolumeTool : Tool() {

    override val name = "set_volume"
    override val description =
        "Ses seviyesini değiştirir (müzik, zil, bildirim, alarm veya arama sesi). " +
            "Ya level (yüzde) ya da adjustment ver."
    override val parameters = mapOf(
        "stream" to ToolProperty(
            type = "string",
            description = "Hangi ses. Belirtilmezse music.",
            enum = listOf("music", "ring", "notification", "alarm", "call")
        ),
        "level" to ToolProperty(
            type = "integer",
            description = "Hedef seviye, yüzde olarak 0-100"
        ),
        "adjustment" to ToolProperty(
            type = "string",
            description = "Göreli değişiklik: up (aç), down (kıs), max (tamamen aç), min veya mute (kapat)",
            enum = listOf("up", "down", "max", "min", "mute")
        )
    )
    override val required = emptyList<String>()

    override suspend fun execute(args: JsonObject): ToolResult {
        val (stream, label) = when (args.optString("stream")?.lowercase()) {
            "ring" -> AudioManager.STREAM_RING to "Zil sesi"
            "notification" -> AudioManager.STREAM_NOTIFICATION to "Bildirim sesi"
            "alarm" -> AudioManager.STREAM_ALARM to "Alarm sesi"
            "call" -> AudioManager.STREAM_VOICE_CALL to "Arama sesi"
            else -> AudioManager.STREAM_MUSIC to "Müzik sesi"
        }
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(stream)
        val current = am.getStreamVolume(stream)
        val step = (max * 0.15).roundToInt().coerceAtLeast(1)
        val level = args.optInt("level")

        val target = when {
            level != null -> (level.coerceIn(0, 100) * max / 100.0).roundToInt()
            else -> when (args.optString("adjustment")?.lowercase()) {
                "up" -> current + step
                "down" -> current - step
                "max" -> max
                "min", "mute" -> 0
                else -> return ToolResult(false, "", error = "Ses seviyesi veya değişiklik yönü belirtilmedi.")
            }
        }.coerceIn(0, max)

        try {
            am.setStreamVolume(stream, target, AudioManager.FLAG_SHOW_UI)
        } catch (e: SecurityException) {
            return ToolResult(false, "", error = "Rahatsız Etmeyin modu açıkken $label değiştirilemiyor.")
        }

        val percent = if (max == 0) 0 else (am.getStreamVolume(stream) * 100.0 / max).roundToInt()
        return ToolResult(true, "$label yüzde $percent.")
    }
}

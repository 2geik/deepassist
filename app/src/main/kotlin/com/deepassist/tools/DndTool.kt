package com.deepassist.tools

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.util.Log
import com.deepassist.data.ToolProperty
import com.deepassist.data.ToolResult
import com.google.gson.JsonObject
import java.util.Locale

class DndTool : Tool() {

    override val name = "set_ringer_mode"
    override val description =
        "Telefonun ses modunu ayarlar: sessiz, titreşim, normal veya sadece öncelikli. " +
            "Telefonu sessize al, sesi aç gibi komutlar için kullan."
    override val parameters = mapOf(
        "mode" to ToolProperty(
            type = "string",
            description = "Telefonun geçeceği ses modu",
            enum = listOf("silent", "vibrate", "normal", "priority")
        )
    )
    override val required = listOf("mode")

    override fun dynamicThinkingPhrase(args: JsonObject): String? =
        when (args.optString("mode")) {
            "priority" -> "Sadece öncelikli bildirimlere izin veriyorum..."
            "normal" -> "Telefonun sesini açıyorum..."
            "silent" -> "Telefonu sessize alıyorum..."
            "vibrate" -> "Telefonu titreşime alıyorum..."
            else -> "Ses modunu ayarlıyorum..."
        }

    override suspend fun execute(args: JsonObject): ToolResult {
        val mode = args.optString("mode")
            ?: return ToolResult(
                false, "",
                error = "Ses modu (mode) belirtilmedi. silent, vibrate, normal veya priority olmalı."
            )
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return try {
            when (mode.lowercase(Locale.ROOT)) {
                "priority" -> setPriority(audioManager)
                "normal" -> setNormal(audioManager)
                "silent" -> setSilent(audioManager)
                "vibrate" -> setVibrate(audioManager)
                else -> ToolResult(
                    false, "",
                    error = "Geçersiz ses modu: $mode. silent, vibrate, normal veya priority olmalı."
                )
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Ringer mode change denied", e)
            ToolResult(false, "", error = "Ses modu değiştirme reddedildi.")
        } catch (e: Exception) {
            Log.e(TAG, "Ringer mode change failed", e)
            ToolResult(false, "", error = "Ses modu ayarlanamadı: ${e.message}")
        }
    }

    // Silencing via stream volumes works without Do Not Disturb access, which
    // setRingerMode(SILENT) would otherwise require on Android 7+.
    private fun setSilent(audioManager: AudioManager): ToolResult {
        savedRingVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        savedNotificationVolume = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
            try {
                audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
            } catch (_: Exception) {
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set silent via volume", e)
            try {
                audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
            } catch (_: Exception) {
                return ToolResult(false, "", error = "Telefon sessize alınamadı.")
            }
        }
        return ToolResult(true, "Telefon sessize alındı. Zil ve bildirim sesleri kapatıldı (alarmlar çalmaya devam eder).")
    }

    private fun setVibrate(audioManager: AudioManager): ToolResult {
        savedRingVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        savedNotificationVolume = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
        try {
            audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        } catch (e: Exception) {
            Log.w(TAG, "setRingerMode VIBRATE failed, trying volume approach", e)
            audioManager.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
            try {
                Settings.System.putInt(context.contentResolver, "vibrate_when_ringing", 1)
            } catch (_: Exception) {
            }
        }
        return ToolResult(true, "Telefon titreşime alındı.")
    }

    private fun setNormal(audioManager: AudioManager): ToolResult {
        val ringMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
        val ringVol = if (savedRingVolume > 0) savedRingVolume else ringMax * 2 / 3
        val notifVol = if (savedNotificationVolume > 0) {
            savedNotificationVolume
        } else {
            audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION) * 2 / 3
        }
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_RING, ringVol, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, notifVol, 0)
            audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        } catch (e: Exception) {
            Log.w(TAG, "setNormal partial failure", e)
            try {
                audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
            } catch (_: Exception) {
            }
        }
        savedRingVolume = 0
        savedNotificationVolume = 0
        val ringPct = ringVol * 100 / ringMax
        return ToolResult(true, "Telefonun sesi açıldı (zil sesi yaklaşık yüzde $ringPct seviyesinde).")
    }

    private fun setPriority(audioManager: AudioManager): ToolResult {
        val lowRing = (audioManager.getStreamMaxVolume(AudioManager.STREAM_RING) * 0.2).toInt().coerceAtLeast(1)
        val lowNotif = (audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION) * 0.2).toInt().coerceAtLeast(1)
        savedRingVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        savedNotificationVolume = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
        audioManager.setStreamVolume(AudioManager.STREAM_RING, lowRing, 0)
        audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, lowNotif, 0)
        return ToolResult(true, "Sadece öncelikli moda alındı. Zil ve bildirim sesleri kısıldı (yüzde 20).")
    }

    companion object {
        private const val TAG = "DndTool"

        // Volumes before silencing, restored by "normal".
        @Volatile private var savedRingVolume = 0
        @Volatile private var savedNotificationVolume = 0
    }
}

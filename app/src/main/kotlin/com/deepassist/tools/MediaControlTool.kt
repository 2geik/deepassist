package com.deepassist.tools

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import com.deepassist.data.ToolProperty
import com.deepassist.data.ToolResult
import com.google.gson.JsonObject
import kotlinx.coroutines.delay

class MediaControlTool : Tool() {

    override val name = "control_media"
    override val description =
        "Çalan müziği/medyayı kontrol eder: oynat, duraklat, sonraki veya önceki parça. " +
            "Yeni bir şey çalmak için DEĞİL, sadece mevcut medyayı kontrol etmek için kullan."
    override val parameters = mapOf(
        "action" to ToolProperty(
            type = "string",
            description = "Medya komutu",
            enum = listOf("play", "pause", "play_pause", "next", "previous")
        )
    )
    override val required = listOf("action")

    override suspend fun execute(args: JsonObject): ToolResult {
        val action = args.optString("action")?.lowercase()
        val (keyCode, done) = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY to "Müzik devam ediyor."
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE to "Müzik durduruldu."
            "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to "Oynat/duraklat komutu gönderildi."
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT to "Sonraki parçaya geçildi."
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS to "Önceki parçaya dönüldü."
            else -> return ToolResult(false, "", error = "Geçersiz medya komutu: $action")
        }

        // Without this the key event routes to the assistant's own audio session.
        Tool.releaseAudioFocusForMediaControl?.invoke()
        delay(200)

        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val now = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        return ToolResult(true, done)
    }
}

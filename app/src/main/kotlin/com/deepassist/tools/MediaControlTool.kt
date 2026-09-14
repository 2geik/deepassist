package com.deepassist.tools

import android.content.Context
import android.media.AudioManager
import android.media.session.MediaSession
import android.util.Log
import android.view.KeyEvent
import com.deepassist.data.ToolProperty
import com.deepassist.data.ToolResult
import com.google.gson.JsonObject
import kotlinx.coroutines.delay

class MediaControlTool : Tool() {

    override val name = "control_media"
    override val description =
        "Medya oynatmayı kontrol eder: durdur, başlat, sonraki parça, önceki parça, devam ettir. " +
            "YouTube Music, Spotify vb. tüm müzik uygulamalarında ve kilit ekranında çalışır."
    override val parameters = mapOf(
        "action" to ToolProperty(
            type = "string",
            description = "Yapılacak medya işlemi",
            enum = listOf("pause", "play", "stop", "next", "previous", "play_pause")
        )
    )
    override val required = listOf("action")

    override fun dynamicThinkingPhrase(args: JsonObject): String? =
        when (args.optString("action")) {
            "previous" -> "Önceki parçaya dönüyorum..."
            "next" -> "Sonraki parçaya geçiyorum..."
            "play" -> "Müziği başlatıyorum..."
            "stop", "pause" -> "Müziği durduruyorum..."
            "play_pause" -> "Müziği duraklatıp devam ettiriyorum..."
            else -> "Medya kontrolü yapıyorum..."
        }

    override suspend fun execute(args: JsonObject): ToolResult {
        val action = args.optString("action")
            ?: return ToolResult(false, "", error = "Medya işlemi (action) belirtilmedi.")
        val keyCode = when (action) {
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            else -> return ToolResult(false, "", error = "Bilinmeyen medya işlemi: $action")
        }
        val label = when (action) {
            "previous" -> "Önceki parça"
            "next" -> "Sonraki parça"
            "play", "play_pause" -> "Müzik başlatıldı"
            "stop", "pause" -> "Müzik durduruldu"
            else -> "Medya kontrolü yapıldı"
        }

        return try {
            // Without this the key event routes to the assistant's own audio session.
            Tool.releaseAudioFocusForMediaControl?.invoke()
            delay(200)
            dispatchMediaKey(keyCode)
            ToolResult(true, "$label.")
        } catch (e: Exception) {
            Log.e(TAG, "dispatchMediaKey failed: ${e.message}", e)
            ToolResult(false, "", error = "Medya kontrolü yapılamadı: ${e.message}")
        }
    }

    private suspend fun dispatchMediaKey(keyCode: Int) {
        getOrCreateSession()
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        delay(30)
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    @Suppress("DEPRECATION")
    private fun getOrCreateSession(): MediaSession {
        persistentSession?.let { return it }
        synchronized(MediaControlTool::class.java) {
            return persistentSession ?: MediaSession(context, TAG).apply {
                setFlags(0)
                isActive = true
            }.also {
                persistentSession = it
                Log.d(TAG, "Persistent MediaSession created")
            }
        }
    }

    companion object {
        private const val TAG = "MediaCtrl"

        @Volatile
        private var persistentSession: MediaSession? = null
    }
}

package com.deepassist.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.deepassist.agent.ScreenReader
import com.deepassist.service.AccessibilitySvc
import com.deepassist.service.AssistantForegroundService
import com.deepassist.speech.OpenAiGate
import com.deepassist.util.DiagLog

/**
 * adb test hooks (debug builds only, DUMP-protected):
 *
 *   adb shell am broadcast -a com.deepassist.DEBUG_PROMPT --es text "saat kaç"
 *
 * starts a session whose first request is the given text instead of speech.
 *
 *   adb shell am broadcast -p com.deepassist -a com.deepassist.DUMP_SCREEN
 *
 * logs what ScreenReader sees on the current screen (tag ScreenDump) — only on
 * this explicit request, so screen content never reaches logcat otherwise.
 *
 *   adb shell am broadcast -p com.deepassist -a com.deepassist.DEBUG_STT --es mode android
 *
 * picks the speech-to-text path until the process restarts: "android" skips OpenAI
 * and uses Android's recognizer, "invalid_key" sends a rejected key to OpenAI STT and
 * TTS (the quota/auth breaker then switches both to Android), "auto" is the default.
 */
class DebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AssistantForegroundService.ACTION_DEBUG_PROMPT -> {
                val text = intent.getStringExtra(AssistantForegroundService.EXTRA_DEBUG_TEXT) ?: return
                runCatching {
                    context.startForegroundService(
                        Intent(context, AssistantForegroundService::class.java)
                            .setAction(AssistantForegroundService.ACTION_DEBUG_PROMPT)
                            .putExtra(AssistantForegroundService.EXTRA_DEBUG_TEXT, text)
                    )
                }
            }
            ACTION_DEBUG_STT -> {
                val mode = intent.getStringExtra("mode")?.trim()?.lowercase()
                val known = setOf(
                    AssistantForegroundService.STT_MODE_AUTO,
                    AssistantForegroundService.STT_MODE_ANDROID,
                    AssistantForegroundService.STT_MODE_INVALID_KEY
                )
                if (mode !in known) {
                    Log.w(TAG, "DEBUG_STT: mode must be one of $known")
                    return
                }
                AssistantForegroundService.debugSttMode = mode!!
                OpenAiGate.reset()
                DiagLog.event("Debug", "STT mode $mode")
            }
            ACTION_DUMP_SCREEN -> {
                val service = AccessibilitySvc.instance
                if (service == null) {
                    Log.w("ScreenDump", "accessibility service not running")
                    return
                }
                val screen = ScreenReader.read(service)
                Log.i("ScreenDump", "poor=${screen?.poor} caps=${AccessibilitySvc.hasScreenCapabilities()}")
                screen?.describe()?.lines()?.forEach { Log.i("ScreenDump", it) }
            }
        }
    }

    companion object {
        const val ACTION_DUMP_SCREEN = "com.deepassist.DUMP_SCREEN"
        const val ACTION_DEBUG_STT = "com.deepassist.DEBUG_STT"
        private const val TAG = "DebugReceiver"
    }
}

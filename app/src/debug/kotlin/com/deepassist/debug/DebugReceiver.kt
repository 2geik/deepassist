package com.deepassist.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.deepassist.agent.ScreenReader
import com.deepassist.service.AccessibilitySvc
import com.deepassist.service.AssistantForegroundService
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
 *   adb shell am broadcast -p com.deepassist -a com.deepassist.DEBUG_TTS --es mode android
 *
 * use Android's recognizer / voice (the fallbacks) until the process restarts;
 * "auto" goes back to Meta STT / OpenAI realtime TTS.
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
            ACTION_DEBUG_STT, ACTION_DEBUG_TTS -> {
                val mode = intent.getStringExtra("mode")?.trim()?.lowercase()
                if (mode != AssistantForegroundService.SPEECH_MODE_AUTO &&
                    mode != AssistantForegroundService.SPEECH_MODE_ANDROID
                ) {
                    Log.w(TAG, "mode must be auto or android")
                    return
                }
                if (intent.action == ACTION_DEBUG_STT) {
                    AssistantForegroundService.debugSttMode = mode
                } else {
                    AssistantForegroundService.debugTtsMode = mode
                }
                DiagLog.event("Debug", "${intent.action?.substringAfterLast('.')} $mode")
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
        const val ACTION_DEBUG_TTS = "com.deepassist.DEBUG_TTS"
        private const val TAG = "DebugReceiver"
    }
}

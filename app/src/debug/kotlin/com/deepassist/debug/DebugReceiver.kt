package com.deepassist.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.deepassist.service.AssistantForegroundService

/**
 * adb test hooks (debug builds only, DUMP-protected):
 *
 *   adb shell am broadcast -a com.deepassist.DEBUG_PROMPT --es text "saat kaç"
 *
 * starts a session whose first request is the given text instead of speech.
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
        }
    }
}

package com.deepassist.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import com.deepassist.SessionActivity

/**
 * Starts an activity on behalf of a tool. Prefers the foreground
 * [SessionActivity] as the launching context — Android 14+ blocks background
 * activity starts from a plain service/application context.
 */
internal fun launchFromAssistant(context: Context, intent: Intent): Boolean {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val activity = SessionActivity.instance?.takeIf { !it.isFinishing }
    return try {
        (activity ?: context).startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        Log.w("ToolIntents", "launch blocked: ${e.message}")
        false
    }
}

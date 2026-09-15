package com.deepassist.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import kotlin.concurrent.thread

/**
 * Sleep timer: an alarm that gently lowers the media volume, pauses whatever is
 * playing, then puts the volume back. Runs from an AlarmManager broadcast, so it
 * fires even if the app process was killed in the meantime.
 */
object SleepTimer {

    private const val TAG = "SleepTimer"
    const val ACTION_FIRE = "com.deepassist.SLEEP_TIMER_FIRE"
    private const val PREFS = "sleep_timer"
    private const val KEY_END_AT = "end_at"
    private const val REQUEST_CODE = 4401
    private const val FADE_STEPS = 8
    private const val FADE_STEP_MS = 750L
    private const val RESTORE_DELAY_MS = 1_500L

    /** Replaces any running timer. @return the wall-clock time it fires. */
    fun schedule(context: Context, minutes: Int): Long {
        val endAt = System.currentTimeMillis() + minutes * 60_000L
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pi = pendingIntent(context)
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAt, pi)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAt, pi)
        }
        prefs(context).edit().putLong(KEY_END_AT, endAt).apply()
        Log.i(TAG, "scheduled in $minutes min")
        return endAt
    }

    /** @return true when a timer was running. */
    fun cancel(context: Context): Boolean {
        val wasRunning = remainingMs(context) > 0
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context))
        prefs(context).edit().remove(KEY_END_AT).apply()
        return wasRunning
    }

    fun endAt(context: Context): Long = prefs(context).getLong(KEY_END_AT, 0L)

    fun remainingMs(context: Context): Long = (endAt(context) - System.currentTimeMillis()).coerceAtLeast(0L)

    /** Blocking, ~8 s — call off the main thread. */
    internal fun fire(context: Context) {
        prefs(context).edit().remove(KEY_END_AT).apply()
        val audio = context.getSystemService(AudioManager::class.java)
        val original = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        Log.i(TAG, "firing: musicActive=${audio.isMusicActive} volume=$original")
        try {
            if (audio.isMusicActive && original > 0) {
                for (step in 1..FADE_STEPS) {
                    val level = original * (FADE_STEPS - step) / FADE_STEPS
                    runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0) }
                    Thread.sleep(FADE_STEP_MS)
                }
            }
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
            Thread.sleep(RESTORE_DELAY_MS)
        } finally {
            // The next song must not start silent
            runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, original, 0) }
        }
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, SleepTimerReceiver::class.java).setAction(ACTION_FIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

class SleepTimerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SleepTimer.ACTION_FIRE) return
        val pending = goAsync()
        val app = context.applicationContext
        thread(name = "sleep-timer") {
            try {
                SleepTimer.fire(app)
            } catch (e: Exception) {
                Log.w("SleepTimer", "fire failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}

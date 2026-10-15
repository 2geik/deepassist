package com.deepassist.agent

import android.os.SystemClock
import com.deepassist.SessionActivity
import com.deepassist.service.AccessibilitySvc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Moves the session panel out of the way of the app being read or used. */
object PanelControl {

    /**
     * Closes the panel and keeps later status updates from bringing it back
     * until [restore]. Returns once the app behind it is on top and quiet.
     */
    suspend fun stepAside() {
        SessionActivity.relaunchSuppressed = true
        withContext(Dispatchers.Main) { SessionActivity.close() }
        withTimeoutOrNull(1_500L) {
            while (SessionActivity.instance != null) delay(50)
        }
        awaitQuiet()
    }

    /** Lets the next status update show the panel again. */
    fun restore() {
        SessionActivity.relaunchSuppressed = false
    }

    /** Waits until no window or content change arrived for [quietMs] (at most [maxMs]). */
    suspend fun awaitQuiet(quietMs: Long = 400L, maxMs: Long = 2_500L) {
        val deadline = SystemClock.elapsedRealtime() + maxMs
        delay(150)
        while (SystemClock.elapsedRealtime() < deadline) {
            if (SystemClock.elapsedRealtime() - AccessibilitySvc.lastUiEventAt >= quietMs) return
            delay(80)
        }
    }
}

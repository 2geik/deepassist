package com.deepassist

import android.app.Application
import android.os.SystemClock
import com.deepassist.util.DiagLog

class DeepAssistApp : Application() {

    override fun onCreate() {
        super.onCreate()
        processStartedAt = SystemClock.elapsedRealtime()
        DiagLog.init(this)
        DiagLog.event("App", "process start")

        // Record why the process died before the system's handler kills it
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { DiagLog.eventNow("Crash", "${thread.name} ${DiagLog.describe(error)}") }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        /** elapsedRealtime of this process's start — for "service never connected" checks. */
        @Volatile
        var processStartedAt = 0L
            private set
    }
}

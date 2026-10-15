package com.deepassist.camera

import android.os.SystemClock

/**
 * The last camera photo, kept in memory only and only for follow-up questions
 * ("peki son kullanma tarihi ne?"). Expires after two minutes and is wiped when
 * the session ends; it is never written anywhere.
 */
object LastPhoto {

    private const val TTL_MS = 2 * 60 * 1000L

    private var jpeg: ByteArray? = null
    private var takenAt = 0L

    @Synchronized
    fun set(bytes: ByteArray) {
        clear()
        jpeg = bytes.copyOf()
        takenAt = SystemClock.elapsedRealtime()
    }

    /** A copy the caller wipes after use, or null when there is none or it expired. */
    @Synchronized
    fun get(): ByteArray? {
        if (SystemClock.elapsedRealtime() - takenAt > TTL_MS) clear()
        return jpeg?.copyOf()
    }

    @Synchronized
    fun clear() {
        jpeg?.fill(0)
        jpeg = null
        takenAt = 0L
    }
}

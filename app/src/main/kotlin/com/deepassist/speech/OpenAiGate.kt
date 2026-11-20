package com.deepassist.speech

import android.os.SystemClock
import com.deepassist.util.DiagLog

/** Why an OpenAI speech request failed. QUOTA and AUTH won't fix themselves in seconds. */
enum class OpenAiFailure { QUOTA, AUTH, NETWORK, OTHER }

/**
 * Circuit breaker for the OpenAI speech endpoints (realtime STT, multipart STT, TTS).
 *
 * When the credit runs out (429 insufficient_quota) or the key is rejected (401),
 * every request fails the same way, and each failed attempt costs the user a few
 * seconds of silence. After such an error OpenAI is skipped for [COOLDOWN_MS] and
 * the on-device recognizer and voice are used straight away.
 */
object OpenAiGate {

    const val COOLDOWN_MS = 10 * 60 * 1000L

    @Volatile
    private var blockedUntil = 0L

    /** True while OpenAI speech should not be tried at all. */
    fun isBlocked(): Boolean = SystemClock.elapsedRealtime() < blockedUntil

    /** Opens the breaker for QUOTA/AUTH; [source] is "stt" or "tts" (no content is logged). */
    fun report(source: String, failure: OpenAiFailure) {
        if (failure != OpenAiFailure.QUOTA && failure != OpenAiFailure.AUTH) return
        val wasBlocked = isBlocked()
        blockedUntil = SystemClock.elapsedRealtime() + COOLDOWN_MS
        if (!wasBlocked) DiagLog.event("Speech", "OpenAI quota/auth error $source ${failure.name}")
    }

    /** Closes the breaker (debug hook, when switching STT modes). */
    fun reset() {
        blockedUntil = 0L
    }

    /** Classifies an HTTP status of an OpenAI response; null when it isn't a failure we track. */
    fun fromHttpCode(code: Int): OpenAiFailure? = when (code) {
        401 -> OpenAiFailure.AUTH
        429 -> OpenAiFailure.QUOTA
        else -> null
    }

    /** Classifies the text of an OpenAI error object (code/type/message). */
    fun fromErrorText(text: String): OpenAiFailure = when {
        "insufficient_quota" in text -> OpenAiFailure.QUOTA
        "invalid_api_key" in text -> OpenAiFailure.AUTH
        else -> OpenAiFailure.OTHER
    }
}

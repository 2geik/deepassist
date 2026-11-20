package com.deepassist.speech

import android.os.SystemClock
import com.deepassist.util.DiagLog

/**
 * Measures "the user stopped talking → the first sound" for each request and
 * writes only durations to DiagLog: "first audio" is any speech (a status phrase
 * counts), "answer audio" is the first sound of the answer itself.
 */
object SpeechLatency {

    @Volatile
    private var userDoneAt = 0L

    @Volatile
    private var firstLogged = true

    @Volatile
    private var answerChars = -1

    /** The request is known: the transcript is final (or a debug prompt arrived). */
    fun userDone() {
        userDoneAt = SystemClock.elapsedRealtime()
        firstLogged = false
        answerChars = -1
    }

    /** The next sound that starts is the answer; [chars] is its length when known (0 if not). */
    fun armAnswer(chars: Int = 0) {
        if (userDoneAt != 0L) answerChars = chars
    }

    /** Called by the players when speech actually starts. */
    fun audioStarted() {
        val t = userDoneAt
        if (t == 0L) return
        val ms = SystemClock.elapsedRealtime() - t
        if (!firstLogged) {
            firstLogged = true
            DiagLog.event("Latency", "first audio $ms ms")
        }
        val chars = answerChars
        if (chars >= 0) {
            answerChars = -1
            userDoneAt = 0L
            DiagLog.event("Latency", "answer audio $ms ms" + if (chars > 0) " ($chars chars)" else "")
        }
    }
}

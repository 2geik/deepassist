package com.deepassist.speech

import java.io.InputStream

/**
 * A cloud voice: text in, a stream of 24 kHz mono PCM16 out. Playback, sentence
 * splitting and the on-device fallback live in [SentenceSpeaker]; changing the
 * provider means writing one class that implements this.
 */
interface CloudTts {

    /** Prepares synthesis of [text]; nothing is sent until [Synthesis.open]. */
    fun synthesize(text: String): Synthesis

    /** Cancels every request of this provider that is still running. */
    fun stop()

    interface Synthesis {
        /**
         * Sends the request and returns the audio as it arrives (24 kHz mono PCM16,
         * little-endian), or null when the provider refused or could not be reached.
         * Blocking: call it off the main thread.
         */
        fun open(): InputStream?

        /** Aborts this request only; a blocked [open] or read returns at once. */
        fun cancel()
    }

    companion object {
        const val SAMPLE_RATE = 24_000
    }
}

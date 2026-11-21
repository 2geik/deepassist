package com.deepassist.speech

/** Result of one listening turn, whichever recognizer produced it. */
sealed class SttResult {
    data class Final(val text: String) : SttResult()

    /** Listened fine, but the user said nothing intelligible. */
    object NoSpeech : SttResult()

    /**
     * The recognizer failed. [heardSpeech]: the user had already started talking,
     * so the fallback has to ask for the words again.
     */
    data class Unavailable(val heardSpeech: Boolean = false) : SttResult()
}

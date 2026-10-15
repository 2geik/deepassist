package com.deepassist.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the assistant is doing right now, for the panel's animated indicator. */
object VoiceActivity {

    enum class Phase { IDLE, LISTENING, THINKING, SPEAKING }

    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** Microphone loudness 0..1 while listening. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    fun set(phase: Phase) {
        _phase.value = phase
        if (phase != Phase.LISTENING) _level.value = 0f
    }

    /** Feeds one PCM16 chunk's peak (0..32767); smoothed so bars don't flicker. */
    fun reportPeak(peak: Int) {
        val target = (peak / 9000f).coerceIn(0f, 1f)
        val current = _level.value
        _level.value = if (target > current) target else current * 0.7f + target * 0.3f
    }
}

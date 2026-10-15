package com.deepassist.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import com.deepassist.R
import java.util.concurrent.ConcurrentHashMap

/**
 * The "Yumuşak" feedback sounds (kalimba-like chimes made by
 * tools/earcons/generate_earcons.py), played through SoundPool so they start
 * instantly and mix with speech.
 */
class Earcons(context: Context) {

    enum class Sound(val res: Int) {
        LISTEN_START(R.raw.earcon_listen_start),
        LISTEN_END(R.raw.earcon_listen_end),
        SUCCESS(R.raw.earcon_success),
        ERROR(R.raw.earcon_error),
        SHUTTER(R.raw.earcon_shutter),
        TICK(R.raw.earcon_tick)
    }

    private val pool = SoundPool.Builder()
        .setMaxStreams(3)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val ids = Sound.entries.associateWith { pool.load(context, it.res, 1) }
    private val loaded = ConcurrentHashMap.newKeySet<Int>()

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) loaded.add(sampleId)
        }
    }

    fun play(sound: Sound, volume: Float = 1f) {
        val id = ids[sound] ?: return
        if (id !in loaded) {
            Log.w("Earcons", "$sound not loaded yet")
            return
        }
        runCatching { pool.play(id, volume, volume, 1, 0, 1f) }
    }

    fun release() {
        runCatching { pool.release() }
    }
}

package com.deepassist.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler

/** Microphone capture as 24 kHz mono PCM16, the format the realtime STT expects. */
object Mic {

    const val RATE = 24_000

    /** Records at 24 kHz, or at 48 kHz and decimates; echo cancelling when the phone has it. */
    @SuppressLint("MissingPermission")
    fun create(): AudioRecord? {
        for (rate in intArrayOf(24_000, 48_000)) {
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) continue
            val rec = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf * 2, rate)
                )
            } catch (e: Exception) {
                null
            }
            if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) {
                runCatching {
                    if (AcousticEchoCanceler.isAvailable()) {
                        AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true
                    }
                }
                return rec
            }
            rec?.release()
        }
        return null
    }

    /** One chunk read at [nativeRate] → 24 kHz little-endian PCM16 bytes. */
    fun to24kBytes(buf: ShortArray, read: Int, nativeRate: Int): ByteArray {
        if (nativeRate == 48_000) {
            val out = ShortArray(read / 2)
            var j = 0
            var i = 0
            while (i + 1 < read) {
                out[j++] = ((buf[i].toInt() + buf[i + 1].toInt()) / 2).toShort()
                i += 2
            }
            return toBytes(out, j)
        }
        return toBytes(buf, read)
    }

    fun peak(buf: ShortArray, read: Int): Int {
        var peak = 0
        for (i in 0 until read) {
            val a = kotlin.math.abs(buf[i].toInt())
            if (a > peak) peak = a
        }
        return peak
    }

    private fun toBytes(buf: ShortArray, count: Int): ByteArray {
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            val s = buf[i].toInt()
            bytes[i * 2] = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return bytes
    }
}

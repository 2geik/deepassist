package com.deepassist.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Base64
import com.deepassist.util.DiagLog
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

sealed class RealtimeSttResult {
    data class Final(val text: String) : RealtimeSttResult()

    /** Connected fine but the user said nothing intelligible. */
    object NoSpeech : RealtimeSttResult()

    /**
     * Could not connect or stream. [speechPcm24k] is what the user said meanwhile
     * (24 kHz mono PCM16, recorded until they stopped talking), for the fallback
     * recognizer; null when realtime could not even start (no key, no microphone).
     */
    class Unavailable(val speechPcm24k: ByteArray? = null) : RealtimeSttResult()
}

/**
 * Live transcription over the OpenAI Realtime API (gpt-transcribe).
 * Microphone audio streams to the server while the user is still talking;
 * server-side VAD detects end of speech (~1.1s pause) and the transcript
 * streams back token by token through [onPartial] before finalizing — much
 * lower latency than record-then-upload Whisper.
 */
class RealtimeStt(private val apiKeyProvider: () -> String) {

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // websocket stays open
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    @SuppressLint("MissingPermission")
    suspend fun listen(onPartial: (String) -> Unit): RealtimeSttResult = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()
        if (apiKey.isBlank()) return@withContext RealtimeSttResult.Unavailable()

        val recorder = createRecorder() ?: return@withContext RealtimeSttResult.Unavailable()
        val events = Channel<String>(Channel.UNLIMITED)
        var ws: WebSocket? = null
        // Everything the mic hears is also kept here, so a failed connection doesn't
        // lose the request: it goes to the fallback recognizer instead
        val captured = ByteArrayOutputStream()
        val firstLoudAt = AtomicLong(0L)
        val lastLoudAt = AtomicLong(0L)
        val failureNote = AtomicReference<String?>(null)

        try {
            val request = Request.Builder()
                .url("wss://api.openai.com/v1/realtime?intent=transcription")
                .addHeader("Authorization", "Bearer $apiKey")
                .build()
            val socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    events.trySend(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    failureNote.set(t.javaClass.simpleName + (response?.code?.let { " HTTP $it" } ?: ""))
                    events.close()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    events.close()
                }
            })
            ws = socket
            socket.send(gson.toJson(sessionConfig()))

            coroutineScope {
                var socketOpen = true
                val sendJob = launch {
                    val buf = ShortArray(recorder.sampleRate / 10) // 100ms chunks
                    recorder.startRecording()
                    try {
                        while (isActive) {
                            val read = recorder.read(buf, 0, buf.size)
                            if (read <= 0) break
                            var peak = 0
                            for (i in 0 until read) {
                                val a = kotlin.math.abs(buf[i].toInt())
                                if (a > peak) peak = a
                            }
                            com.deepassist.service.VoiceActivity.reportPeak(peak)
                            val pcm = to24kPcmBytes(buf, read, recorder.sampleRate)
                            synchronized(captured) {
                                if (captured.size() < MAX_CAPTURE_BYTES) captured.write(pcm)
                            }
                            if (peak >= LOCAL_SPEECH_PEAK) {
                                val now = SystemClock.elapsedRealtime()
                                firstLoudAt.compareAndSet(0L, now)
                                lastLoudAt.set(now)
                            }
                            // Keep recording when the socket is gone: the fallback needs it
                            if (socketOpen) {
                                val append = JsonObject().apply {
                                    addProperty("type", "input_audio_buffer.append")
                                    addProperty("audio", Base64.encodeToString(pcm, Base64.NO_WRAP))
                                }
                                socketOpen = socket.send(gson.toJson(append))
                            }
                        }
                    } finally {
                        runCatching { recorder.stop() }
                    }
                }

                val partial = StringBuilder()
                var heardSpeech = false
                var result: RealtimeSttResult? = null
                val start = SystemClock.elapsedRealtime()
                // The mic records from the start and OkHttp queues the audio until the
                // socket is open, so nothing said while connecting is lost. The silence
                // limits count from the first server event: a slow connection must not
                // use up the user's 6 seconds.
                var connectedAt = 0L
                // Realtime failed: keep listening locally, then hand the audio over
                var failed = false

                fun fail(why: String) {
                    failed = true
                    socketOpen = false
                    runCatching { socket.cancel() }
                    DiagLog.event("Speech", "realtime unavailable: $why")
                }

                while (result == null) {
                    if (failed) {
                        // Local finish: wait until the user is done talking (or clearly silent)
                        delay(100)
                        val now = SystemClock.elapsedRealtime()
                        val first = firstLoudAt.get()
                        result = when {
                            first == 0L && now - start > LOCAL_NO_SPEECH_MS -> RealtimeSttResult.NoSpeech
                            first == 0L -> null
                            now - lastLoudAt.get() >= LOCAL_END_SILENCE_MS || now - start > HARD_CAP_MS ->
                                RealtimeSttResult.Unavailable(synchronized(captured) { captured.toByteArray() })
                            else -> null
                        }
                        continue
                    }

                    val tick = withTimeoutOrNull(500) { events.receiveCatching() }
                    if (tick != null && tick.isClosed) {
                        fail(failureNote.get() ?: "closed")
                    } else if (tick != null) {
                        if (connectedAt == 0L) {
                            connectedAt = SystemClock.elapsedRealtime()
                            DiagLog.event("Speech", "realtime connected ${connectedAt - start} ms")
                        }
                        val obj = runCatching {
                            gson.fromJson(tick.getOrNull(), JsonObject::class.java)
                        }.getOrNull()
                        when (obj?.get("type")?.takeIf { it.isJsonPrimitive }?.asString) {
                            "input_audio_buffer.speech_started" -> if (!heardSpeech) {
                                heardSpeech = true
                                DiagLog.event(
                                    "Speech",
                                    "realtime speech started ${SystemClock.elapsedRealtime() - start} ms"
                                )
                            }

                            "conversation.item.input_audio_transcription.delta" -> {
                                obj.get("delta")?.takeIf { it.isJsonPrimitive }?.asString?.let {
                                    partial.append(it)
                                    if (partial.isNotBlank()) onPartial(partial.toString())
                                }
                            }

                            "conversation.item.input_audio_transcription.completed" -> {
                                val text = obj.get("transcript")
                                    ?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
                                result = if (text.isBlank()) {
                                    RealtimeSttResult.NoSpeech
                                } else {
                                    RealtimeSttResult.Final(text)
                                }
                            }

                            // Only the error's code is logged, never content
                            "error" -> fail(
                                "error " + (
                                    obj.getAsJsonObject("error")?.get("code")
                                        ?.takeIf { it.isJsonPrimitive }?.asString ?: "?"
                                    )
                            )
                        }
                    }
                    if (result != null || failed) continue

                    // Deadlines, checked on every pass (events can keep arriving)
                    val now = SystemClock.elapsedRealtime()
                    val userDone = firstLoudAt.get() != 0L && now - lastLoudAt.get() >= LOCAL_END_SILENCE_MS
                    if (connectedAt == 0L) {
                        if (now - start > CONNECT_TIMEOUT_MS) {
                            fail("connect timeout")
                        } else if (now - start > EARLY_FALLBACK_MS && userDone) {
                            // The user already finished talking and OpenAI still isn't there
                            fail("slow connect, user done")
                        }
                    } else if (!heardSpeech && now - connectedAt > NO_SPEECH_TIMEOUT_MS) {
                        DiagLog.event("Speech", "realtime no speech")
                        result = RealtimeSttResult.NoSpeech
                    } else if (now - connectedAt > OVERALL_TIMEOUT_MS || now - start > HARD_CAP_MS) {
                        result = if (partial.isNotBlank()) {
                            RealtimeSttResult.Final(partial.toString().trim())
                        } else {
                            RealtimeSttResult.NoSpeech
                        }
                    }
                }
                sendJob.cancel()
                result ?: RealtimeSttResult.NoSpeech
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            DiagLog.event("Speech", "realtime failed ${e.javaClass.simpleName}")
            RealtimeSttResult.Unavailable()
        } finally {
            runCatching { ws?.cancel() }
            runCatching { recorder.release() }
            events.close()
        }
    }

    /** The Realtime API expects 24kHz mono PCM16; capture at 24k or decimate from 48k. */
    @SuppressLint("MissingPermission")
    private fun createRecorder(): AudioRecord? {
        for (rate in intArrayOf(24000, 48000)) {
            val minBuf = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
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
                    if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                        val aec = android.media.audiofx.AcousticEchoCanceler
                            .create(rec.audioSessionId)
                        aec?.enabled = true
                    }
                }
                return rec
            }
            rec?.release()
        }
        return null
    }

    private fun to24kPcmBytes(buf: ShortArray, read: Int, nativeRate: Int): ByteArray {
        if (nativeRate == 48000) {
            var j = 0
            var i = 0
            val out = ShortArray(read / 2)
            while (i + 1 < read) {
                out[j++] = ((buf[i].toInt() + buf[i + 1].toInt()) / 2).toShort()
                i += 2
            }
            return shortsToBytes(out, j)
        }
        return shortsToBytes(buf, read)
    }

    private fun shortsToBytes(buf: ShortArray, count: Int): ByteArray {
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            val s = buf[i].toInt()
            bytes[i * 2] = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    // GA Realtime API shape (the beta "transcription_session.update" shape was
    // shut off server-side); verified live against the API with Turkish audio
    private fun sessionConfig(): JsonObject = JsonObject().apply {
        addProperty("type", "session.update")
        add(
            "session",
            JsonObject().apply {
                addProperty("type", "transcription")
                add(
                    "audio",
                    JsonObject().apply {
                        add(
                            "input",
                            JsonObject().apply {
                                add(
                                    "format",
                                    JsonObject().apply {
                                        addProperty("type", "audio/pcm")
                                        addProperty("rate", 24000)
                                    }
                                )
                                add(
                                    "transcription",
                                    JsonObject().apply {
                                        addProperty("model", "gpt-transcribe")
                                        addProperty("language", "tr")
                                    }
                                )
                                add(
                                    "turn_detection",
                                    JsonObject().apply {
                                        addProperty("type", "server_vad")
                                        addProperty("threshold", 0.5)
                                        addProperty("prefix_padding_ms", 300)
                                        addProperty("silence_duration_ms", 1100)
                                    }
                                )
                                add(
                                    "noise_reduction",
                                    JsonObject().apply { addProperty("type", "near_field") }
                                )
                            }
                        )
                    }
                )
            }
        )
    }

    companion object {
        private const val NO_SPEECH_TIMEOUT_MS = 6_000L
        private const val OVERALL_TIMEOUT_MS = 15_000L
        private const val CONNECT_TIMEOUT_MS = 8_000L

        /** Not connected yet, but the user finished talking this long ago: use the fallback. */
        private const val EARLY_FALLBACK_MS = 4_000L
        private const val LOCAL_END_SILENCE_MS = 1_500L
        private const val LOCAL_NO_SPEECH_MS = 8_000L

        /** Peak (PCM16) that counts as the user talking for the local fallback recording. */
        private const val LOCAL_SPEECH_PEAK = 1_000

        /** 25 s of 24 kHz PCM16 at most. */
        private const val MAX_CAPTURE_BYTES = 24_000 * 2 * 25
        private const val HARD_CAP_MS = 25_000L
    }
}

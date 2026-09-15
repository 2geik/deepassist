package com.deepassist.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
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

    /** Could not connect or stream — caller should fall back to multipart STT. */
    object Unavailable : RealtimeSttResult()
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
        if (apiKey.isBlank()) return@withContext RealtimeSttResult.Unavailable

        val recorder = createRecorder() ?: return@withContext RealtimeSttResult.Unavailable
        val events = Channel<String>(Channel.UNLIMITED)
        var ws: WebSocket? = null

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
                    events.close()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    events.close()
                }
            })
            ws = socket
            socket.send(gson.toJson(sessionConfig()))

            coroutineScope {
                val sendJob = launch {
                    val buf = ShortArray(recorder.sampleRate / 10) // 100ms chunks
                    recorder.startRecording()
                    try {
                        while (isActive) {
                            val read = recorder.read(buf, 0, buf.size)
                            if (read <= 0) break
                            val append = JsonObject().apply {
                                addProperty("type", "input_audio_buffer.append")
                                addProperty(
                                    "audio",
                                    Base64.encodeToString(
                                        to24kPcmBytes(buf, read, recorder.sampleRate),
                                        Base64.NO_WRAP
                                    )
                                )
                            }
                            if (!socket.send(gson.toJson(append))) break
                        }
                    } finally {
                        runCatching { recorder.stop() }
                    }
                }

                val partial = StringBuilder()
                var heardSpeech = false
                var result: RealtimeSttResult? = null
                val start = SystemClock.elapsedRealtime()

                while (result == null) {
                    val tick = withTimeoutOrNull(500) { events.receiveCatching() }
                    val elapsed = SystemClock.elapsedRealtime() - start
                    when {
                        tick == null -> { // no event in this window: check deadlines
                            if (!heardSpeech && elapsed > NO_SPEECH_TIMEOUT_MS) {
                                result = RealtimeSttResult.NoSpeech
                            } else if (elapsed > OVERALL_TIMEOUT_MS) {
                                result = if (partial.isNotBlank()) {
                                    RealtimeSttResult.Final(partial.toString().trim())
                                } else {
                                    RealtimeSttResult.NoSpeech
                                }
                            }
                        }
                        tick.isClosed -> result = RealtimeSttResult.Unavailable
                        else -> {
                            val obj = runCatching {
                                gson.fromJson(tick.getOrNull(), JsonObject::class.java)
                            }.getOrNull()
                            when (obj?.get("type")?.takeIf { it.isJsonPrimitive }?.asString) {
                                "input_audio_buffer.speech_started" -> heardSpeech = true

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

                                "error" -> result = RealtimeSttResult.Unavailable
                            }
                        }
                    }
                }
                sendJob.cancel()
                result
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            RealtimeSttResult.Unavailable
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
    }
}

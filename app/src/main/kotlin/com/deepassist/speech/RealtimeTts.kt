package com.deepassist.speech

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Base64
import com.deepassist.util.DiagLog
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Speech output with OpenAI gpt-realtime-2.1-mini over the Realtime WebSocket,
 * used as a text-to-speech voice (tts-1 is retired on 6 January 2027 and its
 * replacement is not offered on /v1/audio/speech).
 *
 * One session stays open for a whole assistant session ([warmUp] at the trigger,
 * [close] at the end), so each utterance costs one response, not a new
 * connection. Every utterance is an out-of-band response (conversation "none")
 * with the text as its only input, so nothing accumulates in the session and the
 * model reads exactly that text. Audio streams back as 24 kHz PCM16 and plays as
 * it arrives.
 */
class RealtimeTts(private val apiKeyProvider: () -> String) {

    enum class Outcome { PLAYED, FAILED, STOPPED }

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    /** One utterance at a time: a session plays one response. */
    private val speaking = Mutex()

    @Volatile
    private var conn: Connection? = null

    @Volatile
    private var currentTrack: AudioTrack? = null

    @Volatile
    private var currentEvents: Channel<JsonObject>? = null

    @Volatile
    private var stopRequested = false

    private inner class Connection(apiKey: String) {
        val ready = CompletableDeferred<Boolean>()
        @Volatile var closed = false
        @Volatile var sink: Channel<JsonObject>? = null
        val socket: WebSocket

        init {
            val request = Request.Builder()
                .url("wss://api.openai.com/v1/realtime?model=$MODEL")
                .addHeader("Authorization", "Bearer $apiKey")
                .build()
            socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val obj = runCatching { gson.fromJson(text, JsonObject::class.java) }.getOrNull() ?: return
                    when (val type = obj.get("type")?.takeIf { it.isJsonPrimitive }?.asString) {
                        "session.created" -> webSocket.send(gson.toJson(sessionUpdate()))
                        "session.updated" -> ready.complete(true)
                        "error" -> {
                            // Code only: insufficient_quota, invalid_api_key, rate_limit_exceeded...
                            val code = obj.getAsJsonObject("error")?.get("code")
                                ?.takeIf { it.isJsonPrimitive }?.asString ?: "?"
                            DiagLog.event("Speech", "realtime TTS error $code")
                            if (!ready.isCompleted) ready.complete(false) else sink?.trySend(obj)
                        }
                        else -> if (type != null && type.startsWith("response.")) sink?.trySend(obj)
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = gone("close $code")

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = gone("close $code")

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                    gone(t.javaClass.simpleName + (response?.code?.let { " HTTP $it" } ?: ""))
            })
        }

        fun gone(why: String) {
            if (closed) return
            closed = true
            DiagLog.event("Speech", "realtime TTS session ended: $why")
            ready.complete(false)
            sink?.close()
        }

        fun send(obj: JsonObject): Boolean = !closed && socket.send(gson.toJson(obj))
    }

    /** Opens the session ahead of time (connecting takes about 1.5 s). */
    @Synchronized
    fun warmUp() {
        val key = apiKeyProvider()
        if (key.isBlank()) return
        val c = conn
        if (c == null || c.closed) conn = Connection(key)
    }

    /** Closes the session (end of the assistant session). */
    @Synchronized
    fun close() {
        conn?.let { runCatching { it.socket.close(1000, null) } }
        conn = null
    }

    /**
     * Speaks [text]; returns when it has been heard to the end.
     * FAILED when nothing played (no session, error, no audio within
     * [FIRST_AUDIO_TIMEOUT_MS]) — the caller uses the on-device voice. STOPPED
     * when [stop] cut it.
     */
    suspend fun speak(text: String, onFirstAudio: () -> Unit = {}): Outcome = speaking.withLock {
        withContext(Dispatchers.IO) { speakLocked(text, onFirstAudio) }
    }

    private suspend fun speakLocked(text: String, onFirstAudio: () -> Unit): Outcome {
        if (text.isBlank()) return Outcome.PLAYED
        stopRequested = false
        val start = SystemClock.elapsedRealtime()
        warmUp()
        val c = conn ?: return Outcome.FAILED
        val ready = withTimeoutOrNull(FIRST_AUDIO_TIMEOUT_MS) { c.ready.await() } ?: false
        if (!ready || c.closed) {
            DiagLog.event("Speech", "realtime TTS not ready")
            close() // reconnect next time
            return if (stopRequested) Outcome.STOPPED else Outcome.FAILED
        }

        if (stopRequested) return Outcome.STOPPED
        val events = Channel<JsonObject>(Channel.UNLIMITED)
        c.sink = events
        currentEvents = events
        var track: AudioTrack? = null
        var framesWritten = 0L
        var played = false
        var responseId: String? = null
        try {
            c.send(responseCreate(text.take(MAX_CHARS)))
            while (true) {
                val waitMs = if (played) null else FIRST_AUDIO_TIMEOUT_MS - (SystemClock.elapsedRealtime() - start)
                val next = if (waitMs == null) {
                    events.receiveCatching()
                } else {
                    withTimeoutOrNull(waitMs.coerceAtLeast(1L)) { events.receiveCatching() }
                }
                if (next == null) {
                    DiagLog.event("Speech", "realtime TTS no audio in ${FIRST_AUDIO_TIMEOUT_MS} ms")
                    cancelResponse(c, responseId)
                    return Outcome.FAILED
                }
                val ev = next.getOrNull() ?: break // channel closed: session gone or stop()
                when (ev.get("type")?.asString) {
                    "response.created" -> if (responseId == null) {
                        responseId = ev.getAsJsonObject("response")?.get("id")?.asString
                    }

                    "response.output_audio.delta" -> {
                        // Events of an earlier, cancelled response are ignored
                        if (responseId != null && ev.get("response_id")?.asString != responseId) continue
                        val pcm = Base64.decode(ev.get("delta")?.asString ?: continue, Base64.DEFAULT)
                        val t = track ?: createTrack()?.also {
                            track = it
                            currentTrack = it
                            // A little silence first: the output path may swallow its first syllable
                            write(it, ByteArray(LEAD_IN_BYTES))
                            framesWritten += LEAD_IN_BYTES / 2
                        } ?: return Outcome.FAILED
                        if (!write(t, pcm)) return Outcome.STOPPED // released by stop()
                        framesWritten += pcm.size / 2
                        if (!played) {
                            played = true
                            DiagLog.event("Speech", "realtime TTS first audio ${SystemClock.elapsedRealtime() - start} ms")
                            onFirstAudio()
                        }
                    }

                    "response.done" -> {
                        val id = ev.getAsJsonObject("response")?.get("id")?.asString
                        if (responseId == null || id == responseId) break
                    }

                    "error" -> {
                        if (!played) return Outcome.FAILED
                        break
                    }
                }
            }
            if (stopRequested) return Outcome.STOPPED
            if (!played) return Outcome.FAILED
            track?.let { drain(it, framesWritten) }
            return if (stopRequested) Outcome.STOPPED else Outcome.PLAYED
        } catch (e: CancellationException) {
            cancelResponse(c, responseId)
            throw e
        } finally {
            c.sink = null
            currentEvents = null
            track?.let {
                runCatching { it.release() }
                if (currentTrack === it) currentTrack = null
            }
        }
    }

    /** Cuts the current utterance (session end, audio focus loss, watchdog); keeps the session. */
    fun stop() {
        stopRequested = true
        conn?.let { cancelResponse(it, null) }
        currentEvents?.close()
        currentTrack?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            runCatching { it.release() }
        }
        currentTrack = null
    }

    private fun cancelResponse(c: Connection, responseId: String?) {
        c.send(JsonObject().apply {
            addProperty("type", "response.cancel")
            responseId?.let { addProperty("response_id", it) }
        })
    }

    private fun write(t: AudioTrack, data: ByteArray): Boolean {
        var written = 0
        while (written < data.size) {
            val n = t.write(data, written, data.size - written)
            if (n <= 0) return false
            written += n
        }
        return true
    }

    /** Waits until what was written has been heard (bounded), then stops the track. */
    private suspend fun drain(t: AudioTrack, frames: Long) {
        runCatching { t.stop() } // MODE_STREAM: plays out what is buffered
        val head = { runCatching { t.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(Long.MAX_VALUE) }
        val remainingMs = (frames - head()).coerceAtLeast(0) * 1000 / SAMPLE_RATE
        val deadline = SystemClock.elapsedRealtime() + remainingMs + 400
        while (!stopRequested && head() < frames && SystemClock.elapsedRealtime() < deadline) delay(20)
    }

    private fun createTrack(): AudioTrack? = runCatching {
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            maxOf(minBuf * 2, 16_384),
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        ).also { it.play() }
    }.getOrNull()

    private fun sessionUpdate(): JsonObject = gson.toJsonTree(
        mapOf(
            "type" to "session.update",
            "session" to mapOf(
                "type" to "realtime",
                "output_modalities" to listOf("audio"),
                "instructions" to INSTRUCTIONS,
                "reasoning" to mapOf("effort" to "minimal"),
                "audio" to mapOf(
                    "input" to mapOf("turn_detection" to null),
                    "output" to mapOf(
                        "format" to mapOf("type" to "audio/pcm", "rate" to SAMPLE_RATE),
                        "voice" to VOICE,
                        "speed" to SPEED
                    )
                )
            )
        )
    ).asJsonObject.also {
        // Gson drops nulls by default; turn_detection must be an explicit null
        it.getAsJsonObject("session").getAsJsonObject("audio").getAsJsonObject("input")
            .add("turn_detection", com.google.gson.JsonNull.INSTANCE)
    }

    private fun responseCreate(text: String): JsonObject = gson.toJsonTree(
        mapOf(
            "type" to "response.create",
            "response" to mapOf(
                "conversation" to "none",
                "output_modalities" to listOf("audio"),
                "input" to listOf(
                    mapOf(
                        "type" to "message",
                        "role" to "user",
                        "content" to listOf(mapOf("type" to "input_text", "text" to text))
                    )
                )
            )
        )
    ).asJsonObject

    companion object {
        private const val MODEL = "gpt-realtime-2.1-mini"
        private const val VOICE = "marin"
        private const val SPEED = 1.1
        private const val SAMPLE_RATE = 24_000
        private const val MAX_CHARS = 4_000

        /** No sound within this (including connecting) → the on-device voice reads it. */
        private const val FIRST_AUDIO_TIMEOUT_MS = 3_000L

        /** 80 ms of silence at 24 kHz PCM16. */
        private const val LEAD_IN_BYTES = SAMPLE_RATE * 2 * 80 / 1000

        // Verified on 21 November 2026 with Turkish answers, questions and numbers:
        // read word for word, never answered
        private const val INSTRUCTIONS =
            "Sen bir metin okuma sesisin. Kullanıcının gönderdiği metni olduğu gibi, kelimesi kelimesine, " +
                "doğal ve akıcı Türkçeyle sesli oku. Metne cevap verme, soru olsa bile yanıtlama; hiçbir şey ekleme, " +
                "çıkarma, çevirme ya da özetleme. Sadece metni oku."
    }
}

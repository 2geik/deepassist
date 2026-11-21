package com.deepassist.speech

import android.os.SystemClock
import com.deepassist.service.VoiceActivity
import com.deepassist.util.DiagLog
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
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
import okio.ByteString.Companion.toByteString

/**
 * Live transcription with Meta's Muse Voice Transcribe (realtime WebSocket,
 * ENDPOINTING mode, Turkish bias). The microphone starts at once; what is said
 * while the socket connects is queued and sent when the session is acknowledged.
 *
 * End of the request: Meta marks each turn with speechComplete. A turn counts as
 * the end once the user has also been quiet for a moment, so a short pause in
 * the middle of a sentence doesn't cut it (the next turn is appended). For very
 * short words Meta's endpointer can wait for the stream to end, so after a clear
 * local silence the stream is ended by us, which makes Meta send the final text.
 */
class MetaStt(private val apiKeyProvider: () -> String) {

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // stays open while listening
        .build()

    /**
     * Listens for one request. [onPartial] gets the text so far; [onSpeechEnd] is
     * called once, when the microphone stops because the user is done.
     */
    suspend fun listen(onPartial: (String) -> Unit, onSpeechEnd: () -> Unit): SttResult =
        withContext(Dispatchers.IO) {
            val apiKey = apiKeyProvider()
            if (apiKey.isBlank()) return@withContext SttResult.Unavailable()
            val recorder = Mic.create() ?: return@withContext SttResult.Unavailable()

            val events = Channel<String>(Channel.UNLIMITED)
            val closeNote = AtomicReference<String?>(null)
            val openedAt = SystemClock.elapsedRealtime()
            val firstFrameLogged = AtomicBoolean(false)
            val request = Request.Builder().url(ENDPOINT).build()
            val socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    DiagLog.event("Speech", "Meta STT socket open ${SystemClock.elapsedRealtime() - openedAt} ms")
                    // The key goes in the first frame; the endpoint ignores HTTP auth headers
                    webSocket.send(gson.toJson(handshake(apiKey)))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (firstFrameLogged.compareAndSet(false, true)) {
                        // Field names only, never values
                        val keys = runCatching { gson.fromJson(text, JsonObject::class.java).keySet().sorted() }
                            .getOrNull()?.joinToString(",") ?: "not json"
                        DiagLog.event(
                            "Speech",
                            "Meta STT first frame ${SystemClock.elapsedRealtime() - openedAt} ms [$keys]"
                        )
                    }
                    events.trySend(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    closeNote.compareAndSet(null, "close $code")
                    events.close()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    closeNote.compareAndSet(null, "close $code")
                    events.close()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    closeNote.compareAndSet(null, t.javaClass.simpleName + (response?.code?.let { " HTTP $it" } ?: ""))
                    events.close()
                }
            })

            val audio = Channel<ByteArray>(Channel.UNLIMITED)
            val lastLoudAt = AtomicLong(0L)
            val ended = AtomicBoolean(false) // endStream sent: nothing more may be sent

            try {
                coroutineScope {
                    // Microphone → queue, from the first moment
                    val micJob = launch {
                        val buf = ShortArray(recorder.sampleRate * CHUNK_MS / 1000)
                        recorder.startRecording()
                        try {
                            while (isActive) {
                                val read = recorder.read(buf, 0, buf.size)
                                if (read <= 0) break
                                val peak = Mic.peak(buf, read)
                                VoiceActivity.reportPeak(peak)
                                if (peak >= LOCAL_SPEECH_PEAK) lastLoudAt.set(SystemClock.elapsedRealtime())
                                audio.trySend(Mic.to24kBytes(buf, read, recorder.sampleRate))
                            }
                        } finally {
                            runCatching { recorder.stop() }
                        }
                    }
                    val start = SystemClock.elapsedRealtime()
                    var ackAt = 0L
                    var heardSpeech = false
                    var partial = ""
                    val done = StringBuilder() // completed turns
                    var completeAt = 0L // last speechComplete not yet followed by new speech
                    var serverQuietSince = 0L // Meta's speechEnd, not yet followed by speechStart
                    var endSentAt = 0L
                    var result: SttResult? = null
                    var pump: kotlinx.coroutines.Job? = null

                    fun text(): String = listOf(done.toString().trim(), partial.trim())
                        .filter { it.isNotEmpty() }.joinToString(" ")

                    fun endStream() {
                        if (ended.compareAndSet(false, true)) {
                            socket.send("""{"type":"endStream"}""")
                            endSentAt = SystemClock.elapsedRealtime()
                        }
                    }

                    while (result == null) {
                        val tick = withTimeoutOrNull(TICK_MS) { events.receiveCatching() }
                        val now = SystemClock.elapsedRealtime()
                        if (tick != null && tick.isClosed) {
                            result = when {
                                endSentAt != 0L || completeAt != 0L ->
                                    text().takeIf { it.isNotBlank() }?.let { SttResult.Final(it) } ?: SttResult.NoSpeech
                                else -> {
                                    DiagLog.event("Speech", "Meta STT unavailable: ${closeNote.get() ?: "closed"}")
                                    SttResult.Unavailable(heardSpeech)
                                }
                            }
                            break
                        }
                        if (tick != null) {
                            val obj = runCatching { gson.fromJson(tick.getOrNull(), JsonObject::class.java) }.getOrNull()
                            val type = obj?.get("type")?.takeIf { it.isJsonPrimitive }?.asString
                            when {
                                type == null && obj?.has("sessionId") == true -> {
                                    ackAt = now
                                    DiagLog.event("Speech", "Meta STT ready ${now - start} ms")
                                    // Send the backlog recorded while connecting, then live audio
                                    pump = launch {
                                        for (chunk in audio) {
                                            if (ended.get()) break
                                            if (!socket.send(chunk.toByteString())) break
                                        }
                                    }
                                }

                                type == "speechStart" -> {
                                    if (!heardSpeech) DiagLog.event("Speech", "Meta STT speech started ${now - start} ms")
                                    heardSpeech = true
                                    completeAt = 0L // the user goes on talking
                                    serverQuietSince = 0L
                                }

                                type == "speechEnd" -> if (serverQuietSince == 0L) serverQuietSince = now

                                type == "transcript" -> {
                                    val t = obj.get("transcript")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                                    if (t.isNotBlank()) {
                                        heardSpeech = true
                                        partial = t
                                        onPartial(text())
                                    }
                                }

                                type == "speechComplete" -> {
                                    val t = obj.get("transcript")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                                    if (t.isNotBlank()) done.append(t.trim()).append(' ')
                                    partial = ""
                                    if (done.isNotBlank()) onPartial(text())
                                    completeAt = now
                                    if (endSentAt != 0L) {
                                        result = text().takeIf { it.isNotBlank() }?.let { SttResult.Final(it) }
                                            ?: SttResult.NoSpeech
                                    }
                                }

                                type == "error" -> {
                                    // Only the error's type/code, never content
                                    val code = listOf("errorType", "errorCode")
                                        .mapNotNull { obj.get(it)?.takeIf { e -> e.isJsonPrimitive }?.asString }
                                        .joinToString("/")
                                    DiagLog.event("Speech", "Meta STT error ${code.ifBlank { "?" }}")
                                    if (endSentAt == 0L && completeAt == 0L) {
                                        result = SttResult.Unavailable(heardSpeech)
                                    }
                                }
                            }
                            if (result != null) break
                        }

                        // Deadlines and end of speech, checked on every pass. Local loudness
                        // only counts once the mic actually heard the user: a soft voice
                        // that never crosses the threshold must not look like silence.
                        val loudAt = lastLoudAt.get()
                        val localQuiet = if (loudAt != 0L) now - loudAt else -1L
                        val serverQuiet = if (serverQuietSince != 0L) now - serverQuietSince else -1L
                        val quietFor = maxOf(localQuiet, serverQuiet)
                        when {
                            ackAt == 0L && now - start > CONNECT_TIMEOUT_MS -> {
                                DiagLog.event("Speech", "Meta STT unavailable: connect timeout")
                                result = SttResult.Unavailable(lastLoudAt.get() != 0L)
                            }
                            ackAt == 0L -> Unit
                            // A completed turn and the user stays quiet (or the turn is old): done
                            completeAt != 0L && (localQuiet >= END_QUIET_MS || localQuiet < 0L ||
                                now - completeAt >= END_WAIT_MS) -> {
                                result = text().takeIf { it.isNotBlank() }?.let { SttResult.Final(it) }
                                    ?: SttResult.NoSpeech
                            }
                            // Talking stopped but Meta hasn't closed the turn: end the stream
                            heardSpeech && endSentAt == 0L && quietFor >= FORCE_END_QUIET_MS -> endStream()
                            endSentAt != 0L && now - endSentAt > FINAL_WAIT_MS -> {
                                result = text().takeIf { it.isNotBlank() }?.let { SttResult.Final(it) }
                                    ?: SttResult.NoSpeech
                            }
                            !heardSpeech && now - ackAt > NO_SPEECH_TIMEOUT_MS -> {
                                DiagLog.event("Speech", "Meta STT no speech")
                                result = SttResult.NoSpeech
                            }
                            endSentAt == 0L && (now - ackAt > OVERALL_TIMEOUT_MS || now - start > HARD_CAP_MS) ->
                                endStream()
                        }
                    }
                    micJob.cancel()
                    pump?.cancel()
                    audio.close()
                    if (result is SttResult.Final) onSpeechEnd()
                    result ?: SttResult.NoSpeech
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DiagLog.event("Speech", "Meta STT failed ${e.javaClass.simpleName}")
                SttResult.Unavailable()
            } finally {
                audio.close()
                runCatching { recorder.release() }
                runCatching { socket.cancel() }
                events.close()
            }
        }

    private fun handshake(apiKey: String): JsonObject = JsonObject().apply {
        add("authorization", JsonObject().apply { addProperty("accessToken", "Bearer $apiKey") })
        addProperty("audioEncoding", "PCM_24KHZ")
        addProperty("model", MODEL)
        addProperty("mode", "ENDPOINTING")
        addProperty("partialMode", "CUMULATIVE")
        addProperty("emitAudioProgress", false)
        add("languageBias", gson.toJsonTree(listOf("Turkish")))
        // App names the user says; contact names are not sent
        add("keywords", gson.toJsonTree(KEYWORDS))
    }

    companion object {
        private const val ENDPOINT = "wss://api.meta.ai/v1/asr/realtime"
        private const val MODEL = "muse-voice-transcribe-1.0"
        private val KEYWORDS = listOf("WhatsApp", "YouTube", "YouTube Music", "Google", "Instagram", "Spotify")

        private const val CHUNK_MS = 80
        private const val TICK_MS = 100L

        /** Peak (PCM16) that counts as the user talking, for the local end-of-speech check. */
        private const val LOCAL_SPEECH_PEAK = 1_000

        private const val CONNECT_TIMEOUT_MS = 4_000L
        private const val NO_SPEECH_TIMEOUT_MS = 6_000L
        private const val OVERALL_TIMEOUT_MS = 15_000L
        private const val HARD_CAP_MS = 22_000L

        /** After Meta's speechComplete: quiet this long (locally) ends the request... */
        private const val END_QUIET_MS = 700L

        /** ...or this long without a new speechStart, in a noisy room. */
        private const val END_WAIT_MS = 1_200L

        /** Talking stopped this long ago and Meta hasn't closed the turn: end the stream. */
        private const val FORCE_END_QUIET_MS = 1_200L

        /** After endStream, the final text comes within this. */
        private const val FINAL_WAIT_MS = 2_500L
    }
}

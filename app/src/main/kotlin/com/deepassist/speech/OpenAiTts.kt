package com.deepassist.speech

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.PlaybackParams
import com.google.gson.Gson
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenAI TTS client (gpt-4o-mini-tts / marin). Requests raw 24kHz mono PCM and
 * streams it straight into an AudioTrack as it downloads, so speech starts on
 * the first chunk instead of after the whole file is synthesized. Playback is
 * sped up 1.25x via [PlaybackParams] for a deterministic rate independent of
 * how the model itself interprets pacing instructions.
 */
class OpenAiTts(private val apiKeyProvider: () -> String) {

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var currentTrack: AudioTrack? = null

    @Volatile
    private var currentCall: Call? = null

    /**
     * Synthesizes and plays [text]; returns when playback finishes.
     * Returns false only if nothing was played — caller falls back to Android TTS.
     */
    suspend fun speak(text: String): Boolean = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()
        if (apiKey.isBlank() || text.isBlank()) return@withContext false

        var track: AudioTrack? = null
        var playedAnything = false
        try {
            val payload = mapOf(
                "model" to "gpt-4o-mini-tts",
                "input" to text.take(4000),
                "voice" to "marin",
                "response_format" to "pcm", // raw 24kHz mono pcm16 — streamable
                "instructions" to
                    "Türkçe konuş. Doğal, akıcı ve net bir asistan tonuyla seslendir. " +
                    "Telefon numaralarını ve uzun sayı dizilerini rakam rakam, Türkçe olarak oku."
            )
            val request = Request.Builder()
                .url("https://api.openai.com/v1/audio/speech")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
                .build()
            val call = client.newCall(request)
            currentCall = call

            call.execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false
                val source = resp.body?.byteStream() ?: return@withContext false

                val minBuf = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val bufferBytes = maxOf(minBuf * 2, 16384)
                val newTrack = AudioTrack(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                    bufferBytes,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
                )
                runCatching {
                    newTrack.playbackParams = PlaybackParams().setSpeed(PLAYBACK_SPEED)
                }
                currentTrack?.let { runCatching { it.release() } }
                currentTrack = newTrack
                track = newTrack
                newTrack.play()

                // PCM16 frames are 2 bytes; carry any odd tail byte to the next chunk
                val buf = ByteArray(8192)
                var pending = 0
                loop@ while (true) {
                    val read = source.read(buf, pending, buf.size - pending)
                    if (read == -1) break
                    val total = pending + read
                    val writable = total and 0x7FFFFFFE
                    var written = 0
                    while (written < writable) {
                        val n = newTrack.write(buf, written, writable - written)
                        if (n <= 0) break@loop // track released by stop()
                        written += n
                        playedAnything = true
                    }
                    pending = total - writable
                    if (pending > 0) buf[0] = buf[writable]
                }

                // Blocking writes mean at most one buffer remains; let it drain
                runCatching { newTrack.stop() }
                Thread.sleep(minOf(500L, bufferBytes * 1000L / (SAMPLE_RATE * 2)))
            }
            playedAnything
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Mid-playback failure: report success so the caller doesn't repeat
            // the whole sentence over Android TTS
            playedAnything
        } finally {
            if (currentCall?.isCanceled() != true) currentCall = null
            track?.let {
                runCatching { it.release() }
                if (currentTrack === it) currentTrack = null
            }
        }
    }

    fun stop() {
        runCatching { currentCall?.cancel() }
        currentCall = null
        currentTrack?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            runCatching { it.release() }
        }
        currentTrack = null
    }

    companion object {
        private const val SAMPLE_RATE = 24000
        private const val PLAYBACK_SPEED = 1.25f
    }
}

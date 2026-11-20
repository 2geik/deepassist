package com.deepassist.speech

import com.google.gson.Gson
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenAI TTS (tts-1 / nova) behind [CloudTts]. Requests raw 24 kHz mono PCM so
 * playback can start on the first chunk. tts-1 has no speaking-style
 * instructions; the 1.1x pace is set by the player.
 *
 * tts-1 is retired on 6 January 2027 — the replacement provider is one new
 * [CloudTts] class.
 */
class OpenAiTts(private val apiKeyProvider: () -> String) : CloudTts {

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val calls = ConcurrentHashMap.newKeySet<Call>()

    override fun synthesize(text: String): CloudTts.Synthesis = object : CloudTts.Synthesis {
        @Volatile
        private var call: Call? = null

        @Volatile
        private var cancelled = false

        override fun open(): InputStream? {
            val apiKey = apiKeyProvider()
            // After a quota/auth error the on-device voice speaks for the cooldown
            if (apiKey.isBlank() || text.isBlank() || OpenAiGate.isBlocked() || cancelled) return null
            val payload = mapOf(
                "model" to "tts-1",
                "input" to text.take(4000),
                "voice" to "nova",
                "response_format" to "pcm" // raw 24kHz mono pcm16 — streamable
            )
            val request = Request.Builder()
                .url("https://api.openai.com/v1/audio/speech")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
                .build()
            val c = client.newCall(request)
            call = c
            calls.add(c)
            if (cancelled) c.cancel()
            return try {
                val resp = c.execute()
                if (!resp.isSuccessful) {
                    OpenAiGate.fromHttpCode(resp.code)?.let { OpenAiGate.report("tts", it) }
                    resp.close()
                    calls.remove(c)
                    return null
                }
                val body = resp.body ?: run {
                    resp.close()
                    calls.remove(c)
                    return null
                }
                object : java.io.FilterInputStream(body.byteStream()) {
                    override fun close() {
                        calls.remove(c)
                        runCatching { resp.close() }
                    }
                }
            } catch (e: Exception) {
                calls.remove(c)
                null
            }
        }

        override fun cancel() {
            cancelled = true
            call?.let { runCatching { it.cancel() } }
        }
    }

    override fun stop() {
        calls.toList().forEach { runCatching { it.cancel() } }
        calls.clear()
    }
}

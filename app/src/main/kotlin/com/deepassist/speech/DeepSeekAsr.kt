package com.deepassist.speech

import com.deepassist.util.DiagLog
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody

/**
 * DeepSeek speech-to-text — the fallback when OpenAI realtime STT is unavailable
 * (no credit, rejected key, no connection). Takes the recorded 16 kHz mono WAV.
 *
 * The endpoint has no language parameter and now and then writes short Turkish
 * sentences in Devanagari or Arabic script. Such a transcript is dropped (the user
 * is asked to repeat) instead of being sent to the model as a request.
 */
class DeepSeekAsr(private val apiKeyProvider: () -> String) {

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun transcribe(audioFile: File): String? = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()
        if (apiKey.isBlank() || !audioFile.exists()) return@withContext null
        try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("audio/wav".toMediaType()))
                .build()
            val request = Request.Builder()
                .url("https://api.deepseek.com/v1/tools/asr")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body)
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    DiagLog.event("Speech", "DeepSeek ASR HTTP ${resp.code}")
                    return@withContext null
                }
                val json = resp.body?.string() ?: return@withContext null
                val text = gson.fromJson(json, JsonObject::class.java)
                    ?.get("text")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
                    ?.takeIf { it.isNotBlank() } ?: return@withContext null
                if (!isLatinScript(text)) {
                    DiagLog.event("Speech", "DeepSeek ASR dropped non-Latin transcript")
                    return@withContext null
                }
                text
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            DiagLog.event("Speech", "DeepSeek ASR failed ${e.javaClass.simpleName}")
            null
        }
    }

    companion object {
        /** True when most letters are Latin (Turkish included); false for Devanagari, Arabic... */
        fun isLatinScript(text: String): Boolean {
            var letters = 0
            var latin = 0
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                if (Character.isLetter(cp)) {
                    letters++
                    if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN) latin++
                }
                i += Character.charCount(cp)
            }
            return letters == 0 || latin * 10 >= letters * 8
        }
    }
}

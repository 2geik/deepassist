package com.deepassist.speech

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/** Multipart transcription fallback used when the realtime websocket is unavailable. */
class SpeechToText(private val apiKeyProvider: () -> String) {

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun transcribe(audioFile: File, language: String = "tr"): String? =
        withContext(Dispatchers.IO) {
            val apiKey = apiKeyProvider()
            if (apiKey.isBlank() || !audioFile.exists()) return@withContext null

            try {
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("model", "gpt-4o-transcribe")
                    .addFormDataPart("language", language)
                    .addFormDataPart(
                        "file",
                        audioFile.name,
                        audioFile.asRequestBody("audio/wav".toMediaType())
                    )
                    .build()

                val request = Request.Builder()
                    .url("https://api.openai.com/v1/audio/transcriptions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val json = resp.body?.string() ?: return@withContext null
                    val obj = gson.fromJson(json, JsonObject::class.java)
                    obj.get("text")?.asString?.trim()?.takeIf { it.isNotBlank() }
                }
            } catch (e: Exception) {
                null
            }
        }
}

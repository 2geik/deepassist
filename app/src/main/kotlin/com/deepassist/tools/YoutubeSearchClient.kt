package com.deepassist.tools

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * YouTube search via the InnerTube API (`youtubei/v1/search`, WEB client).
 * Free, no user API key. The public InnerTube key is scraped from youtube.com
 * and cached for 6 hours so key rotation is picked up.
 */
object YoutubeSearchClient {

    private const val TAG = "YTSearch"
    private const val USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private const val CLIENT_VERSION = "2.20240319.00.00"
    private const val KEY_TTL_MS = 6 * 60 * 60 * 1000L

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile private var cachedKey: String? = null
    @Volatile private var keyCachedAt = 0L

    data class YoutubeVideoResult(
        val title: String,
        val videoId: String,
        val channel: String
    ) {
        val appUri: String get() = "vnd.youtube://$videoId"
        val watchUrl: String get() = "https://www.youtube.com/watch?v=$videoId"
        val musicUrl: String get() = "https://music.youtube.com/watch?v=$videoId"
    }

    suspend fun search(query: String, maxResults: Int = 5): List<YoutubeVideoResult> =
        withContext(Dispatchers.IO) {
            val key = getApiKey() ?: return@withContext emptyList()
            val body = buildSearchRequest(query)
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/search?key=$key&prettyPrint=false")
                .header("Content-Type", "application/json; charset=utf-8")
                .header("User-Agent", USER_AGENT)
                .header("X-YouTube-Client-Name", "1")
                .header("X-YouTube-Client-Version", CLIENT_VERSION)
                .header("Accept-Language", "tr-TR,tr;q=0.9")
                .post(body)
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "InnerTube HTTP ${response.code}")
                        return@withContext emptyList()
                    }
                    val json = response.body?.string() ?: return@withContext emptyList()
                    parseSearchResults(json, maxResults)
                }
            } catch (e: Exception) {
                Log.e(TAG, "InnerTube error", e)
                emptyList()
            }
        }

    private fun getApiKey(): String? {
        val now = System.currentTimeMillis()
        cachedKey?.let {
            if (now - keyCachedAt < KEY_TTL_MS) return it
            cachedKey = null
        }
        val extracted = fetchKeyFromYouTube()
        if (extracted != null) {
            cachedKey = extracted
            keyCachedAt = now
            return extracted
        }
        Log.w(TAG, "InnerTube key unavailable")
        return null
    }

    private fun fetchKeyFromYouTube(): String? = try {
        val request = Request.Builder()
            .url("https://www.youtube.com/")
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "tr-TR,tr;q=0.9")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val html = response.body?.string() ?: return null
            val match = Regex("\"INNERTUBE_API_KEY\"\\s*:\\s*\"([^\"]+)\"").find(html) ?: return null
            match.groupValues[1].takeIf { it.isNotBlank() && it.startsWith("AIza") }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Key fetch failed", e)
        null
    }

    private fun buildSearchRequest(query: String): String = gson.toJson(
        mapOf(
            "query" to query,
            "context" to mapOf(
                "client" to mapOf(
                    "hl" to "tr",
                    "gl" to "TR",
                    "clientName" to "WEB",
                    "clientVersion" to CLIENT_VERSION,
                    "utcOffsetMinutes" to 180
                )
            )
        )
    )

    private fun parseSearchResults(json: String, maxResults: Int): List<YoutubeVideoResult> {
        val results = mutableListOf<YoutubeVideoResult>()
        try {
            val sections = JsonParser.parseString(json).asJsonObject
                .getAsJsonObject("contents")
                ?.getAsJsonObject("twoColumnSearchResultsRenderer")
                ?.getAsJsonObject("primaryContents")
                ?.getAsJsonObject("sectionListRenderer")
                ?.getAsJsonArray("contents")
                ?: return emptyList()

            for (section in sections) {
                val items = section.asJsonObject
                    .getAsJsonObject("itemSectionRenderer")
                    ?.getAsJsonArray("contents")
                    ?: continue
                for (item in items) {
                    val video = item.asJsonObject.getAsJsonObject("videoRenderer") ?: continue
                    val videoId = video.get("videoId")?.asString ?: continue
                    val title = firstRunText(video.getAsJsonObject("title")) ?: continue
                    val channel = firstRunText(video.getAsJsonObject("ownerText")) ?: ""
                    results.add(YoutubeVideoResult(title, videoId, channel))
                    if (results.size >= maxResults) return results
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Parse error", e)
        }
        return results
    }

    private fun firstRunText(obj: JsonObject?): String? =
        obj?.getAsJsonArray("runs")
            ?.takeIf { it.size() > 0 }
            ?.get(0)?.asJsonObject
            ?.get("text")?.asString
}

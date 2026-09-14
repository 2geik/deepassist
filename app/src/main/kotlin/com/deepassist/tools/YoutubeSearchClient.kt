package com.deepassist.tools

import android.util.Log
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Minimal YouTube InnerTube search client — the same `youtubei/v1/search`
 * endpoint youtube.com uses. Free, no user API key. The public WEB key and
 * client version are scraped from youtube.com and cached for 6 hours so key
 * rotation is picked up; hardcoded fallbacks cover scrape failures.
 */
object YoutubeSearchClient {

    data class Video(
        val id: String,
        val title: String,
        val channel: String?,
        val duration: String?
    )

    private const val TAG = "YoutubeSearch"
    private const val FALLBACK_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    private const val FALLBACK_CLIENT_VERSION = "2.20250901.00.00"
    private const val KEY_TTL_MS = 6 * 60 * 60 * 1000L
    private const val CACHE_TTL_MS = 30 * 60 * 1000L
    private const val CACHE_MAX = 32
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"

    private val TR = Locale.forLanguageTag("tr")

    /** Title markers of alternate versions, dropped unless the query asks for them. */
    private val VARIANT_WORDS = listOf(
        "remix", "cover", "slowed", "sped up", "speed up", "8d", "karaoke", "reverb", "nightcore"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile private var apiKey: String? = null
    @Volatile private var clientVersion: String = FALLBACK_CLIENT_VERSION
    @Volatile private var keyCachedAt = 0L

    // Insertion-ordered so the oldest entry is evicted first.
    private val cache = LinkedHashMap<String, Pair<Long, List<Video>>>()

    /**
     * Cached search so a follow-up call with `pick=N` sees the same ordering
     * the user heard. Blocking — call from Dispatchers.IO.
     */
    fun searchCached(query: String, limit: Int = 10): List<Video> {
        val key = query.trim().lowercase(TR)
        val now = System.currentTimeMillis()
        synchronized(cache) {
            val hit = cache[key]
            if (hit != null) {
                if (now - hit.first < CACHE_TTL_MS) return hit.second
                cache.remove(key)
            }
        }
        val fresh = search(query, limit)
        if (fresh.isNotEmpty()) {
            synchronized(cache) {
                cache[key] = now to fresh
                while (cache.size > CACHE_MAX) cache.remove(cache.keys.first())
            }
        }
        return fresh
    }

    /** Blocking — call from Dispatchers.IO. Returns an empty list on any failure. */
    fun search(query: String, limit: Int = 10): List<Video> {
        val key = currentKey()
        val body = JsonObject().apply {
            add("context", JsonObject().apply {
                add("client", JsonObject().apply {
                    addProperty("clientName", "WEB")
                    addProperty("clientVersion", clientVersion)
                    addProperty("hl", "tr")
                    addProperty("gl", "TR")
                })
            })
            addProperty("query", query)
            addProperty("params", "EgIQAQ==") // filter: type = video
        }
        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/search?key=$key&prettyPrint=false")
            .header("User-Agent", USER_AGENT)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "search HTTP ${resp.code}")
                    if (resp.code == 400 || resp.code == 403) keyCachedAt = 0L
                    return emptyList()
                }
                val text = resp.body?.string() ?: return emptyList()
                val out = mutableListOf<Video>()
                collectVideos(JsonParser.parseString(text), out, limit)
                out
            }
        } catch (e: Exception) {
            Log.w(TAG, "search failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Drops remix/cover/slowed versions when the original is among the results
     * (unless the query asked for one) and collapses duplicate uploads of the
     * same title.
     */
    fun dedupeSmart(query: String, videos: List<Video>): List<Video> {
        val q = query.lowercase(TR)
        val unwanted = VARIANT_WORDS.filter { it !in q }
        val originals = videos.filter { v ->
            val t = v.title.lowercase(TR)
            unwanted.none { it in t }
        }
        return (originals.ifEmpty { videos }).distinctBy { normalizeTitle(it.title) }
    }

    private fun normalizeTitle(title: String): String =
        title.lowercase(TR)
            .replace(Regex("[(\\[].*?[)\\]]"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), "")

    private fun currentKey(): String {
        val now = System.currentTimeMillis()
        apiKey?.let { if (now - keyCachedAt < KEY_TTL_MS) return it }
        try {
            val req = Request.Builder()
                .url("https://www.youtube.com/?hl=tr&gl=TR")
                .header("User-Agent", USER_AGENT)
                .build()
            client.newCall(req).execute().use { resp ->
                val html = resp.body?.string().orEmpty()
                Regex("\"INNERTUBE_API_KEY\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)?.let {
                    apiKey = it
                    keyCachedAt = now
                }
                Regex("\"INNERTUBE_CLIENT_VERSION\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)?.let {
                    clientVersion = it
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "key scrape failed: ${e.message}")
        }
        return apiKey ?: FALLBACK_KEY
    }

    private fun collectVideos(el: JsonElement, out: MutableList<Video>, limit: Int) {
        if (out.size >= limit) return
        when {
            el.isJsonObject -> {
                val obj = el.asJsonObject
                val renderer = obj.get("videoRenderer")
                if (renderer != null && renderer.isJsonObject) {
                    parseVideo(renderer.asJsonObject)?.let { out.add(it) }
                    return
                }
                for ((_, child) in obj.entrySet()) collectVideos(child, out, limit)
            }
            el.isJsonArray -> for (child in el.asJsonArray) collectVideos(child, out, limit)
        }
    }

    private fun parseVideo(vr: JsonObject): Video? {
        val id = vr.get("videoId")?.asString ?: return null
        val title = text(vr.get("title")) ?: return null
        return Video(id, title, text(vr.get("ownerText")), text(vr.get("lengthText")))
    }

    private fun text(el: JsonElement?): String? {
        if (el == null || !el.isJsonObject) return null
        val obj = el.asJsonObject
        obj.get("simpleText")?.let { return it.asString }
        val runs = obj.get("runs")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        return runs.joinToString("") { it.asJsonObject.get("text")?.asString.orEmpty() }.ifBlank { null }
    }
}

package com.deepassist.tools

import android.content.Context
import android.util.Log
import com.deepassist.data.DuckDuckGoResponse
import com.deepassist.data.DuckDuckGoTopic
import com.deepassist.data.SecureStore
import com.deepassist.data.ToolProperty
import com.deepassist.data.ToolResult
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class WebSearchTool : Tool() {

    override val name = "search_web"
    override val description =
        "İnternette arama yapar ve özet sonuçlar döndürür. Güncel bilgiler ve genel bilgi " +
            "soruları için kullanılır."
    override val parameters = mapOf(
        "query" to ToolProperty(
            type = "string",
            description = "Arama sorgusu"
        )
    )
    override val required = listOf("query")
    override val thinkingPhrase: String? = "Tabii, hemen araştırıyorum..."

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private lateinit var secureStore: SecureStore

    override fun initialize(context: Context) {
        super.initialize(context)
        secureStore = SecureStore(this.context)
    }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val query = args.optString("query")?.trim()
        if (query.isNullOrBlank()) {
            return@withContext ToolResult(false, "", error = "Arama sorgusu belirtilmedi.")
        }

        // DuckDuckGo below stays as the keyless fallback (no key, quota used up, outage)
        val tavilyKey = secureStore.tavilyApiKey
        if (tavilyKey.isNotBlank()) {
            val tavily = try {
                searchTavily(query, tavilyKey)
            } catch (e: Exception) {
                Log.w(TAG, "Tavily failed: ${e.message}")
                null
            }
            if (!tavily.isNullOrBlank()) {
                return@withContext ToolResult(true, tavily)
            }
        }

        val instant = try {
            searchInstantAnswers(query)
        } catch (e: Exception) {
            null
        }
        if (!instant.isNullOrBlank()) {
            return@withContext ToolResult(true, instant.take(2500))
        }

        val scraped = try {
            searchHtmlFallback(query)
        } catch (e: Exception) {
            null
        }
        if (!scraped.isNullOrBlank()) {
            return@withContext ToolResult(true, scraped.take(2500))
        }

        ToolResult(true, "\"$query\" için arama sonucu bulunamadı.")
    }

    /**
     * Tavily: real web results with page excerpts plus a short generated answer.
     * A basic search costs 1 credit; the free plan refills 1,000 credits a month.
     */
    private fun searchTavily(query: String, key: String): String? {
        val body = gson.toJson(
            mapOf(
                "query" to query,
                "search_depth" to "basic",
                "max_results" to 5,
                "include_answer" to "basic",
                "include_published_date" to true,
                "country" to "turkey"
            )
        )
        val request = Request.Builder()
            .url("https://api.tavily.com/search")
            .header("Authorization", "Bearer $key")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "Tavily HTTP ${resp.code}") // 432 = monthly credits used up
                return null
            }
            val json = JsonParser.parseString(resp.body?.string() ?: return null).asJsonObject
            val answer = json.get("answer")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
            val sources = json.getAsJsonArray("results")?.mapNotNull { element ->
                val result = element.asJsonObject
                val content = result.get("content")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
                if (content.isEmpty()) return@mapNotNull null
                val title = result.get("title")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
                val date = result.get("published_date")?.takeIf { it.isJsonPrimitive }?.asString
                    ?.take(10)?.let { " ($it)" } ?: ""
                "- $title$date: ${content.take(600)}"
            }.orEmpty()
            if (answer.isEmpty() && sources.isEmpty()) return null

            return buildString {
                if (answer.isNotEmpty()) append("Kısa özet (İngilizce olabilir, Türkçe anlat): $answer\n\n")
                if (sources.isNotEmpty()) append("Kaynaklar:\n${sources.joinToString("\n")}\n\n")
                append(
                    "Cevabı yalnızca bu bilgilere dayandır; kaynaklarda olmayan rakam, tarih veya isim uydurma. " +
                        "Bilgi yetersizse bunu söyle."
                )
            }.take(4000)
        }
    }

    private fun searchInstantAnswers(query: String): String? {
        val url = "https://api.duckduckgo.com/?q=${URLEncoder.encode(query, "UTF-8")}" +
            "&format=json&no_html=1&skip_disambig=1"
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val parsed = gson.fromJson(body, DuckDuckGoResponse::class.java) ?: return null

            val pieces = mutableListOf<String>()
            parsed.Answer?.takeIf { it.isNotBlank() }?.let { pieces.add("Cevap: $it") }
            val abstract = parsed.AbstractText?.takeIf { it.isNotBlank() }
                ?: parsed.Abstract?.takeIf { it.isNotBlank() }
            abstract?.let {
                val source = parsed.AbstractSource?.takeIf { s -> s.isNotBlank() }?.let { s -> " (Kaynak: $s)" } ?: ""
                pieces.add(it + source)
            }
            val topics = flattenTopics(parsed.RelatedTopics)
                .mapNotNull { it.Text?.takeIf { t -> t.isNotBlank() } }
                .take(5)
            if (pieces.isEmpty() && topics.isNotEmpty()) {
                pieces.add("İlgili sonuçlar:")
            }
            pieces.addAll(topics.map { "- $it" })

            return if (pieces.isEmpty()) null else pieces.joinToString("\n")
        }
    }

    private fun flattenTopics(topics: List<DuckDuckGoTopic>?): List<DuckDuckGoTopic> {
        if (topics == null) return emptyList()
        val flat = mutableListOf<DuckDuckGoTopic>()
        for (topic in topics) {
            if (topic.Text != null) flat.add(topic)
            topic.Topics?.let { flat.addAll(flattenTopics(it)) }
        }
        return flat
    }

    private fun searchHtmlFallback(query: String): String? {
        val url = "https://html.duckduckgo.com/html/?q=${URLEncoder.encode(query, "UTF-8")}"
        val request = Request.Builder()
            .url(url)
            .addHeader(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
            )
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val html = resp.body?.string() ?: return null
            val snippetRegex = Regex(
                "class=\"result__snippet\"[^>]*>(.*?)</a>",
                RegexOption.DOT_MATCHES_ALL
            )
            val snippets = snippetRegex.findAll(html)
                .map { cleanHtml(it.groupValues[1]) }
                .filter { it.isNotBlank() }
                .take(5)
                .toList()
            return if (snippets.isEmpty()) null
            else "Arama sonuçları:\n" + snippets.joinToString("\n") { "- $it" }
        }
    }

    private fun cleanHtml(raw: String): String = raw
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#x27;", "'")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private companion object {
        const val TAG = "WebSearch"
    }
}

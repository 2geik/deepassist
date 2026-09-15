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

    // Per-provider budgets keep the whole fallback chain inside the 45 s tool timeout
    private val exaClient = client.newBuilder().callTimeout(12, TimeUnit.SECONDS).build()
    private val tavilyClient = client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()

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

        // Exa first: keyless and the best on real questions in a 10-query test. Tavily
        // covers Exa outages or rate limits; DuckDuckGo rarely answers Turkish queries
        // but needs no key at all.
        runProvider("Exa") { searchExa(query) }?.let {
            return@withContext ToolResult(true, it)
        }
        val tavilyKey = secureStore.tavilyApiKey
        if (tavilyKey.isNotBlank()) {
            runProvider("Tavily") { searchTavily(query, tavilyKey) }?.let {
                return@withContext ToolResult(true, it)
            }
        }

        runProvider("DuckDuckGo") { searchInstantAnswers(query) }?.let {
            return@withContext ToolResult(true, it.take(2500))
        }
        runProvider("DuckDuckGo HTML") { searchHtmlFallback(query) }?.let {
            return@withContext ToolResult(true, it.take(2500))
        }

        ToolResult(true, "\"$query\" için arama sonucu bulunamadı.")
    }

    private inline fun runProvider(provider: String, search: () -> String?): String? =
        try {
            search()?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "$provider failed: ${e.message}")
            null
        }

    /**
     * Exa's hosted MCP server — the keyless search OpenCode uses. One stateless
     * JSON-RPC `tools/call`; the reply arrives as a single SSE `data:` line.
     */
    private fun searchExa(query: String): String? {
        val body = gson.toJson(
            mapOf(
                "jsonrpc" to "2.0",
                "id" to 1,
                "method" to "tools/call",
                "params" to mapOf(
                    "name" to "web_search_exa",
                    "arguments" to mapOf("query" to query, "numResults" to 5)
                )
            )
        )
        val request = Request.Builder()
            .url(EXA_MCP_URL)
            .header("Accept", "application/json, text/event-stream")
            .header("User-Agent", "deepAssist/1.0 (Android)")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        exaClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "Exa HTTP ${resp.code}") // 402/429 = free limit reached
                return null
            }
            val raw = resp.body?.string() ?: return null
            val payload = raw.lineSequence().lastOrNull { it.startsWith("data:") }
                ?.removePrefix("data:")?.trim() ?: raw
            val json = JsonParser.parseString(payload).asJsonObject
            val result = json.getAsJsonObject("result")
            if (result == null || result.get("isError")?.asBoolean == true) {
                Log.w(TAG, "Exa error: ${json.get("error") ?: result}")
                return null
            }
            val text = result.getAsJsonArray("content")
                ?.joinToString("\n") { it.asJsonObject.get("text")?.asString.orEmpty() }
                .orEmpty()
            return formatExa(text)
        }
    }

    /** Keeps each result's title, date and highlights; URLs and authors are noise when read aloud. */
    private fun formatExa(text: String): String? {
        val sources = text.split(EXA_RESULT_START)
            .map { it.trim() }
            .filter { it.startsWith("Title: ") }
            .map { block ->
                block.lineSequence()
                    .filterNot {
                        it.startsWith("URL: ") || it.startsWith("Author: ") ||
                            it == "Published: N/A" || it.trim() == "---"
                    }
                    .joinToString("\n")
                    .replace(BLANK_LINES, "\n")
                    .take(EXA_CHARS_PER_RESULT)
            }
        if (sources.isEmpty()) return null
        return ("Kaynaklar:\n\n" + sources.joinToString("\n\n")).take(EXA_MAX_CHARS) + "\n\n" + GROUNDING
    }

    /**
     * Tavily: web results with page excerpts plus a short generated answer.
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
        tavilyClient.newCall(request).execute().use { resp ->
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
                append(GROUNDING)
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
        const val EXA_MCP_URL = "https://mcp.exa.ai/mcp"
        const val EXA_CHARS_PER_RESULT = 1500
        const val EXA_MAX_CHARS = 6000
        const val GROUNDING =
            "Cevabı yalnızca bu bilgilere dayandır; kaynaklarda olmayan rakam, tarih veya isim uydurma. " +
                "Bilgi yetersizse bunu söyle."
        val EXA_RESULT_START = Regex("(?m)^(?=Title: )")
        val BLANK_LINES = Regex("\n{2,}")
    }
}

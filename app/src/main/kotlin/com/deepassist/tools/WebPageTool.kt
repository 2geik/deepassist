package com.deepassist.tools

import android.util.Log
import com.deepassist.data.ToolProperty
import com.deepassist.data.ToolResult
import com.deepassist.util.DeviceUtils
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
 * Links behind the numbered results of recent search_web calls. The model opens a
 * page by number, so it never has to type (or invent) a URL.
 */
internal object WebResults {

    const val EXA_MCP_URL = "https://mcp.exa.ai/mcp"

    data class Page(val title: String, val url: String)

    private val whitespace = Regex("\\s+")

    private val byQuery = object : LinkedHashMap<String, List<Page>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Page>>?): Boolean = size > 8
    }
    private var last: List<Page> = emptyList()

    @Synchronized
    fun remember(query: String, pages: List<Page>) {
        byQuery[key(query)] = pages
        last = pages
    }

    /** Results of [query] when known, otherwise of the most recent search. */
    @Synchronized
    fun pagesFor(query: String?): List<Page> = query?.let { byQuery[key(it)] } ?: last

    private fun key(query: String) = DeviceUtils.toAsciiTurkce(query).trim().replace(whitespace, " ")
}

class WebPageTool : Tool() {

    override val name = "read_web_page"
    override val description =
        "search_web sonuçlarından birinin sayfasını açıp tam metnini getirir. Arama özetleri soruyu " +
            "cevaplamaya yetmediğinde veya kullanıcı bir haberin, yazının, tarifin detayını istediğinde kullan."
    override val parameters = mapOf(
        "result" to ToolProperty(
            type = "integer",
            description = "search_web sonucundaki sayfa numarası ([1], [2] ...)"
        ),
        "query" to ToolProperty(
            type = "string",
            description = "O sonuçları getiren search_web sorgusu (aynen)"
        )
    )
    override val required = listOf("result")
    override val thinkingPhrase: String? = "Sayfayı okuyorum..."

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val number = args.optInt("result")
            ?: return@withContext ToolResult(false, "", error = "Sayfa numarası (result) belirtilmedi.")
        val pages = WebResults.pagesFor(args.optString("query")?.takeIf { it.isNotBlank() })
        if (pages.isEmpty()) {
            return@withContext ToolResult(false, "", error = "Açılabilecek arama sonucu yok. Önce search_web ile ara.")
        }
        val page = pages.getOrNull(number - 1)
            ?: return@withContext ToolResult(false, "", error = "Geçersiz sonuç numarası; 1 ile ${pages.size} arası olmalı.")
        if (page.url.isBlank()) {
            return@withContext ToolResult(false, "", error = "Bu sonucun bağlantısı yok; arama özetleriyle cevap ver.")
        }

        val text = fetchWithExa(page.url) ?: fetchDirect(page.url)
        Log.d(TAG, "read [$number] ${page.url} → ${text?.length ?: 0} chars")
        if (text.isNullOrBlank()) {
            return@withContext ToolResult(
                false, "",
                error = "\"${page.title}\" sayfası açılamadı; arama özetleriyle cevap ver."
            )
        }
        ToolResult(
            true,
            "SAYFA [$number]: ${page.title}\n\n${text.take(PAGE_MAX_CHARS)}\n\n" +
                "Cevabı bu sayfanın içeriğine dayandır; sayfada olmayan bilgi uydurma. " +
                "Sesli okunacağı için gerekirse özetle."
        )
    }

    /** Exa's keyless `web_fetch_exa`: the page as clean markdown, usually well under a second. */
    private fun fetchWithExa(url: String): String? = try {
        val body = gson.toJson(
            mapOf(
                "jsonrpc" to "2.0",
                "id" to 1,
                "method" to "tools/call",
                "params" to mapOf(
                    "name" to "web_fetch_exa",
                    "arguments" to mapOf("urls" to listOf(url), "maxCharacters" to PAGE_MAX_CHARS)
                )
            )
        )
        val request = Request.Builder()
            .url(WebResults.EXA_MCP_URL)
            .header("Accept", "application/json, text/event-stream")
            .header("User-Agent", "deepAssist/1.0 (Android)")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "Exa fetch HTTP ${resp.code}")
                return@use null
            }
            val raw = resp.body?.string() ?: return@use null
            val payload = raw.lineSequence().lastOrNull { it.startsWith("data:") }
                ?.removePrefix("data:")?.trim() ?: raw
            val result = JsonParser.parseString(payload).asJsonObject.getAsJsonObject("result")
            if (result == null || result.get("isError")?.asBoolean == true) return@use null
            val text = result.getAsJsonArray("content")
                ?.joinToString("\n") { it.asJsonObject.get("text")?.asString.orEmpty() }
                .orEmpty()
            cleanMarkdown(text).takeIf { it.length >= MIN_PAGE_CHARS }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Exa fetch failed: ${e.message}")
        null
    }

    /** Plain download as a fallback; crude, but a menu-laden page beats no page. */
    private fun fetchDirect(url: String): String? = try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_UA)
            .header("Accept-Language", "tr-TR,tr;q=0.9")
            .build()
        client.newCall(request).execute().use { resp ->
            val type = resp.header("Content-Type").orEmpty()
            if (!resp.isSuccessful || !type.contains("html")) return@use null
            htmlToText(resp.body?.string().orEmpty().take(MAX_HTML_CHARS)).takeIf { it.length >= MIN_PAGE_CHARS }
        }
    } catch (e: Exception) {
        Log.w(TAG, "direct fetch failed: ${e.message}")
        null
    }

    private fun cleanMarkdown(markdown: String): String = markdown.lineSequence()
        .filterNot { it.startsWith("URL: ") || it.startsWith("Author: ") }
        .joinToString("\n")
        .replace(MD_IMAGE, "")
        .replace(MD_LINK, "$1")
        .replace(EXTRA_BLANK_LINES, "\n\n")
        .trim()

    private fun htmlToText(html: String): String = html
        .replace(NON_CONTENT, " ")
        .replace(BLOCK_END, "\n")
        .replace(HTML_TAG, " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&#x27;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace(SPACES, " ")
        .replace(LINE_BREAKS, "\n")
        .trim()

    private companion object {
        const val TAG = "WebPage"
        const val PAGE_MAX_CHARS = 7000
        const val MIN_PAGE_CHARS = 200
        const val MAX_HTML_CHARS = 1_500_000
        const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

        val MD_IMAGE = Regex("!\\[[^\\]]*]\\([^)]*\\)")
        val MD_LINK = Regex("\\[([^\\]]*)]\\([^)]*\\)")
        val EXTRA_BLANK_LINES = Regex("\n{3,}")
        val NON_CONTENT = Regex("(?is)<(script|style|noscript|svg|head|nav|footer|form)\\b.*?</\\1>")
        val BLOCK_END = Regex("(?i)<br\\s*/?>|</(p|div|h[1-6]|li|tr|article|section)>")
        val HTML_TAG = Regex("<[^>]+>")
        val SPACES = Regex("[ \\t]+")
        val LINE_BREAKS = Regex("\\s*\n\\s*")
    }
}

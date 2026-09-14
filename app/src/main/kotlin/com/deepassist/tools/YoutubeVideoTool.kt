package com.deepassist.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.deepassist.SessionActivity
import com.deepassist.data.ToolProperty
import com.deepassist.data.ToolResult
import com.google.gson.JsonObject
import java.net.URLEncoder
import java.util.Locale

class YoutubeVideoTool : Tool() {

    override val name = "search_and_play_youtube"
    override val description =
        "YouTube'da video arar ve açar. Her çağrıda GERÇEK arama yapar, sonuçları döndürür. " +
            "Birden fazla sonuç varsa numaralı seçenekleri SESLİ SÖYLER. " +
            "Kullanıcı seçince AYNI query + pick=seçilenNumara ile TEKRAR ÇAĞIR."
    override val parameters = mapOf(
        "query" to ToolProperty(
            type = "string",
            description = "Aranacak video, konu veya içerik. Her çağrıda MUTLAKA doldur."
        ),
        "pick" to ToolProperty(
            type = "integer",
            description = "İSTEĞE BAĞLI. Kullanıcı önceki seçeneklerden birini seçtiyse sıra numarası (1, 2, 3...). İlk aramada GÖNDERME."
        )
    )
    override val required = listOf("query")

    override fun dynamicThinkingPhrase(args: JsonObject): String? {
        if (args.has("pick")) return "Açıyorum..."
        val query = args.optString("query")?.trim() ?: return null
        val shown = if (query.length > 30) query.take(27) + "..." else query
        return "YouTube'da \"$shown\" arıyorum..."
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val query = args.optString("query")?.trim()
        if (query.isNullOrBlank()) {
            return ToolResult(false, "", error = "Arama sorgusu belirtilmedi.")
        }

        val results = YoutubeSearchClient.search(query, 5)
        Log.d(TAG, "Search '$query' → ${results.size} results")

        if (results.isEmpty()) {
            launchUrl("https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8"))
            return ToolResult(
                true,
                "ARAMA SAYFASI: YouTube'da \"$query\" araması açıldı. Sonuçları GÖRMÜYORSUN. Sadece sayfanın açıldığını söyle, tarif etme."
            )
        }

        val seen = HashSet<String>()
        val unique = results.filter { seen.add(titleKey(it.title)) }

        val pick = args.optInt("pick")
        if (pick != null && pick in 1..unique.size) {
            val chosen = unique[pick - 1]
            if (launchVideo(chosen.videoId)) {
                return ToolResult(true, "AÇILDI: ${chosen.title}")
            }
            launchUrl("https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8"))
            return ToolResult(true, "YouTube'da \"$query\" araması açıldı.")
        }

        if (unique.size == 1) {
            val video = unique.first()
            if (launchVideo(video.videoId)) {
                return ToolResult(true, "AÇILDI: ${video.title}")
            }
        }

        val options = unique.mapIndexed { i, r -> "${i + 1}. ${r.title} — ${r.channel}" }.joinToString("\n")
        return ToolResult(
            true,
            "SEÇENEKLER ($query):\n\n$options\n\nKullanıcıya SESLİ SÖYLE: '1: ...', '2: ...' diye. " +
                "Kullanıcı seçince search_and_play_youtube'u AYNI query + pick=seçilenNumara ile TEKRAR ÇAĞIR."
        )
    }

    private fun titleKey(title: String): String =
        title.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9ğüşıöçĞÜŞİÖÇ]"), "").take(40)

    private fun launchVideo(videoId: String): Boolean =
        launch("vnd.youtube://$videoId") ||
            launch("https://www.youtube.com/watch?v=$videoId", YOUTUBE_PACKAGE) ||
            launch("https://www.youtube.com/watch?v=$videoId")

    private fun launchUrl(url: String): Boolean =
        launch(url, YOUTUBE_PACKAGE) || launch(url)

    /** Launches from SessionActivity when available — Android 14+ blocks background starts. */
    private fun launch(uri: String, pkg: String? = null): Boolean = try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pkg != null) intent.setPackage(pkg)
        val ctx: Context = SessionActivity.instance ?: context
        ctx.startActivity(intent)
        Log.d(TAG, "✓ $uri")
        true
    } catch (e: Exception) {
        Log.w(TAG, "✗ $uri — ${e.message}")
        false
    }

    companion object {
        private const val TAG = "YTV"
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    }
}

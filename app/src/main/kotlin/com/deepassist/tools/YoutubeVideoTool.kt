package com.deepassist.tools

import android.content.Intent
import android.net.Uri
import com.deepassist.data.ToolProperty
import com.deepassist.data.ToolResult
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class YoutubeVideoTool : Tool() {

    override val name = "search_and_play_youtube"
    override val description =
        "YouTube'da video arar ve açar (belgesel, radyo tiyatrosu, podcast, vlog, ders vb.; müzik için " +
            "play_youtube_music kullan). pick verilmezse sadece sonuç listesini döndürür, hiçbir şey açmaz. " +
            "Kullanıcı seçince AYNI query ve pick=sıra numarası ile tekrar çağır. ASLA URL uydurma."
    override val parameters = mapOf(
        "query" to ToolProperty(
            type = "string",
            description = "YouTube arama sorgusu"
        ),
        "pick" to ToolProperty(
            type = "integer",
            description = "Kullanıcının seçtiği sonucun sıra numarası (1'den başlar). Sadece seçim yapıldıysa ver."
        )
    )
    override val required = listOf("query")
    override val thinkingPhrase: String? = "YouTube'da arıyorum..."
    override val waitForSpeech = true

    override fun dynamicThinkingPhrase(args: JsonObject): String? =
        if (args.optInt("pick") != null) "Açıyorum..." else thinkingPhrase

    override suspend fun execute(args: JsonObject): ToolResult {
        val query = args.optString("query")?.trim().orEmpty()
        if (query.isBlank()) return ToolResult(false, "", error = "Arama sorgusu boş.")
        val pick = args.optInt("pick")

        val results = withContext(Dispatchers.IO) {
            YoutubeSearchClient.searchCached(query)
        }.take(MAX_OPTIONS)

        if (results.isEmpty()) {
            val url = "https://www.youtube.com/results?search_query=${Uri.encode(query)}"
            return if (launchFromAssistant(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))) {
                ToolResult(
                    true,
                    "ARAMA SAYFASI: \"$query\" için YouTube arama sayfası açıldı. Hiçbir video açılmadı ve sonuçları göremiyorsun."
                )
            } else {
                ToolResult(false, "", error = "YouTube araması yapılamadı ve YouTube açılamadı.")
            }
        }

        if (pick == null && results.size > 1) {
            val list = results.mapIndexed { i, v ->
                buildString {
                    append("${i + 1}. ${v.title}")
                    v.channel?.let { append(" — $it") }
                    v.duration?.let { append(" ($it)") }
                }
            }.joinToString("\n")
            return ToolResult(
                true,
                "\"$query\" için bulunan videolar (henüz hiçbiri AÇILMADI). Başlıkları sıra numarasıyla kısaca oku, " +
                    "hangisini açmak istediğini sor:\n$list"
            )
        }

        val video = results.getOrNull((pick ?: 1) - 1)
            ?: return ToolResult(false, "", error = "$pick numaralı sonuç yok, ${results.size} sonuç var.")

        val opened = launchFromAssistant(
            context,
            Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube://${video.id}")).setPackage(YOUTUBE_PACKAGE)
        ) || launchFromAssistant(
            context,
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=${video.id}"))
        )
        return if (opened) {
            ToolResult(true, "Video açıldı: ${video.title}")
        } else {
            ToolResult(false, "", error = "Video açılamadı.")
        }
    }

    companion object {
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        private const val MAX_OPTIONS = 5
    }
}

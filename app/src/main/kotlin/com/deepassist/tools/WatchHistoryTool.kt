package com.deepassist.tools

import com.deepassist.data.ToolProperty
import com.deepassist.data.ToolResult
import com.deepassist.data.WatchHistoryStore
import com.google.gson.JsonObject
import java.text.SimpleDateFormat
import java.util.Locale

class WatchHistoryTool : Tool() {

    override val name = "watch_history"
    override val description =
        "Daha önce YouTube'da açılan videoların ve YouTube Music'te çalınan müziklerin geçmişini getirir. " +
            "'Bunu izlemiş miydim', 'dün ne izledim', 'en son hangi radyo tiyatrosunu dinledim' gibi sorular için."
    override val parameters = mapOf(
        "query" to ToolProperty(
            type = "string",
            description = "İSTEĞE BAĞLI. Sorulan içeriğin adı. Boş bırakılırsa son izlenenler gelir."
        ),
        "limit" to ToolProperty(
            type = "integer",
            description = "İSTEĞE BAĞLI. Kaç kayıt (varsayılan 10)"
        )
    )
    override val required = emptyList<String>()

    private val dateFormat = SimpleDateFormat("d MMMM HH:mm", Locale("tr", "TR"))

    override suspend fun execute(args: JsonObject): ToolResult {
        val entries = WatchHistoryStore.get(context).list()
        if (entries.isEmpty()) return ToolResult(true, "Kayıtlı izleme geçmişi yok.")
        val since = dateFormat.format(entries.last().watchedAt)

        val query = args.optString("query")?.trim()?.takeIf { it.isNotEmpty() }
        val limit = args.optInt("limit")?.coerceIn(1, 30) ?: 10
        val matched = if (query == null) {
            entries
        } else {
            val words = TitleMatcher.specificWords(query).ifEmpty { TitleMatcher.words(query) }
            entries.filter { TitleMatcher.matches(words, it.title) }
        }
        if (matched.isEmpty()) {
            return ToolResult(
                true,
                "\"$query\" ile eşleşen kayıt yok; izlenmemiş görünüyor. Geçmiş $since tarihinden beri tutuluyor."
            )
        }

        val listing = matched.take(limit).joinToString("\n") { e ->
            val kind = if (e.kind == WatchHistoryStore.KIND_MUSIC) "Müzik" else "Video"
            "- [${dateFormat.format(e.watchedAt)}] $kind: ${e.title} — ${e.channel}"
        }
        return ToolResult(true, "İzleme geçmişi (en yeni önce, $since tarihinden beri tutuluyor):\n$listing")
    }
}

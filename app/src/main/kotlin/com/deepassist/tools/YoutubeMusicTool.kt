package com.deepassist.tools

import android.content.Intent
import android.net.Uri
import com.deepassist.data.ToolProperty
import com.deepassist.data.ToolResult
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class YoutubeMusicTool : Tool() {

    override val name = "play_youtube_music"
    override val description =
        "YouTube Music'te şarkı, sanatçı, albüm veya tür çalar. Sorgu 1-2 kelimeyse (sanatçı/tür) o sonuçtan " +
            "karışık radyo başlatır; şarkı adı da varsa doğrudan o şarkıyı açar. Sonuçta en fazla 3 seçenek döner; " +
            "kullanıcı başka versiyon isterse AYNI query ve pick ile tekrar çağır. Oynat/durdur/sonraki için control_media kullan."
    override val parameters = mapOf(
        "query" to ToolProperty(
            type = "string",
            description = "Sanatçı, şarkı, albüm veya tür. Sadece sanatçı istendiyse sadece sanatçı adı."
        ),
        "pick" to ToolProperty(
            type = "integer",
            description = "Önceki sonuçlardan kullanıcının seçtiği sıra numarası (1'den başlar). Sadece seçim yapıldıysa ver."
        )
    )
    override val required = listOf("query")
    override val thinkingPhrase: String? = "Açıyorum..."
    override val waitForSpeech = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val query = args.optString("query")?.trim().orEmpty()
        if (query.isBlank()) return ToolResult(false, "", error = "Ne çalınacağı belirtilmedi.")
        val pick = args.optInt("pick")

        val results = withContext(Dispatchers.IO) {
            YoutubeSearchClient.dedupeSmart(query, YoutubeSearchClient.searchCached(query))
        }.take(MAX_OPTIONS)

        if (results.isEmpty()) {
            val url = "https://music.youtube.com/search?q=${Uri.encode(query)}"
            val opened = launchFromAssistant(
                context, Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(YT_MUSIC_PACKAGE)
            ) || launchFromAssistant(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            return if (opened) {
                ToolResult(
                    true,
                    "ARAMA SAYFASI: \"$query\" için YouTube Music arama sayfası açıldı. Hiçbir şarkı çalmadı ve sonuçları göremiyorsun."
                )
            } else {
                ToolResult(false, "", error = "YouTube Music araması yapılamadı.")
            }
        }

        val index = (pick ?: 1) - 1
        val song = results.getOrNull(index)
            ?: return ToolResult(false, "", error = "$pick numaralı seçenek yok, ${results.size} seçenek var.")
        val radio = pick == null && query.split(Regex("\\s+")).size <= 2

        if (!open(song.id, radio)) {
            return ToolResult(false, "", error = "YouTube Music açılamadı.")
        }

        val playing = if (radio) "${song.title} ile başlayan karışık liste" else song.title
        val others = results.withIndex()
            .filter { it.index != index }
            .joinToString("; ") { "${it.index + 1}. ${it.value.title}" }
        return ToolResult(
            true,
            "Çalıyor: $playing." +
                if (others.isNotEmpty()) " (Sadece kullanıcı başka versiyon isterse söyle: $others)" else ""
        )
    }

    /** YT Music app → YT Music scheme → browser. Never `vnd.youtube://`, which opens plain YouTube. */
    private fun open(videoId: String, radio: Boolean): Boolean {
        val params = if (radio) "v=$videoId&list=RDAMVM$videoId" else "v=$videoId"
        val webUrl = "https://music.youtube.com/watch?$params"
        return launchFromAssistant(
            context, Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)).setPackage(YT_MUSIC_PACKAGE)
        ) || launchFromAssistant(
            context, Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube.music://watch?$params"))
        ) || launchFromAssistant(
            context, Intent(Intent.ACTION_VIEW, Uri.parse(webUrl))
        )
    }

    companion object {
        private const val YT_MUSIC_PACKAGE = "com.google.android.apps.youtube.music"
        private const val MAX_OPTIONS = 3
    }
}

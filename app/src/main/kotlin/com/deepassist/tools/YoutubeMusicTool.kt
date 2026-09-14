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
import java.util.concurrent.ConcurrentHashMap

class YoutubeMusicTool : Tool() {

    override val name = "play_youtube_music"
    override val description =
        "YouTube Music'te müzik arar ve çalmaya başlar. Her çağrıda GERÇEK arama yapar. " +
            "SADECE sanatçı → KISA query (shuffle/radyo). Şarkı adı da varsa → UZUN query (ilk sonucu direkt açar, SEÇENEK SUNMAZ). " +
            "SADECE genel/çok anlamlı aramalarda en fazla 3 seçenek sunar. " +
            "Kullanıcı seçince AYNI query + pick=numarayla TEKRAR ÇAĞIR."
    override val parameters = mapOf(
        "query" to ToolProperty(
            type = "string",
            description = "Sanatçı: 'Motive', 'Duman'. Şarkı: 'Yung Ouzo Yanılmışım', 'biliyorum evlisin'."
        ),
        "pick" to ToolProperty(
            type = "integer",
            description = "İSTEĞE BAĞLI. Kullanıcının seçtiği sıra numarası (1,2,3). İlk aramada GÖNDERME."
        )
    )
    override val required = listOf("query")

    // Options offered to the user, keyed by query, so pick=N opens exactly what was read out.
    private val resultCache = ConcurrentHashMap<String, List<YoutubeSearchClient.YoutubeVideoResult>>()
    private val cacheKeys = LinkedHashSet<String>()

    @Synchronized
    private fun cachePut(key: String, value: List<YoutubeSearchClient.YoutubeVideoResult>) {
        if (cacheKeys.size >= CACHE_MAX) {
            val oldest = cacheKeys.firstOrNull() ?: return
            resultCache.remove(oldest)
            cacheKeys.remove(oldest)
        }
        resultCache[key] = value
        cacheKeys.remove(key)
        cacheKeys.add(key)
    }

    @Synchronized
    private fun cacheGet(key: String): List<YoutubeSearchClient.YoutubeVideoResult>? = resultCache[key]

    @Synchronized
    private fun cacheRemove(key: String) {
        resultCache.remove(key)
        cacheKeys.remove(key)
    }

    override fun dynamicThinkingPhrase(args: JsonObject): String? {
        if (args.has("pick")) return "Açıyorum..."
        val query = args.optString("query")?.trim() ?: return null
        val shown = if (query.length > 30) query.take(27) + "..." else query
        return "YouTube Music'te \"$shown\" arıyorum..."
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val query = args.optString("query")?.trim()
        if (query.isNullOrBlank()) {
            return ToolResult(false, "", error = "Arama sorgusu belirtilmedi.")
        }
        val isArtist = looksLikeArtistName(query)

        val pick = args.optInt("pick")
        if (pick != null) {
            val cached = cacheGet(query)
            if (cached != null && pick in 1..cached.size) {
                val chosen = cached[pick - 1]
                val url = if (isArtist) "${chosen.musicUrl}&list=RDAMVM${chosen.videoId}" else chosen.musicUrl
                if (launchMusicUrl(url)) {
                    cacheRemove(query)
                    return ToolResult(true, "AÇILDI: YouTube Music — ${chosen.title}")
                }
            }
            cacheRemove(query)
        }

        val results = YoutubeSearchClient.search(query, 6)
        Log.d(TAG, "Search '$query' → ${results.size} results, artist=$isArtist")

        if (results.isEmpty()) {
            launchMusicUrl("https://music.youtube.com/search?q=" + URLEncoder.encode(query, "UTF-8"))
            return ToolResult(
                true,
                "ARAMA SAYFASI: YouTube Music'te \"$query\" araması açıldı. Sonuçları GÖRMÜYORSUN. Sadece sayfanın açıldığını söyle."
            )
        }

        val deduped = dedupeSmart(results, query)

        if (isArtist) {
            val top = deduped.first()
            if (launchMusicUrl("${top.musicUrl}&list=RDAMVM${top.videoId}")) {
                return ToolResult(true, "AÇILDI: YouTube Music radyo — $query. İlk: ${top.title}.")
            }
        }

        if (deduped.size == 1 || !isArtist) {
            val best = deduped.first()
            if (launchMusicVideo(best.videoId)) {
                return ToolResult(true, "AÇILDI: YouTube Music — ${best.title}.")
            }
            launchMusicUrl("https://music.youtube.com/search?q=" + URLEncoder.encode(query, "UTF-8"))
            return ToolResult(true, "YouTube Music'te \"$query\" araması açıldı.")
        }

        val top3 = deduped.take(3)
        val options = top3.mapIndexed { i, r -> "${i + 1}. ${r.title} — ${r.channel}" }.joinToString("\n")
        cachePut(query, top3)
        val more = if (deduped.size > 3) {
            "\n\n${deduped.size - 3} alternatif daha var. Kullanıcı 'diğerleri' derse söyle."
        } else ""
        return ToolResult(
            true,
            "SEÇENEKLER (YouTube Music — $query):\n\n$options$more\n\n" +
                "Kullanıcıya SESLİ SÖYLE (başlık + numara). Seçince play_youtube_music'i AYNI query + pick=seçilenNumara ile TEKRAR ÇAĞIR."
        )
    }

    /**
     * Drops duplicate uploads, and — when the top hit clearly matches the query —
     * remix/cover/slowed variants of that same song.
     */
    private fun dedupeSmart(
        results: List<YoutubeSearchClient.YoutubeVideoResult>,
        query: String
    ): List<YoutubeSearchClient.YoutubeVideoResult> {
        val seen = HashSet<String>()
        val basic = results.filter {
            seen.add(it.title.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9ğüşıöçĞÜŞİÖÇ]"), "").take(40))
        }
        if (basic.size <= 1) return basic

        val first = basic.first()
        val queryWords = query.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.length > 2 }
        val firstTitle = first.title.lowercase(Locale.ROOT)
        val matchScore = queryWords.count { firstTitle.contains(it) }
        val isGoodMatch = matchScore >= queryWords.size * 0.6 ||
            firstTitle.contains(query.lowercase(Locale.ROOT))
        if (!isGoodMatch) return basic

        val firstBase = firstTitle
            .replace(Regex("\\s*\\(.*?\\)\\s*"), "")
            .replace(Regex("\\s*\\[.*?\\]\\s*"), "")
            .trim()
        val basePrefix = firstBase.take(15).lowercase(Locale.ROOT)
        val remixWords = listOf(
            "remix", "edit", "version", "cover", "mix", "slow", "sped up", "reverb",
            "slowed", "nightcore", "instrumental", "acoustic", "live", "bootleg", "flip"
        )
        val significantOthers = basic.drop(1).filter { r ->
            val title = r.title.lowercase(Locale.ROOT)
            val isVariant = remixWords.any { title.contains(it) }
            !(isVariant && title.contains(basePrefix))
        }
        return listOf(first) + significantOthers
    }

    private fun looksLikeArtistName(query: String): Boolean {
        val clean = query.trim()
        if (clean.length > 22 || clean.split(Regex("\\s+")).size > 2) return false
        val markers = listOf(
            "şarkı", "official", "video", "sözleri", "söz", "remix", "live", "akustik", "cover",
            "version", "çeviri", "lyrics", "music", "müzik", "konser", "performans", "yanılmışım"
        )
        val lower = clean.lowercase(Locale.ROOT)
        return markers.none { lower.contains(it) }
    }

    /** YT Music app → YT Music scheme → browser. Never `vnd.youtube://`, which opens plain YouTube. */
    private fun launchMusicVideo(videoId: String): Boolean {
        val url = "https://music.youtube.com/watch?v=$videoId"
        return launch(url, YT_MUSIC_PACKAGE) ||
            launch("vnd.youtube.music://$videoId") ||
            launch(url)
    }

    private fun launchMusicUrl(url: String): Boolean =
        launch(url, YT_MUSIC_PACKAGE) || launch(url)

    /** Launches from SessionActivity when available — Android 14+ blocks background starts. */
    private fun launch(uri: String, pkg: String? = null): Boolean = try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pkg != null) intent.setPackage(pkg)
        val ctx: Context = SessionActivity.instance ?: context
        ctx.startActivity(intent)
        Log.d(TAG, "✓ $uri" + if (pkg != null) " via $pkg" else "")
        true
    } catch (e: Exception) {
        Log.w(TAG, "✗ $uri — ${e.message}")
        false
    }

    companion object {
        private const val TAG = "YTM"
        private const val YT_MUSIC_PACKAGE = "com.google.android.apps.youtube.music"
        private const val CACHE_MAX = 32
    }
}

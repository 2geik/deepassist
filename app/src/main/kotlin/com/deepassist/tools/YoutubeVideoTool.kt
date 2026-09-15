package com.deepassist.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.deepassist.SessionActivity
import com.deepassist.data.ToolProperty
import com.deepassist.data.ToolResult
import com.deepassist.data.WatchHistoryStore
import com.deepassist.tools.YoutubeSearchClient.YoutubeVideoResult
import com.google.gson.JsonObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

class YoutubeVideoTool : Tool() {

    override val name = "search_and_play_youtube"
    override val description =
        "YouTube'da video arar ve açar. Her çağrıda GERÇEK arama yapar, sonuçları döndürür. " +
            "Daha önce izlenen videoları genel aramalarda otomatik gizler. " +
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
        ),
        "include_watched" to ToolProperty(
            type = "boolean",
            description = "İSTEĞE BAĞLI. Kullanıcı daha önce izlediği BELİRLİ bir içeriği adıyla istiyorsa ya da " +
                "'tekrar/yine izlemek istiyorum' diyorsa true. Genel aramalarda (ör. 'polisiye radyo tiyatrosu') GÖNDERME."
        ),
        "mark_offered_watched" to ToolProperty(
            type = "boolean",
            description = "İSTEĞE BAĞLI. Kullanıcı az önce okunan seçenekleri zaten izlediğini söylerse " +
                "('bunların hepsini izledim') AYNI query ile true gönder: o seçenekler izlendi sayılır ve yeni sonuçlar gelir."
        )
    )
    override val required = listOf("query")

    private data class Option(val video: YoutubeVideoResult, val watchedAt: Long?)

    private data class Options(val list: List<Option>, val hidden: Int, val allWatched: Boolean)

    // Options read out to the user, keyed by query, so pick=N opens exactly what was offered.
    private val offered = object : LinkedHashMap<String, List<Option>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Option>>?): Boolean =
            size > CACHE_MAX
    }

    private val dateFormat = SimpleDateFormat("d MMMM", Locale("tr", "TR"))

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
        val history = WatchHistoryStore.get(context)
        val key = TitleMatcher.fold(query).replace(Regex("\\s+"), " ")

        val markWatched = isTrue(args, "mark_offered_watched")
        val pick = if (markWatched) null else args.optInt("pick")
        if (markWatched) {
            val previous = synchronized(offered) { offered.remove(key) }
            previous?.forEach {
                history.record(it.video.videoId, it.video.title, it.video.channel, query, WatchHistoryStore.KIND_VIDEO)
            }
        } else if (pick != null) {
            val cached = synchronized(offered) { offered[key] }
            if (cached != null && pick in 1..cached.size) return open(cached[pick - 1], query, history)
        }

        val results = YoutubeSearchClient.search(query, SEARCH_POOL)
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
        val includeWatched = isTrue(args, "include_watched") ||
            TitleMatcher.REWATCH_PHRASES.any { TitleMatcher.fold(query).contains(it) }
        val options = buildOptions(query, unique, history, includeWatched)
        synchronized(offered) { offered[key] = options.list }

        if (pick != null && pick in 1..options.list.size) {
            return open(options.list[pick - 1], query, history)
        }
        if (unique.size == 1) return open(options.list.first(), query, history)

        val listing = options.list.mapIndexed { i, o ->
            val mark = o.watchedAt?.let { " (DAHA ÖNCE İZLENDİ: ${dateFormat.format(it)})" } ?: ""
            "${i + 1}. ${o.video.title} — ${o.video.channel}$mark"
        }.joinToString("\n")
        val notes = buildString {
            if (options.allWatched) {
                append(
                    "\n\nDİKKAT: Bu aramadaki sonuçların HEPSİ daha önce izlenmiş. Kullanıcıya bunu söyle; " +
                        "farklı bir arama öner ya da tekrar izlemek isterse bu listeden seçtir."
                )
            } else {
                if (options.hidden > 0) append("\n\nDaha önce izlenmiş ${options.hidden} sonuç listeden çıkarıldı.")
                if (options.list.any { it.watchedAt != null }) {
                    append("\nİşaretli seçeneği okurken 'bunu daha önce izlemiştin' de.")
                }
            }
        }
        return ToolResult(
            true,
            "SEÇENEKLER ($query):\n\n$listing$notes\n\nKullanıcıya SESLİ SÖYLE: '1: ...', '2: ...' diye. " +
                "Kullanıcı seçince search_and_play_youtube'u AYNI query + pick=seçilenNumara ile TEKRAR ÇAĞIR."
        )
    }

    /**
     * Hides watched videos unless the request names them (most of its specific words
     * are in the title) or asks for a re-watch. When everything is watched, all are
     * offered and flagged instead of showing nothing.
     */
    private fun buildOptions(
        query: String,
        unique: List<YoutubeVideoResult>,
        history: WatchHistoryStore,
        includeWatched: Boolean
    ): Options {
        val specific = TitleMatcher.specificWords(query)
        val all = unique.map { Option(it, history.lastWatched(it.videoId)?.watchedAt) }
        val visible = all.filter {
            it.watchedAt == null || includeWatched || TitleMatcher.matches(specific, it.video.title)
        }
        return if (visible.isNotEmpty()) {
            Options(visible.take(MAX_OPTIONS), hidden = all.size - visible.size, allWatched = false)
        } else {
            Options(all.take(MAX_OPTIONS), hidden = 0, allWatched = true)
        }
    }

    private fun open(option: Option, query: String, history: WatchHistoryStore): ToolResult {
        val video = option.video
        if (launchVideo(video.videoId)) {
            history.record(video.videoId, video.title, video.channel, query, WatchHistoryStore.KIND_VIDEO)
            val before = option.watchedAt?.let {
                " (Bu video daha önce ${dateFormat.format(it)} tarihinde izlenmişti; kısaca belirt.)"
            } ?: ""
            return ToolResult(true, "AÇILDI: ${video.title}$before")
        }
        launchUrl("https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8"))
        return ToolResult(true, "YouTube'da \"$query\" araması açıldı.")
    }

    private fun isTrue(args: JsonObject, key: String): Boolean =
        runCatching { args.get(key)?.asBoolean == true }.getOrDefault(false)

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
        private const val SEARCH_POOL = 20
        private const val MAX_OPTIONS = 5
        private const val CACHE_MAX = 32
    }
}

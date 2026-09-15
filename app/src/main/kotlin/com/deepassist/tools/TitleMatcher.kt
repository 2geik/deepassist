package com.deepassist.tools

import com.deepassist.util.DeviceUtils

/** Loose Turkish title matching for the watch history: ASCII-folded words, suffix-tolerant. */
internal object TitleMatcher {

    private val apostrophes = Regex("['’`]")
    private val separators = Regex("[^a-z0-9]+")

    /** Command and genre words — they say nothing about WHICH video is meant. */
    private val GENERIC_WORDS = setOf(
        "youtube", "youtubedan", "youtubeden", "acar", "acsana", "misin", "musun", "lutfen", "bana",
        "bir", "iki", "ile", "icin", "adli", "isimli", "olan", "bul", "oynat", "baslat", "izle",
        "dinle", "goster", "video", "radyo", "radio", "tiyatro", "piyes", "oyun", "polisiye",
        "yabanci", "yerli", "turk", "arkasi", "yarin", "gerilim", "gizem", "korku", "dram",
        "komedi", "komik", "macera", "belgesel", "film", "dizi", "bolum", "full", "parca", "hikaye",
        "masal", "sesli", "kitap", "sarki", "turku", "muzik", "klip", "canli", "yeni", "eski",
        "guzel", "farkli", "baska", "diger", "sahne", "klasik", "kisa", "uzun", "program",
        "haber", "ders", "tekrar", "yine", "yeniden", "daha", "once", "onceki", "sonraki",
        "devam", "kaldigim", "yerden"
    )

    /** Phrases asking to watch something again. */
    val REWATCH_PHRASES = listOf("tekrar", "yine", "yeniden", "bir daha", "kaldigim yerden")

    fun fold(s: String): String = DeviceUtils.toAsciiTurkce(s)

    fun words(s: String): List<String> =
        fold(s).replace(apostrophes, "").split(separators).filter { it.length >= 3 }

    /** Words of a request that could name a specific title ("Ayak İzleri", an author). */
    fun specificWords(query: String): List<String> =
        words(query).filter { w -> GENERIC_WORDS.none { g -> w == g || (g.length >= 4 && w.startsWith(g)) } }
            .distinct()

    fun wordMatches(a: String, b: String): Boolean =
        a == b || (minOf(a.length, b.length) >= 4 && (a.startsWith(b) || b.startsWith(a)))

    /** True when at least 60% of [queryWords] appear in [title]. */
    fun matches(queryWords: List<String>, title: String): Boolean {
        if (queryWords.isEmpty()) return false
        val titleWords = words(title)
        val hits = queryWords.count { q -> titleWords.any { wordMatches(q, it) } }
        return hits >= 1 && hits * 10 >= queryWords.size * 6
    }
}

package com.deepassist.agent

import java.util.Locale

/**
 * Pure helpers for learned shortcuts: turning an agent run into a short recipe
 * (no typed text, no answers, no message previews) and finding the recipe that
 * fits a new goal. Unit-tested; storage lives in [ShortcutStore].
 */
object Recipes {

    private val tr = Locale("tr", "TR")
    private val stopWords = setOf(
        "bir", "bu", "şu", "ve", "ile", "de", "da", "mi", "mı", "mu", "mü", "bana", "benim", "lütfen",
        "için", "gibi", "kadar", "olan", "en", "çok", "biraz", "şimdi", "hemen", "uygulamasında", "uygulamasından",
        "misin", "mısın", "musun", "müsün", "misiniz", "mısınız"
    )

    /** Step lines of a successful run → recipe lines, e.g. `Dokun "Ekran ve parlaklık"`. */
    fun fromSteps(steps: List<String>): List<String> = steps.mapNotNull { line ->
        val body = line.substringAfter(". ", line)
        when {
            body.startsWith("Uygulama aç") && body.contains("açıldı") ->
                "Uygulama aç " + Regex("\"([^\"]+)\"").find(body)?.value.orEmpty()
            body.startsWith("Dokun ") && body.endsWith("dokunuldu") ->
                "Dokun \"" + firstPart(Regex("\"([^\"]*)\"").find(body)?.groupValues?.get(1).orEmpty()) + "\""
            body.startsWith("Yaz ") && body.endsWith("yazıldı") -> "Yazı alanına istenen metni yaz"
            body.startsWith("Kaydır ") && body.endsWith("kaydırıldı") -> body.substringBefore(" →")
            body.startsWith("Klavyeden onayla") && body.endsWith("yapıldı") -> "Klavyeden onayla"
            body.startsWith("Geri") && body.endsWith("yapıldı") -> "Geri"
            body.startsWith("Onaylanan ") && body.endsWith("dokunuldu") -> "Onay alıp gönder/öde düğmesine dokun"
            else -> null
        }
    }.filter { it.isNotBlank() && it != "Dokun \"\"" }

    /** Rows merge child texts with " – "; the first part is the title, the rest may be a message preview. */
    private fun firstPart(label: String) = label.substringBefore(" – ").take(60)

    fun keywords(goal: String): Set<String> =
        goal.lowercase(tr)
            .replace(Regex("'[\\p{L}]+"), "") // Ayarlar'dan → ayarlar
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .map { fold(it) }
            .filter { it.length > 1 && it !in stopWords.map(::fold) }
            .map { it.take(5) } // crude Turkish stemming: büyüt/büyütür/büyütsene → buyut
            .toSet()

    /** Share of keywords the two goals have in common (Jaccard). */
    fun similarity(a: String, b: String): Double {
        val ka = keywords(a)
        val kb = keywords(b)
        if (ka.isEmpty() || kb.isEmpty()) return 0.0
        return ka.intersect(kb).size.toDouble() / ka.union(kb).size
    }

    private fun fold(s: String) = s
        .replace('ç', 'c').replace('ğ', 'g').replace('ı', 'i').replace('ö', 'o').replace('ş', 's').replace('ü', 'u')
}

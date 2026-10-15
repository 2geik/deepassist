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
        "misin", "mısın", "musun", "müsün", "misiniz", "mısınız",
        // Words the model adds when it rewrites a goal; they say nothing about the task
        "öğren", "söyle", "bak", "bakar", "gir", "git", "sonra", "uygulamasını", "uygulaması", "uygulamayı",
        "bölümüne", "bölümünden", "kısmına", "içinden", "üzerinden", "telefonun", "telefonda"
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
        // Retries leave the same step twice in a row; once is enough for a hint
        .fold(mutableListOf<String>()) { acc, step -> if (acc.lastOrNull() != step) acc.add(step); acc }

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

    /**
     * How much the shorter goal is contained in the longer one (overlap coefficient),
     * so a rewritten, wordier goal still matches. One shared word is never enough.
     */
    fun similarity(a: String, b: String): Double {
        val ka = keywords(a)
        val kb = keywords(b)
        if (ka.isEmpty() || kb.isEmpty()) return 0.0
        val shared = ka.intersect(kb).size
        if (shared < minOf(2, ka.size, kb.size)) return 0.0
        return shared.toDouble() / minOf(ka.size, kb.size)
    }

    private fun fold(s: String) = s
        .replace('ç', 'c').replace('ğ', 'g').replace('ı', 'i').replace('ö', 'o').replace('ş', 's').replace('ü', 'u')
}

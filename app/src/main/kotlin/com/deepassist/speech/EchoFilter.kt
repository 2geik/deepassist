package com.deepassist.speech

import java.util.Locale

/**
 * Tells the user talking over an answer apart from the answer itself coming back
 * through the microphone. While the assistant speaks, the recognizer also hears
 * the speaker (echo cancelling only weakens it); those transcripts are the words
 * being read out, in order, give or take a misheard word. The user's words are
 * mostly not in the answer at all ("birinciyi aç", "dur", "tamam yeter").
 *
 * Pure Kotlin (no Android, no lookaround regex) so it is unit-tested on the JVM.
 */
class EchoFilter(spoken: String) {

    data class Verdict(
        /** The heard text is (at least partly) the user, not the assistant's voice. */
        val isUser: Boolean,
        /** Leading words that are still the assistant's voice, to drop from the request. */
        val echoPrefix: Int,
        val words: Int,
        val novel: Int
    )

    private val spokenWords: List<String> = words(spoken)

    fun judge(heard: String): Verdict {
        val heardWords = words(heard)
        if (heardWords.isEmpty()) return Verdict(false, 0, 0, 0)
        val novelWords = heardWords.filter { w -> spokenWords.none { similar(w, it) } }
        val novel = novelWords.size
        val isUser = when {
            // One clear command word the answer doesn't contain: "dur", "yeter", "aç"
            novelWords.any { it in COMMANDS } -> true
            // A single new word may be a misheard word of the answer: wait for more
            heardWords.size < 2 -> false
            else -> novel >= 2
        }
        return Verdict(isUser, if (isUser) echoPrefix(heardWords) else 0, heardWords.size, novel)
    }

    /** Drops the first [count] words of [heard], keeping its original spelling. */
    fun dropWords(heard: String, count: Int): String {
        if (count <= 0) return heard.trim()
        val raw = heard.trim().split(SPACE).filter { it.isNotBlank() }
        var kept = 0
        var dropped = 0
        val out = ArrayList<String>()
        for (token in raw) {
            // Tokens that fold to nothing (punctuation) don't count as words
            if (dropped < count && words(token).isNotEmpty()) {
                dropped++
                continue
            }
            if (dropped >= count) {
                out.add(token)
                kept++
            }
        }
        return if (kept == 0) heard.trim() else out.joinToString(" ")
    }

    /**
     * Leading heard words that follow the answer in order (the tail end of the
     * voice before the user cut in). Fewer than two is not trusted: the user's own
     * first word may happen to be in the answer ("birinciyi aç").
     */
    private fun echoPrefix(heard: List<String>): Int {
        var cursor = -1
        var count = 0
        for (w in heard) {
            val from = cursor + 1
            val until = minOf(spokenWords.size, from + ALIGN_WINDOW + if (cursor < 0) spokenWords.size else 0)
            val at = (from until until).firstOrNull { similar(w, spokenWords[it]) } ?: break
            cursor = at
            count++
        }
        // The whole text in order means no user words were found after all
        return if (count >= 2 && count < heard.size) count else 0
    }

    companion object {
        private val TR = Locale("tr", "TR")
        private val SPACE = Regex("\\s+")
        private const val ALIGN_WINDOW = 4

        /** Short replies that end or redirect an answer; they decide on their own. */
        private val COMMANDS = setOf(
            "dur", "durdur", "sus", "yeter", "kes", "bekle", "tamam", "ac", "oynat", "evet", "hayir",
            "istiyorum", "istemiyorum", "olsun", "gec", "sonraki", "baska", "kapat", "anladim"
        )

        private val NUMBERS = mapOf(
            "1" to "bir", "2" to "iki", "3" to "uc", "4" to "dort", "5" to "bes",
            "6" to "alti", "7" to "yedi", "8" to "sekiz", "9" to "dokuz", "10" to "on"
        )

        /** Lowercase, Turkish letters folded, digits as words, punctuation dropped. */
        fun words(text: String): List<String> {
            val folded = fold(text)
            val out = ArrayList<String>()
            val sb = StringBuilder()
            fun flush() {
                if (sb.isNotEmpty()) {
                    val w = sb.toString()
                    out.add(NUMBERS[w] ?: w)
                    sb.setLength(0)
                }
            }
            for (c in folded) {
                when {
                    c in 'a'..'z' || c in '0'..'9' -> sb.append(c)
                    // "Manço'nun": the suffix belongs to the word
                    c == '\'' || c == '’' -> Unit
                    else -> flush()
                }
            }
            flush()
            return out
        }

        fun fold(s: String): String {
            val sb = StringBuilder(s.length)
            for (c in s.lowercase(TR)) {
                sb.append(
                    when (c) {
                        'ç' -> 'c'; 'ş' -> 's'; 'ğ' -> 'g'; 'ö' -> 'o'; 'ü' -> 'u'; 'ı' -> 'i'
                        'â' -> 'a'; 'î' -> 'i'; 'û' -> 'u'
                        else -> c
                    }
                )
            }
            return sb.toString()
        }

        /**
         * The heard word is the spoken one give or take a Turkish suffix (same first
         * four letters) or cut off at the end of a partial transcript. Short words
         * must match exactly: "aç" is not "açık", "birinciyi" is not "bir".
         */
        fun similar(heard: String, spoken: String): Boolean {
            if (heard == spoken) return true
            if (heard.length >= 3 && spoken.startsWith(heard)) return true
            if (heard.length < 4 || spoken.length < 4) return false
            return heard.regionMatches(0, spoken, 0, 4)
        }
    }
}

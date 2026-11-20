package com.deepassist.speech

import java.util.Locale

/**
 * Cuts streamed answer text into pieces to speak one after another, with
 * Turkish in mind. Plain Kotlin: no Android classes, unit tested.
 *
 * A piece ends at `.` `!` `?` `…` (followed by a space) or a line break; each
 * list item (a line starting with `-`, `*`, `•` or `1.`) is a piece of its own,
 * spoken without its marker. Not sentence ends: numbers (`3.5`, `1.650`,
 * `07.30`, `15.11.2026`), abbreviations (`Dr.`, `vb.`, `örn.`…), and a dot
 * followed by a lowercase word — ordinals like "255. ayet".
 *
 * The first piece goes out as soon as it is complete so speech starts early.
 * After that, sentences shorter than [mergeBelow] are joined with the next, and
 * pieces longer than [maxLength] are cut at a comma. Every piece is passed
 * through [SpeechText.sanitize].
 */
class SentenceSplitter(
    private val mergeBelow: Int = 60,
    private val maxLength: Int = 300
) {

    private val buffer = StringBuilder()

    /** True when [buffer] starts at the beginning of a line. */
    private var bufferAtLineStart = true

    /** Short sentences waiting to be joined with the next one. */
    private var pending = ""

    private var emittedAny = false

    /** Adds streamed text; returns the pieces that are complete now. */
    fun push(delta: String): List<String> {
        buffer.append(delta)
        return drain(final = false)
    }

    /** The text is complete: returns everything that is left. */
    fun flush(): List<String> {
        val out = drain(final = true).toMutableList()
        if (pending.isNotBlank()) out += split(pending)
        pending = ""
        return out
    }

    private class Segment(val text: String, val atLineStart: Boolean)

    private fun drain(final: Boolean): List<String> {
        val out = mutableListOf<String>()
        segments(final).forEach { out += place(it) }
        return out
    }

    /** Takes complete segments off the front of [buffer]. */
    private fun segments(final: Boolean): List<Segment> {
        val s = buffer
        val out = mutableListOf<Segment>()
        var start = 0
        var startAtLine = bufferAtLineStart
        var lineStart = if (bufferAtLineStart) 0 else -1
        var i = 0
        scan@ while (i < s.length) {
            val c = s[i]
            if (c == '\n') {
                out += Segment(s.substring(start, i), startAtLine)
                start = i + 1
                startAtLine = true
                lineStart = i + 1
                i++
                continue
            }
            if (c !in TERMINATORS) {
                i++
                continue
            }
            var end = i + 1
            while (end < s.length && s[end] in TERMINATORS) end++
            val singleDot = c == '.' && end == i + 1
            while (end < s.length && s[end] in CLOSERS) end++
            // At the end of the text so far the next character decides; when the
            // text is complete the rest becomes the last segment below
            if (end >= s.length) break@scan
            if (!s[end].isWhitespace()) { // 3.5 · 15.11.2026 · example.com
                i = end
                continue
            }
            if (singleDot) {
                var k = end
                while (k < s.length && s[k].isWhitespace() && s[k] != '\n') k++
                if (k >= s.length && !final) break@scan // wait for the next word
                val next = if (k < s.length) s[k] else null
                if (dotContinues(s, i, lineStart, next)) {
                    i = end
                    continue
                }
            }
            out += Segment(s.substring(start, end), startAtLine)
            start = end
            startAtLine = false
            i = end
        }
        // A very long sentence: don't wait for its end, cut it at a comma
        while (!final && s.length - start > maxLength) {
            val cut = commaCut(s.substring(start, start + maxLength)) ?: break
            out += Segment(s.substring(start, start + cut), startAtLine)
            start += cut
            startAtLine = false
        }
        if (final) {
            if (start < s.length) out += Segment(s.substring(start), startAtLine)
            s.setLength(0)
            bufferAtLineStart = true
        } else {
            s.delete(0, start)
            bufferAtLineStart = startAtLine
        }
        return out
    }

    /** True when the dot at [i] does not end the sentence. */
    private fun dotContinues(s: CharSequence, i: Int, lineStart: Int, next: Char?): Boolean {
        // "255. ayetin", "vb. şeyler": a lowercase word goes on with the sentence
        if (next != null && next.isLowerCase()) return true
        var w = i
        while (w > 0 && s[w - 1].isLetter()) w--
        val word = s.substring(w, i)
        if (word.isNotEmpty()) {
            if (word.lowercase(TR) in ABBREVIATIONS) return true
            if (word.length == 1 && word[0].isUpperCase()) return true // initials: "M. Kemal"
            return false
        }
        // "1. " at the start of a line is a list marker
        var d = i
        while (d > 0 && s[d - 1].isDigit()) d--
        if (d == i || i - d > 2 || lineStart < 0) return false
        return s.substring(lineStart, d).all { it.isWhitespace() || it in "*_#" } // "**1. Başlık**"
    }

    /** Turns a raw segment into pieces, joining short sentences. */
    private fun place(segment: Segment): List<String> {
        var raw = segment.text.trim()
        if (raw.isEmpty()) return emptyList()
        var item = false
        if (segment.atLineStart) {
            stripMarker(raw)?.let { raw = it; item = true }
        }
        var text = SpeechText.sanitize(raw)
        if (segment.atLineStart && !item) {
            stripMarker(text)?.let { text = it; item = true } // "**1. Başlık**"
        }
        text = text.trim()
        if (text.none { it.isLetterOrDigit() }) return emptyList()

        val out = mutableListOf<String>()
        if (item) {
            // A list item stands alone: flush what waits, then the item itself
            if (pending.isNotBlank()) out += split(pending)
            pending = ""
            out += split(text)
            emittedAny = true
            return out
        }
        if (!emittedAny) { // the first piece starts the voice — don't hold it back
            emittedAny = true
            return split(text)
        }
        pending = join(pending, text)
        if (pending.length >= mergeBelow) {
            out += split(pending)
            pending = ""
        }
        return out
    }

    private fun join(a: String, b: String): String = when {
        a.isEmpty() -> b
        a.last() in JOINABLE_ENDS -> "$a $b"
        else -> "$a. $b" // a heading or line without punctuation: keep a pause
    }

    /** Cuts [text] into pieces of at most [maxLength], preferably after a comma. */
    private fun split(text: String): List<String> {
        val out = mutableListOf<String>()
        var rest = text.trim()
        while (rest.length > maxLength) {
            val cut = commaCut(rest.substring(0, maxLength))
                ?: rest.lastIndexOf(' ', maxLength).takeIf { it > MIN_CUT }
                ?: maxLength
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }

    /** Index just after the last ", " (or "; ") in [window], or null. "3,5" has no space. */
    private fun commaCut(window: String): Int? {
        val comma = window.lastIndexOf(", ")
        if (comma > MIN_CUT) return comma + 1
        val semicolon = window.lastIndexOf("; ")
        return if (semicolon > MIN_CUT) semicolon + 1 else null
    }

    private fun stripMarker(text: String): String? {
        val m = LIST_MARKER.find(text) ?: return null
        val rest = text.substring(m.range.last + 1)
        // "15. yüzyılda" at a line start is an ordinal, not a numbered item
        if (m.value.trim().first().isDigit() && rest.firstOrNull()?.isLowerCase() == true) return null
        return rest.takeIf { it.isNotBlank() }
    }

    companion object {
        private val TR = Locale("tr", "TR")
        private const val TERMINATORS = ".!?…"
        private const val CLOSERS = "\"'”’)»"
        private const val JOINABLE_ENDS = ".!?…:;,"
        private const val MIN_CUT = 40
        private val LIST_MARKER = Regex("^\\s*(?:[-*•–]|\\d{1,2}[.)])\\s+")

        private val ABBREVIATIONS = setOf(
            "dr", "prof", "doç", "yrd", "av", "sn", "hz", "st", "mr", "mrs",
            "vb", "vs", "vd", "örn", "ör", "bkz", "no", "nr", "tel", "cad", "sok",
            "mah", "apt", "yy", "bl", "alb", "gen", "org", "kur", "müh", "op", "uzm"
        )
    }
}

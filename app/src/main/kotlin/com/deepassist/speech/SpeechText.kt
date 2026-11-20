package com.deepassist.speech

/** Text clean-up shared by everything that speaks or shows a model answer. */
object SpeechText {

    private val codeBlock = Regex("```[\\s\\S]*?```")
    private val markdownLink = Regex("\\[([^\\]]*)]\\([^)]*\\)")
    private val markdownMarks = Regex("[*_~`#>]+")
    private val spaces = Regex("[ \\t]+")

    /** Drops markdown the voice would read out (code, link targets, emphasis marks). */
    fun sanitize(text: String): String = text
        .replace(codeBlock, " ")
        .replace(markdownLink, "$1")
        .replace(markdownMarks, "")
        .replace(spaces, " ")
        .trim()

    /**
     * What goes to the voice: "01:12" → "1:12". With the leading zero the cloud
     * voice read the hour in another language; the panel keeps the original text.
     */
    fun forVoice(text: String): String {
        // Plain code on purpose: Android's ICU regex engine rejected the lookaround
        // version that java.util.regex (the unit tests) accepted
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (isLeadingZeroClock(text, i)) {
                i++ // drop the zero, keep the rest as it is
                continue
            }
            out.append(text[i])
            i++
        }
        return out.toString()
    }

    /** "0d:dd" or "0d.dd" starting at [i], not part of a longer number or date. */
    private fun isLeadingZeroClock(t: String, i: Int): Boolean {
        if (i + 5 > t.length || t[i] != '0') return false
        if (!t[i + 1].isDigit() || (t[i + 2] != ':' && t[i + 2] != '.')) return false
        if (!t[i + 3].isDigit() || !t[i + 4].isDigit()) return false
        val before = t.getOrNull(i - 1)
        if (before != null && before.isDigit()) return false
        if ((before == '.' || before == ':') && t.getOrNull(i - 2)?.isDigit() == true) return false
        val after = t.getOrNull(i + 5)
        if (after != null && after.isDigit()) return false
        if ((after == '.' || after == ':') && t.getOrNull(i + 6)?.isDigit() == true) return false
        return true
    }
}

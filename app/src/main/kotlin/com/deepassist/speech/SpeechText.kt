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
     * What goes to the voice: clock times in words ("01:22" → "bir yirmi iki").
     * Read as digits the cloud voice said "sıfır bir" or switched language; the
     * panel and the history keep the original text.
     */
    fun forVoice(text: String): String = TurkishClock.toWords(text)
}

package com.deepassist.speech

/**
 * Clock times the way they are said aloud in Turkish: 01:22 → "bir yirmi iki",
 * 13:05 → "on üç sıfır beş", 11:00 → "on bir". Plain Kotlin, unit tested.
 */
object TurkishClock {

    private val ONES = listOf("", "bir", "iki", "üç", "dört", "beş", "altı", "yedi", "sekiz", "dokuz")
    private val TENS = listOf("", "on", "yirmi", "otuz", "kırk", "elli")

    /** Simple regex on purpose: Android's ICU engine rejects some lookaround patterns. */
    private val CLOCK = Regex("\\d{1,2}[:.]\\d{2}")

    /** 0..59 in words. */
    fun number(n: Int): String {
        require(n in 0..59)
        if (n == 0) return "sıfır"
        val tens = TENS[n / 10]
        val ones = ONES[n % 10]
        return listOf(tens, ones).filter { it.isNotEmpty() }.joinToString(" ")
    }

    fun words(hour: Int, minute: Int): String = when {
        minute == 0 -> number(hour)
        minute < 10 -> "${number(hour)} sıfır ${number(minute)}"
        else -> "${number(hour)} ${number(minute)}"
    }

    /** "HH:mm" (as DeviceUtils formats it) in words; the input unchanged if it isn't a time. */
    fun spoken(hhmm: String): String {
        val parts = hhmm.split(':', '.')
        val h = parts.getOrNull(0)?.toIntOrNull() ?: return hhmm
        val m = parts.getOrNull(1)?.toIntOrNull() ?: return hhmm
        return if (h in 0..23 && m in 0..59) words(h, m) else hhmm
    }

    /**
     * Replaces clock times in [text] with words. "13:05" always counts; with a dot
     * ("07.30", "15.30'da", "saat 15.30") only when it clearly is a time, so prices
     * like "3.50" stay. Dates ("15.11.2026") and longer numbers are left alone.
     */
    fun toWords(text: String): String {
        val out = StringBuilder()
        var last = 0
        for (match in CLOCK.findAll(text)) {
            val start = match.range.first
            val end = match.range.last + 1
            if (!isClock(text, start, end)) continue
            val value = match.value
            val sep = value.indexOfFirst { it == ':' || it == '.' }
            val h = value.substring(0, sep).toInt()
            val m = value.substring(sep + 1).toInt()
            out.append(text, last, start).append(words(h, m))
            last = end
        }
        out.append(text, last, text.length)
        return out.toString()
    }

    private fun isClock(t: String, start: Int, end: Int): Boolean {
        val value = t.substring(start, end)
        val sep = value.indexOfFirst { it == ':' || it == '.' }
        val h = value.substring(0, sep).toInt()
        val m = value.substring(sep + 1).toInt()
        if (h !in 0..23 || m !in 0..59) return false
        // Part of a longer number, a date or a list of times? Leave it.
        val before = t.getOrNull(start - 1)
        if (before != null && before.isDigit()) return false
        if ((before == '.' || before == ':' || before == ',') && t.getOrNull(start - 2)?.isDigit() == true) return false
        val after = t.getOrNull(end)
        if (after != null && after.isDigit()) return false
        if ((after == '.' || after == ':' || after == ',') && t.getOrNull(end + 1)?.isDigit() == true) return false
        if (t[start + sep] == ':') return true
        // A dot is also a decimal point: only clear time forms
        if (sep == 2 && value[0] == '0') return true // 07.30
        if (after == '\'' || after == '’') return true // 15.30'da
        val lead = t.substring(0, start).trimEnd().takeLast(4).lowercase()
        return lead == "saat"
    }
}

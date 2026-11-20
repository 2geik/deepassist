package com.deepassist.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceSplitterTest {

    /** Whole text at once; merging off so each sentence is visible on its own. */
    private fun split(text: String, mergeBelow: Int = 0): List<String> {
        val s = SentenceSplitter(mergeBelow = mergeBelow)
        return s.push(text) + s.flush()
    }

    /** The same text fed in small deltas, the way the model streams it. */
    private fun streamed(text: String, step: Int, mergeBelow: Int = 0): List<String> {
        val s = SentenceSplitter(mergeBelow = mergeBelow)
        val out = mutableListOf<String>()
        text.chunked(step).forEach { out += s.push(it) }
        return out + s.flush()
    }

    @Test
    fun `sentence ends`() {
        assertEquals(
            listOf("Merhaba.", "Nasılsın?", "Çok iyi!", "Peki…", "Görüşürüz."),
            split("Merhaba. Nasılsın? Çok iyi! Peki… Görüşürüz.")
        )
    }

    @Test
    fun `line break ends a piece`() {
        assertEquals(listOf("Birinci satır", "İkinci satır"), split("Birinci satır\nİkinci satır"))
    }

    @Test
    fun `numbers are not sentence ends`() {
        assertEquals(
            listOf("Fiyat 3.5 lira.", "Nüfus 1.650 kişi.", "Saat 07.30 oldu.", "Tarih 15.11.2026 idi."),
            split("Fiyat 3.5 lira. Nüfus 1.650 kişi. Saat 07.30 oldu. Tarih 15.11.2026 idi.")
        )
    }

    @Test
    fun `abbreviations are not sentence ends`() {
        val text = "Dr. Ahmet geldi. Prof. Ayşe de. Elma, armut vb. Meyveler. Kalem vs. Eşyalar. " +
            "Örn. Bu bir örnek. Bkz. Ek bölüm. No. 5 kapı."
        assertEquals(
            listOf(
                "Dr. Ahmet geldi.", "Prof. Ayşe de.", "Elma, armut vb. Meyveler.", "Kalem vs. Eşyalar.",
                "Örn. Bu bir örnek.", "Bkz. Ek bölüm.", "No. 5 kapı."
            ),
            split(text)
        )
        assertEquals(listOf("Bu, örn. şöyle bir şey."), split("Bu, örn. şöyle bir şey."))
    }

    @Test
    fun `ordinals followed by a lowercase word are not sentence ends`() {
        assertEquals(
            listOf("Bakara Suresi 255. ayetin anlamı şudur.", "Sonra gelir."),
            split("Bakara Suresi 255. ayetin anlamı şudur. Sonra gelir.")
        )
        assertEquals(listOf("15. yüzyılda yaşadı."), split("15. yüzyılda yaşadı."))
    }

    @Test
    fun `list items are pieces of their own without the marker`() {
        val text = "Özellikleri şunlar:\n- Uzun kulaklıdır.\n- Süt verimi yüksektir.\n" +
            "1. Halep'te yetişir.\n2. Kırmızı tüylüdür.\n* Sakin bir hayvandır."
        assertEquals(
            listOf(
                "Özellikleri şunlar:", "Uzun kulaklıdır.", "Süt verimi yüksektir.",
                "Halep'te yetişir.", "Kırmızı tüylüdür.", "Sakin bir hayvandır."
            ),
            split(text, mergeBelow = 60)
        )
    }

    @Test
    fun `bold numbered heading loses marker and markdown`() {
        assertEquals(listOf("Tarihçe"), split("**1. Tarihçe**"))
    }

    @Test
    fun `markdown is removed from every piece`() {
        assertEquals(
            listOf("Kaynak: Vikipedi sayfası.", "Önemli bilgi."),
            split("**Kaynak:** [Vikipedi sayfası](https://tr.wikipedia.org/wiki/X). ## Önemli bilgi.")
        )
    }

    @Test
    fun `first piece goes out alone, later short sentences are joined`() {
        val out = split("Tabii. Halep keçisi bir keçi ırkıdır. Kökeni Suriye. Sütü bol. Eti az yenir. Sevimlidir.")
        assertEquals("Tabii.", out.first())
        assertEquals(
            listOf("Tabii.", "Halep keçisi bir keçi ırkıdır. Kökeni Suriye. Sütü bol. Eti az yenir.", "Sevimlidir."),
            split("Tabii. Halep keçisi bir keçi ırkıdır. Kökeni Suriye. Sütü bol. Eti az yenir. Sevimlidir.", 60)
        )
    }

    @Test
    fun `joined line without punctuation keeps a pause`() {
        assertEquals(listOf("Giriş", "Başlık. Kısa açıklama burada."), split("Giriş\nBaşlık\nKısa açıklama burada.", 60))
    }

    @Test
    fun `long pieces are cut at a comma`() {
        val clause = "bu cümle oldukça uzun bir yan cümledir"
        val text = (1..12).joinToString(", ") { clause } + "."
        val out = split(text)
        assertTrue(out.size > 1)
        out.forEach { assertTrue("too long: ${it.length}", it.length <= 300) }
        out.dropLast(1).forEach { assertTrue(it, it.endsWith(",")) }
        assertEquals(text, out.joinToString(" "))
    }

    @Test
    fun `decimal comma is not a cut point`() {
        val text = "Değer " + "3,5 ".repeat(100) + "oldu."
        split(text).forEach { assertTrue(!it.endsWith("3,")) }
    }

    @Test
    fun `streaming gives the same pieces as whole text`() {
        val text = "Halep keçisi, Orta Doğu'ya özgü bir keçi ırkıdır. Dr. Ahmet'e göre 1.650 yıllık bir geçmişi vardır. " +
            "Bakara Suresi 255. ayetin aksine bu bir hayvandır! Saat 07.30'da sağılır.\n" +
            "- Süt verimi yüksektir.\n2. Kırmızı tüylüdür.\nSon söz… Bitti."
        val whole = split(text, 60)
        for (step in listOf(1, 2, 3, 5, 7, 13)) {
            assertEquals("step $step", whole, streamed(text, step, 60))
        }
    }

    @Test
    fun `a piece is emitted before the text is complete`() {
        val s = SentenceSplitter()
        assertEquals(emptyList<String>(), s.push("Halep keçisi bir keçi ırkıdır"))
        assertEquals(emptyList<String>(), s.push(". ")) // the next word decides
        assertEquals(listOf("Halep keçisi bir keçi ırkıdır."), s.push("Kökeni"))
    }

    @Test
    fun `short answer`() {
        assertEquals(listOf("Saat 07:30."), split("Saat 07:30.", 60))
    }

    @Test
    fun `quote after the dot stays with the sentence`() {
        assertEquals(listOf("Dedi ki: \"Geliyorum.\"", "Sonra gitti."), split("Dedi ki: \"Geliyorum.\" Sonra gitti."))
    }

    @Test
    fun `punctuation only pieces are dropped`() {
        assertEquals(listOf("Tamam."), split("Tamam.\n---\n"))
    }

    @Test
    fun `clock times are spoken as words`() {
        assertEquals("Saat bir yirmi iki.", SpeechText.forVoice("Saat 01:22."))
        assertEquals("Saat on üç sıfır beş.", SpeechText.forVoice("Saat 13:05."))
        assertEquals("Saat on bir.", SpeechText.forVoice("Saat 11:00."))
        assertEquals("Saat sıfır kırk beş.", SpeechText.forVoice("Saat 00:45."))
        assertEquals("Saat yedi otuz'da", SpeechText.forVoice("Saat 07.30'da"))
        assertEquals("Maç on altı'da", SpeechText.forVoice("Maç 16.00'da"))
        assertEquals("saat on beş otuz gibi", SpeechText.forVoice("saat 15.30 gibi"))
    }

    @Test
    fun `numbers that are not times stay as they are`() {
        assertEquals("15.01.2026", SpeechText.forVoice("15.01.2026"))
        assertEquals("1.05.2026", SpeechText.forVoice("1.05.2026"))
        assertEquals("3.05 lira", SpeechText.forVoice("3.05 lira"))
        assertEquals("15.30 lira", SpeechText.forVoice("15.30 lira"))
        assertEquals("Nüfus 1.650 kişi", SpeechText.forVoice("Nüfus 1.650 kişi"))
        assertEquals("Skor 25:99", SpeechText.forVoice("Skor 25:99"))
    }

    @Test
    fun `device time in words`() {
        assertEquals("bir yirmi iki", TurkishClock.spoken("01:22"))
        assertEquals("yirmi üç elli dokuz", TurkishClock.spoken("23:59"))
        assertEquals("on iki", TurkishClock.spoken("12:00"))
    }

    @Test
    fun `sanitize removes markdown`() {
        assertEquals("Başlık kalın link", SpeechText.sanitize("## Başlık **kalın** [link](http://x.y)"))
    }
}

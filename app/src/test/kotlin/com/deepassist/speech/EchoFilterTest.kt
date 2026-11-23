package com.deepassist.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoFilterTest {

    private val options = EchoFilter(
        "Üç sonuç buldum. 1: Barış Manço - Gülpembe, açıklamalı canlı kayıt. " +
            "2: Barış Manço - Dağlar Dağlar. 3: Cem Karaca - Resimdeki Gözyaşları. Hangisini açayım?"
    )

    @Test
    fun theAnswerReadBackIsEcho() {
        assertFalse(options.judge("Barış Manço Gülpembe açıklamalı canlı").isUser)
        assertFalse(options.judge("iki Barış Manço Dağlar Dağlar").isUser)
        // Cumulative partial with its last word cut off
        assertFalse(options.judge("Cem Karaca Resim").isUser)
        assertFalse(options.judge("Barış Man").isUser)
    }

    @Test
    fun oneMisheardWordIsStillEcho() {
        assertFalse(options.judge("Barış Manço Dağlar Bağlar").isUser)
        assertFalse(options.judge("Gülbeyaz").isUser)
    }

    @Test
    fun pickingAnOptionCutsIn() {
        assertTrue(options.judge("birinciyi aç").isUser)
        assertTrue(options.judge("bir numarayı aç").isUser)
        assertTrue(options.judge("ilkini istiyorum").isUser)
        assertTrue(options.judge("onu oynat").isUser)
    }

    @Test
    fun shortCommandsCutIn() {
        assertTrue(options.judge("dur").isUser)
        assertTrue(options.judge("tamam yeter").isUser)
        assertTrue(options.judge("Sus").isUser)
    }

    @Test
    fun shortWordsMustMatchExactly() {
        // "aç" is not "açıklamalı"/"açayım" and "birinciyi" is not "bir"
        assertTrue(options.judge("aç").isUser)
        assertFalse(EchoFilter.similar("birinciyi", "bir"))
        assertTrue(EchoFilter.similar("manconun", "manco"))
        assertTrue(EchoFilter.similar("kar", "karaca"))
    }

    @Test
    fun voiceTailBeforeTheUserIsDropped() {
        val text = "Cem Karaca Resimdeki birinciyi aç"
        val verdict = options.judge(text)
        assertTrue(verdict.isUser)
        assertEquals(3, verdict.echoPrefix)
        assertEquals("birinciyi aç", options.dropWords(text, verdict.echoPrefix))
    }

    @Test
    fun aSingleMatchingFirstWordIsKept() {
        val verdict = options.judge("Barış olanı açar mısın")
        assertTrue(verdict.isUser)
        assertEquals(0, verdict.echoPrefix)
    }

    @Test
    fun digitsAndNumberWordsMatch() {
        assertEquals(listOf("bir", "barıs".replace('ı', 'i')), EchoFilter.words("1: Barış"))
        assertFalse(options.judge("3 Cem Karaca").isUser)
    }
}

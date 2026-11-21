package com.deepassist.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekAsrTest {

    @Test
    fun `turkish transcripts are kept`() {
        assertTrue(DeepSeekAsr.isLatinScript("Saat kaç?"))
        assertTrue(DeepSeekAsr.isLatinScript("Ömer oğlumu ara"))
        assertTrue(DeepSeekAsr.isLatinScript("Sevinç'i WhatsApp üzerinden ara"))
        assertTrue(DeepSeekAsr.isLatinScript("Evet."))
        assertTrue(DeepSeekAsr.isLatinScript("12:30"))
    }

    @Test
    fun `transcripts in another script are dropped`() {
        assertFalse(DeepSeekAsr.isLatinScript("साट काच"))
        assertFalse(DeepSeekAsr.isLatinScript("سات كاچ"))
        assertFalse(DeepSeekAsr.isLatinScript("Saat سات كاچ مرحبا"))
    }
}

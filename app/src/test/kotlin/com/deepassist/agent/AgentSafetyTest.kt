package com.deepassist.agent

import com.deepassist.agent.AgentSafety.Sensitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSafetyTest {

    @Test
    fun sendButtonsNeedConfirmation() {
        assertEquals(Sensitive.MESSAGE, AgentSafety.classifyTap("Gönder"))
        assertEquals(Sensitive.MESSAGE, AgentSafety.classifyTap("SMS gönder"))
        assertEquals(Sensitive.MESSAGE, AgentSafety.classifyTap("Send"))
        assertEquals(Sensitive.MESSAGE, AgentSafety.classifyTap("", "com.whatsapp:id/send"))
    }

    @Test
    fun lookalikesAreNotSends() {
        assertEquals(Sensitive.NONE, AgentSafety.classifyTap("Gönderme"))
        assertEquals(Sensitive.NONE, AgentSafety.classifyTap("Gönderiler"))
        assertEquals(Sensitive.NONE, AgentSafety.classifyTap("Gönderildi"))
        assertEquals(Sensitive.NONE, AgentSafety.classifyTap("Ödeme yöntemleri"))
        assertEquals(Sensitive.NONE, AgentSafety.classifyTap("Ara"))
        assertEquals(Sensitive.NONE, AgentSafety.classifyTap("Sohbetler"))
    }

    @Test
    fun paymentsNeedConfirmation() {
        assertEquals(Sensitive.PAYMENT, AgentSafety.classifyTap("Öde"))
        assertEquals(Sensitive.PAYMENT, AgentSafety.classifyTap("Satın al"))
        assertEquals(Sensitive.PAYMENT, AgentSafety.classifyTap("Siparişi onayla"))
        assertEquals(Sensitive.PAYMENT, AgentSafety.classifyTap("Ödemeyi Tamamla"))
        assertEquals(Sensitive.PAYMENT, AgentSafety.classifyTap("Para gönder"))
        assertEquals(Sensitive.PAYMENT, AgentSafety.classifyTap("EFT"))
    }

    @Test
    fun callsAreLeftToCallTools() {
        assertEquals(Sensitive.CALL, AgentSafety.classifyTap("Sesli arama"))
        assertEquals(Sensitive.CALL, AgentSafety.classifyTap("Görüntülü arama"))
        assertEquals(Sensitive.CALL, AgentSafety.classifyTap("", "com.whatsapp:id/voip_call"))
    }

    @Test
    fun stepsThatCutOffTheAssistantAreBlocked() {
        assertNotNull(AgentSafety.blockedTap("com.android.settings", "Uçak modu", checked = false))
        assertNull(AgentSafety.blockedTap("com.android.settings", "Uçak modu", checked = true))
        assertNotNull(AgentSafety.blockedTap("com.android.settings", "Wi-Fi", checked = true))
        assertNull(AgentSafety.blockedTap("com.android.settings", "Wi-Fi", checked = false))
        assertNotNull(AgentSafety.blockedTap("com.android.settings", "Mobil veri", checked = true))
        assertNotNull(AgentSafety.blockedTap("com.android.settings", "Erişilebilirlik", checked = null))
        assertNotNull(AgentSafety.blockedTap("com.android.settings", "deepAssist Bildirim Okuyucu", checked = null))
        assertNotNull(AgentSafety.blockedTap("com.android.settings", "Zorla durdur", checked = null))
        assertNotNull(AgentSafety.blockedTap("com.android.settings", "Fabrika ayarlarına sıfırla", checked = null))
        assertNotNull(AgentSafety.blockedTap("com.android.vending", "Kaldır", checked = null))
    }

    @Test
    fun ordinarySettingsAreAllowed() {
        assertNull(AgentSafety.blockedTap("com.android.settings", "Bluetooth", checked = true))
        assertNull(AgentSafety.blockedTap("com.android.settings", "Yazı boyutu", checked = null))
        assertNull(AgentSafety.blockedTap("com.android.settings", "Ekran", checked = null))
        assertNull(AgentSafety.blockedTap("com.whatsapp", "Ayarlar", checked = null))
    }

    @Test
    fun ownAppIsNeverOpened() {
        assertNotNull(AgentSafety.blockedApp("deepAssist"))
        assertNotNull(AgentSafety.blockedApp("deep assist"))
        assertNull(AgentSafety.blockedApp("WhatsApp"))
    }

    @Test
    fun yesMeansYes() {
        listOf("evet", "Evet, gönder.", "tamam", "olur", "onaylıyorum", "gönder", "öde", "evet ödeme yap", "tabii").forEach {
            assertTrue(it, AgentSafety.isYes(it))
        }
    }

    @Test
    fun anythingElseIsNo() {
        listOf(
            "hayır", "gönderme", "evet ama gönderme", "vazgeç", "iptal", "dur", "bekle bir dakika",
            "ödeme", "istemiyorum", "", "hmm", "ne dedin", null
        ).forEach { assertFalse(it.toString(), AgentSafety.isYes(it)) }
    }
}

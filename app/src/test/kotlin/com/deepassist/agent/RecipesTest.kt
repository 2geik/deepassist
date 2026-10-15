package com.deepassist.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipesTest {

    @Test
    fun recipesKeepNavigationButNoPrivateText() {
        val recipe = Recipes.fromSteps(
            listOf(
                "1. Uygulama aç \"WhatsApp\" → WhatsApp açıldı",
                "2. Dokun [6] \"Sevinç – Tamam canım, yarın görüşürüz – 18:02\" → dokunuldu",
                "3. Yaz [9] \"akşam geliyorum\" → yazıldı",
                "4. Onay soruldu: \"Sevinç kişisine \"akşam geliyorum\" mesajını gönderiyorum, onaylıyor musun?\" → cevap: \"evet\"",
                "5. Onaylanan [10] \"Gönder\" → dokunuldu",
                "6. Dokun [3] \"Ekle\" → dokunulamadı"
            )
        )
        assertEquals(
            listOf(
                "Uygulama aç \"WhatsApp\"",
                "Dokun \"Sevinç\"",
                "Yazı alanına istenen metni yaz",
                "Onay alıp gönder/öde düğmesine dokun"
            ),
            recipe
        )
        assertFalse(recipe.joinToString().contains("geliyorum"))
        assertFalse(recipe.joinToString().contains("Tamam canım"))
    }

    @Test
    fun similarGoalsMatch() {
        assertTrue(Recipes.similarity("Ayarlardan yazı boyutunu büyüt", "yazı boyutunu büyütür müsün") >= 0.5)
        assertTrue(Recipes.similarity("Bluetooth'u aç", "bluetooth aç") >= 0.5)
    }

    @Test
    fun differentGoalsDoNot() {
        assertTrue(Recipes.similarity("Bluetooth'u aç", "yazı boyutunu büyüt") < 0.2)
        assertTrue(Recipes.similarity("Getir'de son siparişime bak", "Ayarlardan ekran süresini uzat") < 0.2)
    }
}

package com.deepassist.agent

import java.util.Locale

/**
 * The phone agent's rules, kept free of Android types so they are unit-tested.
 *
 * - Sending a message or paying needs a spoken yes (the user's choice: only
 *   these two ask; everything else runs on its own).
 * - A few steps are never taken, because they would cut off the assistant
 *   itself (accessibility, network, deepAssist's own app page) or are
 *   irreversible for a blind user (reset, uninstall, screen lock, passwords).
 */
object AgentSafety {

    enum class Sensitive { NONE, MESSAGE, PAYMENT, CALL }

    private val tr = Locale("tr", "TR")

    /** Lower-case words; Turkish i/ı handled by the tr locale. */
    fun words(text: String): List<String> =
        text.lowercase(tr).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }

    private fun normalized(text: String) = words(text).joinToString(" ")

    private val messageWords = setOf("gönder", "gönderin", "yolla", "send")
    private val paymentWords = setOf("öde", "ödeyin", "pay", "buy", "checkout", "havale", "eft", "fast")
    private val paymentPhrases = listOf(
        "ödeme yap", "ödemeyi yap", "ödemeyi tamamla", "ödemeyi onayla", "satın al", "sipariş ver",
        "siparişi onayla", "siparişi tamamla", "siparişi ver", "sepeti onayla", "para gönder", "para transferi",
        "onayla ve öde", "place order", "complete purchase", "confirm payment", "send money"
    )
    private val callPhrases = listOf(
        "sesli arama", "görüntülü arama", "video arama", "arama yap", "voice call", "video call"
    )

    /**
     * What tapping an element labelled [label] would do. Matches whole words, so
     * "Gönderme", "Gönderiler" (posts) and "Ödeme yöntemleri" don't count; a lone
     * "Ara" is usually "search" and isn't treated as a call.
     */
    fun classifyTap(label: String, viewId: String? = null): Sensitive {
        val w = words(label)
        val n = w.joinToString(" ")
        val id = viewId?.lowercase(Locale.ROOT).orEmpty()
        return when {
            callPhrases.any { n.contains(it) } || id.contains("voip") || Regex("(^|[_/])(call|video_call|voice_call)([_/]|$)").containsMatchIn(id) -> Sensitive.CALL
            w.any { it in paymentWords } || paymentPhrases.any { n.contains(it) } -> Sensitive.PAYMENT
            w.any { it in messageWords } || id.endsWith(":id/send") || id.contains("send_button") -> Sensitive.MESSAGE
            else -> Sensitive.NONE
        }
    }

    private val settingsPackages = setOf(
        "com.android.settings", "com.miui.securitycenter", "com.android.permissioncontroller",
        "com.google.android.permissioncontroller", "com.miui.powerkeeper", "com.xiaomi.misettings"
    )
    private val blockedInSettings = listOf(
        "erişilebilirlik", "accessibility", "bildirim erişimi", "notification access",
        "zorla durdur", "force stop", "verileri temizle", "veriyi temizle", "depolamayı temizle", "clear data", "clear storage",
        "fabrika", "factory", "sıfırla", "reset",
        "ekran kilidi", "screen lock", "pin", "şifre", "parola", "password",
        "geliştirici seçenekleri", "developer options", "hesabı kaldır", "remove account",
        "deepassist"
    )
    private val blockedAnywhere = listOf("kaldır", "uninstall", "fabrika ayarlarına", "factory reset")

    /**
     * Why tapping this element is never done, or null when it's allowed.
     * [checked] is the current state of a switch, if it is one.
     */
    fun blockedTap(packageName: String, label: String, checked: Boolean?): String? {
        val n = normalized(label)
        if (n.isBlank()) return null
        if (n.contains("uçak modu") || n.contains("airplane mode") || n.contains("uçuş modu")) {
            if (checked != true) return "Uçak modunu açarsam seni duyamam."
        }
        if (Regex("\\b(wi fi|wifi|wlan|kablosuz|mobil veri|hücresel veri|mobile data)\\b").containsMatchIn(n) && checked == true) {
            return "İnterneti kapatırsam sana cevap veremem."
        }
        if (packageName in settingsPackages && blockedInSettings.any { matches(n, it) }) {
            return "Bu ayarı kendim değiştirmiyorum, asistanın çalışmasını ya da telefonun güvenliğini etkileyebilir."
        }
        if (blockedAnywhere.any { n == it || n.startsWith("$it ") || n.endsWith(" $it") || n.contains(" $it ") }) {
            return "Silme ve sıfırlama gibi geri alınamayan işlemleri yapmıyorum."
        }
        return null
    }

    /** Phrases and long words match anywhere; short single words only as whole words ("pin"). */
    private fun matches(normalizedLabel: String, phrase: String): Boolean =
        if (phrase.contains(' ') || phrase.length > 5) normalizedLabel.contains(phrase)
        else phrase in normalizedLabel.split(' ')

    /** Opening our own app would cover the screen the agent works on. */
    fun blockedApp(appName: String): String? =
        if (normalized(appName).replace(" ", "").contains("deepassist")) "Kendi uygulamamı açmıyorum." else null

    private val negatives = setOf(
        "hayır", "hayir", "yok", "iptal", "vazgeç", "vazgec", "dur", "durdur", "istemiyorum", "olmaz",
        "yapma", "gönderme", "basma", "ödeme", "etme", "silme", "bekle", "sakın", "sakin", "no", "cancel", "stop"
    )
    private val positives = setOf(
        "evet", "tamam", "olur", "gönder", "onaylıyorum", "onayla", "onay", "öde", "yap", "bas", "devam",
        "peki", "tabii", "tabi", "elbette", "aynen", "kabul", "doğru", "yes", "ok", "okay"
    )

    /**
     * A spoken confirmation. Anything negative wins ("evet ama gönderme" is a
     * no); silence or anything unclear counts as no.
     */
    fun isYes(answer: String?): Boolean {
        // "ödeme yap" means "make the payment"; on its own "ödeme" means "don't pay"
        val text = normalized(answer ?: return false).replace("ödeme yap", "öde")
        val w = text.split(' ').filter { it.isNotBlank() }
        if (w.isEmpty()) return false
        if (w.any { it in negatives }) return false
        return w.any { it in positives }
    }
}

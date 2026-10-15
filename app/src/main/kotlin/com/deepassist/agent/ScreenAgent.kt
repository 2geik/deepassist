package com.deepassist.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.util.Log
import com.deepassist.llm.DeepSeekClient
import com.deepassist.util.AppLauncher
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Uses the phone for the user: look at the screen, let deepseek-flash pick one
 * action, do it, repeat. Each step sends only the current screen (as text, plus
 * a screenshot when the text isn't enough) and a short history.
 *
 * Messages and payments are only sent after a spoken yes, built from what is on
 * screen; a few steps are never taken ([AgentSafety]). "Done" must be visible on
 * the last screen — the model is told to say when it isn't sure.
 */
class ScreenAgent(
    private val context: Context,
    private val service: AccessibilityService,
    private val deepSeek: DeepSeekClient,
    private val say: suspend (String) -> Unit,
    private val ask: suspend (String) -> String?,
    private val tick: () -> Unit
) {

    /** [declined]: the user said no to sending or paying — not a failure of the way taken. */
    data class Outcome(val success: Boolean, val spoken: String, val steps: List<String>, val declined: Boolean = false)

    private val actions = ScreenActions(service)
    private val history = mutableListOf<String>()

    suspend fun run(goal: String, hint: String? = null): Outcome {
        running = true
        try {
            return withTimeoutOrNull(DEADLINE_MS) { loop(goal, hint) }
                ?: Outcome(false, "Bu iş çok uzun sürdü, yarıda bıraktım.", history.toList())
        } finally {
            running = false
        }
    }

    private suspend fun loop(goal: String, hint: String?): Outcome {
        var lastSignature = ""
        var sameScreen = 0
        var badReplies = 0
        var wantScreenshot = false
        var lastTyped: String? = null

        for (step in 1..MAX_STEPS) {
            currentCoroutineContext().ensureActive()
            val screen = ScreenReader.read(service) ?: run {
                delay(700)
                ScreenReader.read(service)
            } ?: return done(false, "Ekranı okuyamadım, devam edemiyorum.")

            val signature = screen.signature()
            if (signature == lastSignature) sameScreen++ else {
                sameScreen = 0
                lastSignature = signature
            }
            if (sameScreen >= 3) {
                return done(false, "Aynı ekranda takıldım, devam edemedim. Şu an ${screen.appName} açık.")
            }

            val shot = if (screen.poor || wantScreenshot) ScreenCapture.take(service, screen.elements) else null
            wantScreenshot = false
            val image = (shot as? ScreenCapture.Result.Image)?.image
            val reply = image.use {
                deepSeek.completeJson(
                    system = SYSTEM_PROMPT,
                    user = stepMessage(goal, hint, step, screen, withImage = image != null,
                        secure = (shot as? ScreenCapture.Result.Failed)?.reason == ScreenCapture.Reason.SECURE),
                    jpeg = image?.bytes,
                    maxTokens = 400
                )
            }
            val decision = reply.getOrNull()?.let { parse(it) }
            if (decision == null) {
                Log.w(TAG, "step $step: no usable reply (${reply.exceptionOrNull()?.javaClass?.simpleName})")
                if (reply.isFailure && ++badReplies >= 2) {
                    return done(false, "İnternet bağlantısında sorun var, işlemi bitiremedim.")
                }
                if (++badReplies >= 3) return done(false, "Ne yapacağıma karar veremedim, işlemi bıraktım.")
                continue
            }
            val action = decision.str("action")
            Log.i(TAG, "step $step on ${screen.packageName}: $action ${decision.str("id")} ${decision.str("thought").take(120)}")

            when (action) {
                "done" -> return done(true, decision.str("summary").ifBlank { "Tamamladım." })
                "fail" -> return done(false, decision.str("summary").ifBlank { "Bunu yapamadım." })
                "ask_user" -> {
                    val question = decision.str("question").ifBlank { return done(false, "Bir şey sormam gerekti ama soramadım.") }
                    val answer = ask(question)
                    history += "$step. Kullanıcıya sordum: \"$question\" → cevap: ${answer?.let { "\"$it\"" } ?: "(cevap yok)"}"
                    if (answer.isNullOrBlank()) return done(false, "Cevap alamadım, işlemi bıraktım.")
                    continue
                }
                "look" -> {
                    wantScreenshot = true
                    history += "$step. Ekran görüntüsüne baktım."
                    continue
                }
                "open_app" -> {
                    val name = decision.str("app")
                    AgentSafety.blockedApp(name)?.let { reason ->
                        history += "$step. \"$name\" açılmadı: $reason"
                        continue
                    }
                    val app = AppLauncher.find(context, name)
                    val opened = app != null && AppLauncher.launch(context, app)
                    history += "$step. Uygulama aç \"$name\" → ${if (opened) "${app?.label} açıldı" else "bulunamadı"}"
                }
                "tap" -> {
                    val element = screen.find(decision.int("id")) ?: run {
                        history += "$step. Geçersiz numara ${decision.str("id")}, ekrandaki numaralardan birini seç."
                        continue
                    }
                    AgentSafety.blockedTap(screen.packageName, element.label, if (element.checkable) element.checked else null)?.let { reason ->
                        history += "$step. [${element.id}] \"${element.label}\" engellendi: $reason"
                        if (history.count { it.contains("engellendi") } >= 2) return done(false, reason)
                        continue
                    }
                    val byCode = AgentSafety.classifyTap(element.label, element.viewId)
                    val byModel = when (decision.str("sensitive")) {
                        "message" -> AgentSafety.Sensitive.MESSAGE
                        "payment" -> AgentSafety.Sensitive.PAYMENT
                        else -> AgentSafety.Sensitive.NONE
                    }
                    val sensitive = if (byCode != AgentSafety.Sensitive.NONE) byCode else byModel
                    when (sensitive) {
                        AgentSafety.Sensitive.CALL -> return done(
                            false,
                            "Aramaları buradan başlatmıyorum. Kimi aramak istediğini söylersen kendi arama özelliğimle ararım."
                        )
                        AgentSafety.Sensitive.MESSAGE, AgentSafety.Sensitive.PAYMENT -> {
                            val question = confirmation(sensitive, screen, decision, lastTyped)
                            val answer = ask(question)
                            history += "$step. Onay soruldu: \"$question\" → cevap: ${answer?.let { "\"$it\"" } ?: "(cevap yok)"}"
                            if (!AgentSafety.isYes(answer)) {
                                return Outcome(
                                    success = false,
                                    spoken = if (sensitive == AgentSafety.Sensitive.MESSAGE) "Tamam, mesajı göndermedim."
                                    else "Tamam, ödemeyi yapmadım.",
                                    steps = history.toList(),
                                    declined = true
                                )
                            }
                            // Confirmed: tap once, never retried automatically
                            val ok = actions.tap(element)
                            tick()
                            history += "$step. Onaylanan [${element.id}] \"${element.label}\" → ${if (ok) "dokunuldu" else "DOKUNULAMADI"}"
                            if (!ok) return done(false, "Onay verdin ama düğmeye basamadım; gönderilmedi.")
                        }
                        AgentSafety.Sensitive.NONE -> {
                            val ok = actions.tap(element)
                            tick()
                            history += "$step. Dokun [${element.id}] \"${element.label}\" → ${if (ok) "dokunuldu" else "dokunulamadı"}"
                        }
                    }
                }
                "type" -> {
                    val element = screen.find(decision.int("id"))?.takeIf { it.editable } ?: run {
                        history += "$step. Yazmak için yazı alanı numarası gerekli."
                        continue
                    }
                    if (element.password) {
                        return done(false, "Şifre alanlarına ben yazmıyorum; bunu senin ya da bir yakınının yapması gerekiyor.")
                    }
                    val text = decision.str("text")
                    val ok = actions.type(element, text)
                    tick()
                    if (ok) lastTyped = text
                    history += "$step. Yaz [${element.id}] \"$text\" → ${if (ok) "yazıldı" else "yazılamadı"}"
                }
                "submit" -> {
                    val element = screen.find(decision.int("id")) ?: screen.elements.firstOrNull { it.editable && it.focused }
                    val ok = element != null && actions.submit(element)
                    history += "$step. Klavyeden onayla → ${if (ok) "yapıldı" else "yapılamadı"}"
                }
                "scroll" -> {
                    val down = decision.str("direction") != "up"
                    val ok = actions.scroll(screen, screen.find(decision.int("id")), down)
                    tick()
                    history += "$step. Kaydır ${if (down) "aşağı" else "yukarı"} → ${if (ok) "kaydırıldı" else "kaydırılamadı"}"
                }
                "back" -> history += "$step. Geri → ${if (actions.back()) "yapıldı" else "yapılamadı"}"
                "home" -> history += "$step. Ana ekran → ${if (actions.home()) "yapıldı" else "yapılamadı"}"
                "notifications" -> history += "$step. Bildirimleri aç → ${if (actions.notifications()) "yapıldı" else "yapılamadı"}"
                "wait" -> {
                    delay(1_500)
                    history += "$step. Bekledim."
                }
                else -> {
                    history += "$step. Bilinmeyen aksiyon \"$action\"."
                    if (++badReplies >= 3) return done(false, "Ne yapacağıma karar veremedim, işlemi bıraktım.")
                }
            }
            PanelControl.awaitQuiet()
            if (step % 4 == 0) decision.str("say").takeIf { it.isNotBlank() }?.let { say(it) }
        }
        return done(false, "Çok fazla adım sürdü, yarıda bıraktım.")
    }

    private fun done(success: Boolean, spoken: String) = Outcome(success, spoken, history.toList())

    /** The spoken yes/no question, built from what is on screen rather than what the model believes. */
    private fun confirmation(
        kind: AgentSafety.Sensitive,
        screen: ScreenReader.Screen,
        decision: JsonObject,
        lastTyped: String?
    ): String {
        val labels = screen.elements.joinToString(" ") { "${it.label} ${it.value.orEmpty()}" }.lowercase()
        val recipient = decision.str("recipient").takeIf { it.isNotBlank() && labels.contains(it.lowercase()) }
        return if (kind == AgentSafety.Sensitive.MESSAGE) {
            val body = screen.elements.firstOrNull { it.editable && !it.password && !it.value.isNullOrBlank() }?.value
                ?: lastTyped
            when {
                recipient != null && body != null -> "$recipient kişisine \"$body\" mesajını gönderiyorum, onaylıyor musun?"
                body != null -> "\"$body\" mesajını ${screen.appName} üzerinden gönderiyorum, onaylıyor musun?"
                recipient != null -> "$recipient kişisine gönder düğmesine basıyorum, onaylıyor musun?"
                else -> "${screen.appName} üzerinde gönder düğmesine basıyorum, onaylıyor musun?"
            }
        } else {
            val amount = AMOUNT.findAll(screen.elements.joinToString("\n") { it.label }).map { it.value.trim() }.lastOrNull()
            if (amount != null) "${screen.appName} üzerinde $amount tutarında ödeme yapılacak, onaylıyor musun?"
            else "${screen.appName} üzerinde ödemeyi onaylama adımındayım, devam edeyim mi?"
        }
    }

    private fun stepMessage(
        goal: String,
        hint: String?,
        step: Int,
        screen: ScreenReader.Screen,
        withImage: Boolean,
        secure: Boolean
    ): String = buildString {
        append("HEDEF: ").append(goal).append('\n')
        if (!hint.isNullOrBlank()) append("DAHA ÖNCE İŞE YARAYAN YOL (ekrana uyuyorsa izle):\n").append(hint).append('\n')
        append("ADIM: ").append(step).append('/').append(MAX_STEPS).append('\n')
        append("ÖNCEKİ ADIMLAR:\n")
        if (history.isEmpty()) append("(yok)\n") else history.takeLast(14).forEach { append(it).append('\n') }
        append("\nŞU ANKİ EKRAN:\n").append(screen.describe())
        if (withImage) append("\n(Ekran görüntüsü ekte; numaralı kutular listedeki numaralardır.)\n")
        if (secure) append("\n(Bu uygulama ekran görüntüsüne izin vermiyor; sadece listeyi kullan.)\n")
        append("\nSıradaki TEK aksiyonu JSON olarak ver.")
    }

    private fun parse(raw: String): JsonObject? = runCatching {
        JsonParser.parseString(raw.trim().removePrefix("```json").removeSuffix("```")).asJsonObject
    }.getOrNull()?.takeIf { it.has("action") }

    private fun JsonObject.str(key: String): String =
        get(key)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() }?.trim().orEmpty()

    private fun JsonObject.int(key: String): Int =
        get(key)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asInt }.getOrNull() } ?: -1

    companion object {
        private const val TAG = "ScreenAgent"
        const val MAX_STEPS = 25
        const val DEADLINE_MS = 220_000L

        /** True while an agent runs — the service then ignores audio focus loss from apps it opens. */
        @Volatile
        var running = false
            private set

        private val AMOUNT = Regex("(₺\\s?\\d[\\d.]*(,\\d{1,2})?|\\d[\\d.]*(,\\d{1,2})?\\s?(TL|₺))")

        val SYSTEM_PROMPT = """
Sen görme engelli bir kullanıcının telefonunu onun yerine kullanan bir asistansın. Her adımda ekrandaki öğelerin numaralı listesini (ve gerekirse ekran görüntüsünü) görürsün ve hedefe ulaşmak için TEK bir aksiyon seçersin.

CEVAP: Sadece şu biçimde bir JSON nesnesi ver:
{"thought": "kısa gerekçe", "action": "...", "id": 0, "text": "", "app": "", "direction": "down", "sensitive": "none", "recipient": "", "say": "", "summary": "", "question": ""}

AKSİYONLAR:
- open_app: "app" alanındaki uygulamayı aç (ör. "Ayarlar", "WhatsApp", "Haritalar"). Hedef başka bir uygulamadaysa önce bunu kullan.
- tap: "id" numaralı öğeye dokun.
- type: "id" numaralı yazı alanına "text" yaz (alanı değiştirir).
- submit: klavyedeki ara/gönder/enter tuşu ("id": yazı alanı).
- scroll: listeyi kaydır; "direction": "down" ya da "up", istersen "id" ile hangi liste.
- back, home, notifications: geri, ana ekran, bildirim paneli.
- wait: ekran yükleniyorsa bekle.
- look: liste yetersizse bir sonraki adımda ekran görüntüsüne bak.
- ask_user: gerçekten belirsizse kullanıcıya kısa bir soru sor ("question"). Hangi kişi, hangi seçenek gibi.
- done: hedef tamamlandı. "summary": kullanıcıya söylenecek tek doğal Türkçe cümle.
- fail: devam edilemiyor. "summary": nedenini kullanıcıya söyleyecek tek cümle.

KURALLAR:
- Sadece listede olan numaraları kullan. Bir adımda tek aksiyon.
- Mesaj gönderen düğmeye dokunurken "sensitive": "message" ve "recipient": ekranda görünen kişi/grup adı. Ödeme, satın alma, sipariş, para transferi düğmesinde "sensitive": "payment". Sistem bu adımlarda kullanıcıdan sesli onay alır; sen ayrıca sorma.
- Mesaj metnini kullanıcının söylediği gibi yaz, değiştirme.
- Arama (telefon, WhatsApp sesli/görüntülü) başlatma; hedef arama ise fail ile "Aramayı kendi arama özelliğimle yapabilirim" de.
- Şifre yazma, güvenlik ve erişilebilirlik ayarlarını değiştirme, uygulama kaldırma, veri silme, sıfırlama, Wi-Fi'yi ya da mobil veriyi kapatma, uçak modunu açma YAPMA. Bunlar istenirse fail.
- Başka bir uygulama izin isterse (konum, bildirim vb.) ask_user ile kullanıcıya sor.
- Reklam veya açılır pencere çıkarsa kapat ve devam et.
- Aynı şeyi iki kez deneyip olmuyorsa farklı yol dene; üç denemede olmazsa fail.
- done demeden önce hedefin gerçekten ekranda gerçekleştiğini gör (ör. mesaj sohbette görünüyor, ayar açık görünüyor). Göremiyorsan summary'de "emin değilim" de.
- "say": kullanıcıyı bilgilendirmek için en fazla altı kelimelik durum (ör. "Ayarları açtım, ilerliyorum"); boş da olabilir.
- Numara, paket adı, koordinat gibi teknik şeyleri summary veya say içinde söyleme.
        """.trimIndent()
    }
}

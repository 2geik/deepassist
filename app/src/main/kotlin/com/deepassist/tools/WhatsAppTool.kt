package com.deepassist.tools

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.deepassist.data.ToolProperty
import com.deepassist.data.ToolResult
import com.deepassist.service.AccessibilitySvc
import com.deepassist.service.NotificationReplier
import com.deepassist.util.DeviceUtils
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WhatsAppTool : Tool() {

    override val name = "send_whatsapp"
    override val description =
        "WhatsApp ile mesaj gönderir. SADECE kullanıcı onayladıktan sonra confirmed=true ile çağır."
    override val parameters = mapOf(
        "contact_name" to ToolProperty(
            type = "string",
            description = "Rehberdeki kişinin adını AYNEN yaz, numarayı değil."
        ),
        "phone_number" to ToolProperty(
            type = "string",
            description = "Telefon numarası (sadece contact_name yoksa)"
        ),
        "message" to ToolProperty(
            type = "string",
            description = "Gönderilecek mesaj içeriği"
        ),
        "confirmed" to ToolProperty(
            type = "boolean",
            description = "Kullanıcı onayladıysa true yap. Onaysız çağrıda gönderme yapmaz, sadece onay metni döner."
        )
    )
    override val required = listOf("message")

    // Opens the WhatsApp UI over everything — announce fully first
    override val waitForSpeech = true

    override fun dynamicThinkingPhrase(args: JsonObject): String? =
        if (isConfirmed(args)) "WhatsApp'tan mesaj gönderiyorum..." else "WhatsApp mesajı hazırlanıyor..."

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val message = args.optString("message")
        if (message.isNullOrBlank()) {
            return@withContext ToolResult(false, "", error = "Mesaj içeriği eksik.")
        }
        val contactName = args.optString("contact_name")?.trim()?.takeIf { it.isNotBlank() }
        val rawNumber = args.optString("phone_number")?.trim()?.takeIf { it.isNotBlank() }

        // Resolve by name here so the model never has to relay digits.
        var resolvedName = contactName
        var resolvedNumber = rawNumber
        if (contactName != null) {
            val best = ContactLookup.search(context, contactName).firstOrNull()
            if (best != null) {
                resolvedName = best.name
                resolvedNumber = best.phoneNumber
                Log.d(TAG, "Contact '$contactName' -> '${best.name}'")
            } else {
                Log.w(TAG, "Contact '$contactName' not found")
            }
        }
        if (resolvedNumber.isNullOrBlank()) {
            return@withContext ToolResult(false, "", error = "Numara bulunamadı. Kişi adı veya numara belirtin.")
        }

        // An unread chat from this person can be answered straight from its notification
        val canReplyInline = NotificationReplier.hasWhatsAppTarget(resolvedNumber, resolvedName)

        // Without the accessibility service nothing can tap Send; opening WhatsApp would
        // only strand an unsent draft on a screen the user can't see.
        if (AccessibilitySvc.instance == null && !canReplyInline) {
            return@withContext ToolResult(false, "", error = ACCESSIBILITY_OFF)
        }

        // Never send on the model's own initiative — it must relay the user's yes.
        if (!isConfirmed(args)) {
            return@withContext ToolResult(
                false, "",
                error = "Önce kullanıcıya sorup onay almalısın. ask_user ile onay aldıktan sonra confirmed=true ile tekrar çağır."
            )
        }

        val number = DeviceUtils.toInternationalPhoneNumber(resolvedNumber)
        val who = resolvedName ?: number

        if (canReplyInline) {
            when (NotificationReplier.replyToWhatsApp(context, resolvedNumber, resolvedName, message)) {
                NotificationReplier.Outcome.SENT -> {
                    Log.d(TAG, "Sent via notification reply")
                    return@withContext ToolResult(true, "$who kişisine mesaj gönderildi.")
                }
                // The reply may have gone out — reopening the chat could send it twice
                NotificationReplier.Outcome.UNVERIFIED -> return@withContext ToolResult(
                    false, "",
                    error = "Mesaj WhatsApp bildiriminden gönderilmeye çalışıldı ama gidip gitmediği doğrulanamadı. " +
                        "Kullanıcıya böyle söyle; mesajı ASLA kendiliğinden tekrar gönderme."
                )
                NotificationReplier.Outcome.NO_TARGET, NotificationReplier.Outcome.FAILED ->
                    Log.w(TAG, "Notification reply unavailable, opening the chat instead")
            }
            if (AccessibilitySvc.instance == null) {
                return@withContext ToolResult(false, "", error = ACCESSIBILITY_OFF)
            }
        }

        val encoded = Uri.encode(message)
        val direct = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$number?text=$encoded")).apply {
            setPackage("com.whatsapp")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(direct)
            sentResult(who, autoSend())
        } catch (e: ActivityNotFoundException) {
            try {
                val fallback = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://api.whatsapp.com/send?phone=$number&text=$encoded")
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(fallback)
                sentResult(who, autoSend())
            } catch (e2: Exception) {
                ToolResult(false, "", error = "WhatsApp açılamadı. Uygulama yüklü olmayabilir.")
            }
        } catch (e: Exception) {
            ToolResult(false, "", error = "WhatsApp açılamadı: ${e.message}")
        }
    }

    /** Taps the send button through the accessibility service, then backs out of the chat. */
    private fun autoSend(): Boolean {
        val sent = AccessibilitySvc.clickByLabel("Gönder", 5_000L) ||
            AccessibilitySvc.clickByLabel("Send", 2_000L)
        if (sent) {
            Thread.sleep(300)
            AccessibilitySvc.pressBack()
        }
        Log.d(TAG, "Auto-send=$sent")
        return sent
    }

    // A blind user can't see an unsent draft, so never claim "sent" unless the tap happened.
    private fun sentResult(who: String, sent: Boolean): ToolResult =
        if (sent) {
            ToolResult(true, "$who kişisine mesaj gönderildi.")
        } else {
            ToolResult(
                false, "",
                error = "WhatsApp açıldı ve mesaj yazıldı ama Gönder tuşuna basılamadı. " +
                    "Erişilebilirlik servisi kapalı olabilir; mesaj henüz GÖNDERİLMEDİ."
            )
        }

    private fun isConfirmed(args: JsonObject): Boolean =
        runCatching { args.get("confirmed")?.asBoolean == true }.getOrDefault(false)

    companion object {
        private const val TAG = "WhatsAppTool"
        private const val ACCESSIBILITY_OFF =
            "Erişilebilirlik servisi kapalı olduğu için WhatsApp mesajını gönderemiyorum. " +
                "Telefon ayarlarından deepAssist erişilebilirlik servisini açman gerekiyor."
    }
}

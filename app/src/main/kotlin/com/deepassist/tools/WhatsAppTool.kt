package com.deepassist.tools

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import com.deepassist.data.ToolProperty
import com.deepassist.data.ToolResult
import com.google.gson.JsonObject

class WhatsAppTool : Tool() {

    override val name = "send_whatsapp"
    override val description =
        "WhatsApp'ı verilen numarayla, mesaj önceden yazılmış şekilde açar. " +
            "Numara ülke koduyla birlikte olmalıdır (örn. 90 ile başlayan Türkiye numarası). " +
            "Kullanıcının göndermek için onaylaması gerekir."
    override val parameters = mapOf(
        "phone_number" to ToolProperty(
            type = "string",
            description = "Ülke kodu dahil telefon numarası (örn. 905551234567)"
        ),
        "message" to ToolProperty(
            type = "string",
            description = "Önceden doldurulacak mesaj içeriği"
        )
    )
    override val required = listOf("phone_number", "message")
    override val thinkingPhrase: String? = "WhatsApp'tan mesaj atıyorum..."

    // Opens the WhatsApp UI over everything — announce fully first
    override val waitForSpeech = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val rawNumber = args.optString("phone_number")?.trim()
        val message = args.optString("message")
        if (rawNumber.isNullOrBlank() || message.isNullOrBlank()) {
            return ToolResult(false, "", error = "Numara veya mesaj içeriği eksik.")
        }

        var number = rawNumber.filter(Char::isDigit)
        // Local Turkish numbers (05xx... / 5xx...) need the country code for wa.me
        if (number.startsWith("0") && number.length == 11) number = "9" + number
        else if (number.startsWith("5") && number.length == 10) number = "90$number"

        val encoded = Uri.encode(message)
        val direct = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$number?text=$encoded")).apply {
            setPackage("com.whatsapp")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(direct)
            ToolResult(true, "WhatsApp açıldı, mesaj yazıldı. Kullanıcının gönder tuşuna basması gerekiyor.")
        } catch (e: ActivityNotFoundException) {
            try {
                val fallback = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://api.whatsapp.com/send?phone=$number&text=$encoded")
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(fallback)
                ToolResult(true, "WhatsApp web bağlantısı açıldı, mesaj hazır.")
            } catch (e2: Exception) {
                ToolResult(false, "", error = "WhatsApp açılamadı. Uygulama yüklü olmayabilir.")
            }
        } catch (e: Exception) {
            ToolResult(false, "", error = "WhatsApp açılamadı: ${e.message}")
        }
    }
}

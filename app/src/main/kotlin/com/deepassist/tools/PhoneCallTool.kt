package com.deepassist.tools

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import com.deepassist.data.ToolProperty
import com.deepassist.data.ToolResult
import com.deepassist.util.PermissionsHelper
import com.google.gson.JsonObject

class PhoneCallTool : Tool() {

    override val name = "make_phone_call"
    override val description =
        "Verilen telefon numarasını hemen arar. Numara bilinmiyorsa önce search_contacts ile bulunmalıdır."
    override val parameters = mapOf(
        "phone_number" to ToolProperty(
            type = "string",
            description = "Aranacak telefon numarası"
        ),
        "contact_name" to ToolProperty(
            type = "string",
            description = "Aranan kişinin adı (isteğe bağlı, sesli geri bildirim için)"
        )
    )
    override val required = listOf("phone_number")

    // Phrase needs the contact name from the arguments, so it is dynamic only
    override val thinkingPhrase: String? = null

    // The dialer takes over the screen and audio — announce fully, then dial
    override val waitForSpeech = true

    override fun dynamicThinkingPhrase(args: JsonObject): String? {
        val contactName = args.optString("contact_name")?.trim()
        return if (contactName.isNullOrBlank()) "Arıyorum..." else "$contactName'i arıyorum..."
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        if (!PermissionsHelper.hasCallPhone(context)) {
            return ToolResult(false, "", error = "Telefon arama izni verilmemiş.")
        }
        val number = args.optString("phone_number")?.trim()?.replace(" ", "")
        if (number.isNullOrBlank()) {
            return ToolResult(false, "", error = "Telefon numarası belirtilmedi.")
        }
        val uri = Uri.fromParts("tel", number, null)
        val who = args.optString("contact_name")?.takeIf { it.isNotBlank() } ?: number

        // ACTION_CALL via startActivity is silently dropped when launched from a
        // background service (Android 10+ activity-start restriction), so the
        // Telecom framework is the primary path — it places the call directly.
        try {
            val telecom = context.getSystemService(TelecomManager::class.java)
            if (telecom != null) {
                telecom.placeCall(uri, Bundle())
                return ToolResult(true, "$who aranıyor.")
            }
        } catch (e: Exception) {
            // fall through to the intent path
        }
        return try {
            val intent = Intent(Intent.ACTION_CALL, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult(true, "$who aranıyor.")
        } catch (e: Exception) {
            ToolResult(false, "", error = "Arama başlatılamadı: ${e.message}")
        }
    }
}

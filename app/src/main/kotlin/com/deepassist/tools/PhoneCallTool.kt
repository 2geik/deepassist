package com.deepassist.tools

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import com.deepassist.data.ToolProperty
import com.deepassist.data.ToolResult
import com.deepassist.util.DeviceUtils
import com.deepassist.util.PermissionsHelper
import com.google.gson.JsonObject

class PhoneCallTool : Tool() {

    override val name = "make_phone_call"
    override val description =
        "Telefonla arama yapar. SADECE kullanıcı onayladıktan sonra confirmed=true ile çağır. " +
            "Numara bilinmiyorsa önce search_contacts ile bulunmalıdır."
    override val parameters = mapOf(
        "phone_number" to ToolProperty(
            type = "string",
            description = "Aranacak telefon numarası"
        ),
        "contact_name" to ToolProperty(
            type = "string",
            description = "Aranan kişinin adı (isteğe bağlı, sesli geri bildirim için)"
        ),
        "confirmed" to ToolProperty(
            type = "boolean",
            description = "Kullanıcı onayladıysa true yap. Onaysız çağrıda arama yapmaz, sadece onay metni döner."
        )
    )
    override val required = listOf("phone_number")

    // Phrase needs the contact name from the arguments, so it is dynamic only
    override val thinkingPhrase: String? = null

    // The dialer takes over the screen and audio — announce fully, then dial
    override val waitForSpeech = true

    override fun dynamicThinkingPhrase(args: JsonObject): String? {
        if (!isConfirmed(args)) return "Arama onayı bekleniyor..."
        val contactName = args.optString("contact_name")?.trim()
        return if (contactName.isNullOrBlank()) "Arıyorum..." else "$contactName'i arıyorum..."
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        if (!PermissionsHelper.hasCallPhone(context)) {
            return ToolResult(false, "", error = "Telefon arama izni verilmemiş.")
        }
        val rawNumber = args.optString("phone_number")?.trim()?.replace(" ", "")
        if (rawNumber.isNullOrBlank()) {
            return ToolResult(false, "", error = "Telefon numarası belirtilmedi.")
        }
        val number = DeviceUtils.normalizePhoneNumber(rawNumber)
        val who = args.optString("contact_name")?.takeIf { it.isNotBlank() } ?: number

        // Never dial on the model's own initiative — it must relay the user's yes.
        if (!isConfirmed(args)) {
            return ToolResult(
                false, "",
                error = "Önce kullanıcıya sorup onay almalısın. $who kişisini aramak istediğini söyleyip " +
                    "ask_user ile onay aldıktan sonra confirmed=true ile tekrar çağır."
            )
        }

        val uri = Uri.fromParts("tel", number, null)

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

    private fun isConfirmed(args: JsonObject): Boolean =
        runCatching { args.get("confirmed")?.asBoolean == true }.getOrDefault(false)
}

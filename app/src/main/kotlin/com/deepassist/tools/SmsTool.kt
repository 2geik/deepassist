package com.deepassist.tools

import android.os.Build
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import com.deepassist.data.SmsInfo
import com.deepassist.data.ToolProperty
import com.deepassist.data.ToolResult
import com.deepassist.util.PermissionsHelper
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

class SmsReadTool : Tool() {

    override val name = "read_sms"
    override val description =
        "Son SMS mesajlarını okur. İsteğe bağlı olarak belirli bir kişiden gelenlerle filtrelenebilir."
    override val parameters = mapOf(
        "from_contact" to ToolProperty(
            type = "string",
            description = "Mesajları filtrelenecek kişinin adı veya numarası (isteğe bağlı)"
        ),
        "limit" to ToolProperty(
            type = "string",
            description = "Okunacak mesaj sayısı (varsayılan 10)"
        )
    )
    override val required = emptyList<String>()
    override val thinkingPhrase: String? = "Mesajlara bakıyorum..."

    private val dateFormat = SimpleDateFormat("d MMMM HH:mm", Locale("tr", "TR"))

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        if (!PermissionsHelper.hasReadSms(context)) {
            return@withContext ToolResult(false, "", error = "SMS okuma izni verilmemiş.")
        }
        val limit = args.optString("limit")?.toIntOrNull()?.coerceIn(1, 50) ?: 10
        val filter = args.optString("from_contact")?.trim()

        val filterNumbers: List<String> = when {
            filter.isNullOrBlank() -> emptyList()
            filter.count(Char::isDigit) >= 7 -> listOf(filter.filter(Char::isDigit))
            else -> resolveContactNumbers(filter)
        }
        if (!filter.isNullOrBlank() && filterNumbers.isEmpty() && filter.count(Char::isDigit) < 7) {
            return@withContext ToolResult(true, "Rehberde \"$filter\" bulunamadığı için mesajlar filtrelenemedi.")
        }

        val messages = mutableListOf<SmsInfo>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE
            ),
            null,
            null,
            "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            while (cursor.moveToNext() && messages.size < limit) {
                val address = cursor.getString(1) ?: continue
                if (filterNumbers.isNotEmpty() && !matchesNumber(address, filterNumbers)) continue
                messages.add(
                    SmsInfo(
                        id = cursor.getString(0) ?: "",
                        address = address,
                        body = cursor.getString(2) ?: "",
                        date = cursor.getLong(3),
                        type = if (cursor.getInt(4) == Telephony.Sms.MESSAGE_TYPE_SENT) "giden" else "gelen"
                    )
                )
            }
        }

        if (messages.isEmpty()) {
            ToolResult(true, "Hiç mesaj bulunamadı.")
        } else {
            val listing = messages.joinToString("\n") { sms ->
                val direction = if (sms.type == "giden") "Giden →" else "Gelen ←"
                "- [${dateFormat.format(sms.date)}] $direction ${sms.address}: ${sms.body.take(300)}"
            }
            ToolResult(true, "Son ${messages.size} mesaj:\n$listing")
        }
    }

    private fun resolveContactNumbers(contactName: String): List<String> {
        if (!PermissionsHelper.hasContacts(context)) return emptyList()
        val numbers = mutableListOf<String>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$contactName%"),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                cursor.getString(0)?.let { numbers.add(it.filter(Char::isDigit)) }
            }
        }
        return numbers.filter { it.length >= 7 }
    }

    private fun matchesNumber(address: String, filterNumbers: List<String>): Boolean {
        val addressDigits = address.filter(Char::isDigit)
        if (addressDigits.length < 7) return false
        val addressSuffix = addressDigits.takeLast(7)
        return filterNumbers.any { it.takeLast(7) == addressSuffix }
    }
}

class SmsSendTool : Tool() {

    override val name = "send_sms"
    override val description =
        "Verilen numaraya SMS gönderir. Numara bilinmiyorsa önce search_contacts ile bulunmalıdır. " +
            "Mesaj içeriği kullanıcının söylediği gibi birebir gönderilmelidir."
    override val parameters = mapOf(
        "phone_number" to ToolProperty(
            type = "string",
            description = "Mesaj gönderilecek telefon numarası"
        ),
        "message" to ToolProperty(
            type = "string",
            description = "Gönderilecek mesaj içeriği"
        )
    )
    override val required = listOf("phone_number", "message")
    override val thinkingPhrase: String? = "Mesajını gönderiyorum..."

    override val waitForSpeech = true

    override suspend fun execute(args: JsonObject): ToolResult {
        if (!PermissionsHelper.hasSendSms(context)) {
            return ToolResult(false, "", error = "SMS gönderme izni verilmemiş.")
        }
        val number = args.optString("phone_number")?.trim()
        val message = args.optString("message")
        if (number.isNullOrBlank() || message.isNullOrBlank()) {
            return ToolResult(false, "", error = "Numara veya mesaj içeriği eksik.")
        }
        return try {
            val smsManager = if (Build.VERSION.SDK_INT >= 31) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            } ?: return ToolResult(false, "", error = "SMS servisi kullanılamıyor.")

            val parts = smsManager.divideMessage(message)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(number, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(number, null, message, null, null)
            }
            ToolResult(true, "Mesaj $number numarasına gönderildi.")
        } catch (e: Exception) {
            ToolResult(false, "", error = "Mesaj gönderilemedi: ${e.message}")
        }
    }
}

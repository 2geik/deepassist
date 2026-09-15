package com.deepassist.tools

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.ContactsContract
import android.util.Log
import com.deepassist.data.ToolProperty
import com.deepassist.data.ToolResult
import com.deepassist.service.AccessibilitySvc
import com.deepassist.util.DeviceUtils
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Places a WhatsApp voice or video call.
 *
 * Primary path: WhatsApp syncs "Sesli arama" / "Görüntülü arama" rows into the
 * contacts provider; viewing such a data row starts the call directly. Fallback
 * (contact not synced yet): open the chat and tap the toolbar call button
 * through the accessibility service.
 */
class WhatsAppCallTool : Tool() {

    override val name = "whatsapp_call"
    override val description =
        "WhatsApp üzerinden SESLİ veya GÖRÜNTÜLÜ arama yapar. \"WhatsApp'tan ara\" → video=false, " +
            "\"görüntülü ara\" → video=true. Normal telefon araması için make_phone_call kullan. " +
            "SADECE kullanıcı onayladıktan sonra confirmed=true ile çağır."
    override val parameters = mapOf(
        "contact_name" to ToolProperty(
            type = "string",
            description = "Rehberdeki kişinin adını AYNEN yaz, numarayı değil."
        ),
        "phone_number" to ToolProperty(
            type = "string",
            description = "Telefon numarası (sadece contact_name yoksa)"
        ),
        "video" to ToolProperty(
            type = "boolean",
            description = "Görüntülü arama için true, sesli arama için false."
        ),
        "confirmed" to ToolProperty(
            type = "boolean",
            description = "Kullanıcı onayladıysa true yap. Onaysız çağrıda arama yapmaz, sadece onay metni döner."
        )
    )
    override val required = listOf("video")

    // WhatsApp's call screen takes over the screen and audio — announce fully first
    override val waitForSpeech = true

    override fun dynamicThinkingPhrase(args: JsonObject): String? {
        if (!isConfirmed(args)) return "Arama onayı bekleniyor..."
        val kind = if (isVideo(args)) "görüntülü" else "sesli"
        val who = args.optString("contact_name")?.trim()
        return if (who.isNullOrBlank()) "WhatsApp'tan $kind arıyorum..." else "$who kişisini WhatsApp'tan $kind arıyorum..."
    }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val video = isVideo(args)
        val kind = if (video) "görüntülü" else "sesli"
        val contactName = args.optString("contact_name")?.trim()?.takeIf { it.isNotBlank() }
        val rawNumber = args.optString("phone_number")?.trim()?.takeIf { it.isNotBlank() }

        // Resolve by name here so the model never has to relay digits.
        var resolvedName = contactName
        var resolvedNumber = rawNumber
        var contactId: String? = null
        if (contactName != null) {
            val best = ContactLookup.search(context, contactName).firstOrNull()
            if (best != null) {
                resolvedName = best.name
                resolvedNumber = best.phoneNumber
                contactId = best.id
                Log.d(TAG, "Contact '$contactName' -> '${best.name}'")
            } else {
                Log.w(TAG, "Contact '$contactName' not found")
            }
        }
        if (resolvedNumber.isNullOrBlank()) {
            return@withContext ToolResult(false, "", error = "Kişi rehberde bulunamadı. Kişi adı veya numara belirtin.")
        }
        if (!isWhatsAppInstalled()) {
            return@withContext ToolResult(false, "", error = "Telefonda WhatsApp yüklü değil.")
        }

        val number = DeviceUtils.toInternationalPhoneNumber(resolvedNumber)
        val who = resolvedName ?: number

        // Never call on the model's own initiative — it must relay the user's yes.
        if (!isConfirmed(args)) {
            return@withContext ToolResult(
                false, "",
                error = "Önce kullanıcıya sorup onay almalısın. $who kişisini WhatsApp'tan $kind arayacağını söyleyip " +
                    "onay aldıktan sonra confirmed=true ile tekrar çağır."
            )
        }

        val dataId = findCallDataRow(contactId, number, video)
        Log.d(TAG, "video=$video dataRow=$dataId accessibility=${AccessibilitySvc.instance != null}")

        val started = if (dataId != null) {
            callViaContactRow(dataId, video)
        } else {
            callViaChatScreen(number, video)
        }

        if (started) {
            ToolResult(true, "$who WhatsApp'tan $kind aranıyor.")
        } else if (dataId == null && AccessibilitySvc.instance == null) {
            ToolResult(
                false, "",
                error = "$who için WhatsApp araması başlatılamadı. Erişilebilirlik servisi kapalı; " +
                    "telefon ayarlarından deepAssist erişilebilirlik servisini açman gerekiyor."
            )
        } else {
            ToolResult(
                false, "",
                error = "$who için WhatsApp $kind araması başlatılamadı. Kişinin WhatsApp kullanmıyor olması mümkün. " +
                    "Arama YAPILMADI."
            )
        }
    }

    /** Starts the call from WhatsApp's synced contact row. */
    private suspend fun callViaContactRow(dataId: Long, video: Boolean): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, dataId), mimeType(video))
            setPackage(WHATSAPP)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Contact-row call intent failed: ${e.message}")
            return false
        }
        confirmCallDialog()
        // Without accessibility the foreground can't be checked; a delivered intent is the best signal.
        return AccessibilitySvc.instance == null || waitForWhatsAppForeground()
    }

    /** Opens the chat and taps the toolbar call button (needs the accessibility service). */
    private suspend fun callViaChatScreen(number: String, video: Boolean): Boolean {
        if (AccessibilitySvc.instance == null) return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$number")).apply {
            setPackage(WHATSAPP)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Chat intent failed: ${e.message}")
            return false
        }
        val labels = if (video) listOf("Görüntülü arama", "Video call") else listOf("Sesli arama", "Voice call")
        val tapped = AccessibilitySvc.clickByLabel(labels[0], 5_000L, fallbackViewId = null) ||
            AccessibilitySvc.clickByLabel(labels[1], 1_500L, fallbackViewId = null)
        Log.d(TAG, "Chat call button tapped=$tapped")
        if (!tapped) return false
        confirmCallDialog()
        return true
    }

    /** Some WhatsApp versions ask "Arama yapılsın mı?" first — accept it if it shows up. */
    private fun confirmCallDialog() {
        val confirmed = AccessibilitySvc.clickByViewId(
            "android:id/button1", CONFIRM_DIALOG_WAIT_MS, packageName = WHATSAPP
        )
        if (confirmed) Log.d(TAG, "Accepted WhatsApp call confirmation dialog")
    }

    private suspend fun waitForWhatsAppForeground(): Boolean {
        val deadline = SystemClock.elapsedRealtime() + FOREGROUND_WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (AccessibilitySvc.activePackage() == WHATSAPP) return true
            delay(150)
        }
        Log.w(TAG, "WhatsApp never came to the foreground")
        return false
    }

    /**
     * Finds WhatsApp's voice/video call row for the contact: same aggregate
     * contact first, otherwise a row whose JID matches the number.
     */
    private fun findCallDataRow(contactId: String?, internationalNumber: String, video: Boolean): Long? {
        var byNumber: Long? = null
        try {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data._ID, ContactsContract.Data.CONTACT_ID, ContactsContract.Data.DATA1),
                "${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(mimeType(video)),
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    if (contactId != null && c.getString(1) == contactId) return id
                    val jidNumber = c.getString(2)?.substringBefore('@')
                    if (byNumber == null && jidNumber == internationalNumber) byNumber = id
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "WhatsApp contact rows unavailable: ${e.message}")
        }
        return byNumber
    }

    private fun isWhatsAppInstalled(): Boolean =
        runCatching { context.packageManager.getPackageInfo(WHATSAPP, 0) }.isSuccess

    private fun mimeType(video: Boolean) = if (video) MIME_VIDEO else MIME_VOICE

    private fun isVideo(args: JsonObject): Boolean =
        runCatching { args.get("video")?.asBoolean == true }.getOrDefault(false)

    private fun isConfirmed(args: JsonObject): Boolean =
        runCatching { args.get("confirmed")?.asBoolean == true }.getOrDefault(false)

    companion object {
        private const val TAG = "WhatsAppCallTool"
        private const val WHATSAPP = "com.whatsapp"
        private const val MIME_VOICE = "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
        private const val MIME_VIDEO = "vnd.android.cursor.item/vnd.com.whatsapp.video.call"
        private const val CONFIRM_DIALOG_WAIT_MS = 2_000L
        private const val FOREGROUND_WAIT_MS = 4_000L
    }
}

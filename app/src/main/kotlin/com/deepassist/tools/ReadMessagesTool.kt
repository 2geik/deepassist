package com.deepassist.tools

import android.content.Context
import android.provider.Telephony
import com.deepassist.data.MessageStore
import com.deepassist.data.ToolProperty
import com.deepassist.data.ToolResult
import com.deepassist.util.DeviceUtils
import com.deepassist.util.PermissionsHelper
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * One inbox for WhatsApp (and other chat apps, via the notification listener) and SMS,
 * grouped by chat. Personal messages by default; bank/operator/ad SMS are only
 * counted unless asked for, since they drowned out family messages.
 */
class ReadMessagesTool : Tool() {

    override val name = "read_messages"
    override val description =
        "Gelen mesajları okur: WhatsApp (ve diğer mesajlaşma uygulamaları) ile SMS birlikte, kişi ve gruba göre toplanmış. " +
            "Varsayılan olarak sadece kişisel mesajları verir; banka, operatör ve reklam mesajlarını sayı olarak özetler. " +
            "'Mesajlarımı oku', 'yeni mesaj var mı', 'Sevinç ne yazmış', 'grubundan gelen mesajları oku' için kullan."
    override val parameters = mapOf(
        "source" to ToolProperty(
            type = "string",
            description = "Hangi kaynaktan (varsayılan all)",
            enum = listOf("all", "whatsapp", "sms")
        ),
        "contact" to ToolProperty(
            type = "string",
            description = "İSTEĞE BAĞLI. Kişi, grup veya kurum adı (ör. Sevinç, Bozuklar, DenizBank)"
        ),
        "hours" to ToolProperty(
            type = "integer",
            description = "İSTEĞE BAĞLI. Son kaç saat (varsayılan 24; 'dün ve bugün' için 48)"
        ),
        "only_new" to ToolProperty(
            type = "boolean",
            description = "İSTEĞE BAĞLI. Sadece daha önce okunmamış mesajlar ('yeni mesaj var mı')"
        ),
        "include_ads" to ToolProperty(
            type = "boolean",
            description = "İSTEĞE BAĞLI. Kullanıcı banka, kurum veya reklam mesajlarını da isterse true"
        ),
        "limit" to ToolProperty(
            type = "integer",
            description = "İSTEĞE BAĞLI. En fazla kaç mesaj (varsayılan 20)"
        )
    )
    override val required = emptyList<String>()
    override val thinkingPhrase: String? = "Mesajlara bakıyorum..."

    private data class Item(
        val channel: String,
        val conversation: String,
        val isGroup: Boolean,
        val sender: String,
        val text: String,
        val timestamp: Long,
        val personal: Boolean,
        val isCode: Boolean,
        val storeId: String? = null,
        val smsId: String? = null
    )

    private val turkish = Locale("tr", "TR")
    private val timeFormat = SimpleDateFormat("HH:mm", turkish)
    private val dateFormat = SimpleDateFormat("d MMMM HH:mm", turkish)

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val hours = args.optInt("hours")?.coerceIn(1, 24 * 14) ?: 24
        val since = now - hours * 3_600_000L
        val source = args.optString("source")?.lowercase(Locale.ROOT) ?: "all"
        val contact = args.optString("contact")?.let(::fold)?.trim()?.takeIf { it.isNotEmpty() }
        val onlyNew = isTrue(args, "only_new")
        val includeAds = isTrue(args, "include_ads")
        val limit = args.optInt("limit")?.coerceIn(1, 50) ?: 20
        val listenerOn = PermissionsHelper.isNotificationListenerEnabled(context)

        val sms = if (source != "whatsapp") smsItems(since, onlyNew) else emptyList()
        val chats = if (source != "sms") chatItems(since, source == "whatsapp", onlyNew, sms) else emptyList()

        val matching = (chats + sms)
            .filter { contact == null || fold(it.conversation).contains(contact) || fold(it.sender).contains(contact) }
            .sortedByDescending { it.timestamp }
        val codes = matching.filter { it.isCode }
        val corporate = matching.filter { !it.personal && !it.isCode }
        val wanted = matching.filter { !it.isCode && (it.personal || includeAds || contact != null) }.take(limit)

        val output = StringBuilder()
        val spoken = mutableListOf<Item>()
        if (wanted.isEmpty()) {
            output.append(
                when {
                    onlyNew -> "Yeni mesaj yok."
                    contact != null -> "Son $hours saatte bu kişiden veya gruptan mesaj yok."
                    else -> "Son $hours saatte kişisel mesaj yok."
                }
            )
        } else {
            output.append("MESAJLAR (en yeni konuşma önce):\n")
            // groupBy keeps first-seen order, and the list is newest first
            for (msgs in wanted.groupBy { it.channel + "|" + it.conversation }.values) {
                val first = msgs.first()
                val label = "${first.channel} · ${first.conversation}${if (first.isGroup) " grubu" else ""}"
                val lines = msgs.sortedBy { it.timestamp }.joinToString(" | ") { m ->
                    val who = if (m.isGroup) "${m.sender}: " else ""
                    "[${timeLabel(m.timestamp)}] $who${clean(m.text).take(MAX_MESSAGE_CHARS)}"
                }
                val line = "- $label (${msgs.size} mesaj): $lines\n"
                if (output.length + line.length > MAX_OUTPUT_CHARS) {
                    output.append("(Daha fazla mesaj var; kullanıcı kişi adıyla ayrıca sorabilir.)\n")
                    break
                }
                output.append(line)
                spoken += msgs
            }
        }
        if (!includeAds && contact == null && corporate.isNotEmpty()) {
            val bySender = corporate.groupingBy { it.sender }.eachCount().entries
                .sortedByDescending { it.value }.take(5)
                .joinToString(", ") { "${it.key} ${it.value}" }
            output.append("\nAyrıca ${corporate.size} kurumsal/reklam mesajı var ($bySender). Kullanıcı isterse include_ads=true ile oku.")
        }
        if (codes.isNotEmpty()) {
            output.append("\n${codes.size} doğrulama kodu mesajı var; kod sorulursa read_verification_code kullan.")
        }
        if (!listenerOn && source != "sms") {
            output.append(
                "\nNOT: Bildirim erişimi kapalı olduğu için WhatsApp mesajlarını göremiyorum; " +
                    "telefon ayarlarından deepAssist'e bildirim erişimi verilmesi gerekiyor."
            )
        }
        output.append("\nMesajları kimden geldiğini söyleyerek doğal cümlelerle oku; telefon numarası okuma.")

        // Counted-only messages are "announced" too, so "yeni mesaj var mı" doesn't repeat them
        markRead(spoken + codes + if (includeAds || contact != null) emptyList() else corporate)
        ToolResult(true, output.toString())
    }

    private fun chatItems(since: Long, whatsappOnly: Boolean, onlyNew: Boolean, sms: List<Item>): List<Item> {
        val smsPackage = runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()
        return MessageStore.get(context).snapshot()
            .filter { m ->
                m.isChat && !m.fromMe && m.timestamp >= since &&
                    (!whatsappOnly || m.packageName in WHATSAPP_PACKAGES) &&
                    (!onlyNew || m.readAloudAt == 0L)
            }
            // The SMS app's own notifications repeat what the SMS inbox already has
            .filterNot { m ->
                m.packageName == smsPackage && sms.any { s ->
                    Math.abs(s.timestamp - m.timestamp) < 5 * 60_000L && clean(s.text) == clean(m.text)
                }
            }
            .map { m ->
                Item(
                    channel = m.appName,
                    conversation = m.conversation,
                    isGroup = m.isGroup,
                    sender = m.sender,
                    text = m.text,
                    timestamp = m.timestamp,
                    personal = true,
                    isCode = false,
                    storeId = m.id
                )
            }
    }

    private fun smsItems(since: Long, onlyNew: Boolean): List<Item> {
        if (!PermissionsHelper.hasReadSms(context)) return emptyList()
        val readIds = readSmsIds()
        val names = HashMap<String, String?>()
        val out = mutableListOf<Item>()
        runCatching {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                "${Telephony.Sms.DATE} >= ?",
                arrayOf(since.toString()),
                "${Telephony.Sms.DATE} DESC"
            )?.use { c ->
                var scanned = 0
                while (c.moveToNext() && scanned++ < MAX_SMS) {
                    val id = c.getString(0) ?: continue
                    if (onlyNew && id in readIds) continue
                    val address = c.getString(1).orEmpty()
                    val body = c.getString(2) ?: continue
                    val alphanumeric = address.any(Char::isLetter)
                    val contactName = if (alphanumeric) null else {
                        names.getOrPut(address) { VerificationCodeTool.contactNameFor(context, address) }
                    }
                    val sender = when {
                        alphanumeric -> address
                        contactName != null -> contactName
                        else -> "kayıtlı olmayan numara"
                    }
                    out.add(
                        Item(
                            channel = "SMS",
                            conversation = sender,
                            isGroup = false,
                            sender = sender,
                            text = body,
                            timestamp = c.getLong(3),
                            personal = contactName != null || !isCorporateSms(address, body),
                            isCode = contactName == null && VerificationCodeTool.extractCode(body) != null,
                            smsId = id
                        )
                    )
                }
            }
        }
        return out
    }

    private fun isCorporateSms(address: String, body: String): Boolean {
        if (address.any(Char::isLetter)) return true
        val digits = address.filter(Char::isDigit)
        if (digits.length in 1..7) return true
        val normalized = DeviceUtils.normalizePhoneNumber(address)
        if (normalized.startsWith("0850") || normalized.startsWith("0444") || normalized.startsWith("0800")) return true
        val folded = fold(body)
        return AD_MARKERS.any { folded.contains(it) }
    }

    private fun markRead(items: List<Item>) {
        MessageStore.get(context).markReadAloud(items.mapNotNull { it.storeId })
        val smsIds = items.mapNotNull { it.smsId }
        if (smsIds.isEmpty()) return
        val updated = (readSmsIds() + smsIds)
            .sortedBy { it.toLongOrNull() ?: 0L }
            .takeLast(MAX_READ_SMS_IDS)
            .toSet()
        prefs().edit().putStringSet(KEY_READ_SMS, updated).apply()
    }

    private fun readSmsIds(): Set<String> = prefs().getStringSet(KEY_READ_SMS, emptySet()) ?: emptySet()

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun timeLabel(timestamp: Long): String {
        val msg = Calendar.getInstance().apply { timeInMillis = timestamp }
        val today = Calendar.getInstance()
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        fun sameDay(a: Calendar, b: Calendar) =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        return when {
            sameDay(msg, today) -> timeFormat.format(timestamp)
            sameDay(msg, yesterday) -> "dün " + timeFormat.format(timestamp)
            else -> dateFormat.format(timestamp)
        }
    }

    private fun isTrue(args: JsonObject, key: String): Boolean =
        runCatching { args.get(key)?.asBoolean == true }.getOrDefault(false)

    private fun fold(s: String): String = DeviceUtils.toAsciiTurkce(s)

    /** Drops emoji and invisible joiners — the text is read aloud. */
    private fun clean(text: String): String =
        text.replace(EMOJI, "").replace(WHITESPACE, " ").trim()

    companion object {
        private const val PREFS = "read_messages"
        private const val KEY_READ_SMS = "read_sms_ids"
        private const val MAX_READ_SMS_IDS = 500
        private const val MAX_SMS = 300
        private const val MAX_MESSAGE_CHARS = 700
        private const val MAX_OUTPUT_CHARS = 3_800

        private val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
        private val AD_MARKERS = listOf(
            "ret icin", "iptal icin", "mersis", "sms almak istemiyorsaniz", "iletisim izni", "kampanya"
        )
        private val EMOJI = Regex("[\\p{So}\\p{Cn}\\u200d\\ufe0f]")
        private val WHITESPACE = Regex("\\s+")
    }
}

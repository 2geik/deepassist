package com.deepassist.tools

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.TelephonyManager
import com.deepassist.data.ToolProperty
import com.deepassist.data.ToolResult
import com.deepassist.service.NotificationStore
import com.deepassist.util.DeviceUtils
import com.deepassist.util.PermissionsHelper
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Finds the newest one-time code in SMS and captured notifications and prepares it
 * for digit-by-digit speech — read as a whole number ("504253") the user misheard it.
 * Warns first when a call is in progress or an unknown number just called, the
 * usual "tell me the code we sent you" scam.
 */
class VerificationCodeTool : Tool() {

    override val name = "read_verification_code"
    override val description =
        "Son gelen doğrulama/onay kodunu veya tek kullanımlık şifreyi SMS ve bildirimlerden bulur, " +
            "rakam rakam okunacak metni hazırlar. 'Kodu oku', 'doğrulama kodu geldi mi', 'şifre geldi mi' için kullan."
    override val parameters = mapOf(
        "from" to ToolProperty(
            type = "string",
            description = "İSTEĞE BAĞLI. Kodu gönderen kurum veya uygulama (ör. Migros, WhatsApp, e-Devlet)"
        )
    )
    override val required = emptyList<String>()
    override val thinkingPhrase: String? = "Koda bakıyorum..."

    private data class Found(
        val source: String,
        val text: String,
        val rawCode: String,
        val digits: String,
        val timestamp: Long
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val since = now - LOOKBACK_MS
        val from = args.optString("from")?.let(::fold)?.trim()?.takeIf { it.isNotEmpty() }

        val found = (smsCandidates(since) + notificationCandidates(since))
            .filter { from == null || fold(it.source).contains(from) || fold(it.text).contains(from) }
            .maxByOrNull { it.timestamp }

        if (found == null) {
            val scope = from?.let { " ($it için)" } ?: ""
            val note = if (!PermissionsHelper.hasReadSms(context)) {
                " SMS okuma izni kapalı olduğu için SMS'lere bakamadım."
            } else ""
            return@withContext ToolResult(true, "Son 24 saatte doğrulama kodu içeren bir mesaj bulamadım$scope.$note")
        }

        val spoken = spellDigits(found.digits)
        val result = buildString {
            scamWarning(now)?.let { append("UYARI — KODDAN ÖNCE BUNU SÖYLE: $it\n") }
            append("KAYNAK: ${found.source}, ${ago(now - found.timestamp)}\n")
            append("MESAJ (kod gizlendi): ${found.text.replace(found.rawCode, "[KOD]").take(300)}\n")
            append("SESLİ OKU (birebir, rakam rakam): \"Kod: $spoken. Tekrar ediyorum: $spoken.\"\n")
            append(
                "Kodu ASLA tek bir sayı gibi okuma. Kullanıcı tekrar isterse yine rakam rakam oku. " +
                    "Kodun ne için olduğunu mesajdan tek cümleyle söyleyebilirsin."
            )
        }
        ToolResult(true, result)
    }

    private fun smsCandidates(since: Long): List<Found> {
        if (!PermissionsHelper.hasReadSms(context)) return emptyList()
        val out = mutableListOf<Found>()
        runCatching {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                "${Telephony.Sms.DATE} >= ?",
                arrayOf(since.toString()),
                "${Telephony.Sms.DATE} DESC"
            )?.use { c ->
                var scanned = 0
                while (c.moveToNext() && scanned++ < MAX_SMS) {
                    val body = c.getString(1) ?: continue
                    val code = extractCode(body) ?: continue
                    val source = senderLabel(context, c.getString(0).orEmpty())
                    out.add(Found(source, body, code.first, code.second, c.getLong(2)))
                }
            }
        }
        return out
    }

    private fun notificationCandidates(since: Long): List<Found> =
        NotificationStore.snapshot()
            .filter { it.timestamp >= since }
            .mapNotNull { n ->
                val text = listOfNotNull(n.title, n.text).joinToString(" ")
                val code = extractCode(text) ?: return@mapNotNull null
                Found(n.appName, text, code.first, code.second, n.timestamp)
            }

    private fun scamWarning(now: Long): String? {
        val audioInCall = runCatching {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION
        }.getOrDefault(false)
        val phoneInCall = runCatching {
            @Suppress("DEPRECATION")
            val state = (context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).callState
            state != TelephonyManager.CALL_STATE_IDLE
        }.getOrDefault(false)
        if (audioInCall || phoneInCall) {
            return "Şu an bir görüşme açık görünüyor. Bu kodu telefondaki kişiye söyleme; " +
                "banka, operatör, polis ya da kargo firması telefonda asla kod istemez."
        }
        if (unknownCallSince(now - RECENT_CALL_MS)) {
            return "Az önce rehberde kayıtlı olmayan bir numarayla konuşuldu. Bu kodu o kişi istediyse söyleme; " +
                "banka, operatör, polis ya da kargo firması telefonda asla kod istemez."
        }
        return null
    }

    /** A connected call (in or out) with a number that isn't in the contacts. */
    private fun unknownCallSince(since: Long): Boolean {
        if (!PermissionsHelper.hasCallLog(context)) return false
        return runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DURATION),
                "${CallLog.Calls.DATE} >= ?",
                arrayOf(since.toString()),
                "${CallLog.Calls.DATE} DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val type = c.getInt(2)
                    if (type != CallLog.Calls.INCOMING_TYPE && type != CallLog.Calls.OUTGOING_TYPE) continue
                    if (c.getLong(3) <= 0) continue
                    val number = c.getString(0) ?: continue
                    if (!c.getString(1).isNullOrBlank()) continue
                    if (contactNameFor(context, number) == null) return@runCatching true
                }
                false
            } ?: false
        }.getOrDefault(false)
    }

    private fun ago(ms: Long): String = when {
        ms < 60_000L -> "az önce"
        ms < 3_600_000L -> "${ms / 60_000L} dakika önce"
        else -> "${ms / 3_600_000L} saat önce"
    }

    companion object {
        private const val LOOKBACK_MS = 24 * 60 * 60 * 1000L
        private const val RECENT_CALL_MS = 20 * 60 * 1000L
        private const val MAX_SMS = 40

        private val KEYWORDS = listOf(
            "kod", "code", "sifre", "parola", "otp", "dogrulama", "onay",
            "tek kullanimlik", "verification", "pin", "guvenlik"
        )
        // Sentence punctuation may touch a code ("Kod:123456."), but not a decimal or date ("1.500", "11.09.2026").
        private val CODE_REGEX = Regex("(?<![\\d*+])(?<!\\d[.,:/])(\\d{3}[ -]\\d{3}|\\d{4,8})(?!\\d|[.,:/]\\d)")
        private val NOT_A_CODE_AFTER = listOf(
            "tl", "₺", "lira", "ile biten", "nolu", "numarali", "adet", "gb", "mb", "dk", "dakika"
        )
        private val DIGIT_WORDS = listOf("sıfır", "bir", "iki", "üç", "dört", "beş", "altı", "yedi", "sekiz", "dokuz")

        private fun fold(s: String): String = DeviceUtils.toAsciiTurkce(s)

        /**
         * The number nearest an OTP keyword as (written form, digits only), or null
         * when the text mentions no code. Amounts, card endings and reference
         * numbers are skipped.
         */
        fun extractCode(text: String): Pair<String, String>? {
            val lower = fold(text)
            val keywordAt = KEYWORDS.flatMap { kw ->
                Regex(Regex.escape(kw)).findAll(lower).map { it.range.first }.toList()
            }
            if (keywordAt.isEmpty()) return null

            var best: MatchResult? = null
            var bestScore = Int.MAX_VALUE
            for (m in CODE_REGEX.findAll(lower)) {
                val digits = m.value.filter(Char::isDigit)
                val before = lower.substring(0, m.range.first).trimEnd()
                val after = lower.substring(m.range.last + 1).trimStart()
                if (NOT_A_CODE_AFTER.any { after.startsWith(it) }) continue
                if (before.endsWith("tl") || before.endsWith("₺") || before.endsWith("sonu") ||
                    before.endsWith(" no") || before.endsWith("no:")
                ) continue
                var score = keywordAt.minOf { k -> if (k < m.range.first) m.range.first - k else k - m.range.last }
                if (digits.length != 6) score += 15
                if (digits.length == 4 && (digits.startsWith("19") || digits.startsWith("20"))) score += 40
                if (score < bestScore) {
                    bestScore = score
                    best = m
                }
            }
            val match = best ?: return null
            return match.value to match.value.filter(Char::isDigit)
        }

        fun spellDigits(digits: String): String =
            digits.filter(Char::isDigit).map { DIGIT_WORDS[it - '0'] }.joinToString(", ")

        /** Alphanumeric senders (banks, shops) as-is; numbers as a contact name, never the digits. */
        fun senderLabel(context: Context, address: String): String = when {
            address.any { it.isLetter() } -> address
            else -> contactNameFor(context, address) ?: "kayıtlı olmayan bir numara"
        }

        fun contactNameFor(context: Context, number: String): String? {
            if (!PermissionsHelper.hasContacts(context) || number.isBlank()) return null
            return runCatching {
                context.contentResolver.query(
                    Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                    arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull()
        }
    }
}

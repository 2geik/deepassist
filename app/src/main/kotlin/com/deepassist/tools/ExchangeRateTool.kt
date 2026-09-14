package com.deepassist.tools

import com.deepassist.data.ToolProperty
import com.deepassist.data.ToolResult
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

class ExchangeRateTool : Tool() {

    override val name = "get_exchange_rate"
    override val description =
        "Güncel döviz kurunu döndürür. Dolar, euro, sterlin, ruble gibi para birimlerinin " +
            "TL karşılığını öğrenmek için kullan. Kur sorularında search_web yerine BUNU kullan."
    override val parameters = mapOf(
        "base" to ToolProperty(
            type = "string",
            description = "Kaynak para birimi (ISO kodu: USD, EUR, GBP, TRY, CHF, SAR, RUB)",
            enum = listOf("USD", "EUR", "GBP", "TRY", "CHF", "SAR", "RUB")
        ),
        "target" to ToolProperty(
            type = "string",
            description = "Hedef para birimi (varsayılan: TRY). Belirtilmezse TL'ye çevirir."
        ),
        "amount" to ToolProperty(
            type = "string",
            description = "Opsiyonel miktar. Belirtilmezse 1 birim üzerinden hesaplanır."
        )
    )
    override val required = listOf("base")
    override val thinkingPhrase: String? = "Kura bakıyorum..."

    companion object {
        private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, JsonObject>>()
        private const val CACHE_TTL_MS = 10 * 60 * 1000L // 10 minutes
    }

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val base = args.optString("base")?.trim()?.uppercase()
        if (base.isNullOrBlank()) {
            return@withContext ToolResult(false, "", error = "Kaynak para birimi belirtilmedi.")
        }

        val validCodes = setOf("USD", "EUR", "GBP", "TRY", "CHF", "SAR", "RUB")
        if (base !in validCodes) {
            return@withContext ToolResult(
                false, "",
                error = "Geçersiz para birimi kodu: $base. ISO kodunu kontrol et."
            )
        }

        val target = args.optString("target")?.trim()?.uppercase() ?: "TRY"
        val amount = args.optString("amount")?.toDoubleOrNull() ?: 1.0

        val json = try {
            fetchRates(base)
        } catch (e: Exception) {
            return@withContext ToolResult(
                false, "",
                error = "Kur servisine şu an ulaşılamıyor. İnternet bağlantını kontrol et veya search_web ile dene."
            )
        }

        val result = json?.get("result")?.asString
        if (result != "success") {
            return@withContext ToolResult(
                false, "",
                error = "Kur servisine şu an ulaşılamıyor. İnternet bağlantını kontrol et veya search_web ile dene."
            )
        }

        val rates = json.getAsJsonObject("rates")
        if (rates == null || !rates.has(target)) {
            return@withContext ToolResult(
                false, "",
                error = "Geçersiz para birimi kodu: $base. ISO kodunu kontrol et."
            )
        }

        val rate = rates.get(target).asDouble
        val converted = amount * rate
        val lastUpdate = json.get("time_last_update_utc")?.asString ?: ""

        val formatted = if (target == "TRY") {
            buildString {
                val prefix = if (amount == 1.0) "" else formatAmount(amount) + " "
                append(prefix)
                append("$base = ")
                append(formatAmount(converted))
                append(" TL")
                append(" (${formatTl(converted)}")
                append(" civarı).")
                if (lastUpdate.isNotBlank()) {
                    append(" Son güncelleme: $lastUpdate.")
                }
            }
        } else {
            buildString {
                val prefix = if (amount == 1.0) "" else formatAmount(amount) + " "
                append(prefix)
                append("$base = ")
                append(formatAmount(converted))
                append(" $target.")
                if (lastUpdate.isNotBlank()) {
                    append(" Son güncelleme: $lastUpdate.")
                }
            }
        }

        ToolResult(true, formatted)
    }

    private fun fetchRates(base: String): JsonObject? {
        val now = System.currentTimeMillis()
        val cached = cache[base]
        if (cached != null && (now - cached.first) < CACHE_TTL_MS) {
            return cached.second
        }

        val url = "https://open.er-api.com/v6/latest/$base"
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val json = gson.fromJson(body, JsonObject::class.java)
            cache[base] = Pair(now, json)
            return json
        }
    }

    private fun formatAmount(value: Double): String {
        val intPart = value.toInt()
        val frac = ((value - intPart) * 100).toInt()
        return if (frac > 0) {
            "%,d.%02d".format(Locale.US, intPart, frac)
        } else {
            "%,d".format(Locale.US, intPart)
        }
    }

    private fun formatTl(value: Double): String {
        val intPart = value.toInt()
        val frac = ((value - intPart) * 100).toInt()

        return when {
            intPart == 0 -> "$frac kuruş"
            frac == 0 -> "$intPart lira"
            else -> "$intPart lira $frac kuruş"
        }
    }
}

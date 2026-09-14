package com.deepassist.tools

import android.util.Log
import com.deepassist.data.CallLogEntry
import com.deepassist.data.CallLogStore
import com.deepassist.data.ToolProperty
import com.deepassist.data.ToolResult
import com.deepassist.util.PermissionsHelper
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CallLogTool : Tool() {

    override val name = "call_history"
    override val description =
        "Çağrı geçmişini (gelen, giden, cevapsız aramalar) listeler. Varsayılan olarak son 3 arama gösterilir. " +
            "İsteğe bağlı olarak kişi adı veya numara ile filtreleme yapar. Son aramalar, kim aramış gibi sorularda kullan."
    override val parameters = mapOf(
        "filter" to ToolProperty(
            type = "string",
            description = "İsteğe bağlı. Aramalarda aranacak kişi adı veya numara. Boş bırakılırsa tüm son aramaları getirir."
        ),
        "call_type" to ToolProperty(
            type = "string",
            description = "İsteğe bağlı. Filtrelenecek arama tipi. Boş bırakılırsa tüm tipler getirilir.",
            enum = listOf("INCOMING", "OUTGOING", "MISSED", "REJECTED", "BLOCKED")
        ),
        "limit" to ToolProperty(
            type = "integer",
            description = "İsteğe bağlı. Döndürülecek maksimum arama sayısı. Varsayılan: 3. Kullanıcı daha fazla isterse artırılır."
        )
    )
    override val required = emptyList<String>()
    override val thinkingPhrase: String? = "Çağrı geçmişine bakıyorum..."

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        if (!PermissionsHelper.hasCallLog(context)) {
            return@withContext ToolResult(
                false, "",
                error = "Çağrı geçmişi izni verilmemiş. Lütfen ayarlardan Arama Kayıtları iznini açın."
            )
        }
        val filter = args.optString("filter")?.trim()
        val callType = args.optString("call_type")?.trim()?.uppercase(Locale.ROOT)
        val limit = args.optInt("limit") ?: 3

        try {
            val store = CallLogStore.get(context)
            val added = store.refreshFromContentResolver(context)
            Log.d(TAG, "Refresh added $added entries, store size=${store.size()}")
            if (store.size() == 0) {
                return@withContext ToolResult(true, "Çağrı geçmişinde hiç arama bulunamadı.")
            }

            val results = store.query(filter = filter, callType = callType, limit = limit)
            if (results.isNotEmpty()) {
                val typeDesc = if (callType != null) " ${typeLabel(callType)}" else ""
                return@withContext ToolResult(
                    true,
                    "Son$typeDesc aramalar (${results.size}):\n${formatEntries(results)}"
                )
            }

            val msg = when {
                filter != null && callType != null ->
                    "Çağrı geçmişinde \"$filter\" ile eşleşen ${typeLabel(callType)} arama bulunamadı."
                filter != null -> "Çağrı geçmişinde \"$filter\" ile eşleşen arama bulunamadı."
                callType != null -> "Çağrı geçmişinde ${typeLabel(callType)} arama bulunamadı."
                else -> "Çağrı geçmişinde arama bulunamadı."
            }
            ToolResult(true, msg)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException", e)
            ToolResult(
                false, "",
                error = "Çağrı geçmişine erişim reddedildi. Ayarlar > Uygulamalar > deepAssist > İzinler'den Arama Kayıtları iznini kontrol edin."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception", e)
            ToolResult(false, "", error = "Çağrı geçmişi okunurken hata: ${e.message}")
        }
    }

    private fun formatEntries(entries: List<CallLogEntry>): String {
        val dateFormat = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
        return entries.joinToString("\n") { entry ->
            val displayName = entry.contactName ?: entry.phoneNumber
            val typeStr = when (entry.callType) {
                "MISSED" -> "Cevapsız"
                "REJECTED" -> "Reddedildi"
                "BLOCKED" -> "Engellendi"
                "OUTGOING" -> "Giden"
                "INCOMING" -> "Gelen"
                else -> "Diğer"
            }
            val durStr = if (entry.duration > 0) " (${entry.duration}s)" else ""
            "- [$typeStr] $displayName$durStr — ${dateFormat.format(Date(entry.timestamp))}"
        }
    }

    private fun typeLabel(type: String): String = when (type.uppercase(Locale.ROOT)) {
        "MISSED" -> "cevapsız"
        "REJECTED" -> "reddedilen"
        "BLOCKED" -> "engellenen"
        "OUTGOING" -> "giden"
        "INCOMING" -> "gelen"
        else -> ""
    }

    companion object {
        private const val TAG = "CallLogTool"
    }
}

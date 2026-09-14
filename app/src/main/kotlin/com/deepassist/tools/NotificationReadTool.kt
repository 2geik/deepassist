package com.deepassist.tools

import com.deepassist.data.ToolProperty
import com.deepassist.data.ToolResult
import com.deepassist.service.NotificationStore
import com.deepassist.util.PermissionsHelper
import com.google.gson.JsonObject
import java.text.SimpleDateFormat
import java.util.Locale

class NotificationReadTool : Tool() {

    override val name = "read_notifications"
    override val description =
        "Son bildirimleri okur. İsteğe bağlı olarak uygulama adına göre filtrelenebilir " +
            "(örn. WhatsApp, Instagram)."
    override val parameters = mapOf(
        "app_filter" to ToolProperty(
            type = "string",
            description = "Bildirimleri filtrelenecek uygulama adı (isteğe bağlı)"
        ),
        "limit" to ToolProperty(
            type = "string",
            description = "Okunacak bildirim sayısı (varsayılan 10)"
        )
    )
    override val required = emptyList<String>()
    override val thinkingPhrase: String? = "Bildirimlere bakıyorum..."

    private val timeFormat = SimpleDateFormat("HH:mm", Locale("tr", "TR"))

    override suspend fun execute(args: JsonObject): ToolResult {
        if (!PermissionsHelper.isAccessibilityEnabled(context)) {
            return ToolResult(
                false,
                "",
                error = "Erişilebilirlik servisi etkin değil. Bildirimleri okuyabilmem için " +
                    "ayarlardan deepAssist erişilebilirlik servisini açman gerekiyor."
            )
        }
        val limit = args.optString("limit")?.toIntOrNull()?.coerceIn(1, 50) ?: 10
        val filter = args.optString("app_filter")?.trim()?.lowercase(Locale("tr", "TR"))

        var notifications = NotificationStore.snapshot().reversed() // newest first
        if (!filter.isNullOrBlank()) {
            notifications = notifications.filter {
                it.appName.lowercase(Locale("tr", "TR")).contains(filter) ||
                    it.packageName.lowercase(Locale.ROOT).contains(filter)
            }
        }
        notifications = notifications.take(limit)

        return if (notifications.isEmpty()) {
            val scope = if (filter.isNullOrBlank()) "" else " ($filter için)"
            ToolResult(true, "Kayıtlı bildirim yok$scope. Servis açıldığından beri bildirim gelmemiş olabilir.")
        } else {
            val listing = notifications.joinToString("\n") { n ->
                val title = n.title?.takeIf { it.isNotBlank() }?.let { "$it: " } ?: ""
                "- [${timeFormat.format(n.timestamp)}] ${n.appName} — $title${n.text.orEmpty().take(200)}"
            }
            ToolResult(true, "Son ${notifications.size} bildirim:\n$listing")
        }
    }
}

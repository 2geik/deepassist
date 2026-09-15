package com.deepassist.tools

import com.deepassist.data.MessageStore
import com.deepassist.data.NotificationInfo
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
    private val dateTimeFormat = SimpleDateFormat("d MMMM HH:mm", Locale("tr", "TR"))
    private val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.ROOT)

    override suspend fun execute(args: JsonObject): ToolResult {
        val listenerOn = PermissionsHelper.isNotificationListenerEnabled(context)
        if (!listenerOn && !PermissionsHelper.isAccessibilityEnabled(context)) {
            return ToolResult(
                false,
                "",
                error = "Bildirimleri okuyabilmem için telefon ayarlarından deepAssist'e " +
                    "bildirim erişimi verilmesi gerekiyor."
            )
        }
        val limit = args.optString("limit")?.toIntOrNull()?.coerceIn(1, 50) ?: 10
        val filter = args.optString("app_filter")?.trim()?.lowercase(Locale("tr", "TR"))

        // The listener's store survives app restarts; the accessibility capture is memory-only
        var notifications = if (listenerOn) storedNotifications() else NotificationStore.snapshot().reversed()
        if (!filter.isNullOrBlank()) {
            notifications = notifications.filter {
                it.appName.lowercase(Locale("tr", "TR")).contains(filter) ||
                    it.packageName.lowercase(Locale.ROOT).contains(filter)
            }
        }
        notifications = notifications.take(limit)

        return if (notifications.isEmpty()) {
            val scope = if (filter.isNullOrBlank()) "" else " ($filter için)"
            val hint = if (listenerOn) "" else " Servis açıldığından beri bildirim gelmemiş olabilir."
            ToolResult(true, "Kayıtlı bildirim yok$scope.$hint")
        } else {
            val today = dayFormat.format(System.currentTimeMillis())
            val listing = notifications.joinToString("\n") { n ->
                val title = n.title?.takeIf { it.isNotBlank() }?.let { "$it: " } ?: ""
                val time = if (dayFormat.format(n.timestamp) == today) {
                    timeFormat.format(n.timestamp)
                } else {
                    dateTimeFormat.format(n.timestamp)
                }
                "- [$time] ${n.appName} — $title${n.text.orEmpty().take(500)}"
            }
            ToolResult(true, "Son ${notifications.size} bildirim:\n$listing")
        }
    }

    /** Newest first. */
    private fun storedNotifications(): List<NotificationInfo> =
        MessageStore.get(context).snapshot().asReversed()
            .filter { !it.fromMe }
            .map { m ->
                val title = when {
                    !m.isChat -> m.conversation
                    m.isGroup -> "${m.conversation} — ${m.sender}"
                    else -> m.sender
                }
                NotificationInfo(m.packageName, m.appName, title, m.text, m.timestamp)
            }
}

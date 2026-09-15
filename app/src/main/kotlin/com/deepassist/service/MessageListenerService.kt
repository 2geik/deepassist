package com.deepassist.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.deepassist.data.MessageStore
import com.deepassist.data.NotificationInfo
import com.deepassist.data.StoredMessage
import com.deepassist.util.PermissionsHelper

/**
 * Captures chat messages (MessagingStyle: WhatsApp, Telegram, SMS apps…) one by one
 * with sender and group, plus other notifications with their full text, into
 * [MessageStore]. Also tells [NotificationReplier] which WhatsApp chats can be
 * answered from their notification.
 */
class MessageListenerService : NotificationListenerService() {

    private val appLabels = HashMap<String, String>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "connected")
        // Messages that arrived while the listener was unbound are still in the shade
        runCatching { activeNotifications?.forEach(::ingest) }
            .onFailure { Log.w(TAG, "active notifications: ${it.message}") }
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        NotificationReplier.clear()
        Log.i(TAG, "disconnected")
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        runCatching { ingest(sbn) }.onFailure { Log.w(TAG, "ingest ${sbn.packageName}: ${it.message}") }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.let { NotificationReplier.onRemoved(it) }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun ingest(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return // children carry the messages
        if (sbn.isOngoing) return // media players, calls in progress, uploads
        if (n.extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0) return

        val appName = appLabel(sbn.packageName)
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val messages = if (style != null && style.messages.isNotEmpty()) {
            chatMessages(sbn, appName, style)
        } else {
            plainNotification(sbn, appName, n)
        }
        if (messages.isEmpty()) return

        val added = MessageStore.get(this).addAll(messages)
        NotificationReplier.onPosted(sbn, messages)
        for (m in added) {
            if (m.fromMe) continue
            val title = when {
                !m.isChat -> m.conversation
                m.isGroup -> "${m.conversation} — ${m.sender}"
                else -> m.sender
            }
            NotificationStore.add(NotificationInfo(m.packageName, m.appName, title, m.text, m.timestamp))
        }
        if (added.isNotEmpty()) Log.d(TAG, "${sbn.packageName}: +${added.size}")
    }

    private fun chatMessages(
        sbn: StatusBarNotification,
        appName: String,
        style: NotificationCompat.MessagingStyle
    ): List<StoredMessage> {
        val title = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val conversation = cleanTitle(
            style.conversationTitle?.toString()?.takeIf { it.isNotBlank() } ?: title ?: appName
        )
        val isGroup = style.isGroupConversation
        val me = style.user
        return style.messages.mapNotNull { m ->
            val text = m.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return@mapNotNull null
            val person = m.person
            val fromMe = person == null ||
                (me.key != null && person.key == me.key) ||
                (person.name != null && person.name.toString() == me.name?.toString())
            val sender = if (fromMe) "Ben" else person?.name?.toString()?.takeIf { it.isNotBlank() } ?: conversation
            StoredMessage(
                id = MessageStore.idOf(sbn.packageName, conversation, sender, text, m.timestamp),
                packageName = sbn.packageName,
                appName = appName,
                conversation = conversation,
                isGroup = isGroup,
                sender = sender,
                text = text,
                timestamp = m.timestamp.takeIf { it > 0 } ?: sbn.postTime,
                fromMe = fromMe,
                isChat = true
            )
        }
    }

    private fun plainNotification(sbn: StatusBarNotification, appName: String, n: Notification): List<StoredMessage> {
        val extras = n.extras
        val title = cleanTitle(extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty())
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n")
        val text = listOf(
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            lines,
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        ).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return emptyList()
        val conversation = title.ifEmpty { appName }
        return listOf(
            StoredMessage(
                id = MessageStore.idOf(sbn.packageName, sbn.id, sbn.tag, title, text),
                packageName = sbn.packageName,
                appName = appName,
                conversation = conversation,
                isGroup = false,
                sender = conversation,
                text = text,
                timestamp = sbn.postTime,
                fromMe = false,
                isChat = false
            )
        )
    }

    private fun appLabel(pkg: String): String = appLabels.getOrPut(pkg) {
        runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    companion object {
        private const val TAG = "MessageListener"

        private val directionMarks = Regex("[\\u200e\\u200f\\u202a-\\u202e]")
        private val unreadCount = Regex("\\s*\\(\\d+[^)]*\\)\\s*$")

        @Volatile
        var instance: MessageListenerService? = null

        /** "Bozuklar (3 mesaj)" → "Bozuklar", so a chat keeps one stable title. */
        private fun cleanTitle(title: String): String =
            title.replace(directionMarks, "").replace(unreadCount, "").trim()

        /** HyperOS sometimes unbinds listeners after the process dies; ask to reconnect. */
        fun rebindIfNeeded(context: Context) {
            if (instance != null || !PermissionsHelper.isNotificationListenerEnabled(context)) return
            runCatching {
                requestRebind(ComponentName(context, MessageListenerService::class.java))
            }.onFailure { Log.w(TAG, "rebind: ${it.message}") }
        }
    }
}

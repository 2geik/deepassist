package com.deepassist.service

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.util.Log
import com.deepassist.data.StoredMessage
import com.deepassist.util.DeviceUtils
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Answers a WhatsApp chat through its notification's reply action — no WhatsApp
 * screen, no accessibility tap, works on the lock screen. A reply only counts as
 * sent when WhatsApp re-posts the notification showing our text as our own message.
 */
object NotificationReplier {

    enum class Outcome { NO_TARGET, SENT, UNVERIFIED, FAILED }

    private const val TAG = "NotifReply"
    private const val ECHO_TIMEOUT_MS = 6_000L
    private val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
    private val whitespace = Regex("\\s+")

    private class Target(val sbn: StatusBarNotification, val action: Notification.Action, val title: String)

    private class PendingEcho(val key: String, val title: String, val text: String) {
        val latch = CountDownLatch(1)
    }

    /** One-to-one WhatsApp chats with a reply action, by notification key. */
    private val targets = ConcurrentHashMap<String, Target>()

    @Volatile
    private var pendingEcho: PendingEcho? = null

    fun onPosted(sbn: StatusBarNotification, messages: List<StoredMessage>) {
        if (sbn.packageName !in WHATSAPP_PACKAGES) return
        val chat = messages.firstOrNull()?.takeIf { it.isChat }
        val action = replyAction(sbn.notification)
        if (chat != null && !chat.isGroup && action != null) {
            targets[sbn.key] = Target(sbn, action, chat.conversation)
        } else {
            targets.remove(sbn.key)
        }

        val echo = pendingEcho ?: return
        if (sbn.key != echo.key && chat?.conversation != echo.title) return
        val history = sbn.notification.extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY)
        val echoed = messages.any { it.fromMe && same(it.text, echo.text) } ||
            history?.any { same(it.toString(), echo.text) } == true
        if (echoed) echo.latch.countDown()
    }

    fun onRemoved(sbn: StatusBarNotification) {
        targets.remove(sbn.key)
    }

    fun clear() = targets.clear()

    fun hasWhatsAppTarget(number: String?, name: String?): Boolean = findTarget(number, name) != null

    /** Blocks up to [ECHO_TIMEOUT_MS] — call off the main thread. */
    fun replyToWhatsApp(context: Context, number: String?, name: String?, text: String): Outcome {
        val target = findTarget(number, name) ?: return Outcome.NO_TARGET
        val inputs = target.action.remoteInputs ?: return Outcome.NO_TARGET
        val echo = PendingEcho(target.sbn.key, target.title, text)
        pendingEcho = echo
        var fired = false
        return try {
            val fillIn = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
            RemoteInput.addResultsToIntent(inputs, fillIn, results)
            if (Build.VERSION.SDK_INT >= 28) RemoteInput.setResultsSource(fillIn, RemoteInput.SOURCE_FREE_FORM_INPUT)
            target.action.actionIntent.send(context, 0, fillIn)
            fired = true
            Log.i(TAG, "reply fired for '${target.title}', waiting for echo")
            val echoed = echo.latch.await(ECHO_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            Log.i(TAG, "echo=$echoed")
            if (echoed) Outcome.SENT else Outcome.UNVERIFIED
        } catch (e: Exception) {
            Log.w(TAG, "reply failed (fired=$fired): ${e.message}")
            if (fired) Outcome.UNVERIFIED else Outcome.FAILED
        } finally {
            if (pendingEcho === echo) pendingEcho = null
        }
    }

    /** Matches the chat by the number in WhatsApp's conversation shortcut, else by contact name. */
    private fun findTarget(number: String?, name: String?): Target? {
        val digits = number?.filter(Char::isDigit)?.takeLast(10)?.takeIf { it.length == 10 }
        val folded = name?.let(::fold)?.takeIf { it.isNotEmpty() }
        return targets.values.firstOrNull { t ->
            val shortcutDigits = t.sbn.notification.shortcutId?.substringBefore('@')?.filter(Char::isDigit)
            (digits != null && shortcutDigits != null && shortcutDigits.endsWith(digits)) ||
                (folded != null && fold(t.title) == folded)
        }
    }

    private fun replyAction(n: Notification): Notification.Action? {
        val withInput = n.actions?.filter { it.remoteInputs?.isNotEmpty() == true }.orEmpty()
        val semanticReply = if (Build.VERSION.SDK_INT >= 28) {
            withInput.firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY }
        } else null
        return semanticReply ?: withInput.firstOrNull()
    }

    private fun fold(s: String): String = DeviceUtils.toAsciiTurkce(s).trim()

    private fun same(a: String, b: String): Boolean =
        a.trim().replace(whitespace, " ") == b.trim().replace(whitespace, " ")
}

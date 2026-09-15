package com.deepassist.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A chat message or notification captured by the notification listener. */
data class StoredMessage(
    val id: String,
    val packageName: String,
    val appName: String,
    /** Chat title (contact or group name); for a plain notification, its title. */
    val conversation: String,
    val isGroup: Boolean,
    val sender: String,
    val text: String,
    val timestamp: Long,
    val fromMe: Boolean,
    /** A MessagingStyle chat message (WhatsApp, Telegram…) rather than a plain notification. */
    val isChat: Boolean,
    /** When the assistant read it out; 0 = never. */
    val readAloudAt: Long = 0L
)

/**
 * Persistent inbox fed by [com.deepassist.service.MessageListenerService] in
 * `files/messages.json`, so captured messages survive the app restarting. Keeps two
 * weeks, at most [MAX_ENTRIES] entries; writes are coalesced off the listener's
 * main thread.
 */
class MessageStore private constructor(private val file: File) {

    private val gson = Gson()
    private val entries = ArrayList<StoredMessage>() // oldest first
    private val ids = HashSet<String>()
    private var loaded = false
    private val writer = Executors.newSingleThreadScheduledExecutor()
    private val writeScheduled = AtomicBoolean(false)

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!file.exists()) return
        runCatching {
            val type = object : TypeToken<List<StoredMessage>>() {}.type
            gson.fromJson<List<StoredMessage>>(file.readText(), type)?.let { list ->
                entries.addAll(list)
                list.forEach { ids.add(it.id) }
            }
        }.onFailure { Log.w(TAG, "Corrupted messages.json, starting fresh: ${it.message}") }
    }

    /** @return the messages that were not stored yet. */
    @Synchronized
    fun addAll(messages: List<StoredMessage>): List<StoredMessage> {
        ensureLoaded()
        val added = messages.filter { ids.add(it.id) }
        if (added.isNotEmpty()) {
            entries.addAll(added)
            prune()
            schedulePersist()
        }
        return added
    }

    @Synchronized
    fun snapshot(): List<StoredMessage> {
        ensureLoaded()
        return entries.toList()
    }

    @Synchronized
    fun markReadAloud(messageIds: Collection<String>) {
        if (messageIds.isEmpty()) return
        ensureLoaded()
        val wanted = messageIds.toHashSet()
        val now = System.currentTimeMillis()
        var changed = false
        for (i in entries.indices) {
            if (entries[i].id in wanted) {
                entries[i] = entries[i].copy(readAloudAt = now)
                changed = true
            }
        }
        if (changed) schedulePersist()
    }

    private fun prune() {
        entries.sortBy { it.timestamp }
        val before = entries.size
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        entries.removeAll { it.timestamp < cutoff }
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
        if (entries.size != before) {
            ids.clear()
            entries.forEach { ids.add(it.id) }
        }
    }

    private fun schedulePersist() {
        if (!writeScheduled.compareAndSet(false, true)) return
        writer.schedule({
            writeScheduled.set(false)
            val json = synchronized(this) { gson.toJson(entries) }
            runCatching {
                val tmp = File("${file.absolutePath}.tmp")
                tmp.writeText(json)
                tmp.renameTo(file)
                tmp.delete()
            }.onFailure { Log.w(TAG, "persist failed: ${it.message}") }
        }, WRITE_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    companion object {
        private const val TAG = "MessageStore"
        private const val MAX_ENTRIES = 3000
        private const val MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000
        private const val WRITE_DELAY_MS = 2_000L

        @Volatile
        private var instance: MessageStore? = null

        fun get(context: Context): MessageStore =
            instance ?: synchronized(this) {
                instance ?: MessageStore(File(context.applicationContext.filesDir, "messages.json"))
                    .also { instance = it }
            }

        /** Stable id for the same message re-posted in an updated notification. */
        fun idOf(vararg parts: Any?): String =
            UUID.nameUUIDFromBytes(parts.joinToString("").toByteArray()).toString()
    }
}

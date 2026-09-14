package com.deepassist.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.UUID

data class ChatMessage(
    val role: String,
    val text: String,
    val ts: Long
)

data class ChatRecord(
    val id: String,
    val title: String,
    val createdAt: Long,
    var updatedAt: Long,
    val messages: MutableList<ChatMessage>
)

data class ChatSummary(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int
)

class ChatHistoryStore private constructor(private val dir: File) {

    private val gson = Gson()

    @Synchronized
    fun list(): List<ChatSummary> {
        // Snapshot the file list under the lock so the directory scan is
        // consistent, then do the JSON parsing outside the lock so
        // concurrent create/append/delete calls aren't stalled.
        val snapshot: List<File> = runCatching { dir.listFiles() }
            .getOrNull()?.filter { it.isFile } ?: emptyList()

        val summaries = mutableListOf<ChatSummary>()

        for (file in snapshot) {
            val name = file.name

            // Clean up stale .tmp files
            if (name.endsWith(".tmp")) {
                runCatching { file.delete() }
                continue
            }

            if (!name.endsWith(".json")) continue

            val result = runCatching {
                val raw = file.readText()
                val obj = JsonParser.parseString(raw).asJsonObject
                val id = obj.get("id")?.asString ?: return@runCatching null
                val title = obj.get("title")?.asString ?: ""
                val createdAt = obj.get("createdAt")?.asLong ?: 0L
                val updatedAt = obj.get("updatedAt")?.asLong ?: 0L
                val messages = obj.getAsJsonArray("messages")
                val messageCount = messages?.size() ?: 0
                ChatSummary(id, title, createdAt, updatedAt, messageCount)
            }.getOrNull()

            if (result != null) {
                summaries.add(result)
            }
            // Corrupt JSON → skip silently
        }

        summaries.sortByDescending { it.updatedAt }
        return summaries
    }

    @Synchronized
    fun load(id: String): ChatRecord? {
        val file = File(dir, "$id.json")
        if (!file.exists()) return null

        return runCatching {
            val raw = file.readText()
            gson.fromJson(raw, ChatRecord::class.java)
        }.getOrNull()
    }

    @Synchronized
    fun create(firstUserText: String): ChatRecord {
        val id = System.currentTimeMillis().toString(36) + "-" +
                UUID.randomUUID().toString().take(8)
        val now = System.currentTimeMillis()
        val record = ChatRecord(
            id = id,
            title = firstUserText.take(48),
            createdAt = now,
            updatedAt = now,
            messages = mutableListOf()
        )
        prune()
        persist(record)
        return record
    }

    @Synchronized
    fun append(id: String, role: String, text: String) {
        val record = load(id) ?: return // silently skip if deleted
        record.messages.add(
            ChatMessage(role = role, text = text, ts = System.currentTimeMillis())
        )
        // Cap at 400 messages — drop oldest
        while (record.messages.size > 400) {
            record.messages.removeAt(0)
        }
        record.updatedAt = System.currentTimeMillis()
        persist(record)
    }

    @Synchronized
    fun delete(id: String) {
        val jsonFile = File(dir, "$id.json")
        val tmpFile = File(dir, "$id.json.tmp")

        runCatching { jsonFile.delete() }
        runCatching { tmpFile.delete() }
    }

    @Synchronized
    private fun prune() {
        val files = runCatching { dir.listFiles() }.getOrNull() ?: return
        val jsonFiles = files.filter {
            it.isFile && it.name.endsWith(".json") && !it.name.endsWith(".tmp")
        }
        if (jsonFiles.size <= 200) return

        // Delete oldest by lastModified until ≤ 200
        val sorted = jsonFiles.sortedBy { it.lastModified() }
        val toDelete = jsonFiles.size - 200
        for (i in 0 until toDelete) {
            val file = sorted[i]
            runCatching { file.delete() }
            // Also clean up any stale .tmp sibling
            val tmp = File(dir, "${file.name}.tmp")
            runCatching { tmp.delete() }
        }
    }

    private fun persist(record: ChatRecord) {
        runCatching {
            val json = gson.toJson(record)
            val file = File(dir, "${record.id}.json")
            val tmp = File(dir, "${record.id}.json.tmp")
            tmp.writeText(json)
            tmp.renameTo(file)
            tmp.delete() // clean up if renameTo silently failed
        }
    }

    companion object {
        @Volatile
        private var instance: ChatHistoryStore? = null

        fun get(context: Context): ChatHistoryStore {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val dir = File(context.filesDir, "chats")
                    dir.mkdirs()
                    ChatHistoryStore(dir).also { instance = it }
                }
            }
        }
    }
}

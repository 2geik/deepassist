package com.deepassist.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

data class MemoryEntry(
    val id: String,
    val text: String,
    val createdAt: Long
)

class MemoryStore private constructor(private val file: File) {

    private val gson = Gson()
    private val entries: MutableList<MemoryEntry> = CopyOnWriteArrayList()
    private var loaded = false

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!file.exists()) return
        runCatching {
            val json = file.readText()
            val type = object : TypeToken<List<MemoryEntry>>() {}.type
            val list: List<MemoryEntry> = gson.fromJson(json, type)
            if (list != null) {
                entries.addAll(list)
            }
        }.onFailure {
            // Corrupted file — start fresh
        }
    }

    @Synchronized
    fun list(): List<MemoryEntry> {
        ensureLoaded()
        return entries.toList()
    }

    @Synchronized
    fun add(text: String): MemoryEntry {
        ensureLoaded()
        val trimmed = text.trim().take(300)
        if (trimmed.isBlank()) {
            throw IllegalArgumentException("Content is blank")
        }
        val lower = trimmed.lowercase()
        if (entries.any { it.text.trim().lowercase() == lower }) {
            // Duplicate — return the existing entry
            return entries.first { it.text.trim().lowercase() == lower }
        }
        val entry = MemoryEntry(
            id = UUID.randomUUID().toString(),
            text = trimmed,
            createdAt = System.currentTimeMillis()
        )
        entries.add(entry)
        // Cap at 200, drop oldest (first in list)
        while (entries.size > 200) {
            entries.removeAt(0)
        }
        persist()
        return entry
    }

    @Synchronized
    fun remove(id: String): Boolean {
        ensureLoaded()
        val removed = entries.removeAll { it.id == id }
        if (removed) persist()
        return removed
    }

    @Synchronized
    fun removeMatching(query: String): Int {
        ensureLoaded()
        val lowerQuery = query.lowercase()
        val toRemove = entries.filter { it.text.lowercase().contains(lowerQuery) }
        if (toRemove.isEmpty()) return 0
        entries.removeAll(toRemove)
        persist()
        return toRemove.size
    }

    @Synchronized
    fun clear() {
        entries.clear()
        runCatching { file.delete() }
        loaded = true // still loaded, just empty
    }

    private fun persist() {
        runCatching {
            val json = gson.toJson(entries)
            val tmp = File("${file.absolutePath}.tmp")
            tmp.writeText(json)
            tmp.renameTo(file)
            tmp.delete() // clean up if renameTo silently failed on tmp
        }
    }

    companion object {
        @Volatile
        private var instance: MemoryStore? = null

        fun get(context: Context): MemoryStore {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val file = File(context.filesDir, "memories.json")
                    MemoryStore(file).also { instance = it }
                }
            }
        }
    }
}

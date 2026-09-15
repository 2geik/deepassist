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
    val createdAt: Long,
    /** Last rewrite by the memory extractor; 0 = never changed. */
    val updatedAt: Long = 0L
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
        val trimmed = text.trim().take(MAX_TEXT)
        if (trimmed.isBlank()) {
            throw IllegalArgumentException("Content is blank")
        }
        findSame(trimmed)?.let { return it }
        val entry = MemoryEntry(
            id = UUID.randomUUID().toString(),
            text = trimmed,
            createdAt = System.currentTimeMillis()
        )
        entries.add(entry)
        // Cap at MAX_ENTRIES, drop oldest (first in list)
        while (entries.size > MAX_ENTRIES) {
            entries.removeAt(0)
        }
        persist()
        return entry
    }

    /** @return false when the text is blank or already stored. */
    @Synchronized
    fun addIfNew(text: String): Boolean {
        ensureLoaded()
        val trimmed = text.trim().take(MAX_TEXT)
        if (trimmed.isBlank() || findSame(trimmed) != null) return false
        add(trimmed)
        return true
    }

    /** Rewrites an entry in place (merging or correcting it); false when missing or blank. */
    @Synchronized
    fun update(id: String, text: String): Boolean {
        ensureLoaded()
        val trimmed = text.trim().take(MAX_TEXT)
        val index = entries.indexOfFirst { it.id == id }
        if (index < 0 || trimmed.isBlank()) return false
        entries[index] = entries[index].copy(text = trimmed, updatedAt = System.currentTimeMillis())
        persist()
        return true
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

    private fun findSame(text: String): MemoryEntry? {
        val lower = text.lowercase()
        return entries.firstOrNull { it.text.trim().lowercase() == lower }
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
        private const val MAX_ENTRIES = 200
        private const val MAX_TEXT = 300

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

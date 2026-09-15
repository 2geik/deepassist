package com.deepassist.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

data class WatchEntry(
    val videoId: String,
    val title: String,
    val channel: String,
    val query: String,
    val kind: String,
    val watchedAt: Long
)

/**
 * What the assistant opened on YouTube and played on YouTube Music, oldest first,
 * in `files/watch_history.json`. Video searches use it to skip what was already watched.
 */
class WatchHistoryStore private constructor(private val file: File) {

    private val gson = Gson()
    private val entries = mutableListOf<WatchEntry>()
    private var loaded = false

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!file.exists()) return
        runCatching {
            val type = object : TypeToken<List<WatchEntry>>() {}.type
            gson.fromJson<List<WatchEntry>>(file.readText(), type)?.let { entries.addAll(it) }
        }
    }

    /** Adds or refreshes an entry; re-watching moves it to the newest position. */
    @Synchronized
    fun record(videoId: String, title: String, channel: String, query: String, kind: String) {
        ensureLoaded()
        entries.removeAll { it.videoId == videoId && it.kind == kind }
        entries.add(WatchEntry(videoId, title, channel, query, kind, System.currentTimeMillis()))
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
        persist()
    }

    @Synchronized
    fun lastWatched(videoId: String, kind: String = KIND_VIDEO): WatchEntry? {
        ensureLoaded()
        return entries.lastOrNull { it.videoId == videoId && it.kind == kind }
    }

    /** Newest first. */
    @Synchronized
    fun list(): List<WatchEntry> {
        ensureLoaded()
        return entries.reversed()
    }

    private fun persist() {
        runCatching {
            val tmp = File("${file.absolutePath}.tmp")
            tmp.writeText(gson.toJson(entries))
            tmp.renameTo(file)
            tmp.delete()
        }
    }

    companion object {
        const val KIND_VIDEO = "video"
        const val KIND_MUSIC = "music"
        private const val MAX_ENTRIES = 1000

        @Volatile
        private var instance: WatchHistoryStore? = null

        fun get(context: Context): WatchHistoryStore =
            instance ?: synchronized(this) {
                instance ?: WatchHistoryStore(File(context.filesDir, "watch_history.json")).also { instance = it }
            }
    }
}

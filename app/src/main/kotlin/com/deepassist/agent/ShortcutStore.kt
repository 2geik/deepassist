package com.deepassist.agent

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Learned shortcuts (files/agent_shortcuts.json): the steps of agent runs that
 * worked, as text recipes without typed text or message previews. A similar
 * goal later gets the recipe as a hint; the agent still checks every screen,
 * so an app update can't make it tap blindly. Two failed uses delete a recipe.
 */
class ShortcutStore private constructor(private val file: File) {

    data class Shortcut(
        val id: String,
        val goal: String,
        val steps: List<String>,
        val uses: Int = 0,
        val failures: Int = 0,
        val updatedAt: Long = System.currentTimeMillis()
    )

    private val gson = Gson()
    private var cache: MutableList<Shortcut>? = null

    @Synchronized
    fun list(): List<Shortcut> = load().sortedByDescending { it.updatedAt }

    /** The best recipe for [goal], or null when nothing is similar enough. */
    @Synchronized
    fun match(goal: String): Shortcut? =
        load().map { it to Recipes.similarity(goal, it.goal) }
            .filter { it.second >= MIN_SIMILARITY }
            .maxByOrNull { it.second }
            ?.first

    /** After a successful run: store or refresh the recipe. */
    @Synchronized
    fun remember(goal: String, runSteps: List<String>, usedId: String?) {
        val steps = Recipes.fromSteps(runSteps)
        if (steps.size < 2) return // a one-tap job needs no recipe
        val items = load()
        val existing = usedId?.let { id -> items.firstOrNull { it.id == id } }
            ?: items.firstOrNull { Recipes.similarity(goal, it.goal) >= SAME_GOAL }
        if (existing != null) items.remove(existing)
        items.add(
            Shortcut(
                id = existing?.id ?: java.util.UUID.randomUUID().toString().take(8),
                goal = goal.take(160),
                steps = steps.take(20),
                uses = (existing?.uses ?: 0) + 1
            )
        )
        while (items.size > MAX_SHORTCUTS) items.remove(items.minBy { it.updatedAt })
        save(items)
    }

    /** A run that used [id] as a hint failed; the second failure in a row removes it. */
    @Synchronized
    fun failed(id: String) {
        val items = load()
        val s = items.firstOrNull { it.id == id } ?: return
        items.remove(s)
        if (s.failures + 1 < 2) items.add(s.copy(failures = s.failures + 1))
        save(items)
    }

    @Synchronized
    fun remove(id: String) {
        val items = load()
        if (items.removeAll { it.id == id }) save(items)
    }

    private fun load(): MutableList<Shortcut> {
        cache?.let { return it }
        val loaded = runCatching {
            if (!file.exists()) mutableListOf()
            else gson.fromJson<MutableList<Shortcut>>(file.readText(), object : TypeToken<MutableList<Shortcut>>() {}.type)
        }.getOrNull() ?: mutableListOf()
        cache = loaded
        return loaded
    }

    private fun save(items: MutableList<Shortcut>) {
        cache = items
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(gson.toJson(items))
            tmp.renameTo(file)
        }
    }

    companion object {
        private const val MAX_SHORTCUTS = 50
        private const val MIN_SIMILARITY = 0.5
        private const val SAME_GOAL = 0.8

        @Volatile
        private var instance: ShortcutStore? = null

        fun get(context: Context): ShortcutStore =
            instance ?: synchronized(this) {
                instance ?: ShortcutStore(File(context.applicationContext.filesDir, "agent_shortcuts.json"))
                    .also { instance = it }
            }
    }
}

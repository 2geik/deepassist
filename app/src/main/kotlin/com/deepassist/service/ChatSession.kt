package com.deepassist.service

/**
 * Process-wide transcript of the current assistant session. The session UI
 * ([com.deepassist.SessionActivity]) renders whatever is here, so bubbles
 * survive the activity being recreated (e.g. triggered again from the lock
 * screen) and updates that arrive before the activity is up are not lost.
 */
object ChatSession {

    enum class Kind { USER, ASSISTANT, STATUS, LISTENING }

    data class Entry(val kind: Kind, var text: String, var partial: Boolean = false)

    private val entries = mutableListOf<Entry>()

    @Volatile
    var lastKind: Kind = Kind.STATUS
        private set

    @Volatile
    private var listener: (() -> Unit)? = null

    @Synchronized
    fun snapshot(): List<Entry> = entries.map { it.copy() }

    /** Single observer: the currently visible session UI. */
    fun setListener(l: (() -> Unit)?) {
        listener = l
    }

    @Synchronized
    fun newSession(listeningText: String) {
        entries.clear()
        entries.add(Entry(Kind.LISTENING, listeningText))
        lastKind = Kind.LISTENING
        fire()
    }

    /** Pre-populates the chat UI with messages from a prior conversation. */
    @Synchronized
    fun seedHistory(messages: List<Pair<Boolean, String>>) {
        // Insert history entries before the existing LISTENING pill
        val historyEntries = messages.mapIndexed { index, (isUser, text) ->
            Entry(
                kind = if (isUser) Kind.USER else Kind.ASSISTANT,
                text = text,
                partial = false
            )
        }
        entries.addAll(0, historyEntries)
        fire()
    }

    @Synchronized
    fun addUser(text: String) {
        dropTrailingTransient()
        val last = entries.lastOrNull()
        if (last != null && last.kind == Kind.USER && last.partial) {
            last.text = text
            last.partial = false
        } else {
            entries.add(Entry(Kind.USER, text))
        }
        lastKind = Kind.USER
        fire()
    }

    @Synchronized
    fun updatePartial(text: String) {
        dropTrailingTransient()
        val last = entries.lastOrNull()
        if (last != null && last.kind == Kind.USER && last.partial) {
            last.text = text
        } else {
            entries.add(Entry(Kind.USER, text, partial = true))
        }
        lastKind = Kind.USER
        fire()
    }

    /** Final assistant text; replaces the bubble that grew while the answer was spoken. */
    @Synchronized
    fun addAssistant(text: String) {
        dropTrailingTransient()
        val last = entries.lastOrNull()
        if (last != null && last.kind == Kind.ASSISTANT && last.partial) {
            last.text = text
            last.partial = false
        } else {
            entries.add(Entry(Kind.ASSISTANT, text))
        }
        lastKind = Kind.ASSISTANT
        fire()
    }

    /** An answer being spoken sentence by sentence: updates its bubble in place. */
    @Synchronized
    fun updateAssistantPartial(text: String) {
        dropTrailingTransient()
        val last = entries.lastOrNull()
        if (last != null && last.kind == Kind.ASSISTANT && last.partial) {
            last.text = text
        } else {
            entries.add(Entry(Kind.ASSISTANT, text, partial = true))
        }
        lastKind = Kind.ASSISTANT
        fire()
    }

    /** The growing bubble is complete as it is (a spoken lead-in before a tool ran). */
    @Synchronized
    fun closeAssistantPartial() {
        entries.lastOrNull { it.kind == Kind.ASSISTANT && it.partial }?.partial = false
        fire()
    }

    @Synchronized
    fun addStatus(text: String) {
        dropTrailingTransient()
        entries.add(Entry(Kind.STATUS, text))
        lastKind = Kind.STATUS
        fire()
    }

    @Synchronized
    fun addListening(text: String) {
        dropTrailingTransient()
        entries.add(Entry(Kind.LISTENING, text))
        lastKind = Kind.LISTENING
        fire()
    }

    // Status/listening pills mean "what is happening right now" — each new
    // event replaces the stale one instead of stacking outdated steps
    private fun dropTrailingTransient() {
        val last = entries.lastOrNull() ?: return
        if (last.kind == Kind.STATUS || last.kind == Kind.LISTENING) {
            entries.removeAt(entries.size - 1)
        }
    }

    private fun fire() {
        listener?.invoke()
    }
}

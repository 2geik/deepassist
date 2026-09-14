package com.deepassist.data

class ConversationManager(private val maxMessages: Int = 40) {

    private val messages = mutableListOf<Message>()
    private var systemPrompt: String? = null

    @Synchronized
    fun setSystemPrompt(prompt: String) {
        systemPrompt = prompt
    }

    @Synchronized
    fun addUserMessage(content: String) {
        add(Message(role = "user", content = content))
    }

    @Synchronized
    fun addAssistantMessage(content: String?, toolCalls: List<ToolCall>? = null) {
        add(Message(role = "assistant", content = content, tool_calls = toolCalls))
    }

    @Synchronized
    fun addMessages(newMessages: List<Message>) {
        newMessages.forEach { add(it) }
    }

    // The system message is never stored in the list; getMessages() prepends it,
    // so trimming can never strand or duplicate it.
    private fun add(message: Message) {
        messages.add(message)
        while (messages.size > maxMessages) {
            messages.removeAt(0)
        }
        // History must not start with an orphan tool result or the API rejects it
        while (messages.isNotEmpty() && messages.first().role == "tool") {
            messages.removeAt(0)
        }
    }

    @Synchronized
    fun getMessages(): List<Message> {
        val result = mutableListOf<Message>()
        systemPrompt?.let { result.add(Message(role = "system", content = it)) }
        result.addAll(messages)
        return result
    }

    @Synchronized
    fun clear() {
        messages.clear()
    }
}

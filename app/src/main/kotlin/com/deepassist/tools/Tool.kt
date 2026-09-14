package com.deepassist.tools

import android.content.Context
import com.deepassist.data.FunctionDef
import com.deepassist.data.ToolDefinition
import com.deepassist.data.ToolParameters
import com.deepassist.data.ToolProperty
import com.deepassist.data.ToolResult
import com.google.gson.JsonObject

abstract class Tool {

    abstract val name: String
    abstract val description: String
    abstract val parameters: Map<String, ToolProperty>
    abstract val required: List<String>

    /** Spoken as soon as the first tool_call delta names this tool. Null = nothing static to say. */
    open val thinkingPhrase: String? = null

    /**
     * When true the orchestrator waits for the spoken phrase to finish before
     * executing — for actions whose effect is immediately audible or takes over
     * the screen (placing a call, sending a message).
     */
    open val waitForSpeech: Boolean = false

    protected lateinit var context: Context

    open fun initialize(context: Context) {
        this.context = context.applicationContext
    }

    companion object {
        /** Set by ToolRegistry so any tool can ask the user a question. */
        @Volatile
        var askUser: (suspend (String) -> String?)? = null

        /**
         * Called by [MediaControlTool] before dispatching a media key event.
         * Releases the assistant's audio focus so the media key routes to
         * the actual media app's session instead of the assistant's.
         *
         * Set by [AssistantForegroundService] during initialization.
         */
        @Volatile
        var releaseAudioFocusForMediaControl: (suspend () -> Unit)? = null
    }

    /** Phrase computed once full arguments are available (e.g. "{isim}'i arıyorum..."). */
    open fun dynamicThinkingPhrase(args: JsonObject): String? = thinkingPhrase

    abstract suspend fun execute(args: JsonObject): ToolResult

    fun toDefinition(): ToolDefinition = ToolDefinition(
        function = FunctionDef(
            name = name,
            description = description,
            parameters = ToolParameters(properties = parameters, required = required)
        )
    )

    protected fun JsonObject.optString(key: String): String? =
        if (has(key) && !get(key).isJsonNull) {
            try {
                get(key).asString
            } catch (e: Exception) {
                get(key).toString()
            }
        } else {
            null
        }

    protected fun JsonObject.optInt(key: String): Int? =
        if (has(key) && !get(key).isJsonNull) {
            try {
                get(key).asInt
            } catch (e: Exception) {
                try {
                    get(key).asString.toIntOrNull()
                } catch (_: Exception) {
                    null
                }
            }
        } else {
            null
        }
}

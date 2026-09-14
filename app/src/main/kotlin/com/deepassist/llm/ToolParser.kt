package com.deepassist.llm

import com.deepassist.data.Message
import com.deepassist.data.ToolCall
import com.deepassist.data.ToolResult
import com.google.gson.JsonObject
import com.google.gson.JsonParser

object ToolParser {

    fun parseToolCallArguments(arguments: String): JsonObject {
        return try {
            val element = JsonParser.parseString(arguments)
            if (element.isJsonObject) element.asJsonObject else JsonObject()
        } catch (e: Exception) {
            JsonObject()
        }
    }

    fun buildToolCallMessages(
        toolCalls: List<ToolCall>,
        results: Map<String, ToolResult>
    ): List<Message> {
        return toolCalls.mapNotNull { call ->
            val id = call.id ?: return@mapNotNull null
            val name = call.function?.name ?: return@mapNotNull null
            val result = results[id]
            val content = when {
                result == null -> "Araç çalıştırılamadı."
                result.success -> result.data
                else -> "HATA: ${result.error ?: result.data}"
            }
            Message(role = "tool", content = content, tool_call_id = id, name = name)
        }
    }
}

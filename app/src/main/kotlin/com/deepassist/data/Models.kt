package com.deepassist.data

// ---- Chat / function-calling models (DeepSeek, OpenAI-compatible) ----

data class Message(
    val role: String,
    val content: String? = null,
    val tool_calls: List<ToolCall>? = null,
    val tool_call_id: String? = null,
    val name: String? = null,
    /** Thinking mode: must go back with the assistant's tool-call message within the same turn. */
    val reasoning_content: String? = null
)

data class ToolCall(
    val id: String?,
    val type: String? = "function",
    val function: ToolFunction?
)

data class ToolFunction(
    val name: String,
    val arguments: String
)

data class ToolDefinition(
    val type: String = "function",
    val function: FunctionDef
)

data class FunctionDef(
    val name: String,
    val description: String,
    val parameters: ToolParameters
)

data class ToolParameters(
    val type: String = "object",
    val properties: Map<String, ToolProperty>,
    val required: List<String>
)

data class ToolProperty(
    val type: String,
    val description: String,
    val enum: List<String>? = null,
    val items: ToolProperty? = null
)

data class ToolResult(
    val success: Boolean,
    val data: String,
    val thinkingPhrase: String? = null,
    val error: String? = null
)

// ---- Device-side models ----

data class ContactInfo(
    val id: String,
    val name: String,
    val phoneNumber: String
)

data class SmsInfo(
    val id: String,
    val address: String,
    val body: String,
    val date: Long,
    val type: String
)

data class NotificationInfo(
    val packageName: String,
    val appName: String,
    val title: String?,
    val text: String?,
    val timestamp: Long
)

data class DeviceState(
    val currentTime: String,
    val currentDate: String,
    val batteryLevel: Int,
    val isCharging: Boolean,
    val networkType: String,
    val dayOfWeek: String
)

data class CallLogEntry(
    val id: String,
    val contactName: String?,
    val phoneNumber: String,
    val callType: String,
    val timestamp: Long,
    val duration: Long
)

// ---- DeepSeek SSE streaming models ----

data class StreamDelta(
    val content: String?,
    val tool_calls: List<StreamToolCallDelta>?,
    val reasoning_content: String? = null
)

data class StreamToolCallDelta(
    val index: Int,
    val id: String?,
    val function: StreamFunctionDelta?
)

data class StreamFunctionDelta(
    val name: String?,
    val arguments: String?
)

data class StreamChoice(
    val index: Int,
    val delta: StreamDelta?,
    val finish_reason: String?
)

data class StreamResponse(
    val id: String?,
    val choices: List<StreamChoice>?
)

// ---- Non-streaming completion models ----

data class CompletionResponse(
    val id: String?,
    val choices: List<CompletionChoice>?
)

data class CompletionChoice(
    val index: Int,
    val message: Message?,
    val finish_reason: String?
)

// ---- DuckDuckGo models ----

data class DuckDuckGoResponse(
    val Abstract: String?,
    val AbstractSource: String?,
    val Answer: String?,
    val AbstractText: String?,
    val Heading: String?,
    val RelatedTopics: List<DuckDuckGoTopic>?,
    val Results: List<DuckDuckGoExternalResult>?
)

data class DuckDuckGoTopic(
    val Text: String?,
    val FirstURL: String?,
    val Topics: List<DuckDuckGoTopic>?
)

data class DuckDuckGoExternalResult(
    val Text: String?,
    val FirstURL: String?
)

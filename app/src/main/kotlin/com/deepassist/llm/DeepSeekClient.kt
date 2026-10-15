package com.deepassist.llm

import com.deepassist.data.CompletionResponse
import com.deepassist.data.Message
import com.deepassist.data.StreamResponse
import com.deepassist.data.ToolCall
import com.deepassist.data.ToolDefinition
import com.deepassist.data.ToolFunction
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Tool call assembled from multiple SSE deltas, keyed by stream index. */
class AccumulatingToolCall(val index: Int) {
    var id: String? = null
    var name: String? = null
    val arguments = StringBuilder()

    fun toToolCall(): ToolCall = ToolCall(
        id = id,
        type = "function",
        function = ToolFunction(name = name ?: "", arguments = arguments.toString())
    )
}

/** A DeepSeek call that got an HTTP error back (the body is logged, never shown). */
class DeepSeekHttpException(val code: Int) : IOException("DeepSeek HTTP $code")

data class StreamEvent(
    val contentDelta: String? = null,
    val toolCalls: List<AccumulatingToolCall>? = null,
    val finishReason: String? = null,
    val isComplete: Boolean = false,
    val error: String? = null,
    /** Thinking-mode reasoning of a tool-call turn; send it back as [Message.reasoning_content]. */
    val reasoning: String? = null
)

class DeepSeekClient(private val apiKeyProvider: () -> String) {

    private val gson = Gson()
    private val jsonMediaType = "application/json".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun streamChat(messages: List<Message>, tools: List<ToolDefinition>): Flow<StreamEvent> = flow {
        val apiKey = apiKeyProvider()
        if (apiKey.isBlank()) {
            emit(StreamEvent(error = "DeepSeek API anahtarı ayarlanmamış."))
            return@flow
        }

        val request = buildRequest(apiKey, messages, tools, stream = true)
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val detail = try {
                    resp.body?.string()?.take(300)
                } catch (e: Exception) {
                    null
                }
                emit(StreamEvent(error = "DeepSeek API hatası (HTTP ${resp.code}). ${detail.orEmpty()}"))
                return@use
            }
            val source = resp.body?.source()
            if (source == null) {
                emit(StreamEvent(error = "DeepSeek API boş yanıt döndürdü."))
                return@use
            }

            val accumulating = sortedMapOf<Int, AccumulatingToolCall>()
            var announcedFirstToolCall = false
            val reasoning = StringBuilder()

            while (true) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank() || !line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break

                val parsed = try {
                    gson.fromJson(data, StreamResponse::class.java)
                } catch (e: Exception) {
                    null
                } ?: continue
                val choice = parsed.choices?.firstOrNull() ?: continue
                val delta = choice.delta

                delta?.reasoning_content?.let { reasoning.append(it) }

                // Disjoint branches (gotcha #6): a delta is either tool-call data or content
                when {
                    delta?.tool_calls != null -> {
                        for (tc in delta.tool_calls) {
                            val acc = accumulating.getOrPut(tc.index) { AccumulatingToolCall(tc.index) }
                            tc.id?.let { acc.id = it }
                            tc.function?.name?.let { acc.name = (acc.name ?: "") + it }
                            tc.function?.arguments?.let { acc.arguments.append(it) }
                        }
                        // Early signal so the orchestrator can speak the thinking phrase
                        // as soon as the first tool call is identified
                        if (!announcedFirstToolCall) {
                            val first = accumulating.values.firstOrNull()
                            if (first != null && !first.name.isNullOrBlank()) {
                                announcedFirstToolCall = true
                                emit(StreamEvent(toolCalls = listOf(first)))
                            }
                        }
                    }
                    delta?.content != null -> emit(StreamEvent(contentDelta = delta.content))
                }

                // finish_reason handled separately so it is never clobbered
                when (choice.finish_reason) {
                    "tool_calls" -> {
                        emit(
                            StreamEvent(
                                toolCalls = accumulating.values.toList(),
                                finishReason = "tool_calls",
                                reasoning = reasoning.toString().ifEmpty { null }
                            )
                        )
                        return@use
                    }
                    "stop" -> {
                        emit(StreamEvent(finishReason = "stop", isComplete = true))
                        return@use
                    }
                }
            }
            // Stream ended without an explicit finish_reason
            if (accumulating.isNotEmpty()) {
                emit(
                    StreamEvent(
                        toolCalls = accumulating.values.toList(),
                        finishReason = "tool_calls",
                        reasoning = reasoning.toString().ifEmpty { null }
                    )
                )
            } else {
                emit(StreamEvent(finishReason = "stop", isComplete = true))
            }
        }
    }
        .catch { e -> emit(StreamEvent(error = "Bağlantı hatası: ${e.message}")) }
        .flowOn(Dispatchers.IO)

    /** Non-streaming completion used for tool-result follow-ups. */
    suspend fun complete(messages: List<Message>, tools: List<ToolDefinition>): Message? =
        withContext(Dispatchers.IO) {
            val apiKey = apiKeyProvider()
            if (apiKey.isBlank()) return@withContext null
            try {
                val request = buildRequest(apiKey, messages, tools, stream = false)
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "complete HTTP ${resp.code}: ${resp.body?.string()?.take(300)}")
                        return@withContext null
                    }
                    val body = resp.body?.string() ?: return@withContext null
                    val parsed = gson.fromJson(body, CompletionResponse::class.java)
                    parsed.choices?.firstOrNull()?.message
                }
            } catch (e: Exception) {
                Log.w(TAG, "complete failed: ${e.javaClass.simpleName} ${e.message}")
                null
            }
        }

    /**
     * One image plus a question, answered without thinking (about 2 s for a
     * 1280 px JPEG). The image travels inline in this request only and never
     * enters the conversation history.
     */
    suspend fun describeImage(
        system: String,
        question: String,
        jpeg: ByteArray,
        maxTokens: Int = 700
    ): Result<String> = rawCompletion(
        system = system,
        userContent = listOf(textPart(question), imagePart(jpeg)),
        maxTokens = maxTokens,
        json = false
    )

    /**
     * A JSON-only answer (response_format json_object, no thinking) — the screen
     * agent's one-action-per-step calls. [jpeg] adds a screenshot when needed.
     */
    suspend fun completeJson(
        system: String,
        user: String,
        jpeg: ByteArray? = null,
        maxTokens: Int = 500
    ): Result<String> = rawCompletion(
        system = system,
        userContent = listOfNotNull(textPart(user), jpeg?.let { imagePart(it) }),
        maxTokens = maxTokens,
        json = true
    )

    private suspend fun rawCompletion(
        system: String,
        userContent: List<Map<String, Any>>,
        maxTokens: Int,
        json: Boolean
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()
        if (apiKey.isBlank()) return@withContext Result.failure(IOException("DeepSeek key missing"))
        val payload = mutableMapOf<String, Any>(
            "model" to MODEL,
            "messages" to listOf(
                mapOf("role" to "system", "content" to system),
                mapOf("role" to "user", "content" to userContent)
            ),
            "stream" to false,
            "temperature" to 0.3,
            "max_tokens" to maxTokens,
            "thinking" to mapOf("type" to "disabled")
        )
        if (json) payload["response_format"] = mapOf("type" to "json_object")
        val request = Request.Builder()
            .url(ENDPOINT)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(gson.toJson(payload).toRequestBody(jsonMediaType))
            .build()
        try {
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "rawCompletion HTTP ${resp.code}: ${body.take(300)}")
                    return@withContext Result.failure(DeepSeekHttpException(resp.code))
                }
                val content = JsonParser.parseString(body).asJsonObject
                    .getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
                    ?.getAsJsonObject("message")?.get("content")
                    ?.takeUnless { it.isJsonNull }?.asString
                if (content.isNullOrBlank()) Result.failure(IOException("empty answer"))
                else Result.success(content.trim())
            }
        } catch (e: Exception) {
            Log.w(TAG, "rawCompletion failed: ${e.javaClass.simpleName} ${e.message}")
            Result.failure(e)
        }
    }

    private fun textPart(text: String): Map<String, Any> = mapOf("type" to "text", "text" to text)

    private fun imagePart(jpeg: ByteArray): Map<String, Any> = mapOf(
        "type" to "image_url",
        "image_url" to mapOf(
            "url" to "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP),
            "detail" to "high"
        )
    )

    private fun buildRequest(
        apiKey: String,
        messages: List<Message>,
        tools: List<ToolDefinition>,
        stream: Boolean
    ): Request {
        val payload = mutableMapOf<String, Any>(
            "model" to MODEL,
            "messages" to messages,
            "stream" to stream,
            "temperature" to 0.7
        )
        if (tools.isNotEmpty()) {
            payload["tools"] = tools
        }
        return Request.Builder()
            .url(ENDPOINT)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(gson.toJson(payload).toRequestBody(jsonMediaType))
            .build()
    }

    companion object {
        private const val TAG = "DeepSeekClient"
        private const val ENDPOINT = "https://api.deepseek.com/chat/completions"
        private const val MODEL = "deepseek-flash"
    }
}

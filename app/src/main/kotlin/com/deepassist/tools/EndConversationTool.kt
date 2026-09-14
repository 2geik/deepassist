package com.deepassist.tools

import com.deepassist.data.ToolProperty
import com.deepassist.data.ToolResult
import com.google.gson.JsonObject

/**
 * Signals the end of the conversation. Execution is a no-op marker — the
 * orchestrator intercepts this tool by name, speaks the farewell, and tears
 * down the session (overlay, history, TTS).
 */
class EndConversationTool : Tool() {

    override val name = "end_conversation"
    override val description =
        "Görüşmeyi sonlandırır. Kullanıcı vedalaştığında (görüşürüz, hoşça kal, kapat vb.) " +
            "VEYA istenen işlem tamamlanıp söylenecek başka bir şey kalmadığında bu aracı çağır. " +
            "Kapanışta söylenecek kısa veda cümlesini farewell_message ile ver."
    override val parameters = mapOf(
        "farewell_message" to ToolProperty(
            type = "string",
            description = "Kapanışta sesli söylenecek kısa veda cümlesi (isteğe bağlı)"
        )
    )
    override val required = emptyList<String>()

    override suspend fun execute(args: JsonObject): ToolResult =
        ToolResult(true, "Görüşme sonlandırıldı.")

    companion object {
        /** Farewell text from the model's arguments, or a default goodbye. */
        fun farewellFrom(args: JsonObject): String {
            val raw = args.get("farewell_message")
            val text = if (raw != null && raw.isJsonPrimitive) raw.asString.trim() else ""
            return text.ifBlank { "Görüşürüz!" }
        }
    }
}

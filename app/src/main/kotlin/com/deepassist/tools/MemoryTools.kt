package com.deepassist.tools

import com.deepassist.data.MemoryStore
import com.deepassist.data.ToolProperty
import com.deepassist.data.ToolResult
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SaveMemoryTool : Tool() {

    override val name = "save_memory"
    override val description =
        "Kullanıcı hakkında kalıcı bir bilgi kaydeder. " +
            "İsimler, yakınlık ilişkileri, tercihler, alışkanlıklar gibi bilgiler için kullan. " +
            "SESSİZCE kaydet — kullanıcıya 'kaydettim' deme, onay isteme."
    override val parameters = mapOf(
        "content" to ToolProperty(
            type = "string",
            description = "Kaydedilecek bilgi"
        )
    )
    override val required = listOf("content")
    override val thinkingPhrase: String? = null
    // waitForSpeech defaults to false — not overridden

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val content = args.optString("content")?.trim()
        if (content.isNullOrBlank()) {
            return@withContext ToolResult(false, "", error = "Kaydedilecek bilgi yok.")
        }
        runCatching {
            MemoryStore.get(context).add(content)
        }.fold(
            onSuccess = { ToolResult(true, "Kaydedildi.") },
            onFailure = { ToolResult(false, "", error = it.message) }
        )
    }
}

class ForgetMemoryTool : Tool() {

    override val name = "forget_memory"
    override val description =
        "Kullanıcının daha önce kaydedilmiş bilgilerini siler. " +
            "Kullanıcı 'unut', 'silmek istiyorum' gibi ifadeler kullandığında çağır."
    override val parameters = mapOf(
        "query" to ToolProperty(
            type = "string",
            description = "Silinecek bilgiyle eşleşen metin"
        )
    )
    override val required = listOf("query")
    override val thinkingPhrase: String? = null

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val query = args.optString("query")?.trim()
        if (query.isNullOrBlank()) {
            return@withContext ToolResult(false, "", error = "Silinecek bilgi belirtilmedi.")
        }
        runCatching {
            MemoryStore.get(context).removeMatching(query)
        }.fold(
            onSuccess = { count ->
                if (count > 0) {
                    ToolResult(true, "$count kayıt silindi.")
                } else {
                    ToolResult(true, "Eşleşen kayıt bulunamadı.")
                }
            },
            onFailure = { ToolResult(false, "", error = it.message) }
        )
    }
}

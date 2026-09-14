package com.deepassist.tools

import com.deepassist.data.ToolProperty
import com.deepassist.data.ToolResult
import com.deepassist.util.PermissionsHelper
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ContactsTool : Tool() {

    override val name = "search_contacts"
    override val description =
        "Telefon rehberinde isme göre kişi arar ve telefon numaralarını döndürür. " +
            "Arama, SMS veya WhatsApp işlemlerinden önce numara bulmak için kullanılır."
    override val parameters = mapOf(
        "query" to ToolProperty(
            type = "string",
            description = "Aranacak kişinin adı veya adının bir kısmı"
        )
    )
    override val required = listOf("query")
    override val thinkingPhrase: String? = "Rehbere bakıyorum..."

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        if (!PermissionsHelper.hasContacts(context)) {
            return@withContext ToolResult(false, "", error = "Rehber okuma izni verilmemiş.")
        }
        val rawQuery = args.optString("query")?.trim()
        if (rawQuery.isNullOrBlank()) {
            return@withContext ToolResult(false, "", error = "Aranacak isim belirtilmedi.")
        }

        val results = ContactLookup.search(context, rawQuery)

        if (results.isEmpty()) {
            ToolResult(true, "Rehberde \"$rawQuery\" ile eşleşen kişi bulunamadı.")
        } else {
            val listing = results.joinToString("\n") { "- ${it.name}: ${it.phoneNumber}" }
            ToolResult(true, "Bulunan kişiler (${results.size}):\n$listing")
        }
    }
}

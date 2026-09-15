package com.deepassist.data

import android.content.Context
import android.util.Log
import com.deepassist.llm.DeepSeekClient
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the long-term memory current without the user asking: after each session
 * the transcript goes to DeepSeek, which returns facts to add, merge or drop. Doing
 * it after the session keeps save_memory round-trips out of the conversation.
 */
class MemoryExtractor(context: Context, private val deepSeek: DeepSeekClient) {

    private val appContext = context.applicationContext
    private val mutex = Mutex()

    /** Learns from the messages of chat [chatId] sent since [sessionStart]. */
    suspend fun extract(chatId: String, sessionStart: Long) {
        mutex.withLock { learn(chatId, sessionStart) }
    }

    private suspend fun learn(chatId: String, sessionStart: Long) {
        val record = ChatHistoryStore.get(appContext).load(chatId) ?: return
        val messages = record.messages.filter { it.ts >= sessionStart }
        // "Saat kaç" and the like hold nothing to remember
        if (messages.filter { it.role == "user" }.sumOf { it.text.length } < MIN_USER_CHARS) return

        val store = MemoryStore.get(appContext)
        val existing = store.list()
        val memory = existing.joinToString("\n") { "${it.id.take(ID_CHARS)}: ${it.text}" }.ifEmpty { "(boş)" }
        val transcript = messages.joinToString("\n") { m ->
            (if (m.role == "user") "Kullanıcı: " else "Asistan: ") + m.text.take(500)
        }.takeLast(MAX_TRANSCRIPT_CHARS)

        val reply = deepSeek.complete(
            listOf(
                Message(role = "system", content = INSTRUCTIONS),
                Message(role = "user", content = "MEVCUT HAFIZA:\n$memory\n\nKONUŞMA:\n$transcript")
            ),
            emptyList()
        )?.content
        if (reply.isNullOrBlank()) {
            Log.w(TAG, "no reply for chat $chatId")
            return
        }
        val changes = parse(reply) ?: run {
            Log.w(TAG, "unparseable reply: ${reply.take(200)}")
            return
        }

        fun resolve(shortId: String): String? = existing.firstOrNull { it.id.startsWith(shortId.trim()) }?.id

        var removed = 0
        var updated = 0
        var added = 0
        changes.getAsJsonArray("remove")?.take(MAX_CHANGES)?.forEach { element ->
            val id = runCatching { element.asString }.getOrNull()?.let(::resolve) ?: return@forEach
            if (store.remove(id)) removed++
        }
        changes.getAsJsonArray("update")?.take(MAX_CHANGES)?.forEach { element ->
            val change = runCatching { element.asJsonObject }.getOrNull() ?: return@forEach
            val id = runCatching { change.get("id").asString }.getOrNull()?.let(::resolve) ?: return@forEach
            val text = runCatching { change.get("text").asString }.getOrNull()?.let(::acceptable) ?: return@forEach
            if (store.update(id, text)) updated++
        }
        changes.getAsJsonArray("add")?.take(MAX_CHANGES)?.forEach { element ->
            val text = runCatching { element.asString }.getOrNull()?.let(::acceptable) ?: return@forEach
            if (store.addIfNew(text)) added++
        }
        Log.i(TAG, "chat $chatId: +$added ~$updated -$removed")
    }

    private fun parse(reply: String): JsonObject? = runCatching {
        JsonParser.parseString(reply.substring(reply.indexOf('{'), reply.lastIndexOf('}') + 1)).asJsonObject
    }.getOrNull()

    /** Trimmed text, or null for blanks and anything carrying a phone number or code. */
    private fun acceptable(text: String): String? =
        text.trim().takeIf { it.isNotEmpty() && !LONG_NUMBER.containsMatchIn(it) }

    companion object {
        private const val TAG = "MemoryExtractor"
        private const val MIN_USER_CHARS = 20
        private const val ID_CHARS = 8
        private const val MAX_TRANSCRIPT_CHARS = 8000
        private const val MAX_CHANGES = 5
        private val LONG_NUMBER = Regex("\\d{6,}")

        private val INSTRUCTIONS = """
            Sen bir sesli asistanın hafıza yöneticisisin. Görevin: aşağıdaki konuşmadan kullanıcı hakkında İLERİDE İŞE YARAYACAK KALICI bilgileri çıkarıp mevcut hafızayı güncellemek.

            KAYDET:
            - Kullanıcının kendisi: adı, yaşadığı yer, uğraşı, kendisi söylediyse sağlık durumu.
            - Yakınları ve ilişkiler: kim kimdir (ör. "Sevinç kullanıcının eşi"); rehberdeki kayıt adı farklıysa onu da yaz.
            - İlgi alanları ve sevdikleri: müzik türleri ve sanatçılar, dinlediği/izlediği içerik türleri, merak ettiği konular.
            - Düzenli alışkanlıklar (ör. geceleri radyo tiyatrosu dinler).
            - Tercihler ve itirazlar (ör. seçeneklerin kısa okunmasını ister).
            - Kullanıcının asistanı düzelttiği ve tekrar edebilecek durumlar (ör. bir kişiye rehberdekinden farklı bir adla hitap etmesi).

            KAYDETME:
            - Tek seferlik işler (birini araması, bir mesaj okutması, bir video açtırması).
            - Anlık bilgiler (hava, saat, haber ve arama sonuçlarının içeriği).
            - Konuşma tanımanın yaptığı tek seferlik yazım ve duyma hataları.
            - Doğrulama kodları, şifreler, banka ve kart bilgileri, telefon numaraları.
            - Asistanın kendi söyledikleri ve emin olmadığın tahminler.

            KURALLAR:
            - Her kayıt tek bir bilgi içeren kısa, üçüncü şahıs Türkçe bir cümle olsun ve şu kategorilerden biriyle başlasın: [Kişisel], [Aile ve yakınlar], [İlgi alanı], [Alışkanlık], [Tercih], [Düzeltme].
            - Mevcut hafızada zaten olan bilgiyi tekrar ekleme.
            - Aynı konudaki kayıtları birleştirmek veya yeni bilgiyle düzeltmek için update kullan (id, hafızadaki satırın başındaki koddur).
            - Konuşmadan yanlış olduğu açıkça anlaşılan kayıtları remove ile sil.
            - Kalıcı yeni bilgi yoksa boş listeler döndür; zorlama.

            SADECE şu JSON'u döndür, başka hiçbir şey yazma:
            {"add": ["..."], "update": [{"id": "...", "text": "..."}], "remove": ["..."]}
        """.trimIndent()
    }
}

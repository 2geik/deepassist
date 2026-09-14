package com.deepassist.tools

import com.deepassist.data.ToolProperty
import com.deepassist.data.ToolResult
import com.deepassist.util.DeviceUtils
import com.google.gson.JsonObject

class DeviceInfoTool : Tool() {

    override val name = "get_device_info"
    override val description = "Cihazın anlık durumunu döndürür: saat, tarih, batarya seviyesi, ağ bağlantısı."
    // Kotlin 2.4 strict inference: empty collections need explicit type arguments
    override val parameters = emptyMap<String, ToolProperty>()
    override val required = emptyList<String>()

    override suspend fun execute(args: JsonObject): ToolResult {
        val state = DeviceUtils.getDeviceState(context)
        val charging = if (state.isCharging) ", şarj oluyor" else ""
        return ToolResult(
            true,
            "Saat: ${state.currentTime}, Tarih: ${state.currentDate} (${state.dayOfWeek}), " +
                "Batarya: %${state.batteryLevel}$charging, Ağ: ${state.networkType}"
        )
    }
}

class AskUserTool(
    private val askUser: suspend (String) -> String?
) : Tool() {

    override val name = "ask_user"
    override val description =
        "Kullanıcıya sesli olarak netleştirme sorusu sorar ve cevabını bekler. " +
            "Örneğin aynı isimde birden fazla kişi bulunduğunda hangisinin istendiğini sormak için kullanılır."
    override val parameters = mapOf(
        "question" to ToolProperty(
            type = "string",
            description = "Kullanıcıya sorulacak kısa ve net soru"
        )
    )
    override val required = listOf("question")

    override suspend fun execute(args: JsonObject): ToolResult {
        val question = args.optString("question")?.trim()
        if (question.isNullOrBlank()) {
            return ToolResult(false, "", error = "Soru belirtilmedi.")
        }
        val answer = askUser(question)
        return if (answer.isNullOrBlank()) {
            ToolResult(false, "", error = "Kullanıcıdan 30 saniye içinde cevap alınamadı.")
        } else {
            ToolResult(true, "Kullanıcının cevabı: $answer")
        }
    }
}

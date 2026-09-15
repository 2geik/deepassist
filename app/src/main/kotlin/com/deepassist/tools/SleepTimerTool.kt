package com.deepassist.tools

import com.deepassist.data.ToolProperty
import com.deepassist.data.ToolResult
import com.deepassist.service.SleepTimer
import com.google.gson.JsonObject
import java.text.SimpleDateFormat
import java.util.Locale

class SleepTimerTool : Tool() {

    override val name = "sleep_timer"
    override val description =
        "Uyku zamanlayıcısı: belirtilen süre sonra çalan müziğin, videonun veya radyo tiyatrosunun sesini " +
            "yavaşça kısıp durdurur. Kurma (set), iptal (cancel) ve kalan süreyi sorma (status)."
    override val parameters = mapOf(
        "action" to ToolProperty(
            type = "string",
            description = "Yapılacak işlem",
            enum = listOf("set", "cancel", "status")
        ),
        "minutes" to ToolProperty(
            type = "integer",
            description = "set için süre (dakika). 'Yarım saat' = 30, 'bir saat' = 60, 'bir buçuk saat' = 90."
        )
    )
    override val required = listOf("action")

    private val timeFormat = SimpleDateFormat("HH:mm", Locale("tr", "TR"))

    override suspend fun execute(args: JsonObject): ToolResult = when (args.optString("action")) {
        "set" -> {
            val minutes = args.optInt("minutes")
            if (minutes == null || minutes !in 1..MAX_MINUTES) {
                ToolResult(false, "", error = "Süre 1 ile $MAX_MINUTES dakika arasında olmalı.")
            } else {
                val endAt = SleepTimer.schedule(context, minutes)
                ToolResult(
                    true,
                    "Uyku zamanlayıcısı kuruldu: $minutes dakika sonra, saat ${timeFormat.format(endAt)} " +
                        "civarında ses yavaşça kısılıp medya durdurulacak."
                )
            }
        }
        "cancel" ->
            if (SleepTimer.cancel(context)) {
                ToolResult(true, "Uyku zamanlayıcısı iptal edildi.")
            } else {
                ToolResult(true, "Kurulu bir uyku zamanlayıcısı yoktu.")
            }
        "status" -> {
            val remaining = SleepTimer.remainingMs(context)
            if (remaining > 0) {
                val minutesLeft = (remaining + 59_999L) / 60_000L
                ToolResult(
                    true,
                    "Uyku zamanlayıcısının dolmasına yaklaşık $minutesLeft dakika var " +
                        "(saat ${timeFormat.format(SleepTimer.endAt(context))})."
                )
            } else {
                ToolResult(true, "Kurulu bir uyku zamanlayıcısı yok.")
            }
        }
        else -> ToolResult(false, "", error = "Bilinmeyen işlem. set, cancel veya status kullan.")
    }

    companion object {
        private const val MAX_MINUTES = 600
    }
}

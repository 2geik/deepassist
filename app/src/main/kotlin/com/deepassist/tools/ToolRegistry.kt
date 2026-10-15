package com.deepassist.tools

import android.content.Context
import com.deepassist.data.ToolDefinition
import com.deepassist.llm.DeepSeekClient

class ToolRegistry(askUserCallback: suspend (String) -> String?, deepSeek: DeepSeekClient) {

    private val tools = linkedMapOf<String, Tool>()

    init {
        // Tools ask the user through this (use_phone confirmations); it was never wired before
        Tool.askUser = askUserCallback
        register(ContactsTool())
        register(PhoneCallTool())
        register(SmsReadTool())
        register(SmsSendTool())
        register(VerificationCodeTool())
        register(WhatsAppTool())
        register(WhatsAppCallTool())
        register(NotificationReadTool())
        register(ReadMessagesTool())
        register(WebSearchTool())
        register(WebPageTool())
        register(DeviceInfoTool())
        register(CameraLookTool(deepSeek))
        register(ReadScreenTool(deepSeek))
        register(UsePhoneTool(deepSeek))
        register(YoutubeMusicTool())
        register(YoutubeVideoTool())
        register(WatchHistoryTool())
        register(ExchangeRateTool())
        register(SaveMemoryTool())
        register(ForgetMemoryTool())
        register(CallLogTool())
        register(PhoneActionTool())
        register(RenameContactTool())
        register(MediaControlTool())
        register(SleepTimerTool())
        register(AskUserTool(askUserCallback))
        register(VolumeTool())
        register(DndTool())
        register(BrightnessTool())
        register(FlashlightTool())
        register(LocationTool())
        register(WeatherTool())
        register(EndConversationTool())
    }

    private fun register(tool: Tool) {
        tools[tool.name] = tool
    }

    fun initialize(context: Context) {
        tools.values.forEach { it.initialize(context) }
    }

    fun get(name: String): Tool? = tools[name]

    fun getDefinitions(): List<ToolDefinition> = tools.values.map { it.toDefinition() }
}

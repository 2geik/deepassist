package com.deepassist.tools

import android.content.Context
import com.deepassist.data.ToolDefinition

class ToolRegistry(askUserCallback: suspend (String) -> String?) {

    private val tools = linkedMapOf<String, Tool>()

    init {
        register(ContactsTool())
        register(PhoneCallTool())
        register(SmsReadTool())
        register(SmsSendTool())
        register(WhatsAppTool())
        register(NotificationReadTool())
        register(WebSearchTool())
        register(DeviceInfoTool())
        register(YoutubeMusicTool())
        register(YoutubeVideoTool())
        register(ExchangeRateTool())
        register(SaveMemoryTool())
        register(ForgetMemoryTool())
        register(CallLogTool())
        register(PhoneActionTool())
        register(RenameContactTool())
        register(MediaControlTool())
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

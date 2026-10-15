package com.deepassist.tools

import android.app.KeyguardManager
import android.os.PowerManager
import android.util.Log
import com.deepassist.SessionActivity
import com.deepassist.agent.PanelControl
import com.deepassist.agent.ScreenAgent
import com.deepassist.agent.ShortcutStore
import com.deepassist.data.ToolProperty
import com.deepassist.data.ToolResult
import com.deepassist.llm.DeepSeekClient
import com.deepassist.service.AccessibilitySvc
import com.deepassist.speech.Earcons
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * use_phone: the screen agent as a tool. Anything the dedicated tools can't do
 * (settings, in-app navigation, forms) is done by looking at the screen and
 * tapping, with a spoken yes before messages and payments.
 */
class UsePhoneTool(private val deepSeek: DeepSeekClient) : Tool() {

    override val name = "use_phone"
    override val description =
        "Telefonu kullanıcının yerine ekrana bakıp dokunarak kullanır: ayar değiştirme (ör. yazı boyutu, Bluetooth, " +
            "ekran zaman aşımı), bir uygulamanın içinde gezinme, bilgi bulma, form doldurma, başka bir araçla yapılamayan " +
            "her türlü telefon işi. Arama, SMS, WhatsApp mesajı, YouTube, alarm, ses, parlaklık gibi işler için ÖNCE kendi " +
            "araçlarını kullan; bu araç onların yapamadığı işler içindir. Sonuç doğrudan kullanıcıya okunur."
    override val parameters = mapOf(
        "goal" to ToolProperty(
            type = "string",
            description = "Yapılacak iş, kullanıcının söylediği ayrıntılarla tam bir cümle olarak. " +
                "Ör. 'Ayarlardan yazı boyutunu en büyüğe getir', 'Getir uygulamasında son siparişimin durumuna bak'"
        )
    )
    override val required = listOf("goal")
    override val thinkingPhrase = "Hallediyorum..."
    override val timeoutMs = 240_000L

    override suspend fun execute(args: JsonObject): ToolResult {
        val goal = args.optString("goal")?.trim().orEmpty()
        if (goal.isBlank()) return spoken(false, "Ne yapmamı istediğini anlayamadım.")
        val service = AccessibilitySvc.instance
            ?: return spoken(false, "Telefonu kullanabilmem için erişilebilirlik servisinin açık olması gerekiyor.")
        if (!AccessibilitySvc.hasScreenCapabilities()) {
            return spoken(
                false,
                "Telefonu kullanma iznim eksik. Bir yakının erişilebilirlik ayarlarında deepAssist'i kapatıp tekrar açmalı."
            )
        }
        unlockIfPossible()?.let { return spoken(false, it) }

        val power = context.getSystemService(PowerManager::class.java)
        @Suppress("DEPRECATION")
        val screenLock = power?.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "deepassist:agent"
        )
        runCatching { screenLock?.acquire(timeoutMs) }
        PanelControl.stepAside()
        try {
            val agent = ScreenAgent(
                context = context,
                service = service,
                deepSeek = deepSeek,
                say = { text -> say?.invoke(text) },
                ask = { question -> askUser?.invoke(question) },
                tick = { playEarcon?.invoke(Earcons.Sound.TICK) }
            )
            val shortcuts = ShortcutStore.get(context)
            val known = shortcuts.match(goal)
            val outcome = agent.run(goal, hint = known?.steps?.joinToString("\n"))
            Log.i(TAG, "goal done=${outcome.success} after ${outcome.steps.size} steps, shortcut=${known != null}")
            if (outcome.success) shortcuts.remember(goal, outcome.steps, known?.id)
            else if (!outcome.declined) known?.let { shortcuts.failed(it.id) }
            return spoken(outcome.success, outcome.spoken)
        } finally {
            runCatching { if (screenLock?.isHeld == true) screenLock.release() }
            // The panel comes back with the spoken result and the follow-up question
            PanelControl.restore()
        }
    }

    /** Null when the screen is usable; otherwise what to tell the user. */
    private suspend fun unlockIfPossible(): String? {
        val keyguard = context.getSystemService(KeyguardManager::class.java) ?: return null
        if (!keyguard.isKeyguardLocked) return null
        if (keyguard.isDeviceSecure) return "Telefon kilitli. Kilidi açarsan devam edebilirim."
        val panel = SessionActivity.instance ?: return "Telefon kilitli. Kilidi açarsan devam edebilirim."
        withContext(Dispatchers.Main) { keyguard.requestDismissKeyguard(panel, null) }
        repeat(20) {
            if (!keyguard.isKeyguardLocked) return null
            delay(100)
        }
        return "Ekran kilidini açamadım. Kilidi açarsan devam edebilirim."
    }

    private fun spoken(ok: Boolean, text: String) =
        ToolResult(success = ok, data = if (ok) text else "", error = if (ok) null else text, speakDirectly = true)

    private companion object {
        const val TAG = "UsePhone"
    }
}

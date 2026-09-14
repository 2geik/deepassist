package com.deepassist.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.deepassist.data.NotificationInfo
import java.util.concurrent.ConcurrentLinkedQueue

/** Process-wide cache of recent notifications captured by the accessibility service. */
object NotificationStore {
    private const val MAX_NOTIFICATIONS = 200

    val notifications = ConcurrentLinkedQueue<NotificationInfo>()

    fun add(info: NotificationInfo) {
        notifications.add(info)
        while (notifications.size > MAX_NOTIFICATIONS) {
            notifications.poll()
        }
    }

    fun snapshot(): List<NotificationInfo> = notifications.toList()
}

/**
 * Reads notification events into [NotificationStore] and detects the
 * screen-off → screen-on power-key gesture to trigger the assistant.
 *
 * ## GELECEK: Ekran Otomasyonu (Screen Agent)
 *
 * AccessibilityService şu an yalnızca bildirim yakalama ve güç tuşu jesti
 * için kullanılıyor.  Bir sonraki fazda servise ekran-okuma ve buton-tıklama
 * yetenekleri eklenerek `phone_action` ve özel araçların başaramadığı
 * işlemler (ör. WhatsApp'ta Gönder'e basma, ayar değiştirme, uygulama içi
 * gezinme) doğrudan UI otomasyonuyla gerçekleştirilebilir.
 *
 * Gerekli altyapı:
 * - `accessibility_service_config.xml` → `canRetrieveWindowContent = true`
 * - `findAccessibilityNodeInfosByText(text)` → hedef düğümü bul
 * - `ACTION_CLICK` / `performGlobalAction(GLOBAL_ACTION_BACK)` → eylem
 * - Model rehberliğinde observe → decide → act döngüsü (`screen_agent` tool)
 * - Hız sınırı (rate limiting) ve kullanıcı onayı mekanizması
 *
 * Bu, AccessibilityService'in tehlikeli izin seviyesi ve sistem tarafından
 * sıkı denetlenmesi nedeniyle dikkatli tasarım gerektirir — ayrı bir
 * geliştirme fazı olarak planlanmıştır.
 */
class AccessibilitySvc : AccessibilityService() {

    private var screenReceiver: BroadcastReceiver? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        registerScreenReceiver()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return // ignore our own foreground-service notification

        var title: String? = null
        var text: String? = null
        (event.parcelableData as? Notification)?.let { notification ->
            title = notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            text = notification.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        }
        if (title.isNullOrBlank() && text.isNullOrBlank()) {
            text = event.text.joinToString(" ").takeIf { it.isNotBlank() }
        }
        if (title.isNullOrBlank() && text.isNullOrBlank()) return

        val appName = try {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(ai).toString()
        } catch (e: Exception) {
            pkg
        }
        NotificationStore.add(
            NotificationInfo(
                packageName = pkg,
                appName = appName,
                title = title,
                text = text,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    override fun onInterrupt() {
        // no continuous feedback to interrupt
    }

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        val receiver = object : BroadcastReceiver() {
            private var lastScreenOff = 0L

            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> lastScreenOff = SystemClock.elapsedRealtime()
                    Intent.ACTION_SCREEN_ON -> {
                        // Anti-retrigger: if the service just ended a session via
                        // screen-off, skip the power gesture to avoid immediately
                        // restarting after the user pressed power to end it.
                        if (SystemClock.elapsedRealtime() - AssistantForegroundService.lastScreenOffEndAt < 2_500L) {
                            return@onReceive
                        }
                        // Quick off→on (double power press) = deliberate trigger gesture
                        val elapsed = SystemClock.elapsedRealtime() - lastScreenOff
                        if (lastScreenOff > 0 && elapsed < POWER_GESTURE_WINDOW_MS) {
                            val callback = onPowerKeyDetected
                            if (callback != null) {
                                callback.invoke()
                            } else {
                                // Orchestrator not running yet — start it with the power trigger
                                runCatching {
                                    AssistantForegroundService.trigger(
                                        this@AccessibilitySvc,
                                        AssistantForegroundService.ACTION_POWER_TRIGGER
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        screenReceiver = receiver
    }

    override fun onDestroy() {
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val POWER_GESTURE_WINDOW_MS = 1500L

        @Volatile
        var instance: AccessibilitySvc? = null

        @Volatile
        var onPowerKeyDetected: (() -> Unit)? = null
    }
}

package com.deepassist.tools

import android.app.KeyguardManager
import android.content.Context
import android.os.SystemClock
import com.deepassist.SessionActivity
import com.deepassist.util.DiagLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Gets the lock screen out of the way before a tool opens another app (YouTube,
 * WhatsApp, Maps...). Apps opened on the keyguard stay hidden behind it, so the
 * request seemed to do nothing.
 *
 * Without a PIN/pattern the keyguard is dismissed at once. With one, the user is
 * told to unlock, the unlock prompt is shown and the request goes on as soon as
 * the phone is unlocked.
 */
object ScreenUnlock {

    /** Null when the screen is (now) unlocked; otherwise what to tell the user. */
    suspend fun ensure(context: Context): String? {
        val keyguard = context.getSystemService(KeyguardManager::class.java) ?: return null
        if (!keyguard.isKeyguardLocked) return null
        val secure = keyguard.isDeviceSecure
        val panel = SessionActivity.instance?.takeUnless { it.isFinishing }
        DiagLog.event("Unlock", "locked secure=$secure panel=${panel != null}")
        if (panel == null) {
            // Only an activity shown over the keyguard may ask to dismiss it
            if (secure) Tool.say?.invoke(ASK_SECURE)
            return if (secure) waitForUser(keyguard, null) else FAILED
        }

        // The unlock prompt covers the panel: status updates must not pull it back on top
        val suppressedBefore = SessionActivity.relaunchSuppressed
        SessionActivity.relaunchSuppressed = true
        try {
            val result = request(keyguard, panel)
            if (!secure) {
                val unlocked = awaitUnlocked(keyguard, INSECURE_WAIT_MS, result)
                DiagLog.event("Unlock", if (unlocked) "dismissed" else "dismiss failed (${result.peek()})")
                return if (unlocked) settled() else FAILED
            }
            Tool.say?.invoke(ASK_SECURE)
            return waitForUser(keyguard, result)
        } finally {
            SessionActivity.relaunchSuppressed = suppressedBefore
        }
    }

    private suspend fun waitForUser(keyguard: KeyguardManager, result: Outcome?): String? {
        val start = SystemClock.elapsedRealtime()
        val unlocked = awaitUnlocked(keyguard, SECURE_WAIT_MS, result)
        DiagLog.event(
            "Unlock",
            if (unlocked) "user unlocked in ${SystemClock.elapsedRealtime() - start} ms"
            else "user did not unlock (${result?.peek()})"
        )
        return if (unlocked) settled() else NOT_UNLOCKED
    }

    /** Asks Android to dismiss the keyguard; with a PIN this shows the unlock prompt. */
    private suspend fun request(keyguard: KeyguardManager, panel: SessionActivity): Outcome {
        val outcome = Outcome()
        withContext(Dispatchers.Main) {
            runCatching {
                keyguard.requestDismissKeyguard(panel, object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() { outcome.done.complete("succeeded") }
                    override fun onDismissCancelled() { outcome.done.complete("cancelled") }
                    override fun onDismissError() { outcome.done.complete("error") }
                })
            }.onFailure { outcome.done.complete("threw ${it.javaClass.simpleName}") }
        }
        return outcome
    }

    /**
     * True once the keyguard is gone. Stops early when Android reports the request
     * cancelled (the user closed the prompt) or failed.
     */
    private suspend fun awaitUnlocked(keyguard: KeyguardManager, maxMs: Long, result: Outcome?): Boolean =
        withTimeoutOrNull(maxMs) {
            while (keyguard.isKeyguardLocked) {
                val r = result?.peek()
                if (r != null && r != "succeeded") {
                    // Give a just-finished unlock a moment to show in isKeyguardLocked
                    delay(300)
                    return@withTimeoutOrNull !keyguard.isKeyguardLocked
                }
                delay(100)
            }
            true
        } ?: false

    /** Lets the unlock animation finish so the app opens on top, not behind it. */
    private suspend fun settled(): String? {
        delay(SETTLE_MS)
        return null
    }

    private class Outcome {
        val done = CompletableDeferred<String>()
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        fun peek(): String? = if (done.isCompleted) done.getCompleted() else null
    }

    private const val INSECURE_WAIT_MS = 2_500L
    private const val SECURE_WAIT_MS = 30_000L
    private const val SETTLE_MS = 350L

    private const val ASK_SECURE = "Telefonun şifreli, önce kilidi açman gerekiyor. Açınca devam ediyorum."
    private const val NOT_UNLOCKED = "Telefonun kilidi açılmadı. Kilidi açıp tekrar söylersen hemen açarım."
    private const val FAILED = "Ekran kilidini kaldıramadım. Kilidi açıp tekrar söylersen hemen açarım."
}

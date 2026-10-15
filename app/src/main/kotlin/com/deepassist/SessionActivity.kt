package com.deepassist

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import com.deepassist.service.AssistantForegroundService
import com.deepassist.service.ChatSession
import com.deepassist.service.OverlayAnchor
import com.deepassist.service.VoiceActivity
import com.deepassist.ui.SessionPanel
import com.deepassist.ui.theme.DeepAssistTheme
import com.deepassist.util.DiagLog
import com.deepassist.util.PermissionsHelper

/**
 * Bottom-sheet session UI: a transparent full-screen activity hosting the
 * Compose panel ([SessionPanel]). Being an activity (unlike a
 * TYPE_APPLICATION_OVERLAY window, which Android hides on the keyguard) it can
 * show over the lock screen via setShowWhenLocked, wake the display via
 * setTurnScreenOn, and keep the screen on for the whole session via
 * FLAG_KEEP_SCREEN_ON. Tapping outside the panel ends the assistant session.
 */
class SessionActivity : ComponentActivity() {

    private val hideHandler = Handler(Looper.getMainLooper())

    // True between onStop and onStart — the panel exists but another app covers it
    @Volatile private var inBackground = false

    private val entries = mutableStateOf(ChatSession.snapshot())
    private val expanded = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        launchPending = false

        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Draw behind the bars; the panel pads itself with the system insets
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Launched via the keyguard full-screen-intent path: clear that notification
        runCatching {
            getSystemService(NotificationManager::class.java)?.cancel(FSI_NOTIFICATION_ID)
        }

        onBackPressedDispatcher.addCallback(this) {
            if (expanded.value) expanded.value = false else endSessionAndFinish()
        }

        setContent {
            DeepAssistTheme {
                val phase by VoiceActivity.phase.collectAsState()
                val level by VoiceActivity.level.collectAsState()
                SessionPanel(
                    entries = entries.value,
                    phase = phase,
                    level = level,
                    expanded = expanded.value,
                    onExpand = { expanded.value = true },
                    onCollapse = { expanded.value = false },
                    onOutsideTap = { if (expanded.value) expanded.value = false else endSessionAndFinish() },
                    onEnd = { endSessionAndFinish() }
                )
            }
        }

        ChatSession.setListener { runOnUiThread { render() } }
        render()
        DiagLog.event("Panel", "created")
        // A launch that arrives after its session already ended (e.g. the power key
        // pressed meanwhile) would leave a stale panel up: close it at once
        if (intent?.hasExtra(EXTRA_BOOTSTRAP_ACTION) != true && !AssistantForegroundService.sessionActive) {
            DiagLog.event("Panel", "late launch after session end, closing")
            finish()
            return
        }
        handleBootstrap(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleBootstrap(intent)
    }

    /** Opened by a trigger whose background service start was refused: start it from here. */
    private fun handleBootstrap(intent: Intent?) {
        val action = intent?.getStringExtra(EXTRA_BOOTSTRAP_ACTION) ?: return
        intent.removeExtra(EXTRA_BOOTSTRAP_ACTION)
        DiagLog.event("Panel", "bootstrap ${action.substringAfterLast('.')}")
        AssistantForegroundService.trigger(this, action)
    }

    /** HOME press (or recents gesture) while the panel is up ends the session. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (AssistantForegroundService.isToolHandoff()) {
            // A tool just opened another app (WhatsApp, YouTube, dialer...). Step aside,
            // but let the session speak the result instead of killing it mid-action.
            relaunchSuppressed = true
            AssistantForegroundService.uiHandedOff = true
            finish()
            return
        }
        endSessionAndFinish()
    }

    override fun onStart() {
        super.onStart()
        inBackground = false
    }

    override fun onStop() {
        super.onStop()
        inBackground = true
    }

    private fun endSessionAndFinish() {
        AssistantForegroundService.endSession(this)
        finish()
    }

    private fun render() {
        entries.value = ChatSession.snapshot()
        hideHandler.removeCallbacksAndMessages(null)
        // The service closes the panel explicitly when the conversation ends
        // (farewell, silence, error, outside tap, home/back/power). This timer
        // is only a safety net for a session that died without cleanup.
        hideHandler.postDelayed({ finish() }, SAFETY_TIMEOUT_MS)
    }

    override fun onDestroy() {
        hideHandler.removeCallbacksAndMessages(null)
        if (instance === this) {
            instance = null
            ChatSession.setListener(null)
            // A relaunch in flight still needs the anchor for its background start
            if (!launchPending) OverlayAnchor.hide()
        }
        super.onDestroy()
    }

    companion object {
        /** Only a dead-man switch — the service calls [close] explicitly. */
        private const val SAFETY_TIMEOUT_MS = 180_000L
        private const val FSI_CHANNEL_ID = "assistant_session_v2"
        private const val FSI_NOTIFICATION_ID = 1002
        private const val ANCHOR_SETTLE_MS = 130L

        @Volatile
        var instance: SessionActivity? = null
            private set

        /** Set when an app opened by a tool took over; keeps updates from pulling the panel over it. */
        @Volatile
        var relaunchSuppressed = false

        @Volatile
        private var launchPending = false

        private const val EXTRA_BOOTSTRAP_ACTION = "bootstrap_action"

        /** How long a launch may take before the full-screen-intent fallback kicks in. */
        private const val LAUNCH_CHECK_MS = 1_500L

        /** The panel is on screen (created, not finishing, not covered). */
        fun isShowing(): Boolean {
            val current = instance ?: return false
            return !current.isFinishing && !current.inBackground
        }

        /**
         * Opens the panel straight away and lets it start the service from the
         * foreground. Used when Android refuses a background service start; callers
         * are system-bound services (accessibility, voice interaction), which may
         * start activities from the background.
         */
        fun bootstrap(context: Context, action: String) {
            DiagLog.event("Panel", "bootstrap launch ${action.substringAfterLast('.')}")
            runCatching { context.startActivity(bootstrapIntent(context, action)) }
                .onFailure { DiagLog.event("Panel", "bootstrap failed ${DiagLog.describe(it)}") }
        }

        fun bootstrapIntent(context: Context, action: String): Intent =
            Intent(context, SessionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_BOOTSTRAP_ACTION, action)

        /**
         * A blocked background activity start fails silently. If the panel still isn't
         * up after [LAUNCH_CHECK_MS] while the session runs, post the full-screen
         * intent: it opens the panel on the lock screen and shows a tappable
         * heads-up notification otherwise.
         */
        private fun verifyLaunch(app: Context, path: String) {
            Handler(Looper.getMainLooper()).postDelayed({
                if (isShowing() || relaunchSuppressed || !AssistantForegroundService.sessionActive) {
                    return@postDelayed
                }
                DiagLog.event(
                    "Panel",
                    "not visible after $path launch (fsi=${PermissionsHelper.canUseFullScreenIntent(app)})"
                )
                postFullScreenNotification(app)
            }, LAUNCH_CHECK_MS)
        }

        /**
         * Brings the session UI up if it is not already showing.
         *
         * Background activity starts are blocked on Android 14+ unless the app
         * has a visible overlay window, so a 1px anchor is shown first. On the
         * keyguard (where overlay windows are hidden and that exemption fails)
         * a full-screen-intent notification launches the activity instead.
         */
        fun ensureVisible(context: Context) {
            if (relaunchSuppressed) return
            val current = instance
            // Still on screen — nothing to do. A covered (stopped) panel is brought back.
            if (current != null && !current.isFinishing && !current.inBackground) return
            val app = context.applicationContext

            val keyguard = app.getSystemService(KeyguardManager::class.java)
            if (keyguard?.isKeyguardLocked == true) {
                // Try direct start first — on many devices (incl. Xiaomi with
                // "Show on lock screen" granted) this succeeds and no FSI is needed.
                runCatching {
                    app.startActivity(
                        Intent(app, SessionActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                // Fall back to silent full-screen-intent after a short grace period. A panel
                // that started but stays hidden behind the keyguard counts as not shown.
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!isShowing()) postFullScreenNotification(app)
                }, 400L)
                verifyLaunch(app, "keyguard")
                return
            }

            OverlayAnchor.show(app)
            launchPending = true
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val now = instance
                    if (now != null && !now.isFinishing && !now.inBackground) return@postDelayed
                    runCatching {
                        app.startActivity(
                            Intent(app, SessionActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }.onFailure {
                        android.util.Log.w("SessionActivity", "panel launch failed", it)
                        DiagLog.event("Panel", "launch failed ${DiagLog.describe(it)}")
                    }
                } finally {
                    launchPending = false
                }
            }, ANCHOR_SETTLE_MS)
            verifyLaunch(app, "anchor")
        }

        fun close() {
            val current = instance
            current?.runOnUiThread { runCatching { current.finish() } }
        }

        private fun postFullScreenNotification(context: Context) {
            runCatching {
                val nm = context.getSystemService(NotificationManager::class.java) ?: return
                // Remove old channel (channels are immutable — new id needed for silent config)
                nm.deleteNotificationChannel("assistant_session")
                nm.createNotificationChannel(
                    NotificationChannel(
                        FSI_CHANNEL_ID,
                        "Asistan Oturumu",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        setSound(null, null)
                        enableVibration(false)
                        setShowBadge(false)
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    }
                )
                val pi = PendingIntent.getActivity(
                    context,
                    10,
                    Intent(context, SessionActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                nm.notify(
                    FSI_NOTIFICATION_ID,
                    Notification.Builder(context, FSI_CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle("deepAssist")
                        .setContentText("Asistan dinliyor")
                        .setContentIntent(pi)
                        .setFullScreenIntent(pi, true)
                        .setAutoCancel(true)
                        .setCategory(Notification.CATEGORY_CALL)
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .build()
                )
            }
        }
    }
}

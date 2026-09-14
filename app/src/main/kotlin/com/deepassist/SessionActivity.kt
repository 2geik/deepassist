package com.deepassist

import android.app.Activity
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.deepassist.service.AssistantForegroundService
import com.deepassist.service.ChatSession
import com.deepassist.service.OverlayAnchor

/**
 * Bottom-sheet style session UI: a transparent full-screen activity hosting
 * the chat panel. Being an activity (unlike a TYPE_APPLICATION_OVERLAY window,
 * which Android hides on the keyguard) it can show over the lock screen via
 * setShowWhenLocked, wake the display via setTurnScreenOn, and keep the screen
 * on for the whole session via FLAG_KEEP_SCREEN_ON. Tapping outside the panel
 * ends the assistant session.
 */
class SessionActivity : Activity() {

    private lateinit var bubbleList: LinearLayout
    private lateinit var scroll: CappedScrollView
    private val hideHandler = Handler(Looper.getMainLooper())

    // Full-screen expansion
    private var expanded = false
    private lateinit var sheetView: LinearLayout
    private lateinit var rootFrame: FrameLayout
    private lateinit var topBarView: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this

        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Launched via the keyguard full-screen-intent path: clear that notification
        runCatching {
            getSystemService(NotificationManager::class.java)?.cancel(FSI_NOTIFICATION_ID)
        }

        setContentView(buildUi())

        ChatSession.setListener { runOnUiThread { render() } }
        render()
    }

    /** HOME press (or recents gesture) while the panel is up ends the session. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        endSessionAndFinish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (expanded) {
            collapse()
        } else {
            endSessionAndFinish()
        }
    }

    private fun endSessionAndFinish() {
        AssistantForegroundService.endSession(this)
        finish()
    }

    private fun buildUi(): View {
        val density = resources.displayMetrics.density

        // --- Top bar (hidden until expanded) ---
        topBarView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())

            val chevron = TextView(this@SessionActivity).apply {
                text = "⌄"
                textSize = 24f
                setTextColor(Color.BLACK)
                setPadding((16 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
                setOnClickListener { collapse() }
            }
            addView(chevron)

            val title = TextView(this@SessionActivity).apply {
                text = "deepAssist"
                textSize = 16f
                setTextColor(Color.parseColor("#212121"))
                setTypeface(typeface, Typeface.BOLD)
            }
            addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val endBtn = TextView(this@SessionActivity).apply {
                text = "Sohbeti bitir"
                textSize = 14f
                setTextColor(Color.parseColor("#B00020"))
                setPadding((12 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
                setOnClickListener { endSessionAndFinish() }
            }
            addView(endBtn)
        }

        // --- Handle with swipe-to-expand touch area ---
        val handle = View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = 2 * density
                setColor(Color.parseColor("#D0CDD7"))
            }
        }

        val swipeThreshold = (64 * density).toInt()
        val handleContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, (14 * density).toInt(), 0, (6 * density).toInt())
            addView(handle, LinearLayout.LayoutParams((36 * density).toInt(), (4 * density).toInt()))
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val dy = downY - event.rawY
                        if (dy > swipeThreshold && !expanded) expand()
                        else if (dy < -swipeThreshold && expanded) collapse()
                        true
                    }
                    else -> false
                }
            }
        }

        bubbleList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = CappedScrollView(this).apply {
            maxHeightPx = (resources.displayMetrics.heightPixels * 0.45f).toInt()
            isVerticalScrollBarEnabled = false
            addView(
                bubbleList,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val pad = (16 * density).toInt()
        sheetView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (10 * density).toInt(), pad, pad)
            background = GradientDrawable().apply {
                val r = 24 * density
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setColor(Color.WHITE)
            }
            elevation = 12 * density
            isClickable = true

            addView(topBarView)
            addView(handleContainer)
            addView(
                scroll,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        rootFrame = FrameLayout(this).apply {
            addView(
                sheetView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM
                )
            )
            setOnClickListener {
                if (expanded) collapse()
                else endSessionAndFinish()
            }
        }

        return rootFrame
    }

    private var downY = 0f

    private fun expand() {
        expanded = true
        val density = resources.displayMetrics.density

        (sheetView.layoutParams as FrameLayout.LayoutParams).apply {
            height = FrameLayout.LayoutParams.MATCH_PARENT
        }
        (scroll.layoutParams as LinearLayout.LayoutParams).apply {
            height = 0
            weight = 1f
        }
        scroll.maxHeightPx = Int.MAX_VALUE

        (sheetView.background as GradientDrawable).apply {
            cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
            setColor(Color.WHITE)
        }

        topBarView.visibility = View.VISIBLE
        rootFrame.setOnClickListener(null) // no scrim-tap-to-end in fullscreen

        // Add status-bar inset padding for the top bar
        runCatching {
            val insets = window.decorView.rootWindowInsets
            if (insets != null) {
                sheetView.setPadding(
                    sheetView.paddingLeft,
                    insets.stableInsetTop,
                    sheetView.paddingRight,
                    sheetView.paddingBottom
                )
            }
        }

        sheetView.requestLayout()
    }

    private fun collapse() {
        expanded = false
        val density = resources.displayMetrics.density

        (sheetView.layoutParams as FrameLayout.LayoutParams).apply {
            height = FrameLayout.LayoutParams.WRAP_CONTENT
        }
        (scroll.layoutParams as LinearLayout.LayoutParams).apply {
            height = LinearLayout.LayoutParams.WRAP_CONTENT
            weight = 0f
        }
        scroll.maxHeightPx = (resources.displayMetrics.heightPixels * 0.45f).toInt()

        (sheetView.background as GradientDrawable).apply {
            val r = 24 * density
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setColor(Color.WHITE)
        }

        topBarView.visibility = View.GONE
        val pad = (16 * density).toInt()
        sheetView.setPadding(pad, (10 * density).toInt(), pad, pad)

        rootFrame.setOnClickListener {
            if (expanded) collapse()
            else endSessionAndFinish()
        }

        sheetView.requestLayout()
    }

    private fun render() {
        bubbleList.removeAllViews()
        for (entry in ChatSession.snapshot()) {
            bubbleList.addView(
                buildBubbleRow(entry),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

        hideHandler.removeCallbacksAndMessages(null)
        // The service closes the panel explicitly when the conversation ends
        // (farewell, silence, error, outside tap, home/back/power). This timer
        // is only a safety net for a session that died without cleanup.
        hideHandler.postDelayed({ finish() }, SAFETY_TIMEOUT_MS)
    }

    private fun buildBubbleRow(entry: ChatSession.Entry): View {
        val density = resources.displayMetrics.density
        val maxBubbleWidth = (resources.displayMetrics.widthPixels * 0.72f).toInt()
        val kind = entry.kind

        val tv = TextView(this).apply {
            text = if (kind == ChatSession.Kind.LISTENING) "●  ${entry.text}" else entry.text
            maxWidth = maxBubbleWidth
            when (kind) {
                ChatSession.Kind.USER -> {
                    setTextColor(Color.WHITE)
                    textSize = 16f
                }
                ChatSession.Kind.ASSISTANT -> {
                    setTextColor(Color.parseColor("#212121"))
                    textSize = 16f
                }
                ChatSession.Kind.STATUS -> {
                    setTextColor(Color.parseColor("#777777"))
                    textSize = 14f
                    setTypeface(typeface, Typeface.ITALIC)
                }
                ChatSession.Kind.LISTENING -> {
                    setTextColor(Color.parseColor("#4527A0"))
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                }
            }
            val padH = (14 * density).toInt()
            val padV = (10 * density).toInt()
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                cornerRadius = 18 * density
                setColor(
                    when (kind) {
                        ChatSession.Kind.USER -> Color.parseColor("#6750A4")
                        ChatSession.Kind.ASSISTANT -> Color.parseColor("#F1F0F5")
                        ChatSession.Kind.STATUS -> Color.parseColor("#F7F6FA")
                        ChatSession.Kind.LISTENING -> Color.parseColor("#EDE7F6")
                    }
                )
            }
            if (kind == ChatSession.Kind.LISTENING) {
                // Pulsing pill = the microphone is open right now
                startAnimation(
                    AlphaAnimation(1f, 0.4f).apply {
                        duration = 550
                        repeatMode = Animation.REVERSE
                        repeatCount = Animation.INFINITE
                    }
                )
            }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (kind == ChatSession.Kind.USER) Gravity.END else Gravity.START
            setPadding(0, (3 * density).toInt(), 0, (3 * density).toInt())
            addView(tv)
        }
    }

    override fun onDestroy() {
        hideHandler.removeCallbacksAndMessages(null)
        if (instance === this) {
            instance = null
            ChatSession.setListener(null)
            OverlayAnchor.hide()
        }
        super.onDestroy()
    }

    /** ScrollView whose height stops growing at [maxHeightPx]; older bubbles scroll. */
    private class CappedScrollView(context: Context) : ScrollView(context) {
        var maxHeightPx = Int.MAX_VALUE

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val capped = MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
            super.onMeasure(widthMeasureSpec, capped)
        }
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

        /**
         * Brings the session UI up if it is not already showing.
         *
         * Background activity starts are blocked on Android 14+ unless the app
         * has a visible overlay window, so a 1px anchor is shown first. On the
         * keyguard (where overlay windows are hidden and that exemption fails)
         * a full-screen-intent notification launches the activity instead.
         */
        fun ensureVisible(context: Context) {
            val current = instance
            if (current != null && !current.isFinishing) return
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
                // Fall back to silent full-screen-intent after a short grace period
                Handler(Looper.getMainLooper()).postDelayed({
                    if (instance == null) postFullScreenNotification(app)
                }, 400L)
                return
            }

            OverlayAnchor.show(app)
            Handler(Looper.getMainLooper()).postDelayed({
                if (instance != null) return@postDelayed
                runCatching {
                    app.startActivity(
                        Intent(app, SessionActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }, ANCHOR_SETTLE_MS)
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
                        .setSmallIcon(R.drawable.ic_launcher_foreground)
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

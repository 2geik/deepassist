package com.deepassist.service

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Invisible 1x1 TYPE_APPLICATION_OVERLAY window. Android 14+ only lets an app
 * holding SYSTEM_ALERT_WINDOW start activities from the background while it has
 * a visible overlay window — this anchor satisfies that requirement so the
 * session UI can launch from the home screen or over other apps.
 */
object OverlayAnchor {

    private val main = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var view: View? = null

    fun show(context: Context) {
        if (!Settings.canDrawOverlays(context)) return
        val app = context.applicationContext
        main.post {
            if (view != null) return@post
            val wm = app.getSystemService(WindowManager::class.java) ?: return@post
            val anchor = View(app)
            val params = WindowManager.LayoutParams(
                1,
                1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }
            runCatching {
                wm.addView(anchor, params)
                windowManager = wm
                view = anchor
            }
        }
    }

    fun hide() {
        main.post {
            view?.let { v -> runCatching { windowManager?.removeView(v) } }
            view = null
            windowManager = null
        }
    }
}

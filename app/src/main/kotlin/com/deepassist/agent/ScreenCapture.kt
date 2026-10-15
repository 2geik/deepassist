package com.deepassist.agent

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Screenshots through the accessibility service (Android 11+), in memory only.
 * Elements of the last [ScreenReader] pass are drawn as numbered boxes so the
 * model answers with an element number instead of guessing coordinates.
 */
object ScreenCapture {

    private const val TAG = "ScreenCapture"
    private const val LONG_EDGE = 1280

    sealed interface Result {
        class Image(val image: EphemeralImage) : Result
        data class Failed(val reason: Reason) : Result
    }

    enum class Reason { UNSUPPORTED, SECURE, FAILED }

    suspend fun take(service: AccessibilityService, marks: List<ScreenReader.Element> = emptyList()): Result {
        if (Build.VERSION.SDK_INT < 30) return Result.Failed(Reason.UNSUPPORTED)
        var outcome = shoot(service)
        // Android allows about one screenshot a second; wait once and retry
        if (outcome is Shot.Error && outcome.code == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
            delay(1_100)
            outcome = shoot(service)
        }
        return when (outcome) {
            is Shot.Error -> {
                Log.w(TAG, "screenshot failed code=${outcome.code}")
                Result.Failed(if (outcome.code == SECURE_WINDOW) Reason.SECURE else Reason.FAILED)
            }
            is Shot.Ok -> withContext(Dispatchers.Default) { encode(outcome.bitmap, marks) }
        }
    }

    private sealed interface Shot {
        class Ok(val bitmap: Bitmap) : Shot
        class Error(val code: Int) : Shot
    }

    @RequiresApi(30)
    private suspend fun shoot(service: AccessibilityService): Shot = suspendCancellableCoroutine { cont ->
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            service.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val software = try {
                        Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.let { hw ->
                            hw.copy(Bitmap.Config.ARGB_8888, true).also { hw.recycle() }
                        }
                    } finally {
                        buffer.close()
                    }
                    if (cont.isActive) {
                        cont.resume(if (software != null) Shot.Ok(software) else Shot.Error(-1))
                    } else {
                        software?.recycle()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    if (cont.isActive) cont.resume(Shot.Error(errorCode))
                }
            }
        )
    }

    private fun encode(bitmap: Bitmap, marks: List<ScreenReader.Element>): Result {
        var scaled: Bitmap? = null
        try {
            // FLAG_SECURE windows (banks) come back black instead of failing on some phones
            if (meanBrightness(bitmap) < 3) return Result.Failed(Reason.SECURE)
            if (marks.isNotEmpty()) drawMarks(bitmap, marks)
            val scale = LONG_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height)
            scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
            } else bitmap
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
            return Result.Image(EphemeralImage(out.toByteArray()))
        } finally {
            if (scaled != null && scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
        }
    }

    private fun drawMarks(bitmap: Bitmap, marks: List<ScreenReader.Element>) {
        val canvas = Canvas(bitmap)
        val density = bitmap.width / 360f
        val box = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            color = Color.rgb(200, 100, 59)
        }
        val tag = Paint().apply { color = Color.rgb(200, 100, 59) }
        val text = Paint().apply {
            color = Color.WHITE
            textSize = 11f * density
            isAntiAlias = true
            isFakeBoldText = true
        }
        for (e in marks) {
            if (!e.actionable) continue
            canvas.drawRect(RectF(e.bounds), box)
            val label = e.id.toString()
            val w = text.measureText(label) + 6 * density
            val h = text.textSize + 4 * density
            val left = e.bounds.left.toFloat()
            val top = e.bounds.top.toFloat()
            canvas.drawRect(left, top, left + w, top + h, tag)
            canvas.drawText(label, left + 3 * density, top + h - 3 * density, text)
        }
    }

    private fun meanBrightness(bitmap: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(bitmap, 48, 96, false)
        val px = IntArray(48 * 96)
        small.getPixels(px, 0, 48, 0, 0, 48, 96)
        small.recycle()
        val mean = px.sumOf { c -> ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) / 3 } / px.size
        px.fill(0)
        return mean
    }

    /** AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW (API 34). */
    private const val SECURE_WINDOW = 5
}

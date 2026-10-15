package com.deepassist.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.deepassist.SessionActivity
import com.deepassist.util.PermissionsHelper
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One still photo from the back camera, entirely in memory: no file is ever
 * written (the user's privacy rule). The camera is bound to the session panel,
 * so it only runs while the panel is on screen — Android lets a visible
 * activity use the camera even over the lock screen.
 */
object CameraCapture {

    private const val TAG = "CameraLook"
    private const val LONG_EDGE = 1280
    private const val SETTLE_MS = 600L

    sealed interface Result {
        /** JPEG bytes the caller must wipe ([ByteArray.fill]) once sent. */
        class Photo(val jpeg: ByteArray, val quality: Quality) : Result
        data class Failed(val spoken: String) : Result
    }

    /** Local checks before anything is uploaded. */
    data class Quality(val brightness: Int, val sharpness: Double) {
        /** Nothing to see: covered lens or a pitch-dark room. */
        val unusable: Boolean get() = brightness < 12
        val dark: Boolean get() = brightness < 45
        val blurry: Boolean get() = sharpness < 18.0
    }

    suspend fun capture(context: Context): Result {
        if (!PermissionsHelper.hasCamera(context)) {
            return Result.Failed("Kamera izni verilmemiş. Bir yakının deepAssist ayarlarından kamera iznini açmalı.")
        }
        val panel = preparePanel(context)
            ?: return Result.Failed("Kamerayı açamadım, ekranı açıp tekrar dener misin?")

        val rotation = currentRotation(context)
        val provider = cameraProvider(context)
        val capture = newImageCapture(rotation)
        return try {
            withContext(Dispatchers.Main) {
                provider.unbindAll()
                provider.bindToLifecycle(panel, CameraSelector.DEFAULT_BACK_CAMERA, capture)
            }
            // Let exposure and focus settle; the first frames are often dark or soft
            delay(SETTLE_MS)
            shoot(context, capture)
        } catch (e: Exception) {
            Log.w(TAG, "capture failed: ${e.javaClass.simpleName} ${e.message}")
            Result.Failed("Fotoğraf çekemedim, kamera şu an kullanılamıyor olabilir.")
        } finally {
            withContext(Dispatchers.Main) { runCatching { provider.unbind(capture) } }
        }
    }

    fun newImageCapture(rotation: Int): ImageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .setFlashMode(ImageCapture.FLASH_MODE_AUTO)
        .setTargetRotation(rotation)
        .build()

    /** Takes the picture on an already bound [capture] and makes the upright in-memory JPEG. */
    suspend fun shoot(context: Context, capture: ImageCapture): Result = try {
        val started = System.currentTimeMillis()
        val image = takePicture(context, capture)
        val photo = try {
            withContext(Dispatchers.Default) { toUprightJpeg(image) }
        } finally {
            image.close()
        }
        Log.i(TAG, "captured ${photo.jpeg.size / 1024} KB in ${System.currentTimeMillis() - started} ms, " +
            "brightness=${photo.quality.brightness} sharpness=${"%.0f".format(photo.quality.sharpness)}")
        photo
    } catch (e: Exception) {
        Log.w(TAG, "shoot failed: ${e.javaClass.simpleName} ${e.message}")
        Result.Failed("Fotoğraf çekemedim, kamera şu an kullanılamıyor olabilir.")
    }

    /** Camera permission plus a visible panel, which is the camera's lifecycle owner. */
    suspend fun preparePanel(context: Context): SessionActivity? {
        if (!PermissionsHelper.hasCamera(context)) return null
        return awaitPanel(context)
    }

    /** The panel is the camera's lifecycle owner; bring it up if it is hidden. */
    private suspend fun awaitPanel(context: Context): SessionActivity? {
        if (!SessionActivity.isShowing()) {
            withContext(Dispatchers.Main) {
                SessionActivity.relaunchSuppressed = false
                SessionActivity.ensureVisible(context)
            }
        }
        return withTimeoutOrNull(2_000L) {
            while (!SessionActivity.isShowing()) delay(50)
            SessionActivity.instance
        }
    }

    suspend fun cameraProvider(context: Context): ProcessCameraProvider =
        suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                runCatching { future.get() }
                    .onSuccess { cont.resume(it) }
                    .onFailure { cont.resumeWithException(it) }
            }, ContextCompat.getMainExecutor(context))
        }

    private suspend fun takePicture(context: Context, capture: ImageCapture): ImageProxy =
        suspendCancellableCoroutine { cont ->
            capture.takePicture(
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        if (cont.isActive) cont.resume(image) else image.close()
                    }

                    override fun onError(exception: ImageCaptureException) {
                        if (cont.isActive) cont.resumeWithException(exception)
                    }
                }
            )
        }

    /**
     * How the phone is physically held, so a document held sideways comes out
     * upright. The panel stays portrait, so the display rotation can't tell.
     */
    suspend fun currentRotation(context: Context): Int = withContext(Dispatchers.Main) {
        val reading = CompletableDeferred<Int>()
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation != ORIENTATION_UNKNOWN) reading.complete(orientation)
            }
        }
        val degrees = if (listener.canDetectOrientation()) {
            listener.enable()
            try {
                withTimeoutOrNull(300L) { reading.await() }
            } finally {
                listener.disable()
            }
        } else null
        when (degrees) {
            null -> Surface.ROTATION_0
            in 45..134 -> Surface.ROTATION_270
            in 135..224 -> Surface.ROTATION_180
            in 225..314 -> Surface.ROTATION_90
            else -> Surface.ROTATION_0
        }
    }

    private fun toUprightJpeg(image: ImageProxy): Result.Photo {
        val buffer = image.planes[0].buffer
        val raw = ByteArray(buffer.remaining()).also { buffer.get(it) }
        var decoded: Bitmap? = null
        var upright: Bitmap? = null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= LONG_EDGE) sample *= 2
            decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: error("decode failed")
            val scale = LONG_EDGE.toFloat() / maxOf(decoded.width, decoded.height)
            val matrix = Matrix().apply {
                if (scale < 1f) postScale(scale, scale)
                postRotate(image.imageInfo.rotationDegrees.toFloat())
            }
            upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            val quality = measure(upright)
            val out = ByteArrayOutputStream()
            upright.compress(Bitmap.CompressFormat.JPEG, 85, out)
            return Result.Photo(out.toByteArray(), quality)
        } finally {
            raw.fill(0)
            if (upright !== decoded) upright?.recycle()
            decoded?.recycle()
        }
    }

    /** Mean luminance (0-255) and Laplacian variance on a small grey copy. */
    private fun measure(bitmap: Bitmap): Quality {
        val w = 160
        val h = (bitmap.height * w / bitmap.width).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== bitmap) small.recycle()
        val grey = IntArray(px.size) { i ->
            val c = px[i]
            ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        }
        px.fill(0)
        val mean = grey.average()
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val lap = (4 * grey[i] - grey[i - 1] - grey[i + 1] - grey[i - w] - grey[i + w]).toDouble()
                sum += lap
                sumSq += lap * lap
                n++
            }
        }
        val variance = if (n == 0) 0.0 else sumSq / n - (sum / n) * (sum / n)
        grey.fill(0)
        return Quality(mean.toInt(), variance)
    }
}

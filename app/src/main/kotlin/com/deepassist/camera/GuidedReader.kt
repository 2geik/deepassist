package com.deepassist.camera

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Guides the user's hand until a document is fully in frame, then takes the
 * photo. On-device ML Kit finds where the text is (a few times a second, no
 * network); [FramingGuide] turns that into "biraz sola çevir"-style cues.
 * Nothing is stored: analysis frames are closed as soon as they're read.
 */
object GuidedReader {

    private const val TAG = "CameraGuide"
    private const val MAX_MS = 35_000L
    private const val GOOD_FRAMES = 3

    /** A cue is only spoken once the same advice held for this many frames (~1 s). */
    private const val STABLE_FRAMES = 3

    /** Quiet time after a cue ends before the next one; longer before repeating it. */
    private const val SPEAK_GAP_MS = 3_000L
    private const val REPEAT_GAP_MS = 5_000L

    /** Saying the opposite direction this soon after means the hand is overshooting. */
    private const val FLIP_WINDOW_MS = 8_000L

    private class Frame(val advice: FramingGuide.Advice, val brightness: Int)

    /**
     * @param cue speaks one short instruction right away (low-latency voice).
     * @return the photo and whether the text was framed before the time ran out.
     */
    suspend fun capture(context: Context, cue: suspend (String) -> Unit): Pair<CameraCapture.Result, Boolean> {
        val panel = CameraCapture.preparePanel(context)
            ?: return CameraCapture.Result.Failed("Kamerayı açamadım, ekranı açıp tekrar dener misin?") to false
        val rotation = CameraCapture.currentRotation(context)
        val provider = CameraCapture.cameraProvider(context)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val frames = Channel<Frame>(Channel.CONFLATED)
        val worker = Executors.newSingleThreadExecutor()

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(rotation)
            .build()
        analysis.setAnalyzer(worker) { proxy -> analyze(proxy, recognizer, frames) }
        val photo = CameraCapture.newImageCapture(rotation)

        var torchOn = false
        var framed = false
        try {
            val camera = withContext(Dispatchers.Main) {
                provider.unbindAll()
                provider.bindToLifecycle(panel, CameraSelector.DEFAULT_BACK_CAMERA, analysis, photo)
            }
            cue("Telefonu yazıya doğru tut, ben yönlendireceğim.")
            val started = SystemClock.elapsedRealtime()
            var good = 0
            var lastSpoken: FramingGuide.Advice? = null
            var lastSpokenAt = SystemClock.elapsedRealtime()
            var pending: FramingGuide.Advice? = null
            var pendingFrames = 0
            withTimeoutOrNull(MAX_MS) {
                for (frame in frames) {
                    if (!torchOn && frame.brightness < 50 && SystemClock.elapsedRealtime() - started > 800) {
                        torchOn = true
                        withContext(Dispatchers.Main) { camera.cameraControl.enableTorch(true) }
                        cue("Ortam karanlık, feneri açtım.")
                        lastSpokenAt = SystemClock.elapsedRealtime()
                        continue
                    }
                    if (frame.advice == FramingGuide.Advice.GOOD) {
                        pending = null
                        if (++good >= GOOD_FRAMES) {
                            framed = true
                            break
                        }
                        continue
                    }
                    good = 0
                    // Ignore a passing wobble of the hand: only advice that holds counts
                    if (frame.advice == pending) pendingFrames++ else {
                        pending = frame.advice
                        pendingFrames = 1
                    }
                    if (pendingFrames < STABLE_FRAMES) continue

                    val now = SystemClock.elapsedRealtime()
                    var advice = frame.advice
                    // Left then right (or up then down) in quick succession: the text is wider
                    // than the view, so stepping back helps more than another turn
                    if (opposite(advice, lastSpoken) && now - lastSpokenAt < FLIP_WINDOW_MS) {
                        advice = FramingGuide.Advice.TOO_CLOSE
                    }
                    val text = advice.spoken ?: continue
                    val gap = if (advice == lastSpoken) REPEAT_GAP_MS else SPEAK_GAP_MS
                    if (now - lastSpokenAt >= gap) {
                        Log.i(TAG, "cue $advice")
                        cue(text)
                        lastSpoken = advice
                        // Measured from the end of the cue, so cues never run into each other
                        lastSpokenAt = SystemClock.elapsedRealtime()
                        pending = null
                    }
                }
            }
            withContext(Dispatchers.Main) { analysis.clearAnalyzer() }
            cue(if (framed) "Tamam, sabit tut." else "Tam oturtamadım, yine de okumaya çalışıyorum.")
            Log.i(TAG, "framed=$framed after ${SystemClock.elapsedRealtime() - started} ms torch=$torchOn")
            return CameraCapture.shoot(context, photo) to framed
        } catch (e: Exception) {
            Log.w(TAG, "guided capture failed: ${e.javaClass.simpleName} ${e.message}")
            return CameraCapture.Result.Failed("Kamerayla okuyamadım, kamera şu an kullanılamıyor olabilir.") to false
        } finally {
            withContext(Dispatchers.Main) {
                runCatching { provider.unbind(analysis, photo) }
            }
            frames.close()
            worker.shutdown()
            recognizer.close()
        }
    }

    private fun opposite(a: FramingGuide.Advice, b: FramingGuide.Advice?): Boolean = when (a) {
        FramingGuide.Advice.TURN_LEFT -> b == FramingGuide.Advice.TURN_RIGHT
        FramingGuide.Advice.TURN_RIGHT -> b == FramingGuide.Advice.TURN_LEFT
        FramingGuide.Advice.MOVE_UP -> b == FramingGuide.Advice.MOVE_DOWN
        FramingGuide.Advice.MOVE_DOWN -> b == FramingGuide.Advice.MOVE_UP
        else -> false
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy, recognizer: com.google.mlkit.vision.text.TextRecognizer, out: Channel<Frame>) {
        val media = proxy.image
        if (media == null) {
            proxy.close()
            return
        }
        val rotation = proxy.imageInfo.rotationDegrees
        val upright = rotation == 90 || rotation == 270
        val width = if (upright) proxy.height else proxy.width
        val height = if (upright) proxy.width else proxy.height
        val brightness = lumaMean(proxy)
        recognizer.process(InputImage.fromMediaImage(media, rotation))
            .addOnSuccessListener { text ->
                val boxes = text.textBlocks
                    .filter { it.text.trim().length >= 2 }
                    .mapNotNull { it.boundingBox }
                    .map { FramingGuide.Box(it.left, it.top, it.right, it.bottom) }
                out.trySend(Frame(FramingGuide.advise(width, height, boxes), brightness))
            }
            .addOnCompleteListener { proxy.close() }
    }

    /** Average of a sparse sample of the Y plane (0-255). */
    private fun lumaMean(proxy: ImageProxy): Int {
        val plane = proxy.planes.firstOrNull() ?: return 128
        val buffer = plane.buffer
        val step = maxOf(1, buffer.remaining() / 2_000)
        var sum = 0L
        var n = 0
        var i = 0
        while (i < buffer.remaining()) {
            sum += buffer.get(i).toInt() and 0xFF
            n++
            i += step
        }
        return if (n == 0) 128 else (sum / n).toInt()
    }
}

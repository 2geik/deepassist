package com.deepassist.camera

/**
 * Turns where text sits in the camera frame into one spoken instruction, the
 * way a sighted helper would guide someone holding a document. Pure logic,
 * unit-tested; coordinates are in the upright frame.
 */
object FramingGuide {

    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }

    enum class Advice(val spoken: String?) {
        NO_TEXT("Yazı göremiyorum, telefonu yavaşça gezdir."),
        TOO_CLOSE("Biraz uzaklaştır."),
        TURN_LEFT("Biraz sola çevir."),
        TURN_RIGHT("Biraz sağa çevir."),
        MOVE_UP("Biraz yukarı kaldır."),
        MOVE_DOWN("Biraz aşağı indir."),
        TOO_FAR("Biraz yaklaştır."),
        GOOD(null)
    }

    /** Edge band (share of the frame) in which text counts as cut off. */
    private const val EDGE = 0.03f

    /** Text covering less than this share of the frame is too small to read. */
    private const val MIN_COVERAGE = 0.06f

    fun advise(frameWidth: Int, frameHeight: Int, boxes: List<Box>): Advice {
        val text = boxes.filter { it.width > 0 && it.height > 0 }
        if (text.isEmpty() || frameWidth <= 0 || frameHeight <= 0) return Advice.NO_TEXT
        val left = text.minOf { it.left }
        val top = text.minOf { it.top }
        val right = text.maxOf { it.right }
        val bottom = text.maxOf { it.bottom }

        val edgeX = frameWidth * EDGE
        val edgeY = frameHeight * EDGE
        val cutLeft = left <= edgeX
        val cutRight = right >= frameWidth - edgeX
        val cutTop = top <= edgeY
        val cutBottom = bottom >= frameHeight - edgeY

        return when {
            (cutLeft && cutRight) || (cutTop && cutBottom) -> Advice.TOO_CLOSE
            cutLeft -> Advice.TURN_LEFT
            cutRight -> Advice.TURN_RIGHT
            cutTop -> Advice.MOVE_UP
            cutBottom -> Advice.MOVE_DOWN
            (right - left).toFloat() * (bottom - top) / (frameWidth.toFloat() * frameHeight) < MIN_COVERAGE -> Advice.TOO_FAR
            else -> Advice.GOOD
        }
    }
}

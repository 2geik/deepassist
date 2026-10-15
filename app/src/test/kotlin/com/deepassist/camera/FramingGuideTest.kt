package com.deepassist.camera

import com.deepassist.camera.FramingGuide.Advice
import com.deepassist.camera.FramingGuide.Box
import org.junit.Assert.assertEquals
import org.junit.Test

class FramingGuideTest {

    private fun advise(vararg boxes: Box) = FramingGuide.advise(1000, 1000, boxes.toList())

    @Test
    fun centredReadableTextIsGood() = assertEquals(Advice.GOOD, advise(Box(200, 300, 800, 700)))

    @Test
    fun noTextAsksToSweep() = assertEquals(Advice.NO_TEXT, advise())

    @Test
    fun textRunningOffAnEdgeTurnsThatWay() {
        assertEquals(Advice.TURN_RIGHT, advise(Box(300, 300, 1000, 500)))
        assertEquals(Advice.TURN_LEFT, advise(Box(0, 300, 600, 500)))
        assertEquals(Advice.MOVE_UP, advise(Box(200, 0, 800, 400)))
        assertEquals(Advice.MOVE_DOWN, advise(Box(200, 600, 800, 1000)))
    }

    @Test
    fun textCutOnBothSidesMeansTooClose() {
        assertEquals(Advice.TOO_CLOSE, advise(Box(0, 300, 1000, 600)))
        assertEquals(Advice.TOO_CLOSE, advise(Box(300, 0, 600, 1000)))
    }

    @Test
    fun tinyTextMeansTooFar() = assertEquals(Advice.TOO_FAR, advise(Box(450, 450, 600, 520)))

    @Test
    fun severalBlocksAreJudgedTogether() =
        assertEquals(Advice.TURN_RIGHT, advise(Box(200, 200, 600, 300), Box(250, 400, 1000, 480)))
}

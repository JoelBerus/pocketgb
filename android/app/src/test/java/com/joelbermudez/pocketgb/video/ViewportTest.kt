package com.joelbermudez.pocketgb.video

import com.joelbermudez.pocketgb.emulator.ScaleMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportTest {
    @Test
    fun integerKeepsWholeMultiples() {
        assertEquals(Viewport(0, 64, 320, 288, 2), Viewport.calculate(320, 416, ScaleMode.INTEGER))
    }

    @Test
    fun fillIsExactTenByNineCenteredWithoutBandsOnWidthInPortrait() {
        val viewport = Viewport.calculate(1080, 2400, ScaleMode.FILL)
        assertEquals(1080, viewport.width)
        assertEquals(972, viewport.height)
        assertEquals(0, viewport.left)
        assertEquals((2400 - 972) / 2, viewport.top)
        assertEquals(0, viewport.scale)
    }

    @Test
    fun fillFitsHeightInLandscapeAndStaysCentered() {
        val viewport = Viewport.calculate(2400, 1080, ScaleMode.FILL)
        assertEquals(1080, viewport.height)
        assertEquals(1200, viewport.width)
        assertEquals(600, viewport.left)
        assertEquals(0, viewport.top)
    }

    @Test
    fun fillAndIntegerDifferWhenThereIsLeftoverSpace() {
        val integer = Viewport.calculate(1080, 2400, ScaleMode.INTEGER)
        val fill = Viewport.calculate(1080, 2400, ScaleMode.FILL)
        assertEquals(960, integer.width) // 6x
        assertTrue(fill.width > integer.width)
    }

    @Test
    fun smallerThanScreenFallsBackToFractionalInBothModes() {
        assertEquals(Viewport.calculate(159, 143, ScaleMode.INTEGER), Viewport.calculate(159, 143, ScaleMode.FILL).copy())
        val viewport = Viewport.calculate(159, 143, ScaleMode.FILL)
        assertTrue(viewport.width <= 159 && viewport.height <= 143)
    }

    @Test
    fun extremeSizesNeverProduceEmptyOrOverflowingRectangles() {
        for ((w, h) in listOf(1 to 1, 1 to 5000, 5000 to 1, Int.MAX_VALUE to Int.MAX_VALUE, Int.MAX_VALUE to 1, 1 to Int.MAX_VALUE)) {
            for (mode in ScaleMode.entries) {
                val v = Viewport.calculate(w, h, mode)
                assertTrue("$w x $h $mode", v.width in 1..w && v.height in 1..h)
                assertTrue(v.left >= 0 && v.top >= 0 && v.left + v.width <= w && v.top + v.height <= h)
            }
        }
    }

    @Test
    fun degenerateSizesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { Viewport.calculate(0, 100, ScaleMode.FILL) }
        assertThrows(IllegalArgumentException::class.java) { Viewport.calculate(100, -1, ScaleMode.INTEGER) }
    }
}

package com.joelbermudez.pocketgb.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegerViewportTest {
    @Test
    fun centersLargestIntegerScale() {
        assertEquals(
            IntegerViewport(left = 0, top = 64, width = 320, height = 288, scale = 2),
            IntegerViewport.calculate(width = 320, height = 416),
        )
    }

    @Test
    fun undersizedSurfaceFallsBackWithoutNegativeBounds() {
        val viewport = IntegerViewport.calculate(width = 159, height = 143)

        assertTrue(viewport.left >= 0 && viewport.top >= 0)
        assertTrue(viewport.width in 1..159)
        assertTrue(viewport.height in 1..143)
        assertEquals(0, viewport.scale)
    }
}

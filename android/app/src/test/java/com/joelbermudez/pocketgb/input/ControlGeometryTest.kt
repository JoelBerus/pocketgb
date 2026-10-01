package com.joelbermudez.pocketgb.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlGeometryTest {
    private val area = ControlBounds(0f, 0f, 400f, 700f)

    @Test
    fun defaultsAndOversizedCentersStayInsideSafeArea() {
        val layout = ControlLayout.defaults(ControlsOrientation.PORTRAIT).copy(
            centers = ControlLayout.defaults(ControlsOrientation.PORTRAIT).centers +
                (ControlId.A to NormalizedPoint(2f, -1f)),
        )
        val geometry = ControlGeometry(layout, ControlsOrientation.PORTRAIT, area)

        ControlId.entries.forEach { id ->
            val frame = geometry.frames.getValue(id)
            assertTrue("$id sale por la izquierda", frame.left >= area.left)
            assertTrue("$id sale por arriba", frame.top >= area.top)
            assertTrue("$id sale por la derecha", frame.right <= area.right)
            assertTrue("$id sale por abajo", frame.bottom <= area.bottom)
            assertTrue("$id no alcanza 48 dp", geometry.touchFrame(id).width >= 48f)
            assertTrue("$id no alcanza 48 dp", geometry.touchFrame(id).height >= 48f)
        }
    }

    @Test
    fun dpadHasDeadZoneEightDirectionsAndNeverOpposites() {
        val right = GameBoyButton.RIGHT.mask
        val left = GameBoyButton.LEFT.mask
        val up = GameBoyButton.UP.mask
        val down = GameBoyButton.DOWN.mask

        assertEquals(0, ControlGeometry.dpadMask(0f, 0f, 50f))
        assertEquals(right, ControlGeometry.dpadMask(50f, 0f, 50f))
        assertEquals(right or up, ControlGeometry.dpadMask(50f, -50f, 50f))
        assertEquals(up, ControlGeometry.dpadMask(0f, -50f, 50f))
        assertEquals(left or up, ControlGeometry.dpadMask(-50f, -50f, 50f))
        assertEquals(left, ControlGeometry.dpadMask(-50f, 0f, 50f))
        assertEquals(left or down, ControlGeometry.dpadMask(-50f, 50f, 50f))
        assertEquals(down, ControlGeometry.dpadMask(0f, 50f, 50f))
        assertEquals(right or down, ControlGeometry.dpadMask(50f, 50f, 50f))

        repeat(360) { degrees ->
            val radians = Math.toRadians(degrees.toDouble())
            val mask = ControlGeometry.dpadMask(
                dx = kotlin.math.cos(radians).toFloat() * 50f,
                dy = kotlin.math.sin(radians).toFloat() * 50f,
                radius = 50f,
            )
            assertFalse(mask and right != 0 && mask and left != 0)
            assertFalse(mask and up != 0 && mask and down != 0)
        }
    }

    @Test
    fun dimensionsScaleWithDisplayDensity() {
        val geometry = ControlGeometry(
            layout = ControlLayout.defaults(ControlsOrientation.PORTRAIT),
            orientation = ControlsOrientation.PORTRAIT,
            area = ControlBounds(0f, 0f, 800f, 1400f),
            density = 2f,
        )

        assertEquals(280f, geometry.frames.getValue(ControlId.DPAD).width)
        assertTrue(geometry.touchFrame(ControlId.START).height >= 96f)
    }
}

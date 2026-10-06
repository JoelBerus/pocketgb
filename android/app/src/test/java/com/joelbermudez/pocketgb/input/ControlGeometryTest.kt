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

    @Test
    fun globalSizeScaleMultipliesPerControlScaleAndIsCapped() {
        val base = ControlLayout.defaults(ControlsOrientation.PORTRAIT)
        val layout = base.copy(scales = mapOf(ControlId.A to 1.5f, ControlId.B to 9f))
        val geometry = ControlGeometry(layout, ControlsOrientation.PORTRAIT, area, sizeScale = 1.15f)

        assertEquals(68f * 1.5f * 1.15f, geometry.frames.getValue(ControlId.A).width, 0.01f)
        // El de B se recorta a 1,6 por control y después se aplica la global.
        assertEquals(68f * 1.6f * 1.15f, geometry.frames.getValue(ControlId.B).width, 0.01f)
        assertEquals(140f * 1.15f, geometry.frames.getValue(ControlId.DPAD).width, 0.01f)
        val small = ControlGeometry(base, ControlsOrientation.PORTRAIT, area, sizeScale = 0.85f)
        assertEquals(140f * 0.85f, small.frames.getValue(ControlId.DPAD).width, 0.01f)
    }

    @Test
    fun safeAreaIsTheZoneWhereControlsLive() {
        val safe = ControlBounds(48f, 0f, 352f, 650f)
        val landscape = ControlLayout.defaults(ControlsOrientation.LANDSCAPE)
        val geometry = ControlGeometry(landscape, ControlsOrientation.LANDSCAPE, safe)
        ControlId.entries.forEach { id ->
            val frame = geometry.frames.getValue(id)
            assertTrue("$id sale del área segura por la izquierda", frame.left >= safe.left - 0.01f)
            assertTrue("$id sale del área segura por la derecha", frame.right <= safe.right + 0.01f)
            assertTrue("$id sale del área segura por abajo", frame.bottom <= safe.bottom + 0.01f)
        }
    }

    @Test
    fun arrowsUseTheSameSectorsAsTheCross() {
        val frame = ControlBounds(100f, 100f, 240f, 240f)
        val arms = ControlGeometry.dpadArms(frame)
        assertEquals(setOf(GameBoyButton.UP, GameBoyButton.DOWN, GameBoyButton.LEFT, GameBoyButton.RIGHT), arms.keys)
        arms.forEach { (button, arm) ->
            // El centro de cada flecha cae en el sector de su dirección, igual que el brazo de la cruz.
            val mask = ControlGeometry.dpadMask(arm.centerX - frame.centerX, arm.centerY - frame.centerY, frame.width / 2f)
            assertEquals("flecha $button", button.mask, mask)
            assertTrue(arm.left >= frame.left && arm.right <= frame.right && arm.top >= frame.top && arm.bottom <= frame.bottom)
        }
    }

    @Test
    fun menuIsOnlyHittableWhenItIsShown() {
        val layout = ControlLayout.defaults(ControlsOrientation.PORTRAIT)
        val shown = ControlGeometry(layout, ControlsOrientation.PORTRAIT, area, showMenu = true)
        val hidden = ControlGeometry(layout, ControlsOrientation.PORTRAIT, area, showMenu = false)
        val menu = shown.frames.getValue(ControlId.MENU)
        val point = ControlPoint(menu.centerX, menu.centerY)
        assertEquals(ControlHit.Single(ControlId.MENU), shown.hit(point))
        assertEquals(null, hidden.hit(point))
    }

    @Test
    fun editorDragSnapsToEdgesWithEightDpMarginAndStaysInside() {
        val geometry = ControlGeometry(
            ControlLayout.defaults(ControlsOrientation.PORTRAIT), ControlsOrientation.PORTRAIT, area, density = 2f,
        )
        val dpad = geometry.frames.getValue(ControlId.DPAD)
        val half = dpad.width / 2f
        val margin = 8f * 2f
        // Fuera del área: queda a 8 dp del borde.
        val outside = geometry.snappedCenter(ControlId.DPAD, ControlPoint(-500f, 5_000f))
        assertEquals((half + margin) / area.width, outside.x, 0.0001f)
        assertEquals((area.height - half - margin) / area.height, outside.y, 0.0001f)
        // Cerca del borde (dentro del umbral): se pega.
        val near = geometry.snappedCenter(ControlId.DPAD, ControlPoint(half + margin + 5f, 300f))
        assertEquals((half + margin) / area.width, near.x, 0.0001f)
        // Lejos de los bordes: no cambia.
        val middle = geometry.snappedCenter(ControlId.DPAD, ControlPoint(200f, 300f))
        assertEquals(200f / area.width, middle.x, 0.0001f)
        assertEquals(300f / area.height, middle.y, 0.0001f)
    }

    @Test
    fun gestureExclusionKeepsDpadFirstAndWithinTheEdgeBudget() {
        val geometry = ControlGeometry(
            ControlLayout.defaults(ControlsOrientation.PORTRAIT), ControlsOrientation.PORTRAIT, area, density = 2f,
        )
        val budget = 200f * 2f
        val rects = GestureExclusion.rects(geometry, area.width, density = 2f)
        assertTrue(rects.isNotEmpty())
        val left = rects.filter { it.centerX < area.width / 2f }
        val right = rects.filter { it.centerX >= area.width / 2f }
        assertTrue(left.sumOf { it.height.toDouble() } <= budget + 0.01)
        assertTrue(right.sumOf { it.height.toDouble() } <= budget + 0.01)
        val dpad = geometry.frames.getValue(ControlId.DPAD)
        assertTrue("la cruceta (280 px) cabe entera en 400 px", left.any { it.top <= dpad.top + 0.01f && it.bottom >= dpad.bottom - 0.01f })
    }

    @Test
    fun gestureExclusionClipsAnOversizedDpadToTheBudgetCentered() {
        val wide = ControlBounds(0f, 0f, 1000f, 1400f)
        val layout = ControlLayout.defaults(ControlsOrientation.PORTRAIT).copy(scales = mapOf(ControlId.DPAD to 1.6f))
        val geometry = ControlGeometry(layout, ControlsOrientation.PORTRAIT, wide, density = 2f, sizeScale = 1.15f)
        val rects = GestureExclusion.rects(geometry, wide.width, density = 2f)
        val dpad = geometry.frames.getValue(ControlId.DPAD)
        assertTrue(dpad.height > 400f)
        val clipped = rects.single { it.centerX < wide.width / 2f }
        assertEquals(400f, clipped.height, 0.01f)
        assertEquals(dpad.centerY, clipped.centerY, 0.01f)
    }
}

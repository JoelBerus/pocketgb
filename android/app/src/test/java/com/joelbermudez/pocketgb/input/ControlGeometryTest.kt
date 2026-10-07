package com.joelbermudez.pocketgb.input

import com.joelbermudez.pocketgb.settings.DiagonalMode
import com.joelbermudez.pocketgb.settings.DpadStyle
import com.joelbermudez.pocketgb.settings.StoredControlLayout
import kotlin.math.hypot
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
    fun crossAndArrowsPlaceTheirSymbolsInTheSectorOfTheirDirection() {
        val frame = ControlBounds(100f, 100f, 240f, 240f)
        val cross = DpadShape.crossArms(frame)
        val arrows = DpadShape.arrowCircles(frame, separation = 1f)
        assertEquals(setOf(GameBoyButton.UP, GameBoyButton.DOWN, GameBoyButton.LEFT, GameBoyButton.RIGHT), cross.keys)
        assertEquals(cross.keys, arrows.keys)
        DiagonalMode.entries.forEach { mode ->
            cross.forEach { (button, arm) ->
                val mask = ControlGeometry.dpadMask(arm.centerX - frame.centerX, arm.centerY - frame.centerY, frame.width / 2f, mode)
                assertEquals("brazo $button con $mode", button.mask, mask)
            }
            arrows.forEach { (button, circle) ->
                val mask = ControlGeometry.dpadMask(circle.centerX - frame.centerX, circle.centerY - frame.centerY, frame.width / 2f, mode)
                assertEquals("flecha $button con $mode", button.mask, mask)
            }
        }
    }

    @Test
    fun separatedArrowsMakeTheDpadFrameFollowTheGroupAndNothingElse() {
        val layout = ControlLayout.defaults(ControlsOrientation.PORTRAIT)
        val cross = ControlGeometry(layout, ControlsOrientation.PORTRAIT, area, dpadStyle = DpadStyle.CROSS)
        assertEquals(140f, cross.frames.getValue(ControlId.DPAD).width, 0.001f)
        // La separación solo cuenta con flechas: con la cruz el marco no cambia.
        val crossWide = ControlGeometry(layout.copy(separation = 1.5f), ControlsOrientation.PORTRAIT, area, dpadStyle = DpadStyle.CROSS)
        assertEquals(140f, crossWide.frames.getValue(ControlId.DPAD).width, 0.001f)
        assertEquals(1f, crossWide.dpadSeparation, 0f)

        listOf(0.7f, 1f, 1.5f).forEach { separation ->
            val geometry = ControlGeometry(layout.copy(separation = separation), ControlsOrientation.PORTRAIT, area, dpadStyle = DpadStyle.ARROWS)
            val dpad = geometry.frames.getValue(ControlId.DPAD)
            assertEquals("separación $separation", 140f * DpadShape.footprint(separation), dpad.width, 0.01f)
            // El resto de controles no se mueve ni cambia de tamaño.
            ControlId.entries.filter { it != ControlId.DPAD }.forEach { id ->
                assertEquals("$id", cross.frames.getValue(id), geometry.frames.getValue(id))
            }
        }
        val centreShift = ControlGeometry(layout.copy(separation = 1.5f), ControlsOrientation.PORTRAIT, area, dpadStyle = DpadStyle.ARROWS)
        assertEquals(cross.frames.getValue(ControlId.DPAD).centerX, centreShift.frames.getValue(ControlId.DPAD).centerX, 0.01f)
        assertEquals(cross.frames.getValue(ControlId.DPAD).centerY, centreShift.frames.getValue(ControlId.DPAD).centerY, 0.01f)
    }

    @Test
    fun theTouchZoneOfSeparatedArrowsFollowsTheGroup() {
        val layout = ControlLayout.defaults(ControlsOrientation.PORTRAIT).copy(separation = 1.5f)
        val geometry = ControlGeometry(layout, ControlsOrientation.PORTRAIT, area, dpadStyle = DpadStyle.ARROWS)
        val frame = geometry.frames.getValue(ControlId.DPAD)
        val circles = DpadShape.arrowCircles(frame, geometry.dpadSeparation)
        // Cada círculo, hasta su borde exterior, está dentro de la zona táctil de la cruceta…
        circles.forEach { (button, circle) ->
            val outer = when (button) {
                GameBoyButton.UP -> ControlPoint(circle.centerX, circle.centerY - circle.radius + 1f)
                GameBoyButton.DOWN -> ControlPoint(circle.centerX, circle.centerY + circle.radius - 1f)
                GameBoyButton.LEFT -> ControlPoint(circle.centerX - circle.radius + 1f, circle.centerY)
                else -> ControlPoint(circle.centerX + circle.radius - 1f, circle.centerY)
            }
            assertEquals("$button", ControlHit.Single(ControlId.DPAD), geometry.hit(outer))
        }
        // …y la zona táctil de fábrica (140) ya no la cubre: una zona fija dejaría fuera la punta de las flechas.
        val factory = ControlGeometry(ControlLayout.defaults(ControlsOrientation.PORTRAIT), ControlsOrientation.PORTRAIT, area, dpadStyle = DpadStyle.ARROWS)
        val tip = circles.getValue(GameBoyButton.UP).let { ControlPoint(it.centerX, it.centerY - it.radius + 1f) }
        assertTrue(hypot(tip.x - factory.frames.getValue(ControlId.DPAD).centerX, tip.y - factory.frames.getValue(ControlId.DPAD).centerY) >
            factory.frames.getValue(ControlId.DPAD).width / 2f)
    }

    @Test
    fun separatedArrowsStayInsideTheSafeAreaAtEverySeparationAndSize() {
        listOf(0.7f, 1f, 1.5f).forEach { separation ->
            listOf(0.6f, 1f, 1.6f).forEach { scale ->
                val layout = ControlLayout.defaults(ControlsOrientation.PORTRAIT)
                    .copy(scales = mapOf(ControlId.DPAD to scale), separation = separation)
                val geometry = ControlGeometry(layout, ControlsOrientation.PORTRAIT, area, sizeScale = 1.15f, dpadStyle = DpadStyle.ARROWS)
                val frame = geometry.frames.getValue(ControlId.DPAD)
                assertTrue("separación $separation escala $scale sale por la izquierda", frame.left >= area.left - 0.01f)
                assertTrue("separación $separation escala $scale sale por la derecha", frame.right <= area.right + 0.01f)
                assertTrue(frame.top >= area.top - 0.01f && frame.bottom <= area.bottom + 0.01f)
                DpadShape.arrowCircles(frame, geometry.dpadSeparation).values.forEach { circle ->
                    assertTrue(circle.centerX - circle.radius >= area.left - 0.01f && circle.centerX + circle.radius <= area.right + 0.01f)
                    assertTrue(circle.centerY - circle.radius >= area.top - 0.01f && circle.centerY + circle.radius <= area.bottom + 0.01f)
                }
            }
        }
    }

    @Test
    fun storedLayoutCarriesTheSeparationIntoTheRuntimeLayout() {
        val stored = StoredControlLayout(separation = 1.3f)
        assertEquals(1.3f, ControlLayout.from(stored, ControlsOrientation.LANDSCAPE).separation, 0f)
        assertEquals(1f, ControlLayout.defaults(ControlsOrientation.LANDSCAPE).separation, 0f)
        assertEquals(1.5f, ControlLayout.defaults(ControlsOrientation.PORTRAIT).copy(separation = 9f).dpadSeparation(), 0f)
        assertEquals(0.7f, ControlLayout.defaults(ControlsOrientation.PORTRAIT).copy(separation = -1f).dpadSeparation(), 0f)
    }

    @Test
    fun theGeometryUsesItsDiagonalModeAndRemembersTheFingerDirection() {
        val layout = ControlLayout.defaults(ControlsOrientation.PORTRAIT)
        val frame = ControlGeometry(layout, ControlsOrientation.PORTRAIT, area).frames.getValue(ControlId.DPAD)
        // A 25° del eje horizontal: «Normales» da diagonal, «Reducidas» solo derecha.
        val dx = kotlin.math.cos(Math.toRadians(25.0)).toFloat() * frame.width * 0.4f
        val dy = -kotlin.math.sin(Math.toRadians(25.0)).toFloat() * frame.width * 0.4f
        val point = ControlPoint(frame.centerX + dx, frame.centerY + dy)
        val right = GameBoyButton.RIGHT.mask
        val up = GameBoyButton.UP.mask
        assertEquals(right or up, ControlGeometry(layout, ControlsOrientation.PORTRAIT, area, diagonals = DiagonalMode.NORMAL).dpadMask(point))
        assertEquals(right, ControlGeometry(layout, ControlsOrientation.PORTRAIT, area, diagonals = DiagonalMode.REDUCED).dpadMask(point))
        assertEquals(right, ControlGeometry(layout, ControlsOrientation.PORTRAIT, area, diagonals = DiagonalMode.DISABLED).dpadMask(point))
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

    // ---- A7 R15: área segura con recorte de pantalla

    @Test
    fun safeAreaSubtractsCutoutAndNoControlTouchesIt() {
        // Horizontal con recorte de 120 px a la izquierda y 90 a la derecha (cámara en el borde corto).
        val insets = SafeInsets(left = 120, top = 0, right = 90, bottom = 40)
        val safe = ControlGeometry.safeArea(2400f, 1080f, insets)
        assertEquals(ControlBounds(120f, 0f, 2310f, 1040f), safe)
        val geometry = ControlGeometry(ControlLayout.defaults(ControlsOrientation.LANDSCAPE), ControlsOrientation.LANDSCAPE, safe)
        ControlId.entries.forEach { id ->
            val frame = geometry.frames.getValue(id)
            assertTrue("$id invade el recorte izquierdo", frame.left >= 120f)
            assertTrue("$id invade el recorte derecho", frame.right <= 2310f)
            assertTrue("$id invade la barra inferior", frame.bottom <= 1040f)
        }
    }

    @Test
    fun safeAreaUnionTakesTheLargestInsetPerSide() {
        val cutout = SafeInsets(left = 100, top = 80)
        val gestures = SafeInsets(left = 24, right = 24, top = 20)
        assertEquals(SafeInsets(left = 100, top = 80, right = 24, bottom = 0), cutout.union(gestures))
    }

    @Test
    fun safeAreaNeverCollapsesWhenInsetsExceedTheView() {
        val safe = ControlGeometry.safeArea(100f, 100f, SafeInsets(left = 80, top = 80, right = 80, bottom = 80))
        assertTrue(safe.width >= 1f)
        assertTrue(safe.height >= 1f)
    }

    @Test
    fun gestureExclusionStaysInsideTheSafeAreaAndWithin200DpPerEdge() {
        val density = 2.5f
        val safe = ControlGeometry.safeArea(2400f, 1080f, SafeInsets(left = 200, right = 200))
        val geometry = ControlGeometry(
            ControlLayout.defaults(ControlsOrientation.LANDSCAPE), ControlsOrientation.LANDSCAPE, safe, density = density,
        )
        val rects = GestureExclusion.rects(geometry, 2400f, density)
        assertTrue(rects.isNotEmpty())
        rects.forEach {
            assertTrue("la exclusión invade el recorte izquierdo", it.left >= 200f)
            assertTrue("la exclusión invade el recorte derecho", it.right <= 2200f)
        }
        listOf(false, true).forEach { right ->
            val total = rects.filter { (it.centerX >= 1200f) == right }.sumOf { it.height.toDouble() }
            assertTrue("lado derecho=$right supera 200 dp: $total px", total <= GestureExclusion.EDGE_BUDGET_DP * density + 0.5)
        }
    }

    @Test
    fun gestureExclusionNeverLeavesTheSafeAreaVertically() {
        // Ventana muy baja (multiventana): el control ocupa todo el alto y su área táctil mínima de 48 dp (144 px a
        // densidad 3) sobresale del área segura si no se recorta también en vertical.
        for (height in listOf(60f, 100f, 120f, 600f)) {
            val safe = ControlBounds(0f, 300f, 1000f, 300f + height)
            val layout = ControlLayout.defaults(ControlsOrientation.PORTRAIT)

            val geometry = ControlGeometry(layout, ControlsOrientation.PORTRAIT, safe, density = 3f)
            GestureExclusion.rects(geometry, safe.width, density = 3f).forEach {
                assertTrue("alto $height: sale por arriba ${it.top}", it.top >= safe.top - 0.01f)
                assertTrue("alto $height: sale por abajo ${it.bottom}", it.bottom <= safe.bottom + 0.01f)
            }
        }
    }
}

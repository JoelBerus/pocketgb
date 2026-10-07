package com.joelbermudez.pocketgb.input

import com.joelbermudez.pocketgb.settings.DiagonalMode
import com.joelbermudez.pocketgb.settings.DpadStyle
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N2 · H1 de la auditoría: con flechas separadas manda lo que se ve. Un toque dentro de un botón da la dirección de ese
 * botón (sea cual sea el ángulo respecto al centro del grupo), el mismo dedo la conserva al salir unos dp, la zona muerta
 * no se come los botones y fuera de ellos decide el ángulo.
 */
class ArrowsTouchTest {
    private val up = GameBoyButton.UP
    private val buttons = listOf(GameBoyButton.UP, GameBoyButton.DOWN, GameBoyButton.LEFT, GameBoyButton.RIGHT)

    private fun geometry(
        separation: Float,
        mode: DiagonalMode = DiagonalMode.REDUCED,
        dpadScale: Float = 1f,
        sizeScale: Float = 1f,
        density: Float = 1f,
    ): ControlGeometry {
        val area = ControlBounds(0f, 0f, 411f * density, 800f * density)
        val layout = ControlLayout.defaults(ControlsOrientation.PORTRAIT)
            .copy(scales = mapOf(ControlId.DPAD to dpadScale), separation = separation)
        return ControlGeometry(layout, ControlsOrientation.PORTRAIT, area, density, sizeScale, dpadStyle = DpadStyle.ARROWS, diagonals = mode)
    }

    private class Lcg(var state: Long) {
        fun next(): Float {
            state = state * 6364136223846793005L + 1442695040888963407L
            return ((state ushr 33).toInt() and 0x7FFFFFFF) / 2147483647f
        }
    }

    @Test
    fun everyTouchInsideAnArrowCircleGivesThatDirectionAtEverySeparationModeAndSize() {
        val rng = Lcg(7_654_321L)
        var tested = 0
        var total = 0
        for (step in 0..8) {
            val separation = 0.7f + step * 0.1f
            for (mode in DiagonalMode.entries) {
                for ((dpadScale, sizeScale, density) in listOf(Triple(1f, 1f, 1f), Triple(0.6f, 0.85f, 2.625f), Triple(1.6f, 1.15f, 3f))) {
                    val geometry = geometry(separation, mode, dpadScale, sizeScale, density)
                    val frame = geometry.frames.getValue(ControlId.DPAD)
                    val circles = DpadShape.arrowCircles(frame, geometry.dpadSeparation)
                    for (button in buttons) {
                        val circle = circles.getValue(button)
                        repeat(300) {
                            // Punto uniforme dentro del círculo (0,999 del radio para no depender del borde exacto).
                            val r = circle.radius * 0.999f * kotlin.math.sqrt(rng.next())
                            val a = (rng.next() * 2.0 * Math.PI).toFloat()
                            val point = ControlPoint(circle.centerX + r * cos(a), circle.centerY + r * sin(a))
                            assertEquals(
                                "separación $separation $mode escala $dpadScale: toque dentro de $button",
                                button.mask,
                                geometry.dpadMask(point),
                            )
                            total += 1
                            // Con el control al máximo la cruceta puede invadir a B: ahí el toque es de B (orden de `hit`).
                            if (geometry.hit(point) == ControlHit.Single(ControlId.DPAD)) {
                                val engine = TouchInputEngine(geometry)
                                engine.pointerDown(1, point)
                                assertEquals("separación $separation $mode: motor, toque dentro de $button", button.mask, engine.mask)
                                tested += 1
                            }
                        }
                    }
                }
            }
        }
        assertEquals(9 * 3 * 3 * 4 * 300, total)
        assertTrue("solo se omitieron los toques que son de otro control ($tested de $total)", tested > total * 9 / 10)
    }

    @Test
    fun theSameFingerKeepsItsButtonUntilItLeavesByTheMargin() {
        val geometry = geometry(separation = 1f)
        val frame = geometry.frames.getValue(ControlId.DPAD)
        val circle = DpadShape.arrowCircles(frame, geometry.dpadSeparation).getValue(up)
        // Hacia el hueco entre ↑ y → (abajo a la derecha desde el centro del círculo ↑): ahí un toque nuevo es diagonal.
        val toward = ControlPoint(0.7071f, 0.7071f)
        fun at(distance: Float) = ControlPoint(circle.centerX + toward.x * distance, circle.centerY + toward.y * distance)
        val rim = circle.radius
        assertEquals("fuera del botón y sin dedo previo, manda el ángulo", GameBoyButton.UP.mask or GameBoyButton.RIGHT.mask, geometry.dpadMask(at(rim + 3f)))
        assertEquals("el dedo que ya pulsaba ↑ lo conserva a 3 dp del borde", up.mask, geometry.dpadMask(at(rim + 3f), previous = up.mask))
        assertEquals("…y a 4 dp", up.mask, geometry.dpadMask(at(rim + ControlGeometry.ARROW_HOLD_MARGIN_DP - 0.1f), previous = up.mask))
        assertEquals("a 6 dp ya lo suelta y decide el ángulo", GameBoyButton.UP.mask or GameBoyButton.RIGHT.mask, geometry.dpadMask(at(rim + 6f), previous = up.mask))
        // Con otro dedo previo (→) no hay retención de ↑.
        assertEquals(GameBoyButton.UP.mask or GameBoyButton.RIGHT.mask, geometry.dpadMask(at(rim + 3f), previous = GameBoyButton.RIGHT.mask))
    }

    @Test
    fun theHoldAlsoWorksThroughTheEngineWhenTheFingerSlidesOutOfTheButton() {
        val geometry = geometry(separation = 1f)
        val frame = geometry.frames.getValue(ControlId.DPAD)
        val circle = DpadShape.arrowCircles(frame, geometry.dpadSeparation).getValue(up)
        val engine = TouchInputEngine(geometry)
        engine.pointerDown(1, ControlPoint(circle.centerX, circle.centerY))
        assertEquals(up.mask, engine.mask)
        val out = circle.radius + 3f
        engine.pointerMove(1, ControlPoint(circle.centerX + 0.7071f * out, circle.centerY + 0.7071f * out))
        assertEquals("sale 3 dp por el lado del hueco: sigue ↑", up.mask, engine.mask)
        engine.pointerMove(1, ControlPoint(circle.centerX + 0.7071f * (circle.radius + 8f), circle.centerY + 0.7071f * (circle.radius + 8f)))
        assertEquals(up.mask or GameBoyButton.RIGHT.mask, engine.mask)
    }

    @Test
    fun theDeadZoneStopsAtTheInnerEdgeOfTheButtonsMinusTwoDp() {
        for (separation in listOf(0.7f, 0.8f, 1f, 1.5f)) {
            val geometry = geometry(separation)
            val frame = geometry.frames.getValue(ControlId.DPAD)
            val circle = DpadShape.arrowCircles(frame, geometry.dpadSeparation).getValue(up)
            val innerEdge = (frame.centerY - circle.centerY) - circle.radius // distancia del centro al borde interior de ↑
            // Dentro del hueco central, junto al borde interior del botón: ya es ↑ (antes la zona muerta se lo comía).
            val justOutsideDeadZone = ControlPoint(frame.centerX, frame.centerY - (innerEdge - 1f))
            assertEquals("separación $separation: a ${innerEdge - 1f} dp del centro", up.mask, geometry.dpadMask(justOutsideDeadZone))
            // En el centro no se pulsa nada, ni justo por debajo del umbral (el menor de 0,30 R y el borde interior − 2 dp).
            val threshold = minOf(0.30f * frame.width / 2f, innerEdge - 2f)
            assertEquals(0, geometry.dpadMask(ControlPoint(frame.centerX, frame.centerY)))
            assertEquals("separación $separation: bajo el umbral ($threshold)", 0, geometry.dpadMask(ControlPoint(frame.centerX, frame.centerY - (threshold - 1f))))
            assertTrue("el umbral nunca pasa del borde interior menos 2 dp", threshold <= innerEdge - 2f + 0.001f)
        }
    }

    @Test
    fun outsideTheCirclesTheAngleDecides() {
        val geometry = geometry(separation = 1f)
        val frame = geometry.frames.getValue(ControlId.DPAD)
        val circles = DpadShape.arrowCircles(frame, geometry.dpadSeparation)
        // Punto medio entre ↑ y →: el hueco en diagonal. «Reducidas» da la diagonal; «Desactivadas», una sola dirección.
        val gap = ControlPoint(
            (circles.getValue(up).centerX + circles.getValue(GameBoyButton.RIGHT).centerX) / 2f,
            (circles.getValue(up).centerY + circles.getValue(GameBoyButton.RIGHT).centerY) / 2f,
        )
        assertTrue("el hueco queda fuera de los dos botones", circles.values.none { hypot(gap.x - it.centerX, gap.y - it.centerY) <= it.radius })
        assertEquals(GameBoyButton.UP.mask or GameBoyButton.RIGHT.mask, geometry.dpadMask(gap))
        val disabled = geometry(separation = 1f, mode = DiagonalMode.DISABLED)
        assertTrue(disabled.dpadMask(gap) in setOf(GameBoyButton.UP.mask, GameBoyButton.RIGHT.mask))
        // Fuera de todo el grupo (la zona táctil es el círculo envolvente): también manda el ángulo.
        val outside = ControlPoint(frame.centerX + frame.width * 0.49f, frame.centerY - frame.width * 0.02f)
        assertEquals(GameBoyButton.RIGHT.mask, geometry.dpadMask(outside))
    }

    @Test
    fun theCrossStyleKeepsThePurelyAngularRule() {
        val layout = ControlLayout.defaults(ControlsOrientation.PORTRAIT)
        val geometry = ControlGeometry(layout, ControlsOrientation.PORTRAIT, ControlBounds(0f, 0f, 411f, 800f), dpadStyle = DpadStyle.CROSS)
        val frame = geometry.frames.getValue(ControlId.DPAD)
        assertEquals(0, geometry.dpadMask(ControlPoint(frame.centerX + 0.29f * frame.width / 2f, frame.centerY)))
        assertEquals(GameBoyButton.RIGHT.mask, geometry.dpadMask(ControlPoint(frame.centerX + 0.31f * frame.width / 2f, frame.centerY)))
    }
}

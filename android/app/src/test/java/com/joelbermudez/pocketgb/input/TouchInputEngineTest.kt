package com.joelbermudez.pocketgb.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchInputEngineTest {
    private val geometry = ControlGeometry(
        layout = ControlLayout.defaults(ControlsOrientation.PORTRAIT),
        orientation = ControlsOrientation.PORTRAIT,
        area = ControlBounds(0f, 0f, 400f, 700f),
    )

    @Test
    fun combinesTwoPointersAndSupportsAbZone() {
        val engine = TouchInputEngine(geometry)
        val dpad = geometry.frames.getValue(ControlId.DPAD)
        val a = geometry.frames.getValue(ControlId.A)

        engine.pointerDown(1, ControlPoint(dpad.centerX, dpad.top))
        engine.pointerDown(2, ControlPoint(a.centerX, a.centerY))
        assertEquals(GameBoyButton.UP.mask or GameBoyButton.A.mask, engine.mask)

        engine.pointerUp(1)
        engine.pointerUp(2)
        val ab = geometry.abFrame
        engine.pointerDown(3, ControlPoint(ab.centerX, ab.centerY))
        assertEquals(GameBoyButton.A.mask or GameBoyButton.B.mask, engine.mask)
    }

    @Test
    fun dpadCapturesPointerWhileFaceButtonSlidesAndCancelClearsAll() {
        val engine = TouchInputEngine(geometry)
        val dpad = geometry.frames.getValue(ControlId.DPAD)
        val a = geometry.frames.getValue(ControlId.A)
        val b = geometry.frames.getValue(ControlId.B)

        engine.pointerDown(1, ControlPoint(dpad.right, dpad.centerY))
        engine.pointerMove(1, ControlPoint(a.centerX, a.centerY))
        assertEquals(GameBoyButton.RIGHT.mask, engine.mask)

        engine.pointerDown(2, ControlPoint(b.centerX, b.centerY))
        assertTrue(engine.mask and GameBoyButton.B.mask != 0)
        engine.pointerMove(2, ControlPoint(a.centerX, a.centerY))
        assertTrue(engine.mask and GameBoyButton.A.mask != 0)
        assertEquals(0, engine.mask and GameBoyButton.B.mask)

        engine.cancelAll()
        assertEquals(0, engine.mask)
        assertTrue(engine.pressed.isEmpty())
    }

    @Test
    fun dpadMaskTracksSectorChangesIndependentlyOfFaceButtons() {
        val engine = TouchInputEngine(geometry)
        val dpad = geometry.frames.getValue(ControlId.DPAD)
        val a = geometry.frames.getValue(ControlId.A)

        engine.pointerDown(1, ControlPoint(dpad.right, dpad.centerY))
        assertEquals(GameBoyButton.RIGHT.mask, engine.dpadMask)
        engine.pointerMove(1, ControlPoint(dpad.centerX, dpad.top))
        assertEquals(GameBoyButton.UP.mask, engine.dpadMask)
        engine.pointerMove(1, ControlPoint(dpad.centerX, dpad.centerY))
        assertEquals(0, engine.dpadMask)

        engine.pointerDown(2, ControlPoint(a.centerX, a.centerY))
        assertEquals("A no cuenta como cruceta", 0, engine.dpadMask)
        engine.pointerUp(1)
        engine.pointerUp(2)
        assertEquals(0, engine.dpadMask)
    }

    @Test
    fun theFingerKeepsItsDirectionThroughHysteresisAndTheDeadZone() {
        val engine = TouchInputEngine(geometry)
        val dpad = geometry.frames.getValue(ControlId.DPAD)
        val r = dpad.width / 2f
        // Entra por el borde de la derecha y retrocede hacia el centro: se mantiene hasta bajar de 0,24 del radio.
        engine.pointerDown(1, ControlPoint(dpad.centerX + r * 0.9f, dpad.centerY))
        assertEquals(GameBoyButton.RIGHT.mask, engine.mask)
        engine.pointerMove(1, ControlPoint(dpad.centerX + r * 0.27f, dpad.centerY))
        assertEquals("sigue activa dentro de la histéresis", GameBoyButton.RIGHT.mask, engine.mask)
        assertTrue(ControlId.DPAD in engine.pressed)
        engine.pointerMove(1, ControlPoint(dpad.centerX + r * 0.20f, dpad.centerY))
        assertEquals(0, engine.mask)
        assertTrue("sin dirección la cruceta no cuenta como pulsada", ControlId.DPAD !in engine.pressed)
        // Desde la zona muerta hace falta llegar a 0,30 para volver a activarse.
        engine.pointerMove(1, ControlPoint(dpad.centerX + r * 0.27f, dpad.centerY))
        assertEquals(0, engine.mask)
        engine.pointerMove(1, ControlPoint(dpad.centerX + r * 0.33f, dpad.centerY))
        assertEquals(GameBoyButton.RIGHT.mask, engine.mask)
    }

    @Test
    fun aDiagonalNeedsTheFingerToLeaveTheCardinalSectorByTheMargin() {
        val engine = TouchInputEngine(geometry)
        val dpad = geometry.frames.getValue(ControlId.DPAD)
        val r = dpad.width / 2f * 0.7f
        fun move(degrees: Double) = engine.pointerMove(
            1,
            ControlPoint(
                dpad.centerX + (kotlin.math.cos(Math.toRadians(degrees)) * r).toFloat(),
                dpad.centerY - (kotlin.math.sin(Math.toRadians(degrees)) * r).toFloat(),
            ),
        )
        engine.pointerDown(1, ControlPoint(dpad.centerX + r, dpad.centerY))
        move(32.0) // pasa el borde de 30° pero queda dentro de los 6° de histéresis
        assertEquals(GameBoyButton.RIGHT.mask, engine.mask)
        move(38.0)
        assertEquals(GameBoyButton.RIGHT.mask or GameBoyButton.UP.mask, engine.mask)
        move(27.0) // de vuelta: la diagonal aguanta hasta 24°
        assertEquals(GameBoyButton.RIGHT.mask or GameBoyButton.UP.mask, engine.mask)
        move(20.0)
        assertEquals(GameBoyButton.RIGHT.mask, engine.mask)
    }

    @Test
    fun twoFingersOnTheDpadKeepTheirOwnHysteresis() {
        val engine = TouchInputEngine(geometry)
        val dpad = geometry.frames.getValue(ControlId.DPAD)
        val r = dpad.width / 2f
        engine.pointerDown(1, ControlPoint(dpad.centerX + r * 0.9f, dpad.centerY))
        engine.pointerDown(2, ControlPoint(dpad.centerX, dpad.centerY - r * 0.9f))
        assertEquals(GameBoyButton.RIGHT.mask or GameBoyButton.UP.mask, engine.dpadMask)
        engine.pointerUp(1)
        assertEquals(GameBoyButton.UP.mask, engine.dpadMask)
        engine.pointerUp(2)
        assertEquals(0, engine.dpadMask)
    }
}

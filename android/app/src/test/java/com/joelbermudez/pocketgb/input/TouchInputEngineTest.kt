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
}

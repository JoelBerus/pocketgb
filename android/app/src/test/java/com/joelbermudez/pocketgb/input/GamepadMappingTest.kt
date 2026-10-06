package com.joelbermudez.pocketgb.input

import android.view.InputDevice
import android.view.KeyEvent
import com.joelbermudez.pocketgb.settings.ControllerMappingData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamepadMappingTest {
    private val a = GameBoyButton.A.mask
    private val b = GameBoyButton.B.mask
    private val start = GameBoyButton.START.mask
    private val select = GameBoyButton.SELECT.mask
    private val up = GameBoyButton.UP.mask
    private val down = GameBoyButton.DOWN.mask
    private val left = GameBoyButton.LEFT.mask
    private val right = GameBoyButton.RIGHT.mask

    @Test
    fun defaultMappingIsByPositionNotByLetter() {
        val pad = GamepadState()
        // Botón derecho (BUTTON_B de Android) = A de Game Boy; botón inferior (BUTTON_A) = B de Game Boy.
        assertEquals(a, pad.onKey(KeyEvent.KEYCODE_BUTTON_B, true).mask)
        assertEquals(a or b, pad.onKey(KeyEvent.KEYCODE_BUTTON_A, true).mask)
        assertEquals(a or b or start, pad.onKey(KeyEvent.KEYCODE_BUTTON_START, true).mask)
        assertEquals(a or b or start or select, pad.onKey(KeyEvent.KEYCODE_BUTTON_SELECT, true).mask)
    }

    @Test
    fun releasingEverythingGivesZero() {
        val pad = GamepadState()
        pad.onKey(KeyEvent.KEYCODE_BUTTON_B, true)
        pad.onKey(KeyEvent.KEYCODE_DPAD_UP, true)
        pad.onAxes(1f, 0f, 0f, 0f)
        pad.onKey(KeyEvent.KEYCODE_BUTTON_B, false)
        pad.onKey(KeyEvent.KEYCODE_DPAD_UP, false)
        assertEquals(0, pad.onAxes(0f, 0f, 0f, 0f).mask)
    }

    @Test
    fun hatAndStickGiveTheSameMaskInEightSectors() {
        // Ángulo en grados (0 = derecha, antihorario) y máscara esperada; se prueba dentro de cada sector.
        val expected = mapOf(
            0.0 to right, 45.0 to (right or up), 90.0 to up, 135.0 to (up or left),
            180.0 to left, 225.0 to (left or down), 270.0 to down, 315.0 to (down or right),
        )
        for ((degrees, mask) in expected) {
            for (delta in listOf(-21.0, 0.0, 21.0)) {
                val rad = Math.toRadians(degrees + delta)
                val x = Math.cos(rad).toFloat()
                val y = -Math.sin(rad).toFloat() // el eje Y de Android crece hacia abajo
                assertEquals("stick $degrees+$delta", mask, GamepadState().onAxes(0f, 0f, x, y).mask)
            }
            val rad = Math.toRadians(degrees)
            val hx = Math.round(Math.cos(rad)).toFloat()
            val hy = -Math.round(Math.sin(rad)).toFloat()
            assertEquals("hat $degrees", mask, GamepadState().onAxes(hx, hy, 0f, 0f).mask)
        }
    }

    @Test
    fun stickThresholdIsHalf() {
        assertEquals(0, GamepadState().onAxes(0f, 0f, 0.49f, 0f).mask)
        assertEquals(right, GamepadState().onAxes(0f, 0f, 0.5f, 0f).mask)
        assertEquals(0, GamepadState().onAxes(0f, 0f, 0.35f, 0.35f).mask) // hypot 0,495
    }

    @Test
    fun oppositeDirectionsCancel() {
        val pad = GamepadState()
        pad.onKey(KeyEvent.KEYCODE_DPAD_UP, true)
        assertEquals(0, pad.onAxes(0f, 0f, 0f, 1f).mask) // cruceta arriba + stick abajo
        assertEquals(left, GamepadState().apply { onKey(KeyEvent.KEYCODE_DPAD_LEFT, true) }.onAxes(0f, 0f, 0f, 0f).mask)
        val both = GamepadState()
        both.onKey(KeyEvent.KEYCODE_DPAD_LEFT, true)
        assertEquals(0, both.onAxes(1f, 0f, 0f, 0f).mask)
    }

    @Test
    fun diagonalSurvivesCancellationOfOneAxis() {
        val pad = GamepadState()
        pad.onKey(KeyEvent.KEYCODE_DPAD_UP, true)
        pad.onKey(KeyEvent.KEYCODE_DPAD_RIGHT, true)
        // El stick empuja abajo: se anula solo el eje vertical.
        assertEquals(right, pad.onAxes(0f, 0f, 0f, 1f).mask)
    }

    @Test
    fun buttonsAndDirectionsAreOred() {
        val pad = GamepadState()
        pad.onKey(KeyEvent.KEYCODE_BUTTON_B, true)
        pad.onKey(KeyEvent.KEYCODE_DPAD_DOWN, true)
        assertEquals(a or down, pad.onAxes(0f, 0f, 1f, 0f).mask and (a or down))
        assertEquals(a or down or right, pad.onAxes(0f, 0f, 1f, 0f).mask)
    }

    @Test
    fun fastForwardOnlyOnPress() {
        val pad = GamepadState()
        assertEquals(setOf(PadAction.FAST_FORWARD), pad.onKey(KeyEvent.KEYCODE_BUTTON_R1, true).actions)
        assertTrue(pad.onKey(KeyEvent.KEYCODE_BUTTON_R1, true).actions.isEmpty()) // repetición
        assertTrue(pad.onKey(KeyEvent.KEYCODE_BUTTON_R1, false).actions.isEmpty())
        assertEquals(setOf(PadAction.FAST_FORWARD), pad.onKey(KeyEvent.KEYCODE_BUTTON_R1, true).actions)
        assertEquals(0, pad.onKey(KeyEvent.KEYCODE_BUTTON_R1, false).mask)
    }

    @Test
    fun modeAndL1OpenTheMenu() {
        assertEquals(setOf(PadAction.MENU), GamepadState().onKey(KeyEvent.KEYCODE_BUTTON_MODE, true).actions)
        assertEquals(setOf(PadAction.MENU), GamepadState().onKey(KeyEvent.KEYCODE_BUTTON_L1, true).actions)
    }

    @Test
    fun appActionsDoNotChangeTheMask() {
        assertEquals(0, GamepadState().onKey(KeyEvent.KEYCODE_BUTTON_MODE, true).mask)
    }

    @Test
    fun unmappedKeysAreIgnored() {
        val out = GamepadState().onKey(KeyEvent.KEYCODE_BUTTON_X, true)
        assertEquals(0, out.mask)
        assertTrue(out.actions.isEmpty())
    }

    @Test
    fun customMappingReplacesDefaults() {
        val mapping = ControllerMappingData(mapOf("A" to KeyEvent.KEYCODE_BUTTON_X, "FAST_FORWARD" to KeyEvent.KEYCODE_BUTTON_Y))
        val pad = GamepadState(mapping)
        assertEquals(a, pad.onKey(KeyEvent.KEYCODE_BUTTON_X, true).mask)
        // BUTTON_B ya no es A: nadie lo tiene (B conserva BUTTON_A). Se suelta X antes para aislar la aserción (DS-H3).
        assertEquals(0, pad.onKey(KeyEvent.KEYCODE_BUTTON_X, false).mask)
        assertFalse(pad.handles(KeyEvent.KEYCODE_BUTTON_B))
        assertEquals(0, pad.onKey(KeyEvent.KEYCODE_BUTTON_B, true).mask)
        assertEquals(b, pad.onKey(KeyEvent.KEYCODE_BUTTON_A, true).mask)
        assertEquals(setOf(PadAction.FAST_FORWARD), pad.onKey(KeyEvent.KEYCODE_BUTTON_Y, true).actions)
        // R1 ya no es avance rápido.
        assertTrue(pad.onKey(KeyEvent.KEYCODE_BUTTON_R1, true).actions.isEmpty())
    }

    @Test
    fun resetReleasesEverything() {
        val pad = GamepadState()
        pad.onKey(KeyEvent.KEYCODE_BUTTON_B, true)
        pad.onAxes(0f, 1f, 1f, 0f)
        assertEquals(0, pad.reset().mask)
        assertEquals(0, pad.onAxes(0f, 0f, 0f, 0f).mask)
    }

    @Test
    fun handlesReportsMappedAndDirectionKeys() {
        val pad = GamepadState()
        assertTrue(pad.handles(KeyEvent.KEYCODE_DPAD_LEFT))
        assertTrue(pad.handles(KeyEvent.KEYCODE_BUTTON_B))
        assertFalse(pad.handles(KeyEvent.KEYCODE_BACK))
        assertFalse("sin asignar: no se consume (A7-H1)", pad.handles(KeyEvent.KEYCODE_BUTTON_THUMBL))
        assertFalse(pad.handles(KeyEvent.KEYCODE_A))
    }

    @Test
    fun motionFromAnyEventOfAGamepadDeviceIsAccepted() {
        val keyboardLike = InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_DPAD
        val pad = InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_DPAD
        assertTrue(isPadMotion(InputDevice.SOURCE_JOYSTICK, 0))
        assertTrue("hat con SOURCE_GAMEPAD", isPadMotion(InputDevice.SOURCE_GAMEPAD, 0))
        assertTrue("hat con SOURCE_DPAD de un dispositivo mando", isPadMotion(InputDevice.SOURCE_DPAD, pad))
        assertFalse("SOURCE_DPAD de un teclado", isPadMotion(InputDevice.SOURCE_DPAD, keyboardLike))
    }

    @Test
    fun onlyGamepadOrJoystickSourcesCountAsPads() {
        assertTrue(isGamepadSources(InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_DPAD))
        assertTrue(isGamepadSources(InputDevice.SOURCE_JOYSTICK))
        assertFalse("teclado con flechas", isGamepadSources(InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_DPAD))
        assertFalse("mando a distancia", isGamepadSources(InputDevice.SOURCE_DPAD))
    }

    @Test
    fun gamepadSourcesAreDetected() {
        assertTrue(isGamepadSources(android.view.InputDevice.SOURCE_GAMEPAD))
        assertTrue(isGamepadSources(android.view.InputDevice.SOURCE_JOYSTICK or android.view.InputDevice.SOURCE_KEYBOARD))
        assertFalse(isGamepadSources(android.view.InputDevice.SOURCE_KEYBOARD))
        assertFalse(isGamepadSources(android.view.InputDevice.SOURCE_TOUCHSCREEN))
    }
}

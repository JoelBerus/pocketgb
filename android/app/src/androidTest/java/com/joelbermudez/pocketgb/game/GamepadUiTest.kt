package com.joelbermudez.pocketgb.game

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.joelbermudez.pocketgb.debug.GameplayTestConfig
import com.joelbermudez.pocketgb.input.FakeGamepadConnection
import com.joelbermudez.pocketgb.input.GameBoyButton
import com.joelbermudez.pocketgb.input.GameControlsView
import com.joelbermudez.pocketgb.settings.ControlsVisibility
import com.joelbermudez.pocketgb.testing.waitUntil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Mando físico (A7-L1) sobre el juego real: teclas y ejes de `SOURCE_GAMEPAD` llegan a la sesión. */
@RunWith(AndroidJUnit4::class)
class GamepadUiTest {
    private val pad = FakeGamepadConnection(false)
    private val harness = GameplayHarness()
    private val compose get() = harness.compose

    @get:Rule
    val chain: RuleChain = RuleChain
        .outerRule(object : ExternalResource() {
            override fun before() { GameplayTestConfig.gamepad = pad }
            override fun after() { GameplayTestConfig.gamepad = null }
        })
        .around(harness.rules)

    @After
    fun tearDown() = harness.close()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun key(code: Int, down: Boolean, source: Int = InputDevice.SOURCE_GAMEPAD) {
        val now = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(
            KeyEvent(now, now, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code, 0, 0, -1, 0, 0, source),
        )
        instrumentation.waitForIdleSync()
    }

    /** Entrega la tecla directamente a la actividad y devuelve si alguien la consumió. */
    private fun dispatchKey(code: Int, source: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val event = KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, 0, -1, 0, 0, source)
        var consumed = false
        compose.runOnUiThread { consumed = compose.activity.dispatchKeyEvent(event) }
        instrumentation.waitForIdleSync()
        return consumed
    }

    private fun hat(x: Float, y: Float) {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_UNKNOWN })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            setAxisValue(MotionEvent.AXIS_HAT_X, x)
            setAxisValue(MotionEvent.AXIS_HAT_Y, y)
        })
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_JOYSTICK, 0)
        compose.runOnUiThread { compose.activity.dispatchGenericMotionEvent(event) }
        event.recycle()
        instrumentation.waitForIdleSync()
    }

    /** Como `waitUntil`, pero deja avanzar el reloj de Compose (la regla de pruebas no lo hace sola). */
    private fun waitIdle(condition: () -> Boolean): Boolean = waitUntil {
        compose.waitForIdle()
        condition()
    }

    private fun mask(game: GameSession) = game.session.requestedButtons

    private fun controlsView(): GameControlsView {
        fun find(view: View): GameControlsView? {
            if (view is GameControlsView) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        var found: GameControlsView? = null
        compose.runOnUiThread { found = find(compose.activity.window.decorView) }
        return checkNotNull(found)
    }

    private fun visibility(): ControlsVisibility {
        var v = ControlsVisibility.ALWAYS
        val view = controlsView()
        compose.runOnUiThread { v = view.controlsVisibility }
        return v
    }

    @Test
    fun physicalButtonsReachTheSessionByPosition() {
        val game = harness.openGame()
        key(KeyEvent.KEYCODE_BUTTON_B, true)
        assertTrue(waitUntil { mask(game) == GameBoyButton.A.mask })
        key(KeyEvent.KEYCODE_BUTTON_A, true)
        key(KeyEvent.KEYCODE_DPAD_RIGHT, true)
        assertTrue(waitUntil { mask(game) == (GameBoyButton.A.mask or GameBoyButton.B.mask or GameBoyButton.RIGHT.mask) })
        key(KeyEvent.KEYCODE_BUTTON_B, false)
        key(KeyEvent.KEYCODE_BUTTON_A, false)
        key(KeyEvent.KEYCODE_DPAD_RIGHT, false)
        assertTrue(waitUntil { mask(game) == 0 })
    }

    @Test
    fun hatAxesGiveTheSameMaskAsTheDpad() {
        val game = harness.openGame()
        hat(-1f, 0f)
        assertTrue(waitUntil { mask(game) == GameBoyButton.LEFT.mask })
        hat(0f, 0f)
        assertTrue(waitUntil { mask(game) == 0 })
    }

    @Test
    fun r1CyclesSpeedOnlyOnPress() {
        val game = harness.openGame()
        assertEquals(1, game.session.speed)
        key(KeyEvent.KEYCODE_BUTTON_R1, true)
        key(KeyEvent.KEYCODE_BUTTON_R1, false)
        assertTrue(waitIdle { game.session.speed == 2 })
        key(KeyEvent.KEYCODE_BUTTON_R1, true)
        key(KeyEvent.KEYCODE_BUTTON_R1, false)
        assertTrue(waitIdle { game.session.speed == 4 })
    }

    @Test
    fun modeOpensThePauseMenuAndSheetsBlockThePad() {
        val game = harness.openGame()
        key(KeyEvent.KEYCODE_BUTTON_B, true) // pulsado cuando se abre el menú: no debe quedar pegado
        assertTrue(waitUntil { mask(game) == GameBoyButton.A.mask })
        key(KeyEvent.KEYCODE_BUTTON_MODE, true)
        key(KeyEvent.KEYCODE_BUTTON_MODE, false)
        harness.waitTag("pause-sheet")
        compose.onNodeWithTag("pause-continue").assertIsDisplayed()
        assertTrue("máscara a 0 al abrir la hoja", waitUntil { mask(game) == 0 })
        key(KeyEvent.KEYCODE_BUTTON_B, true)
        key(KeyEvent.KEYCODE_DPAD_UP, true)
        Thread.sleep(300)
        assertEquals("con la hoja abierta el mando no llega al juego", 0, mask(game))
        key(KeyEvent.KEYCODE_BUTTON_B, false)
        key(KeyEvent.KEYCODE_DPAD_UP, false)
    }

    @Test
    fun losingWindowFocusReleasesEverything() {
        val game = harness.openGame()
        key(KeyEvent.KEYCODE_BUTTON_B, true)
        key(KeyEvent.KEYCODE_DPAD_LEFT, true)
        assertTrue(waitUntil { mask(game) != 0 })
        compose.runOnUiThread { compose.activity.onWindowFocusChanged(false) }
        assertTrue(waitUntil { mask(game) == 0 })
    }

    @Test
    fun connectedPadHidesTouchControlsUnlessSettingAllowsThem() {
        val game = harness.openGame()
        assertEquals(ControlsVisibility.ALWAYS, visibility())
        pad.set(true)
        assertTrue(waitIdle { visibility() == ControlsVisibility.HIDDEN })
        harness.settings.update { it.copy(showTouchControlsWithController = true) }
        assertTrue(waitIdle { visibility() == ControlsVisibility.ALWAYS })
        harness.settings.update { it.copy(showTouchControlsWithController = false) }
        assertTrue(waitIdle { visibility() == ControlsVisibility.HIDDEN })
        // Desconexión con un botón pulsado: máscara 0 y los controles vuelven.
        key(KeyEvent.KEYCODE_BUTTON_B, true)
        assertTrue(waitUntil { mask(game) == GameBoyButton.A.mask })
        pad.set(false)
        assertTrue(waitIdle { visibility() == ControlsVisibility.ALWAYS })
        assertTrue(waitUntil { mask(game) == 0 })
    }

    // ---- A7-H1: solo lo que el mando maneja se consume y oculta los controles

    @Test
    fun unassignedPadKeyIsNotConsumedAndKeepsTouchControls() {
        harness.openGame()
        assertFalse("BUTTON_THUMBL no está asignado: no se consume", dispatchKey(KeyEvent.KEYCODE_BUTTON_THUMBL, InputDevice.SOURCE_GAMEPAD))
        compose.waitForIdle()
        assertEquals(ControlsVisibility.ALWAYS, visibility())
    }

    @Test
    fun keyboardArrowWithOnlyDpadSourceDoesNotHideTouchControls() {
        val game = harness.openGame()
        val source = InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_DPAD
        assertFalse("una flecha de teclado no se consume", dispatchKey(KeyEvent.KEYCODE_DPAD_RIGHT, source))
        compose.waitForIdle()
        assertEquals(ControlsVisibility.ALWAYS, visibility())
        assertEquals(0, mask(game))
    }

    @Test
    fun assignedPadKeyIsConsumedAndHidesTouchControls() {
        harness.openGame()
        assertTrue(dispatchKey(KeyEvent.KEYCODE_BUTTON_A, InputDevice.SOURCE_GAMEPAD))
        assertTrue(waitIdle { visibility() == ControlsVisibility.HIDDEN })
        key(KeyEvent.KEYCODE_BUTTON_A, false)
    }
}

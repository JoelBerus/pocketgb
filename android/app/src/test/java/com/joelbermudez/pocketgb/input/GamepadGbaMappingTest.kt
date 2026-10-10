package com.joelbermudez.pocketgb.input

import android.view.KeyEvent
import com.joelbermudez.pocketgb.emulator.Console
import com.joelbermudez.pocketgb.emulator.GbaButtonBits
import com.joelbermudez.pocketgb.settings.ControllerMappingData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** N8: en GBA, L1/R1 = L/R del juego (como iOS) y la pausa y la velocidad pasan a L2/R2. GB no cambia. */
class GamepadGbaMappingTest {
    @Test
    fun inGbaTheShouldersAreLAndR() {
        val pad = GamepadState(console = Console.GBA)
        assertTrue(pad.handles(KeyEvent.KEYCODE_BUTTON_L1))
        assertEquals(GbaButtonBits.L, pad.onKey(KeyEvent.KEYCODE_BUTTON_L1, true).mask)
        val both = pad.onKey(KeyEvent.KEYCODE_BUTTON_R1, true)
        assertEquals(GbaButtonBits.L or GbaButtonBits.R, both.mask)
        assertTrue("L/R no son acciones de la app", both.actions.isEmpty())
        assertEquals(GbaButtonBits.R, pad.onKey(KeyEvent.KEYCODE_BUTTON_L1, false).mask)
        assertEquals(GbaButtonBits.R or GameBoyButton.A.mask, pad.onKey(KeyEvent.KEYCODE_BUTTON_B, true).mask)
        assertEquals(0, pad.reset().mask)
    }

    @Test
    fun inGbaPauseAndSpeedMoveToTheTriggersAndTheGuideStillPauses() {
        val pad = GamepadState(console = Console.GBA)
        assertEquals(setOf(PadAction.MENU), pad.onKey(KeyEvent.KEYCODE_BUTTON_L2, true).actions)
        assertEquals(setOf(PadAction.FAST_FORWARD), pad.onKey(KeyEvent.KEYCODE_BUTTON_R2, true).actions)
        assertEquals(setOf(PadAction.MENU), pad.onKey(KeyEvent.KEYCODE_BUTTON_MODE, true).actions)
        assertEquals("L2/R2 no pulsan nada del juego", 0, pad.onKey(KeyEvent.KEYCODE_BUTTON_L2, true).mask and 0xFF)
    }

    @Test
    fun gameBoyKeepsItsMapping() {
        val pad = GamepadState(console = Console.GB)
        val l1 = pad.onKey(KeyEvent.KEYCODE_BUTTON_L1, true)
        assertEquals(setOf(PadAction.MENU), l1.actions)
        assertEquals(0, l1.mask)
        assertEquals(setOf(PadAction.FAST_FORWARD), pad.onKey(KeyEvent.KEYCODE_BUTTON_R1, true).actions)
        assertFalse(pad.handles(KeyEvent.KEYCODE_BUTTON_L2))
    }

    @Test
    fun aCustomMappingMovesWhatItHadOnTheShouldersOnlyIfTheTriggerIsFree() {
        // A en L1 y Select en L2: en GBA, L1 es L y A se queda sin hombro (L2 ya era Select).
        val custom = ControllerMappingData.assign(
            ControllerMappingData.assign(null, PadAction.A, KeyEvent.KEYCODE_BUTTON_L1),
            PadAction.SELECT, KeyEvent.KEYCODE_BUTTON_L2,
        )
        val gba = GamepadMapping.forConsole(custom.resolved(), Console.GBA)
        assertEquals(PadAction.SELECT, gba[KeyEvent.KEYCODE_BUTTON_L2])
        assertFalse(KeyEvent.KEYCODE_BUTTON_L1 in gba)
        val pad = GamepadState(custom, Console.GBA)
        assertEquals(GbaButtonBits.L, pad.onKey(KeyEvent.KEYCODE_BUTTON_L1, true).mask)
        // En GB, L1 sigue siendo A.
        assertEquals(GameBoyButton.A.mask, GamepadState(custom, Console.GB).onKey(KeyEvent.KEYCODE_BUTTON_L1, true).mask)
    }
}

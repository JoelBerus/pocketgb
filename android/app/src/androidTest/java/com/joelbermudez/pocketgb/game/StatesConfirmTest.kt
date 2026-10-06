package com.joelbermudez.pocketgb.game

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.joelbermudez.pocketgb.saves.StateSlot
import com.joelbermudez.pocketgb.testing.waitUntil
import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Confirmaciones de estados (K14): «Guardar actual y cargar» frente a «Cargar sin guardar», que no toca AUTO. */
@RunWith(AndroidJUnit4::class)
class StatesConfirmTest {
    private val h = GameplayHarness()

    @get:Rule
    val rules = h.rules

    @After
    fun tearDown() = h.close()

    private fun openStatesWithSlot1(): GameSession {
        val game = h.openGame()
        h.pressBackViaDispatcher()
        h.waitTag("pause-sheet")
        h.compose.onNodeWithTag("pause-states").performClick()
        h.waitTag("states-sheet")
        h.compose.onNodeWithTag("state-save-slot1").performClick()
        assertTrue(waitUntil(8_000) { h.vm.states.value.entries.containsKey(StateSlot.MANUAL1) && !h.vm.states.value.busy })
        return game
    }

    @Test
    fun loadWithoutSavingNeverWritesTheAutoSlot() {
        val game = openStatesWithSlot1()
        h.compose.onNodeWithTag("state-load-slot1").performClick()
        h.waitTag("state-load-dialog")
        h.compose.onNodeWithTag("state-confirm-nosave").assertIsDisplayed()
        h.compose.onNodeWithTag("state-confirm-save").assertIsDisplayed()
        h.compose.onNodeWithTag("state-cancel").assertIsDisplayed()
        h.compose.onNodeWithTag("state-confirm-nosave").performClick()
        h.waitGone("state-load-dialog")
        assertTrue(waitUntil(8_000) { !h.vm.states.value.busy })
        // Esperar a que la operación terminó: el aviso «cargada» lo confirma por el estado de la sesión pausada.
        Thread.sleep(500)
        assertFalse("AUTO intacto", h.vm.states.value.entries.containsKey(StateSlot.AUTO))
        assertFalse(File(h.root, "states/${game.fingerprint}/auto.state").exists())
    }

    @Test
    fun saveAndLoadLeavesTheCurrentStateInAuto() {
        val game = openStatesWithSlot1()
        h.compose.onNodeWithTag("state-load-slot1").performClick()
        h.waitTag("state-load-dialog")
        h.compose.onNodeWithTag("state-confirm-save").performClick()
        h.waitGone("state-load-dialog")
        assertTrue(waitUntil(8_000) { h.vm.states.value.entries.containsKey(StateSlot.AUTO) && !h.vm.states.value.busy })
        assertTrue(File(h.root, "states/${game.fingerprint}/auto.state").exists())
    }

    @Test
    fun cancelLeavesEverythingAsItWas() {
        val game = openStatesWithSlot1()
        h.compose.onNodeWithTag("state-load-slot1").performClick()
        h.waitTag("state-load-dialog")
        h.compose.onNodeWithTag("state-cancel").performClick()
        h.waitGone("state-load-dialog")
        assertFalse(h.vm.states.value.entries.containsKey(StateSlot.AUTO))
        assertFalse(File(h.root, "states/${game.fingerprint}/auto.state").exists())
    }

    @Test
    fun replacingAnOccupiedSlotAsksWithItsDate() {
        openStatesWithSlot1()
        h.compose.onNodeWithTag("state-save-slot1").performClick()
        h.waitTag("state-confirm")
        h.compose.onNode(hasText("se sustituirá por el momento actual", substring = true)).assertIsDisplayed()
        h.compose.onNode(hasText("El estado guardado el ", substring = true)).assertIsDisplayed()
        h.compose.onNodeWithTag("state-cancel").performClick()
        h.waitGone("state-confirm")
    }
}

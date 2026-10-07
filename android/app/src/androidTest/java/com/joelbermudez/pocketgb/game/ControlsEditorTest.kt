package com.joelbermudez.pocketgb.game

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.joelbermudez.pocketgb.emulator.SessionState
import com.joelbermudez.pocketgb.input.ControlId
import com.joelbermudez.pocketgb.input.ControlsOrientation
import com.joelbermudez.pocketgb.settings.DpadStyle
import com.joelbermudez.pocketgb.settings.GameplaySettingsFile
import com.joelbermudez.pocketgb.testing.waitUntil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Editor de disposición de los controles con el juego en pausa (K12): mover, − / +, restablecer, Listo y persistir. */
@RunWith(AndroidJUnit4::class)
class ControlsEditorTest {
    private val h = GameplayHarness()

    @get:Rule
    val rules = h.rules

    @After
    fun tearDown() = h.close()

    private fun openEditor(): GameSession {
        val game = h.openGame()
        h.pressBackViaDispatcher()
        h.waitTag("pause-sheet")
        h.compose.onNodeWithTag("pause-customize").assertIsDisplayed().performClick()
        h.waitTag("controls-editor")
        h.waitGone("pause-sheet")
        assertEquals("el editor deja el juego en pausa", SessionState.Paused, game.state.value)
        return game
    }

    private fun orientation(): ControlsOrientation {
        val size = h.compose.onRoot().fetchSemanticsNode().size
        return if (size.width > size.height) ControlsOrientation.LANDSCAPE else ControlsOrientation.PORTRAIT
    }

    private fun dragCenterOfA(toX: Float, toY: Float) {
        val size = h.compose.onNodeWithTag("game-controls").fetchSemanticsNode().size
        val a = com.joelbermudez.pocketgb.input.ControlLayout.defaults(orientation()).centers.getValue(ControlId.A)
        h.compose.onNodeWithTag("game-controls").performTouchInput {
            down(Offset(size.width * a.x, size.height * a.y))
            moveTo(Offset(size.width * toX, size.height * toY))
            up()
        }
    }

    @Test
    fun draggingAControlMovesItAndPersistsOnlyInThisOrientation() {
        openEditor()
        val orientation = orientation()
        dragCenterOfA(0.5f, 0.5f)

        assertTrue(waitUntil(5_000) { h.settings.state.value.layout(orientation).positions.containsKey(ControlId.A) })
        val point = h.settings.state.value.layout(orientation).positions.getValue(ControlId.A)
        assertEquals(0.5f, point.x, 0.06f)
        assertEquals(0.5f, point.y, 0.06f)
        val other = if (orientation == ControlsOrientation.PORTRAIT) ControlsOrientation.LANDSCAPE else ControlsOrientation.PORTRAIT
        assertTrue("la otra orientación no cambia", h.settings.state.value.layout(other).positions.isEmpty())
        // Persistido en disco (JSON atómico del repositorio de L1).
        assertTrue(
            waitUntil(5_000) { GameplaySettingsFile(h.settingsFile).load().layout(orientation).positions.containsKey(ControlId.A) },
        )
    }

    @Test
    fun theSizeStepsByTenPercentWithinLimitsAndResetRestoresTheFactoryLayout() {
        openEditor()
        val orientation = orientation()
        dragCenterOfA(0.5f, 0.5f) // elige A
        h.waitTag("editor-size")
        h.compose.onNode(hasText("A · 100 %")).assertIsDisplayed()

        h.compose.onNodeWithTag("editor-larger").performClick()
        h.compose.waitUntil(5_000) { h.compose.onAllNodes(hasText("A · 110 %")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1.1f, h.settings.state.value.layout(orientation).scales.getValue(ControlId.A), 0.001f)

        repeat(2) { h.compose.onNodeWithTag("editor-smaller").performClick() }
        h.compose.waitUntil(5_000) { h.compose.onAllNodes(hasText("A · 90 %")).fetchSemanticsNodes().isNotEmpty() }

        repeat(3) { h.compose.onNodeWithTag("editor-smaller").performClick() }
        h.compose.waitUntil(5_000) { h.compose.onAllNodes(hasText("A · 60 %")).fetchSemanticsNodes().isNotEmpty() }
        h.compose.onNodeWithTag("editor-smaller").assertIsNotEnabled()
        h.compose.onNodeWithTag("editor-larger").assertIsEnabled()

        h.compose.onNodeWithTag("editor-reset").performClick()
        assertTrue(waitUntil(5_000) { h.settings.state.value.isFactoryLayout(orientation) })
        assertTrue(h.settings.state.value.layout(orientation).positions.isEmpty())
        assertNull(h.settings.state.value.layout(orientation).scales[ControlId.A])
    }

    /** Toca (sin arrastrar) el centro de fábrica de la cruceta para elegirla. */
    private fun selectDpad() {
        val size = h.compose.onNodeWithTag("game-controls").fetchSemanticsNode().size
        val dpad = com.joelbermudez.pocketgb.input.ControlLayout.defaults(orientation()).centers.getValue(ControlId.DPAD)
        h.compose.onNodeWithTag("game-controls").performTouchInput {
            down(Offset(size.width * dpad.x, size.height * dpad.y))
            up()
        }
    }

    @Test
    fun separationOfTheSeparatedArrowsStepsByTenPercentWithinLimitsAndResetReturnsItToOne() {
        h.settings.update { it.copy(dpadStyle = DpadStyle.ARROWS) }
        openEditor()
        val orientation = orientation()
        selectDpad()
        h.waitTag("editor-separation")
        h.compose.onNode(hasText("Separación · 100 %")).assertIsDisplayed()

        h.compose.onNodeWithTag("editor-farther").performClick()
        h.compose.waitUntil(5_000) { h.compose.onAllNodes(hasText("Separación · 110 %")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1.1f, h.settings.state.value.layout(orientation).separation, 0.001f)
        val other = if (orientation == ControlsOrientation.PORTRAIT) ControlsOrientation.LANDSCAPE else ControlsOrientation.PORTRAIT
        assertEquals("la otra orientación no cambia", 1f, h.settings.state.value.layout(other).separation, 0f)
        // Persistido en disco por orientación.
        assertTrue(waitUntil(5_000) { GameplaySettingsFile(h.settingsFile).load().layout(orientation).separation > 1.05f })

        repeat(5) { h.compose.onNodeWithTag("editor-farther").performClick() }
        h.compose.waitUntil(5_000) { h.compose.onAllNodes(hasText("Separación · 150 %")).fetchSemanticsNodes().isNotEmpty() }
        h.compose.onNodeWithTag("editor-farther").assertIsNotEnabled()

        repeat(9) { h.compose.onNodeWithTag("editor-closer").performClick() }
        h.compose.waitUntil(5_000) { h.compose.onAllNodes(hasText("Separación · 70 %")).fetchSemanticsNodes().isNotEmpty() }
        h.compose.onNodeWithTag("editor-closer").assertIsNotEnabled()
        assertEquals(0.7f, h.settings.state.value.layout(orientation).separation, 0.001f)

        h.compose.onNodeWithTag("editor-reset").performClick()
        assertTrue(waitUntil(5_000) { h.settings.state.value.layout(orientation).separation == 1f })
        assertTrue(h.settings.state.value.isFactoryLayout(orientation))
    }

    @Test
    fun theSeparationControlsOnlyAppearForTheArrowsStyleAndTheDpad() {
        openEditor() // cruz por defecto
        selectDpad()
        h.waitTag("editor-size")
        h.compose.onNodeWithTag("editor-separation").assertDoesNotExist()
    }

    @Test
    fun doneReturnsToThePauseMenuAndTheGameStaysPaused() {
        val game = openEditor()
        h.compose.onNodeWithTag("editor-done").performClick()
        h.waitTag("pause-sheet")
        assertEquals(SessionState.Paused, game.state.value)
        assertNotNull(h.vm.game.value)
    }

    @Test
    fun backInTheEditorLeavesItWithoutLeavingTheGame() {
        val game = openEditor()
        h.pressBackViaDispatcher()
        h.waitTag("pause-sheet")
        h.waitGone("controls-editor")
        assertEquals(SessionState.Paused, game.state.value)
    }
}

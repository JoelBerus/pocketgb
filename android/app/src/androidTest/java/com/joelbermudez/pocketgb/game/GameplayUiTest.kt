package com.joelbermudez.pocketgb.game

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.joelbermudez.pocketgb.debug.GameplayTestActivity
import com.joelbermudez.pocketgb.debug.GameplayTestConfig
import com.joelbermudez.pocketgb.emulator.SessionState
import com.joelbermudez.pocketgb.saves.SaveLoadWarning
import com.joelbermudez.pocketgb.saves.SaveMirror
import com.joelbermudez.pocketgb.saves.SaveOpening
import com.joelbermudez.pocketgb.saves.StateSlot
import com.joelbermudez.pocketgb.testing.FailableOps
import com.joelbermudez.pocketgb.testing.tempDir
import com.joelbermudez.pocketgb.testing.waitUntil
import java.io.File
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.Test
import org.junit.runner.RunWith

/** UI del juego (Compose real sobre el núcleo real): atrás, menú de pausa, estados y fallo de guardado local. */
@RunWith(AndroidJUnit4::class)
class GameplayUiTest {
    private lateinit var root: File
    private val ops = FailableOps()
    @Volatile private var mirrorLocator = MirrorLocator { _, _, _, _ -> null }
    private val compose = createAndroidComposeRule<GameplayTestActivity>()

    // La fábrica del ViewModel debe existir ANTES de que la regla lance la actividad.
    @get:Rule
    val chain: RuleChain = RuleChain
        .outerRule(object : ExternalResource() {
            override fun before() {
                root = tempDir("gameplay-ui")
                GameplayTestConfig.factory = {
                    GameplayViewModel(
                        GameplayTestHost.launcher(root, ops, mirrors = MirrorLocator { e, s, v, d -> mirrorLocator.locate(e, s, v, d) }),
                    )
                }
            }

            override fun after() {
                GameplayTestConfig.factory = null
                root.deleteRecursively()
            }
        })
        .around(compose)

    @After
    fun tearDown() {
        compose.activity.viewModel.game.value?.close()
    }

    private val vm get() = compose.activity.viewModel

    private fun openGame(mirrors: MirrorLocator = MirrorLocator { _, _, _, _ -> null }): GameSession {
        mirrorLocator = mirrors
        vm.open(GameplayTestHost.entry)
        compose.waitUntil(10_000) { vm.game.value != null }
        val game = vm.game.value!!
        // Espera a que la pantalla de juego esté compuesta (si no, "atrás" aún no lo intercepta el juego).
        waitTag("gameplay-surface")
        compose.waitForIdle()
        assertTrue(waitUntil { game.session.sramDirtySequence() > 3 })
        return game
    }

    private fun waitTag(tag: String, timeoutMs: Long = 8_000) =
        compose.waitUntil(timeoutMs) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    private fun waitGone(tag: String, timeoutMs: Long = 8_000) =
        compose.waitUntil(timeoutMs) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty() }

    private fun pressBackViaDispatcher() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
    }

    @Test
    fun backOpensThePauseMenuAndContinueResumes() {
        val game = openGame()
        assertEquals(SessionState.Running, game.state.value)
        pressBackViaDispatcher()
        waitTag("pause-sheet")
        compose.onNodeWithTag("pause-continue").assertIsDisplayed().assertIsEnabled()
        assertEquals("el juego se pausó", SessionState.Paused, game.state.value)

        compose.onNodeWithTag("pause-continue").performClick()
        waitGone("pause-sheet")
        assertTrue(waitUntil { game.state.value == SessionState.Running })
    }

    @Test
    fun backWhilePausedClosesTheMenuAndResumesNeverLeavingTheGame() {
        val game = openGame()
        pressBackViaDispatcher()
        waitTag("pause-sheet")
        Espresso.pressBack() // la hoja es un diálogo: atrás la cierra
        waitGone("pause-sheet")
        assertTrue(waitUntil { game.state.value == SessionState.Running })
        assertFalse("atrás nunca sale del juego", game.isClosed)
        assertNotNull(vm.game.value)
    }

    @Test
    fun statesSaveLoadAndDeleteWithConfirmation() {
        val game = openGame()
        pressBackViaDispatcher()
        waitTag("pause-sheet")
        compose.onNodeWithTag("pause-states").performClick()
        waitTag("states-sheet")

        // Guardar en ranura vacía: sin confirmación.
        compose.onNodeWithTag("state-save-slot1").performClick()
        assertTrue(waitUntil(8_000) { vm.states.value.entries.containsKey(StateSlot.MANUAL1) })
        val saved = vm.states.value.entries.getValue(StateSlot.MANUAL1)
        assertNotNull(saved.thumbnail)

        // Reemplazar una ranura ocupada pide confirmación; cancelar no cambia nada.
        compose.onNodeWithTag("state-save-slot1").performClick()
        waitTag("state-confirm")
        compose.onNodeWithTag("state-cancel").performClick()
        waitGone("state-confirm")
        assertEquals(saved, vm.states.value.entries.getValue(StateSlot.MANUAL1))

        // Cargar: confirmación, y el estado actual se guarda antes en AUTO.
        compose.onNodeWithTag("state-load-slot1").performClick()
        waitTag("state-confirm-save")
        compose.onNodeWithTag("state-confirm-save").performClick()
        waitGone("state-confirm-save")
        assertTrue(waitUntil(8_000) { vm.states.value.entries.containsKey(StateSlot.AUTO) && !vm.states.value.busy })
        assertEquals("tras cargar sigue en pausa", SessionState.Paused, game.state.value)

        // Eliminar: confirmación y desaparece.
        compose.onNodeWithTag("state-delete-slot1").performClick()
        waitTag("state-confirm")
        compose.onNodeWithTag("state-confirm").performClick()
        assertTrue(waitUntil(8_000) { !vm.states.value.entries.containsKey(StateSlot.MANUAL1) })
        assertFalse(File(root, "states/${game.fingerprint}/slot1.state").exists())
    }

    @Test
    fun localSaveFailureDialogOffersRetryAndDoubleConfirmedRiskyExit() {
        val game = openGame()
        ops.failSav = true
        pressBackViaDispatcher()
        waitTag("pause-sheet")
        compose.onNodeWithTag("pause-exit").performClick()
        waitTag("exit-failed-dialog")
        compose.onNode(hasText("No se pudo guardar la partida en este teléfono")).assertIsDisplayed()
        compose.onNode(hasText("disco lleno", substring = true)).assertIsDisplayed()
        assertFalse(game.isClosed)

        // Reintentar con el disco aún roto: el diálogo vuelve y la sesión sigue abierta.
        compose.onNodeWithTag("exit-failed-retry").performClick()
        compose.waitUntil(8_000) { !vm.busy.value }
        waitTag("exit-failed-dialog")
        assertFalse(game.isClosed)

        // Salir sin guardar pide una segunda confirmación; cancelarla vuelve al primer diálogo.
        compose.onNodeWithTag("exit-failed-leave").performClick()
        waitTag("exit-risk-dialog")
        compose.onNode(hasText("Se perderá el progreso desde el último guardado", substring = true)).assertIsDisplayed()
        compose.onNodeWithTag("exit-risk-cancel").performClick()
        waitTag("exit-failed-dialog")
        assertFalse(game.isClosed)

        compose.onNodeWithTag("exit-failed-leave").performClick()
        waitTag("exit-risk-dialog")
        compose.onNodeWithTag("exit-risk-confirm").performClick()
        compose.waitUntil(10_000) { vm.game.value == null }
        waitTag("no-game")
        assertTrue(game.isClosed)
        assertTrue("rescate: se escribió el estado de rescate", File(root, "states/${game.fingerprint}/rescue.state").exists())
    }

    @Test
    fun retryAfterTheDiskRecoversExitsCleanlyAndKeepPlayingResumes() {
        val game = openGame()
        ops.failSav = true
        pressBackViaDispatcher()
        waitTag("pause-sheet")
        compose.onNodeWithTag("pause-exit").performClick()
        waitTag("exit-failed-dialog")

        compose.onNodeWithTag("exit-failed-keep").performClick()
        waitGone("exit-failed-dialog")
        assertTrue(waitUntil { game.state.value == SessionState.Running })
        assertFalse(game.isClosed)

        pressBackViaDispatcher()
        waitTag("pause-sheet")
        compose.onNodeWithTag("pause-exit").performClick()
        waitTag("exit-failed-dialog")
        ops.failSav = false
        compose.onNodeWithTag("exit-failed-retry").performClick()
        compose.waitUntil(10_000) { vm.game.value == null }
        assertTrue(game.isClosed)
        assertTrue(File(root, "saves/${game.fingerprint}.sav").exists())
    }

    @Test
    fun aSaveLoadWarningIsShownAtOpenAndDismissed() {
        val readOnly = MirrorLocator { _, _, _, _ ->
            MirrorSetup(
                object : SaveMirror {
                    override fun snapshot() = SaveMirror.Snapshot.Absent
                    override fun write(data: ByteArray): Long? = null
                },
                SaveOpening.MirrorMode.ReadOnly,
            )
        }
        val game = openGame(readOnly)
        assertEquals(SaveLoadWarning.MirrorReadOnly, game.warning)
        waitTag("save-warning-dialog")
        compose.onNode(hasText("La carpeta solo permite lectura", substring = true)).assertIsDisplayed()
        compose.onNodeWithTag("warning-ok").performClick()
        waitGone("save-warning-dialog")
        assertNull(vm.dialog.value)
    }

    @Test
    fun theSaveProblemIndicatorStaysVisibleWhileTheProblemExistsAndGoesAwayWhenConfirmed() {
        val game = openGame()
        // Sin fallos de disco no hay indicador (un fallo transitorio del emulador lo mostraría y se resolvería solo).
        compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("save-problem-indicator")).fetchSemanticsNodes().isEmpty() }
        ops.failSav = true
        pressBackViaDispatcher() // pausa: el vaciado falla
        waitTag("pause-sheet")
        waitTag("save-problem-indicator")
        compose.onNodeWithTag("save-problem-indicator").assertIsDisplayed()
        compose.onNode(hasText("Guardado pendiente", substring = true)).assertIsDisplayed()
        assertNotNull(game.saveProblem.value)

        // Sigue visible mientras no se confirme (a diferencia de un snackbar).
        Thread.sleep(2_500)
        compose.onNodeWithTag("save-problem-indicator").assertIsDisplayed()

        ops.failSav = false
        compose.onNodeWithTag("pause-continue").performClick()
        waitGone("pause-sheet")
        pressBackViaDispatcher() // nueva pausa: el vaciado ahora sí funciona
        waitTag("pause-sheet")
        waitGone("save-problem-indicator")
        assertNull(game.saveProblem.value)
    }

    @Test
    fun aFailedFlushOnBackgroundingRaisesTheSavePendingNotice() {
        val game = openGame()
        val notices = java.util.concurrent.CopyOnWriteArrayList<GameNotice>()
        val collector = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch(
            start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED,
        ) { vm.notices.collect { notices += it } }
        ops.failSav = true
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED) // ON_PAUSE
        assertTrue("el observador avisa del guardado pendiente", waitUntil(10_000) { notices.contains(GameNotice.SavePending) })
        assertNotNull(game.saveProblem.value)
        collector.cancel()
        ops.failSav = false
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
    }

    @Test
    fun theHudCyclesTheSpeedOneTwoFourAndShowsItsValue() {
        val game = openGame()
        assertEquals(1, game.session.speed)
        compose.onNodeWithTag("hud-pause").assertIsDisplayed()
        compose.onNodeWithTag("hud-speed").assertIsDisplayed()
        compose.onNodeWithTag("hud-speed").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Desactivado"))

        compose.onNodeWithTag("hud-speed").performClick()
        assertTrue(waitUntil { game.session.speed == 2 })
        compose.onNodeWithTag("hud-speed").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "×2"))
        compose.onNodeWithTag("hud-speed").performClick()
        assertTrue(waitUntil { game.session.speed == 4 })
        compose.onNodeWithTag("hud-speed").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "×4"))
        compose.onNodeWithTag("hud-speed").performClick()
        assertTrue(waitUntil { game.session.speed == 1 })
    }

    @Test
    fun theHudPauseButtonOpensThePauseMenu() {
        val game = openGame()
        compose.onNodeWithTag("hud-pause").performClick()
        waitTag("pause-sheet")
        assertEquals(SessionState.Paused, game.state.value)
    }

    @Test
    fun thePauseMenuShowsTheGameTitleAndCustomizeControls() {
        val game = openGame()
        pressBackViaDispatcher()
        waitTag("pause-sheet")
        compose.onNodeWithTag("pause-title").assertIsDisplayed()
        compose.onNode(hasText(game.info.title)).assertIsDisplayed()
        compose.onNodeWithTag("pause-customize").assertIsDisplayed()
        compose.onNode(hasText("Personalizar controles")).assertIsDisplayed()
        compose.onNode(hasText("Estados guardados")).assertIsDisplayed()
        compose.onNode(hasText("Salir del juego")).assertIsDisplayed()
        compose.onNodeWithTag("pause-dim").assertExists()
    }
}

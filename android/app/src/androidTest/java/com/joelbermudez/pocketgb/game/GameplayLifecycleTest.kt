package com.joelbermudez.pocketgb.game

import androidx.lifecycle.Lifecycle
import com.joelbermudez.pocketgb.debug.GameplayTestActivity
import com.joelbermudez.pocketgb.debug.GameplayTestConfig
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.joelbermudez.pocketgb.emulator.SessionState
import com.joelbermudez.pocketgb.testing.tempDir
import com.joelbermudez.pocketgb.testing.waitUntil
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Ciclo de vida real de la actividad con el núcleo real: segundo plano, rotación y cierre. */
@RunWith(AndroidJUnit4::class)
class GameplayLifecycleTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = tempDir("lifecycle")
        GameplayTestConfig.factory = { GameplayViewModel(GameplayTestHost.launcher(root)) }
    }

    @After
    fun tearDown() {
        GameplayTestConfig.factory = null
        root.deleteRecursively()
    }

    private fun ActivityScenario<GameplayTestActivity>.vm(): GameplayViewModel {
        var result: GameplayViewModel? = null
        onActivity { result = it.viewModel }
        return result!!
    }

    private fun launchAndOpen(): Pair<ActivityScenario<GameplayTestActivity>, GameSession> {
        val scenario = ActivityScenario.launch(GameplayTestActivity::class.java)
        scenario.onActivity { it.viewModel.open(GameplayTestHost.entry) }
        val vm = scenario.vm()
        assertTrue(waitUntil(10_000) { vm.game.value != null })
        val game = vm.game.value!!
        assertTrue(waitUntil { game.session.sramDirtySequence() > 3 })
        return scenario to game
    }

    @Test
    fun movingToCreatedSavesTheSramAndComingBackLeavesTheGamePaused() {
        val (scenario, game) = launchAndOpen()
        scenario.use {
            scenario.moveToState(Lifecycle.State.CREATED) // ON_PAUSE + ON_STOP
            assertEquals(SessionState.Paused, game.state.value)
            val saved = File(root, "saves/${game.fingerprint}.sav")
            assertTrue("el disco tiene la partida", saved.exists())
            assertArrayEquals("disco == SRAM del núcleo", game.session.copySram(), saved.readBytes())

            scenario.moveToState(Lifecycle.State.RESUMED)
            assertEquals("al volver, el juego queda en pausa", SessionState.Paused, game.state.value)
            assertTrue("el menú de pausa se muestra", waitUntil { scenario.vm().menu.value == GameMenu.Pause })
            // Y sigue sin avanzar hasta que Joel continúe.
            val frames = game.session.frameCount
            Thread.sleep(200)
            assertEquals(frames, game.session.frameCount)
        }
    }

    @Test
    fun recreateDoesNotCloseTheSession() {
        val (scenario, game) = launchAndOpen()
        scenario.use {
            val seen = CopyOnWriteArrayList<SessionState>()
            val collector = GlobalScope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                game.state.collect { seen += it }
            }
            val framesBefore = game.session.frameCount
            val dirtyBefore = game.session.sramDirtySequence()

            scenario.recreate()

            assertSame("el ViewModel (y la sesión) sobreviven", game, scenario.vm().game.value)
            assertFalse(game.isClosed)
            assertTrue("el contador de fotogramas no se reinicia", game.session.frameCount >= framesBefore)
            assertTrue(game.session.sramDirtySequence() >= dirtyBefore)
            assertFalse("nunca hubo Closed: $seen", seen.contains(SessionState.Closed))
            // La rotación pausó con vaciado: el disco sigue al núcleo.
            assertArrayEquals(game.session.copySram(), File(root, "saves/${game.fingerprint}.sav").readBytes())

            // Y la partida continúa desde donde estaba al pulsar Continuar.
            scenario.onActivity { it.viewModel.continueGame() }
            val resumedFrames = game.session.frameCount
            assertTrue(waitUntil { game.session.frameCount > resumedFrames + 5 })
            assertEquals(SessionState.Running, game.state.value)
            collector.cancel()
        }
    }

    @Test
    fun finishingTheActivityClosesTheSessionBestEffortWithoutLosingTheSave() {
        val (scenario, game) = launchAndOpen()
        scenario.close()
        assertTrue(waitUntil(10_000) { game.isClosed })
        assertTrue(File(root, "saves/${game.fingerprint}.sav").exists())
        assertTrue("sin temporales", File(root, "saves").walkTopDown().none { it.name.endsWith(".tmp") })
    }
}

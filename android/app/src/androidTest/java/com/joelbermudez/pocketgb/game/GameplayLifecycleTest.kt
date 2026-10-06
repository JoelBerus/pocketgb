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
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import androidx.lifecycle.LifecycleEventObserver
import android.content.pm.ActivityInfo
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

    // ---- A7 R12: rotar sin recrear ni pausar

    @Test
    fun rotatingKeepsTheSameActivityAndNeverPausesOrOpensTheMenu() {
        val (scenario, game) = launchAndOpen()
        scenario.use {
            try {
                var activity: GameplayTestActivity? = null
                val events = CopyOnWriteArrayList<Lifecycle.Event>()
                val observer = LifecycleEventObserver { _, event -> events += event }
                scenario.onActivity {
                    activity = it
                    it.lifecycle.addObserver(observer)
                }
                val startOrientation = activity!!.resources.configuration.orientation
                val seen = CopyOnWriteArrayList<SessionState>()
                val collector = GlobalScope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    game.state.collect { seen += it }
                }
                events.clear()
                val framesBefore = game.session.frameCount

                val target = if (startOrientation == Configuration.ORIENTATION_PORTRAIT) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                scenario.onActivity { it.requestedOrientation = target }
                assertTrue("la configuración cambió de orientación", waitUntil(10_000) {
                    var now = startOrientation
                    scenario.onActivity { now = it.resources.configuration.orientation }
                    now != startOrientation
                })

                var after: GameplayTestActivity? = null
                scenario.onActivity { after = it }
                assertSame("la actividad no se recreó", activity, after)
                assertSame(game, scenario.vm().game.value)
                assertEquals(SessionState.Running, game.state.value)
                assertEquals("el menú de pausa no se abre", GameMenu.None, scenario.vm().menu.value)
                assertFalse("rotar no produce ON_PAUSE/ON_STOP: $events",
                    events.any { it == Lifecycle.Event.ON_PAUSE || it == Lifecycle.Event.ON_STOP })
                assertFalse("nunca hubo Paused/Closed: $seen", seen.any { it == SessionState.Paused || it == SessionState.Closed })
                assertTrue("el juego sigue avanzando", waitUntil(5_000) { game.session.frameCount > framesBefore + 5 })
                collector.cancel()
                scenario.onActivity { it.lifecycle.removeObserver(observer) }
            } finally {
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            }
        }
    }

    // ---- A7 R13: memoria baja

    @Test
    fun trimMemoryUiHiddenPausesWithFlushAndRunningLowDoesNot() {
        val (scenario, game) = launchAndOpen()
        scenario.use {
            val vm = scenario.vm()
            vm.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
            assertEquals("en primer plano no se pausa", SessionState.Running, game.state.value)

            vm.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            assertEquals(SessionState.Paused, game.state.value)
            val saved = File(root, "saves/${game.fingerprint}.sav")
            assertTrue("el disco tiene la partida", saved.exists())
            assertArrayEquals("disco == SRAM del núcleo", game.session.copySram(), saved.readBytes())

            // Idempotente: otra señal con la sesión ya en pausa no hace nada ni lanza.
            vm.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
            assertEquals(SessionState.Paused, game.state.value)
            assertFalse(game.isClosed)
        }
    }
}

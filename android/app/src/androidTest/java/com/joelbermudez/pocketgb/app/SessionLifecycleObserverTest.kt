package com.joelbermudez.pocketgb.app

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.testing.TestLifecycleOwner
import com.joelbermudez.pocketgb.audio.AudioState
import com.joelbermudez.pocketgb.emulator.EmulatorSession
import com.joelbermudez.pocketgb.emulator.SessionState
import com.joelbermudez.pocketgb.testing.SyntheticRom
import androidx.test.platform.app.InstrumentationRegistry
import com.joelbermudez.pocketgb.game.GameSession
import com.joelbermudez.pocketgb.saves.StateStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionLifecycleObserverTest {
    private fun newGame(): GameSession {
        val session = EmulatorSession()
        val info = session.load(SyntheticRom.romOnly())
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "observer-states-${System.nanoTime()}")
        return GameSession(session, info, StateStore(dir))
    }

    @Test
    fun backgroundPausesAndForegroundDoesNotResume() {
        newGame().use { game ->
            val session = game.session
            game.start()
            val owner = TestLifecycleOwner(Lifecycle.State.RESUMED)
            val observer = SessionLifecycleObserver(game)
            owner.lifecycle.addObserver(observer)

            owner.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            assertEquals(SessionState.Paused, session.state.value)
            assertEquals(AudioState.Stopped, session.audioState)

            owner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            assertEquals(SessionState.Paused, session.state.value)
        }
    }

    @Test
    fun repeatedPauseAndStopAreIdempotent() {
        newGame().use { game ->
            val session = game.session
            game.start()
            val observer = SessionLifecycleObserver(game)

            observer.onStateChanged(TestLifecycleOwner(), Lifecycle.Event.ON_PAUSE)
            observer.onStateChanged(TestLifecycleOwner(), Lifecycle.Event.ON_PAUSE)
            observer.onStateChanged(TestLifecycleOwner(), Lifecycle.Event.ON_STOP)

            assertEquals(SessionState.Paused, session.state.value)
        }
    }

    /** A9-H6: el AUTO de segundo plano solo se pide en `ON_STOP`, tras el vaciado. */
    @Test
    fun onlyStopAsksForTheBackgroundAutomaticState() {
        newGame().use { game ->
            game.start()
            var stopped = 0
            val owner = TestLifecycleOwner(Lifecycle.State.RESUMED)
            owner.lifecycle.addObserver(SessionLifecycleObserver(game, onStopped = { stopped++ }))
            owner.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            assertEquals(0, stopped)
            owner.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            assertEquals(1, stopped)
            owner.handleLifecycleEvent(Lifecycle.Event.ON_START)
            owner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            assertEquals(1, stopped)
            assertEquals(SessionState.Paused, game.session.state.value)
        }
    }

    /** A9-H6: si pausar falla, no se pide el AUTO (la sesión no está aparcada ni vaciada). */
    @Test
    fun aPauseThatThrowsNeverAsksForTheAutomaticState() {
        val session = object : EmulatorSession() {
            override fun pause() {
                throw IllegalStateException("pausa imposible (inyectado)")
            }
        }
        val info = session.load(SyntheticRom.romOnly())
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "observer-states-${System.nanoTime()}")
        GameSession(session, info, StateStore(dir)).use { game ->
            game.start()
            var stopped = 0
            val observer = SessionLifecycleObserver(game, onStopped = { stopped++ })
            observer.onStateChanged(TestLifecycleOwner(), Lifecycle.Event.ON_STOP)
            assertEquals(0, stopped)
        }
    }
}

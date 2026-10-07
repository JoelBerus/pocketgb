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
}

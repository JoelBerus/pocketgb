package com.joelbermudez.pocketgb.app

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.testing.TestLifecycleOwner
import com.joelbermudez.pocketgb.audio.AudioState
import com.joelbermudez.pocketgb.emulator.EmulatorSession
import com.joelbermudez.pocketgb.emulator.SessionState
import com.joelbermudez.pocketgb.testing.SyntheticRom
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionLifecycleObserverTest {
    @Test
    fun backgroundPausesAndForegroundDoesNotResume() {
        EmulatorSession().use { session ->
            session.load(SyntheticRom.romOnly())
            session.start()
            val owner = TestLifecycleOwner(Lifecycle.State.RESUMED)
            val observer = SessionLifecycleObserver(session)
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
        EmulatorSession().use { session ->
            session.load(SyntheticRom.romOnly())
            session.start()
            val observer = SessionLifecycleObserver(session)

            observer.onStateChanged(TestLifecycleOwner(), Lifecycle.Event.ON_PAUSE)
            observer.onStateChanged(TestLifecycleOwner(), Lifecycle.Event.ON_PAUSE)
            observer.onStateChanged(TestLifecycleOwner(), Lifecycle.Event.ON_STOP)

            assertEquals(SessionState.Paused, session.state.value)
        }
    }
}

package com.joelbermudez.pocketgb.emulator

import android.os.SystemClock
import com.joelbermudez.pocketgb.testing.SyntheticRom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulatorSessionTest {
    @Test
    fun sessionTransitionsAreExplicit() {
        EmulatorSession().use { session ->
            session.load(SyntheticRom.romOnly())
            assertEquals(SessionState.Ready, session.state.value)

            session.start()
            assertEquals(SessionState.Running, session.state.value)
            session.pause()
            assertEquals(SessionState.Paused, session.state.value)
            session.resume()
            assertEquals(SessionState.Running, session.state.value)
            session.stop()
            assertEquals(SessionState.Stopped, session.state.value)
        }
    }

    @Test
    fun runningAdvancesAndPausedRemainsStable() {
        EmulatorSession().use { session ->
            session.load(SyntheticRom.romOnly())
            session.start()
            waitUntil { session.frameCount >= 3 }
            session.pause()
            val pausedAt = session.frameCount
            SystemClock.sleep(100)

            assertEquals(pausedAt, session.frameCount)
        }
    }

    @Test
    fun pauseThenCloseJoinsThread() {
        val session = EmulatorSession()
        session.load(SyntheticRom.romOnly())
        session.start()
        session.pause()

        session.close()

        assertEquals(SessionState.Closed, session.state.value)
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 2_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(10)
        }
        assertTrue("La sesión no avanzó antes del timeout", condition())
    }
}

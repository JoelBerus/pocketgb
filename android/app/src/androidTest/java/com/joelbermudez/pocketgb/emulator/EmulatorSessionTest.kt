package com.joelbermudez.pocketgb.emulator

import android.os.SystemClock
import com.joelbermudez.pocketgb.input.GameBoyButton
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

    @Test
    fun touchAndPhysicalButtonsAreCombinedOnEmulationThread() {
        EmulatorSession().use { session ->
            session.load(SyntheticRom.romOnly())
            session.setTouchButtons(GameBoyButton.A.mask or GameBoyButton.UP.mask)
            session.setPhysicalButtons(GameBoyButton.B.mask or GameBoyButton.RIGHT.mask)
            session.start()

            waitUntil {
                session.appliedButtons == (
                    GameBoyButton.A.mask or GameBoyButton.B.mask or
                        GameBoyButton.UP.mask or GameBoyButton.RIGHT.mask
                    )
            }
        }
    }

    @Test
    fun buttonMasksAndSpeedAreNormalized() {
        EmulatorSession().use { session ->
            session.setTouchButtons(0x1FF)
            session.setPhysicalButtons(-1)
            assertEquals(0xFF, session.requestedButtons)

            session.setSpeed(3)
            assertEquals(1, session.speed)
            session.setSpeed(2)
            assertEquals(2, session.speed)
            session.setSpeed(4)
            assertEquals(4, session.speed)
        }
    }

    @Test
    fun fastForwardAdvancesFasterAndPauseRemainsABarrier() {
        EmulatorSession().use { session ->
            session.load(SyntheticRom.romOnly())
            session.start()
            val normalStart = session.frameCount
            SystemClock.sleep(350)
            val normalFrames = session.frameCount - normalStart

            session.setSpeed(4)
            val fastStart = session.frameCount
            SystemClock.sleep(350)
            val fastFrames = session.frameCount - fastStart
            assertTrue("×4 no avanzó claramente más rápido", fastFrames >= normalFrames * 2)

            session.pause()
            val pausedAt = session.frameCount
            SystemClock.sleep(100)
            assertEquals(pausedAt, session.frameCount)
        }
    }

    @Test
    fun oneHundredPauseResumeCyclesKeepTheSessionHealthy() {
        EmulatorSession().use { session ->
            session.load(SyntheticRom.romOnly())
            session.start()

            repeat(100) {
                session.pause()
                assertEquals(SessionState.Paused, session.state.value)
                assertEquals(com.joelbermudez.pocketgb.audio.AudioState.Stopped, session.audioState)
                session.resume()
                assertEquals(SessionState.Running, session.state.value)
            }

            waitUntil { session.frameCount > 0 }
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 2_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(10)
        }
        assertTrue("La sesión no avanzó antes del timeout", condition())
    }
}

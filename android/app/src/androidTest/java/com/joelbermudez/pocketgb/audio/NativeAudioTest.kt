package com.joelbermudez.pocketgb.audio

import android.os.SystemClock
import com.joelbermudez.pocketgb.emulator.EmulatorSession
import com.joelbermudez.pocketgb.testing.SyntheticRom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAudioTest {
    @Test
    fun sessionProducesAudioAndPauseStopsOutput() {
        EmulatorSession().use { session ->
            session.load(SyntheticRom.romOnly())
            session.start()
            waitUntil {
                session.audioFramesProduced >= 2_048 &&
                    session.audioState in setOf(AudioState.Live, AudioState.ClockFallback)
            }

            assertTrue(session.audioState == AudioState.Live || session.audioState == AudioState.ClockFallback)
            session.pause()
            assertEquals(AudioState.Stopped, session.audioState)
        }
    }

    @Test
    fun resumeCreatesFreshAudioRun() {
        EmulatorSession().use { session ->
            session.load(SyntheticRom.romOnly())
            session.start()
            waitUntil {
                session.audioFramesProduced >= 2_048 &&
                    session.audioState in setOf(AudioState.Live, AudioState.ClockFallback)
            }
            session.pause()
            val before = session.audioFramesProduced

            session.resume()

            waitUntil {
                session.audioFramesProduced > before &&
                    session.audioState in setOf(AudioState.Live, AudioState.ClockFallback)
            }
            assertTrue(session.audioState == AudioState.Live || session.audioState == AudioState.ClockFallback)
        }
    }

    @Test
    fun fastForwardStopsAudioAndNormalSpeedReprimesIt() {
        EmulatorSession().use { session ->
            session.load(SyntheticRom.romOnly())
            session.start()
            waitUntil { session.audioState in setOf(AudioState.Live, AudioState.ClockFallback) }

            session.setSpeed(4)
            waitUntil { session.audioState == AudioState.Stopped }

            session.setSpeed(1)
            waitUntil { session.audioState in setOf(AudioState.Live, AudioState.ClockFallback) }
            if (session.audioState == AudioState.Live) {
                waitUntil { session.audioFramesConsumed > 0 }
            }
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 3_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(10)
        assertTrue("El audio no alcanzó el estado esperado", condition())
    }
}

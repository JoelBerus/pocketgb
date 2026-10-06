package com.joelbermudez.pocketgb.emulator

import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
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

    // ---- A6-L2: opciones de emulación y ajustes en caliente ----

    @Test
    fun forcedCgbModelRunsADmgRomInCompatibilityMode() {
        EmulatorSession().use { session ->
            val info = session.load(SyntheticRom.romOnly(), options = EmulationOptions(GbModel.CGB, compatPalette = 3))
            assertTrue(info.cgbMode)
            assertTrue(info.cgbCompat)
            assertEquals(3, session.compatPalette)
        }
        EmulatorSession().use { session ->
            val info = session.load(SyntheticRom.romOnly(), options = EmulationOptions(GbModel.DMG))
            assertFalse(info.cgbMode)
        }
    }

    @Test
    fun cgbOnlyRomWithDmgModelIsRejectedAsCgbOnly() {
        val rom = SyntheticRom.withCgbFlag(SyntheticRom.romOnly(), 0xC0)
        EmulatorSession().use { session ->
            assertThrows(CoreError.CgbOnly::class.java) { session.load(rom, options = EmulationOptions(GbModel.DMG)) }
        }
        EmulatorSession().use { session ->
            assertTrue(session.load(rom).cgbMode) // AUTO la abre en color
        }
    }

    @Test
    fun palettesOneToTwelveAreAcceptedAndThirteenIsRejectedInKotlinAndC() {
        EmulatorSession().use { session ->
            session.load(SyntheticRom.romOnly(), options = EmulationOptions(GbModel.CGB))
            for (id in 1..12) session.setCompatPalette(id)
            assertEquals(12, session.compatPalette)
            assertThrows(CoreError.InvalidArgument::class.java) { session.setCompatPalette(13) }
            assertEquals(12, session.compatPalette)
        }
        assertThrows(IllegalArgumentException::class.java) { EmulationOptions(GbModel.CGB, 13) }
        // En C: la petición directa fuera de rango se rechaza (17) y la sesión sigue usable.
        val handle = NativeLibrary.nativeSessionCreate()
        try {
            val rom = SyntheticRom.romOnly()
            assertEquals(17, NativeLibrary.nativeSessionLoad(handle, rom, 0L, 2, 13))
            assertEquals(17, NativeLibrary.nativeSessionLoad(handle, rom, 0L, 3, 0))
            assertEquals(17, NativeLibrary.nativeSessionLoad(handle, rom, 0L, -1, 0))
            assertEquals(-1, NativeLibrary.nativeSessionRomInfo(handle, IntArray(10), ByteArray(32), ByteArray(17))) // NS_BUSY: la sesión sigue sin cargar (no se creó el core)
            assertEquals(0, NativeLibrary.nativeSessionLoad(handle, rom, 0L, 2, 12))
            assertEquals(17, NativeLibrary.nativeSessionSetCompatPalette(handle, 13))
            assertEquals(17, NativeLibrary.nativeSessionSetCompatPalette(handle, -1))
            assertEquals(0, NativeLibrary.nativeSessionSetCompatPalette(handle, 5))
        } finally {
            NativeLibrary.nativeSessionDestroy(handle)
        }
    }

    @Test
    fun changingThePaletteRequiresCompatibilityMode() {
        EmulatorSession().use { session ->
            session.load(SyntheticRom.romOnly(), options = EmulationOptions(GbModel.DMG))
            assertThrows(CoreError.NotCompatibilityMode::class.java) { session.setCompatPalette(2) }
        }
    }

    @Test
    fun hotPaletteChangeNeitherBlocksNorFailsAndChangesTheFrame() {
        EmulatorSession().use { session ->
            session.load(SyntheticRom.romOnly(), options = EmulationOptions(GbModel.CGB, compatPalette = 1))
            session.start()
            waitUntil { session.frameCount >= 3 }
            session.pause()
            val before = session.saveState().pixels
            session.resume()
            repeat(20) { session.setCompatPalette(1 + it % 12) }
            session.setCompatPalette(9)
            val frames = session.frameCount
            waitUntil { session.frameCount >= frames + 5 }
            session.pause()
            val after = session.saveState().pixels
            assertEquals(9, session.compatPalette)
            assertTrue("la paleta 9 cambia la imagen", !before.contentEquals(after))
        }
    }

    @Test
    fun copyFrameMatchesTheSavedStateFrameAndNeedsAPausedSession() {
        EmulatorSession().use { session ->
            session.load(SyntheticRom.romOnly())
            assertThrows(SessionError.NotParked::class.java) { session.copyFrame() }
            session.start()
            waitUntil { session.frameCount >= 3 }
            assertThrows(SessionError.NotParked::class.java) { session.copyFrame() }
            session.pause()
            val frame = session.copyFrame()
            assertEquals(CoreBridge.FRAME_PIXELS, frame.size)
            assertTrue(frame.contentEquals(session.saveState().pixels))
        }
    }

    @Test
    fun volumeAndScaleModeValidateAndClampAndSurviveClose() {
        val session = EmulatorSession()
        session.load(SyntheticRom.romOnly())
        assertEquals(1f, session.volume, 0f)
        session.setVolume(0.35f)
        assertEquals(0.35f, session.volume, 0f)
        assertThrows(IllegalArgumentException::class.java) { session.setVolume(1.5f) }
        assertThrows(IllegalArgumentException::class.java) { session.setVolume(Float.NaN) }
        assertEquals(0.35f, session.volume, 0f)
        assertEquals(ScaleMode.INTEGER, session.scaleMode)
        session.setScaleMode(ScaleMode.FILL)
        assertEquals(ScaleMode.FILL, session.scaleMode)
        session.close()
        val handle = NativeLibrary.nativeSessionCreate()
        try {
            NativeLibrary.nativeSessionSetScaleMode(handle, 1)
            NativeLibrary.nativeSessionSetScaleMode(handle, 7) // fuera de rango: se ignora
            assertEquals(1, NativeLibrary.nativeSessionScaleMode(handle))
            NativeLibrary.nativeSessionSetVolume(handle, 5f) // el nativo recorta
            assertEquals(1f, NativeLibrary.nativeSessionVolume(handle), 0f)
            NativeLibrary.nativeSessionSetVolume(handle, -2f)
            assertEquals(0f, NativeLibrary.nativeSessionVolume(handle), 0f)
            NativeLibrary.nativeSessionSetVolume(handle, Float.NaN) // no finito: se ignora
            assertEquals(0f, NativeLibrary.nativeSessionVolume(handle), 0f)
        } finally {
            NativeLibrary.nativeSessionDestroy(handle)
        }
        session.setVolume(0.5f) // tras cerrar: sin efecto, sin lanzar
        session.setScaleMode(ScaleMode.INTEGER)
    }
}

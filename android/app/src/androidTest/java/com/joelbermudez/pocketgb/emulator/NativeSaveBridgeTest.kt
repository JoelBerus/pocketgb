package com.joelbermudez.pocketgb.emulator

import android.os.SystemClock
import com.joelbermudez.pocketgb.saves.SaveSizes
import com.joelbermudez.pocketgb.testing.SyntheticRom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Puente nativo de partidas y estados (A5, etapa 4): SRAM con el hilo nativo corriendo o aparcado,
 * contador de guardados, estados y framebuffer con la sesión en pausa, RTC y cierre seguro.
 * Solo ROMs sintéticas.
 */
class NativeSaveBridgeTest {
    private fun counterSession(): EmulatorSession = EmulatorSession().also { it.load(SyntheticRom.sramCounter()) }

    @Test
    fun loadReturnsTheCartridgeInfoAndSramSize() {
        EmulatorSession().use { session ->
            val info = session.load(SyntheticRom.sramCounter("CONTADOR"))
            assertEquals("CONTADOR", info.title)
            assertTrue(info.hasBattery)
            assertFalse(info.hasRtc)
            assertEquals(8192, info.sramBytes)
            assertEquals(8192, session.sramSaveSize)
            assertEquals(64, info.fingerprintHex.length)
            assertEquals(info, session.info)

            // Es la misma información que da el núcleo suelto.
            CoreBridge().use { core ->
                assertEquals(core.loadRom(SyntheticRom.sramCounter("CONTADOR")), info)
            }
        }
    }

    @Test
    fun infoBeforeLoadIsAnError() {
        EmulatorSession().use { session ->
            assertThrows(CoreError.NoRom::class.java) { session.info }
        }
    }

    @Test
    fun dirtySequenceAdvancesWhenTheGameSavesAndStopsOnPause() {
        counterSession().use { session ->
            assertEquals(0L, session.sramDirtySequence())
            session.start()
            waitUntil { session.sramDirtySequence() >= 3 }
            session.pause()
            val atPause = session.sramDirtySequence()
            SystemClock.sleep(150)
            assertEquals(atPause, session.sramDirtySequence())
        }
    }

    @Test
    fun aRomThatNeverClosesTheRamNeverReportsASave() {
        EmulatorSession().use { session ->
            session.load(SyntheticRom.romOnly())
            session.start()
            waitUntil { session.frameCount >= 10 }
            session.pause()
            assertEquals(0L, session.sramDirtySequence())
        }
    }

    @Test
    fun copySramWhileRunningReturnsFreshDataEachTime() {
        counterSession().use { session ->
            session.start()
            waitUntil { session.sramDirtySequence() >= 1 }
            val first = session.copySram()
            assertEquals(8192, first.size)
            SystemClock.sleep(120)
            val second = session.copySram()
            assertEquals(SessionState.Running, session.state.value)
            assertNotEquals("la copia en marcha debe reflejar el avance del juego", first[0], second[0])

            // Muchas copias seguidas con el hilo nativo corriendo: ninguna agota el plazo.
            var previous = second[0].toInt() and 0xFF
            repeat(40) {
                val copy = session.copySram()
                val now = copy[0].toInt() and 0xFF
                // El contador solo crece (módulo 256) entre copias sucesivas.
                assertTrue("la copia retrocedió: $previous -> $now", ((now - previous) and 0xFF) < 128)
                previous = now
            }
        }
    }

    @Test
    fun copySramWhilePausedCopiesDirectlyAndIsStable() {
        counterSession().use { session ->
            session.start()
            waitUntil { session.sramDirtySequence() >= 2 }
            session.pause()
            val a = session.copySram()
            SystemClock.sleep(100)
            val b = session.copySram()
            assertArrayEquals(a, b)
            assertNotEquals("el juego ya escribió algo", 0, a[0].toInt())
        }
    }

    @Test
    fun copySramBeforeStartingIsTheLoadedSram() {
        EmulatorSession().use { session ->
            session.load(SyntheticRom.sramWriter(0x11))
            val sram = ByteArray(8192) { (it * 7).toByte() }
            session.loadSram(sram)
            assertArrayEquals(sram, session.copySram())
        }
    }

    @Test
    fun copySramAfterStopAndOnATerminatedSessionStillWorks() {
        counterSession().use { session ->
            session.start()
            waitUntil { session.sramDirtySequence() >= 1 }
            session.stop()
            assertEquals(8192, session.copySram().size)
        }
        val closed = counterSession()
        closed.close()
        assertThrows(CoreError.Closed::class.java) { closed.copySram() }
        assertThrows(CoreError.Closed::class.java) { closed.sramDirtySequence() }
    }

    @Test
    fun statesAndFramebufferNeedAParkedSession() {
        counterSession().use { session ->
            session.start()
            assertThrows(SessionError.NotParked::class.java) { session.saveState() }
            assertThrows(SessionError.NotParked::class.java) { session.loadStateRaw(ByteArray(100)) }
            assertThrows(SessionError.NotParked::class.java) { session.loadSram(ByteArray(8192)) }

            session.pause()
            val saved = session.saveState()
            assertEquals(CoreBridge.FRAME_PIXELS, saved.pixels.size)
            session.loadStateRaw(saved.bytes)

            session.resume()
            assertThrows(SessionError.NotParked::class.java) { session.saveState() }
        }
    }

    @Test
    fun savedStateRestoresTheSramOfThatMoment() {
        counterSession().use { session ->
            session.start()
            waitUntil { session.sramDirtySequence() >= 2 }
            session.pause()
            val x = session.copySram()
            val state = session.saveState()

            session.resume()
            waitUntil { session.sramDirtySequence() >= 20 }
            session.pause()
            val y = session.copySram()
            assertFalse(x.contentEquals(y))

            session.loadStateRaw(state.bytes)
            assertArrayEquals("cargar el estado devuelve la SRAM del estado", x, session.copySram())
        }
    }

    @Test
    fun corruptOrForeignStateNeverTouchesTheSram() {
        EmulatorSession().use { session ->
            session.load(SyntheticRom.sramWriter(0x11, "ESTADOS"))
            session.start()
            waitUntil { session.sramDirtySequence() >= 1 }
            session.pause()
            val sram = session.copySram()
            val good = session.saveState().bytes

            val corrupt = good.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0xFF).toByte() }
            assertThrows(CoreError.StateCorrupt::class.java) { session.loadStateRaw(corrupt) }
            assertThrows(CoreError.StateMagic::class.java) { session.loadStateRaw("nada".encodeToByteArray()) }
            assertThrows(CoreError.StateMagic::class.java) { session.loadStateRaw(ByteArray(0)) }
            // Más largo que cualquier estado de este ROM: se rechaza sin copiarlo.
            assertThrows(CoreError.StateCorrupt::class.java) { session.loadStateRaw(ByteArray(5 * 1024 * 1024)) }
            assertArrayEquals(sram, session.copySram())

            EmulatorSession().use { other ->
                other.load(SyntheticRom.sramWriter(0x22, "OTRO JUEGO"))
                other.start()
                waitUntil { other.sramDirtySequence() >= 1 }
                other.pause()
                val foreign = other.saveState().bytes
                assertThrows(CoreError.StateRomMismatch::class.java) { session.loadStateRaw(foreign) }
            }
            assertArrayEquals(sram, session.copySram())
        }
    }

    @Test
    fun loadSramWithTheWrongSizeIsRejectedAndKeepsTheCurrentOne() {
        EmulatorSession().use { session ->
            session.load(SyntheticRom.sramWriter(0x33))
            val original = ByteArray(8192) { 0x5A }
            session.loadSram(original)

            assertThrows(CoreError.SramSize::class.java) { session.loadSram(ByteArray(8192 + 1)) }
            assertThrows(CoreError.SramSize::class.java) { session.loadSram(ByteArray(100)) }
            assertThrows(CoreError.SramSize::class.java) { session.loadSram(ByteArray(0)) }
            // Sin RTC, ni siquiera RAM + 48 es una partida válida; y uno enorme no se copia.
            assertThrows(CoreError.SramSize::class.java) { session.loadSram(ByteArray(8192 + 48)) }
            assertThrows(CoreError.SramSize::class.java) { session.loadSram(ByteArray(16 * 1024 * 1024)) }
            assertArrayEquals(original, session.copySram())
        }
    }

    @Test
    fun loadSramWhilePausedReplacesTheCartridgeRam() {
        EmulatorSession().use { session ->
            session.load(SyntheticRom.sramWriter(0x44))
            session.start()
            waitUntil { session.sramDirtySequence() >= 1 }
            session.pause()
            assertEquals(0x44, session.copySram()[0].toInt())
            val replacement = ByteArray(8192) { 0x77 }
            session.loadSram(replacement)
            assertArrayEquals(replacement, session.copySram())
        }
    }

    @Test
    fun rtcCartridgeAddsTheClockBlockToTheSaveSize() {
        EmulatorSession().use { session ->
            val info = session.load(SyntheticRom.mbc3Rtc(ram = true))
            assertTrue(info.hasRtc)
            assertEquals(8192, info.sramBytes)
            assertEquals(8192 + 48, session.sramSaveSize)
            assertEquals(session.sramSaveSize, session.copySram().size)
            assertEquals(setOf(8192, 8192 + 48, 8192 + 44), SaveSizes.validSizes(info.hasRtc, info.sramBytes))
            assertTrue(session.sramSaveSize in SaveSizes.validSizes(info.hasRtc, info.sramBytes))

            // Las tres formas de .sav con RTC se aceptan; las demás no.
            session.loadSram(ByteArray(8192))
            session.loadSram(ByteArray(8192 + 48))
            session.loadSram(ByteArray(8192 + 44))
            assertThrows(CoreError.SramSize::class.java) { session.loadSram(ByteArray(8192 + 45)) }
        }
    }

    @Test
    fun rtcCartridgeWithoutRamSavesOnlyTheClockBlock() {
        EmulatorSession().use { session ->
            val info = session.load(SyntheticRom.mbc3Rtc(ram = false))
            assertTrue(info.hasRtc)
            assertTrue(info.hasBattery)
            assertEquals(0, info.sramBytes)
            // Con RTC y sin RAM el .sav es el bloque de 48 bytes; SaveSizes lo acepta (y no acepta 0).
            assertEquals(48, session.sramSaveSize)
            assertEquals(setOf(48, 44), SaveSizes.validSizes(info.hasRtc, info.sramBytes))
            assertTrue(session.sramSaveSize in SaveSizes.validSizes(info.hasRtc, info.sramBytes))
            assertEquals(48, session.copySram().size)
            session.loadSram(ByteArray(48))
            session.loadSram(ByteArray(44))
            session.loadSram(ByteArray(0)) // el .sav sin bloque RTC: el núcleo acepta len == RAM (0)
        }
    }

    @Test
    fun clockAdvancesToTheWallTimeWhenTheGameResumes() {
        val now = System.currentTimeMillis() / 1000
        val start = now - 3_600
        EmulatorSession().use { session ->
            session.load(SyntheticRom.mbc3Rtc(ram = true), unixTimeSeconds = start)
            session.start()
            waitUntil { session.frameCount >= 5 }
            session.pause()
            val atPause = rtcUnix(session.copySram())
            assertTrue("sin reanudar, el reloj sigue donde empezó ($atPause vs $start)", atPause in start..(start + 3))

            session.resume()
            waitUntil { session.frameCount >= 10 }
            session.pause()
            val afterResume = rtcUnix(session.copySram())
            assertTrue("al reanudar el reloj debe saltar a la hora actual ($afterResume vs $now)", afterResume in (now - 2)..(now + 10))
        }
    }

    @Test
    fun closeIsIdempotentAndASavingThreadNeverUsesAFreedHandle() {
        val session = counterSession()
        session.start()
        waitUntil { session.sramDirtySequence() >= 1 }
        val failure = AtomicReference<Throwable?>()
        val copies = AtomicReference(0)
        val worker = thread(name = "pocketgb-test-saves") {
            try {
                while (true) {
                    session.copySram()
                    session.sramDirtySequence()
                    copies.updateAndGet { it + 1 }
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        SystemClock.sleep(150)
        session.close()
        session.close()
        worker.join(5_000)
        assertFalse("el hilo de guardado debe terminar", worker.isAlive)
        assertTrue("el hilo llegó a copiar antes del cierre", copies.get() > 0)
        assertTrue("debe fallar con Closed, no con un crash: ${failure.get()}", failure.get() is CoreError.Closed)
        assertEquals(SessionState.Closed, session.state.value)
    }

    @Test
    fun closingARunningSessionJoinsTheNativeThread() {
        val session = counterSession()
        session.start()
        waitUntil { session.frameCount >= 3 }
        session.close()
        assertEquals(SessionState.Closed, session.state.value)
    }

    @Test
    fun copySramIsServedQuicklyWhileRunning() {
        counterSession().use { session ->
            session.start()
            waitUntil { session.sramDirtySequence() >= 1 }
            val samples = LongArray(60)
            for (i in samples.indices) {
                val begin = SystemClock.elapsedRealtimeNanos()
                session.copySram()
                samples[i] = SystemClock.elapsedRealtimeNanos() - begin
            }
            samples.sort()
            // Un frame dura 16,7 ms: la entrega la hace el hilo nativo justo después de cada frame.
            val p99Millis = samples[samples.size - 2] / 1_000_000.0
            assertTrue("copySram p99 = $p99Millis ms (tope 250 ms)", p99Millis < 250.0)
        }
    }

    /** Hora Unix (u64 little-endian) del bloque RTC: los últimos 8 bytes de los 48 finales. */
    private fun rtcUnix(sram: ByteArray): Long {
        var value = 0L
        for (i in 0 until 8) value = value or ((sram[sram.size - 8 + i].toLong() and 0xFF) shl (8 * i))
        return value
    }

    private fun waitUntil(timeoutMs: Long = 4_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(10)
        assertTrue("La sesión no avanzó antes del timeout", condition())
    }
}

package com.joelbermudez.pocketgb.emulator

import android.os.SystemClock
import android.util.Log
import com.joelbermudez.pocketgb.testing.GbaTestRoms
import com.joelbermudez.pocketgb.testing.SyntheticGbaRom
import com.joelbermudez.pocketgb.testing.SyntheticRom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * N8 nativo: el núcleo GBA a través de JNI y de la sesión nativa. Las referencias son del runner nativo de `gba/` en
 * el Mac para las mismas ROMs y el mismo número de frames (`gbatest ROM --dump F --frames 1,…,N`; cum = SHA-256 de los
 * frames 1..N concatenados en RGBA8888, last = del frame N). Ver docs/auditorias/N8-nativo-evidencia.md.
 */
class GbaNativeTest {
    private companion object {
        /** Pantalla de «All tests passed» de jsmolka (la misma en arm, thumb y flash128). */
        const val JSMOLKA_PASS = "afc525600e609311057e2e71eb1e27c45b997a86b22ff4b11d8d9034a4192f33"
        const val ARM_CUM_120 = "fc7d116bec687b01d7d59fc1bda066c5ca62127264f8115698db9772cbc24b7d"
        const val FLASH128_CUM_200 = "c493bfdd730070c52f1d7151cffd95f50b6de59b98604d6f8c2337d0ae4a77ad"
        const val STRIPES_CUM_60 = "bf3aa56c396a0566dfdd4545937db08820628cc6ae8ff200c5fb2044b53348f6"
        const val SHADES_CUM_60 = "aeb216ff644f1376b4623f6bbdcbf0523e4fd8770fa2fda121e4b00d343b4255"
        const val SCENE11_CUM_300 = "bf2f199841f87d326c8354191a9ffecccb167e8a7b5c02bae5cab9a86fdc99cb"
        const val SCENE11_LAST_300 = "d18f90b61f37a6cbee909dfe3ae542ba5c204975e093e1b0da3bafb1376f9c20"
        const val SRAM_32K = 32 * 1024
        const val TAG = "PocketGBN8"
    }

    private fun frameBytes(pixels: IntArray): ByteArray =
        ByteBuffer.allocate(pixels.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { it.asIntBuffer().put(pixels) }.array()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /** Ejecuta [frames] frames en un núcleo suelto y devuelve (cum, last) como el runner nativo. */
    private fun runCore(rom: ByteArray, frames: Int): Pair<String, String> = CoreBridge(Console.GBA).use { core ->
        core.loadGbaRom(rom)
        val cumulative = MessageDigest.getInstance("SHA-256")
        val pixels = IntArray(core.screen.pixelCount)
        var last = ByteArray(0)
        repeat(frames) {
            core.runFrame()
            core.copyFrame(pixels)
            last = frameBytes(pixels)
            cumulative.update(last)
        }
        cumulative.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) } to sha256(last)
    }

    private fun waitUntil(timeoutMs: Long = 6_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(10)
        assertTrue("La sesión no avanzó antes del timeout", condition())
    }

    // ---- Escena determinista igual a la del núcleo ----

    @Test
    fun armGbaPassesAndEveryFrameMatchesTheNativeRunner() {
        val (cumulative, last) = runCore(GbaTestRoms.load("arm.gba"), 120)
        assertEquals("los 120 frames de arm.gba deben ser los del runner nativo", ARM_CUM_120, cumulative)
        assertEquals("arm.gba debe terminar en la pantalla de «All tests passed»", JSMOLKA_PASS, last)
    }

    @Test
    fun thumbGbaPasses() {
        assertEquals(JSMOLKA_PASS, runCore(GbaTestRoms.load("thumb.gba"), 120).second)
    }

    @Test
    fun flash128AndPpuScenesMatchTheNativeRunnerFrameByFrame() {
        assertEquals(FLASH128_CUM_200 to JSMOLKA_PASS, runCore(GbaTestRoms.load("flash128.gba"), 200))
        assertEquals(STRIPES_CUM_60, runCore(GbaTestRoms.load("stripes.gba"), 60).first)
        assertEquals(SHADES_CUM_60, runCore(GbaTestRoms.load("shades.gba"), 60).first)
    }

    @Test
    fun homebrewSceneWithIrqAndDmaMatchesTheNativeRunnerFor300Frames() {
        assertEquals(SCENE11_CUM_300 to SCENE11_LAST_300, runCore(GbaTestRoms.load("ppu_scene_11.gba"), 300))
    }

    @Test
    fun sessionRunsArmGbaToThePassScreenWithAudio() {
        EmulatorSession(Console.GBA).use { session ->
            val info = session.loadGba(GbaTestRoms.load("arm.gba"), unixTimeSeconds = 1_700_000_000)
            assertEquals(Console.GBA, info.console)
            assertEquals(8824, info.romBytes)
            assertEquals("77ee88662552bdc885c1080c0172ff119d54db791bd73b21808cf1ff1fe5b40e", info.fingerprintHex)
            assertFalse(info.biosLoaded)
            assertEquals(ScreenSize(240, 160), session.screen)
            assertEquals((240 shl 16) or 160, session.withNativeHandle { NativeLibrary.nativeSessionScreenSize(it) })
            session.start()
            waitUntil { session.frameCount >= 30 }
            session.pause()
            val frame = session.copyFrame()
            assertEquals(240 * 160, frame.size)
            assertEquals("la sesión llega a la misma pantalla que el runner nativo", JSMOLKA_PASS, sha256(frameBytes(frame)))
            assertTrue("el núcleo GBA produce audio en el ring", session.audioFramesProduced > 0)
        }
    }

    // ---- Estados ----

    @Test
    fun gbaStateRoundTripRestoresFrameAndSram() {
        EmulatorSession(Console.GBA).use { session ->
            session.loadGba(SyntheticGbaRom.sramCounter())
            session.start()
            waitUntil { session.sramDirtySequence() >= 3 }
            session.pause()
            val sram = session.copySram()
            val state = session.saveState()
            assertEquals(240 * 160, state.pixels.size)
            assertTrue("estado GBA de ≈666 KiB: ${state.bytes.size}", state.bytes.size in 600_000..800_000)

            session.resume()
            waitUntil { session.frameCount >= 20 }
            session.pause()
            assertFalse(sram.contentEquals(session.copySram()))

            session.loadStateRaw(state.bytes)
            assertArrayEquals("el estado trae la partida de su momento", sram, session.copySram())
            assertArrayEquals(state.pixels, session.copyFrame())
            assertArrayEquals("guardar justo después de cargar da el mismo estado", state.bytes, session.saveState().bytes)
        }
    }

    @Test
    fun hostileForeignOrOtherConfigurationStatesNeverTouchTheSession() {
        EmulatorSession(Console.GBA).use { session ->
            session.loadGba(SyntheticGbaRom.sramWriter(0x42))
            session.start()
            waitUntil { session.sramDirtySequence() >= 1 }
            session.pause()
            val sram = session.copySram()
            val good = session.saveState().bytes

            val corrupt = good.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0xFF).toByte() }
            assertThrows(CoreError.StateCorrupt::class.java) { session.loadStateRaw(corrupt) }
            assertThrows(CoreError.StateMagic::class.java) { session.loadStateRaw(ByteArray(64)) }
            assertThrows(CoreError.StateCorrupt::class.java) { session.loadStateRaw(ByteArray(2 * 1024 * 1024)) }

            // Un estado de Game Boy en una sesión de GBA: otra magia.
            EmulatorSession().use { gb ->
                gb.load(SyntheticRom.romOnly())
                gb.start()
                waitUntil { gb.frameCount >= 1 }
                gb.pause()
                assertThrows(CoreError.StateMagic::class.java) { session.loadStateRaw(gb.saveState().bytes) }
            }
            // Otro ROM de GBA.
            EmulatorSession(Console.GBA).use { other ->
                other.loadGba(SyntheticGbaRom.sramWriter(0x43, "OTRO"))
                other.start()
                waitUntil { other.frameCount >= 1 }
                other.pause()
                assertThrows(CoreError.StateRomMismatch::class.java) { session.loadStateRaw(other.saveState().bytes) }
            }
            // El mismo ROM con otra configuración (RTC forzado): «estado de otra configuración», no «dañado».
            EmulatorSession(Console.GBA).use { other ->
                other.loadGba(SyntheticGbaRom.sramWriter(0x42), options = GbaOptions(rtc = GbaRtc.ON))
                other.start()
                waitUntil { other.frameCount >= 1 }
                other.pause()
                assertThrows(CoreError.StateConfig::class.java) { session.loadStateRaw(other.saveState().bytes) }
            }
            assertArrayEquals(sram, session.copySram())
            assertArrayEquals(good, session.saveState().bytes)
        }
    }

    // ---- Partida GBA: medio + 16 B de RTC al final (formato de iOS) ----

    @Test
    fun sramWithRtcRoundTripsInTheIosFormat() {
        EmulatorSession(Console.GBA).use { session ->
            val info = session.loadGba(SyntheticGbaRom.idle(saveId = "SRAM_V113"), options = GbaOptions(rtc = GbaRtc.ON))
            assertEquals(GbaSaveType.SRAM, info.gbaSaveType)
            assertEquals(SRAM_32K, info.sramBytes)
            assertTrue(info.hasRtc && info.hasBattery)
            assertEquals(SRAM_32K + GBA_RTC_BYTES, session.sramSaveSize)
            assertEquals(setOf(SRAM_32K, SRAM_32K + 16), info.gbaValidSaveSizes)

            val media = ByteArray(SRAM_32K) { (it * 31 + 7).toByte() }
            // Bloque RTC: desplazamiento de 3600 s (little-endian, con signo) y estado 24 h (0x40).
            val rtc = ByteArray(GBA_RTC_BYTES).also { it[0] = 0x10; it[1] = 0x0E; it[8] = 0x40 }
            session.loadSram(media + rtc)
            assertArrayEquals("ida y vuelta exacta del .sav con RTC", media + rtc, session.copySram())

            // Solo el medio: se acepta y el RTC no cambia.
            val other = ByteArray(SRAM_32K) { 0x5A }
            session.loadSram(other)
            assertArrayEquals(other + rtc, session.copySram())

            // Tamaños imposibles y un RTC fuera de ±200 años: rechazados sin tocar nada (tampoco el RTC).
            val before = session.copySram()
            for (size in listOf(0, 16, SRAM_32K - 1, SRAM_32K + 1, SRAM_32K + 15, SRAM_32K + 17, SRAM_32K + 48)) {
                assertThrows("tamaño $size", CoreError.SramSize::class.java) { session.loadSram(ByteArray(size)) }
            }
            val badRtc = ByteArray(GBA_RTC_BYTES).also { it[7] = 0x40 } // 2^62 s
            assertThrows(CoreError.SramSize::class.java) { session.loadSram(ByteArray(SRAM_32K) { 1 } + badRtc) }
            assertArrayEquals(before, session.copySram())

            // Corriendo: la instantánea la entrega el hilo nativo y también lleva el RTC.
            session.start()
            waitUntil { session.frameCount >= 3 }
            assertArrayEquals(before, session.copySram())
        }
    }

    @Test
    fun rtcOnlyCartridgeSavesJustTheClockBlock() {
        EmulatorSession(Console.GBA).use { session ->
            val info = session.loadGba(SyntheticGbaRom.idle(), options = GbaOptions(saveType = GbaSaveType.NONE, rtc = GbaRtc.ON))
            assertEquals(0, info.sramBytes)
            assertTrue(info.hasRtc && info.hasBattery)
            assertEquals(GBA_RTC_BYTES, session.sramSaveSize)
            assertEquals(setOf(GBA_RTC_BYTES), info.gbaValidSaveSizes)
            val rtc = ByteArray(GBA_RTC_BYTES).also { it[0] = 1; it[8] = 0x40 }
            session.loadSram(rtc)
            assertArrayEquals(rtc, session.copySram())
            assertThrows(CoreError.SramSize::class.java) { session.loadSram(ByteArray(0)) }
            assertThrows(CoreError.SramSize::class.java) { session.loadSram(ByteArray(32)) }
        }
        EmulatorSession(Console.GBA).use { session ->
            val info = session.loadGba(SyntheticGbaRom.idle())
            assertEquals(GbaSaveType.NONE, info.gbaSaveType)
            assertFalse(info.hasBattery)
            assertEquals(0, session.sramSaveSize)
        }
    }

    @Test
    fun eepromWithoutSettingTakesItsSizeFromTheSaveOnce() {
        EmulatorSession(Console.GBA).use { session ->
            val info = session.loadGba(SyntheticGbaRom.idle(saveId = "EEPROM_V124"))
            assertTrue(info.eeprom)
            assertFalse(info.eepromSizeFixed)
            assertEquals(512, session.sramSaveSize)
            assertEquals(8192, session.withNativeHandle { NativeLibrary.nativeSessionSramCapacity(it) })
            val big = ByteArray(8192) { (it xor 0x33).toByte() }
            session.loadSram(big)
            assertEquals("un .sav de 8 KiB confirma la EEPROM", 8192, session.sramSaveSize)
            assertArrayEquals(big, session.copySram())
            assertThrows("confirmada, otro tamaño se rechaza", CoreError.SramSize::class.java) { session.loadSram(ByteArray(512)) }
            assertArrayEquals(big, session.copySram())
        }
        EmulatorSession(Console.GBA).use { session ->
            val info = session.loadGba(SyntheticGbaRom.idle(saveId = "EEPROM_V124"), options = GbaOptions(saveType = GbaSaveType.EEPROM512))
            assertTrue(info.eeprom && info.eepromSizeFixed)
            assertEquals(512, session.withNativeHandle { NativeLibrary.nativeSessionSramCapacity(it) })
            assertThrows(CoreError.SramSize::class.java) { session.loadSram(ByteArray(8192)) }
            assertEquals(512, session.sramSaveSize)
        }
    }

    @Test
    fun eepromSizeConfirmedByTheFirstDmaWhileRunningReachesTheSnapshot() {
        val a = byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x9A.toByte(), 0xBC.toByte(), 0xDE.toByte(), 0xF0.toByte())
        val b = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        EmulatorSession(Console.GBA).use { session ->
            session.loadGba(GbaTestRoms.load("eeprom8k.gba"))
            assertEquals(512, session.sramSaveSize)
            session.start()
            waitUntil { session.sramDirtySequence() >= 1 && session.sramSaveSize == 8192 }
            val running = session.copySram()
            assertEquals("la instantánea en marcha mide lo confirmado por la DMA", 8192, running.size)
            assertArrayEquals(a, running.copyOfRange(40, 48))
            assertArrayEquals(b, running.copyOfRange(48, 56))
            assertArrayEquals(b, running.copyOfRange(8000, 8008))
        }
        EmulatorSession(Console.GBA).use { session ->
            session.loadGba(GbaTestRoms.load("eeprom.gba"))
            session.start()
            waitUntil { session.sramDirtySequence() >= 1 }
            session.pause()
            val sav = session.copySram()
            assertEquals(512, sav.size)
            assertArrayEquals(a, sav.copyOfRange(40, 48))
        }
    }

    @Test
    fun runningCounterIsCopiedFreshAndCountsSaves() {
        EmulatorSession(Console.GBA).use { session ->
            session.loadGba(SyntheticGbaRom.sramCounter())
            assertEquals(0L, session.sramDirtySequence())
            session.start()
            waitUntil { session.sramDirtySequence() >= 2 }
            val first = session.copySram()
            SystemClock.sleep(100)
            val second = session.copySram()
            assertEquals(SRAM_32K, first.size)
            assertNotEquals(first[0], second[0])
            session.pause()
            val atPause = session.sramDirtySequence()
            SystemClock.sleep(100)
            assertEquals(atPause, session.sramDirtySequence())
        }
    }

    // ---- Botones de 16 bits ----

    @Test
    fun shoulderButtonsReachTheGbaCoreAndAreMaskedPerConsole() {
        EmulatorSession(Console.GBA).use { session ->
            session.loadGba(SyntheticGbaRom.idle())
            session.setTouchButtons(GbaButtonBits.L or 0x01)
            session.setPhysicalButtons(GbaButtonBits.R or 0x80)
            assertEquals(GbaButtonBits.L or GbaButtonBits.R or 0x81, session.requestedButtons)
            session.start()
            waitUntil { session.appliedButtons == (GbaButtonBits.L or GbaButtonBits.R or 0x81) }
            // Todo pulsado: recortado a 10 bits y, ya combinado, sin opuestos (N2): A, B, Select, Start, R y L.
            session.setTouchButtons(-1)
            assertEquals(0x30F, session.requestedButtons)
            // El nativo recorta igual aunque Kotlin no lo hiciera.
            session.setTouchButtons(0)
            session.withNativeHandle { NativeLibrary.nativeSessionSetTouchButtons(it, 0xFFFF) }
            assertEquals(0x30F, session.requestedButtons)
        }
        EmulatorSession().use { session ->
            session.withNativeHandle { NativeLibrary.nativeSessionSetTouchButtons(it, GbaButtonBits.L or 0x01) }
            assertEquals("en GB, L y R no existen", 0x01, session.requestedButtons)
        }
    }

    @Test
    fun oppositeDirectionsCancelWithTheGbaMaskAndShouldersStay() {
        val right = 1 shl 4
        val left = 1 shl 5
        val up = 1 shl 6
        val down = 1 shl 7
        val l = GbaButtonBits.L
        val r = GbaButtonBits.R
        EmulatorSession(Console.GBA).use { session ->
            session.loadGba(SyntheticGbaRom.idle())
            // Táctil ↑ + L y mando ↓ + R: las direcciones se anulan, los gatillos llegan.
            session.setTouchButtons(up or l)
            session.setPhysicalButtons(down or r)
            assertEquals(l or r, session.requestedButtons)
            session.setTouchButtons(left or 0x01)
            session.setPhysicalButtons(right or up)
            assertEquals(up or 0x01, session.requestedButtons)
            session.setTouchButtons(up or right or l)
            session.setPhysicalButtons(0)
            assertEquals("sin opuestos no cambia nada", up or right or l, session.requestedButtons)
            // Lo mismo llega al hilo nativo (lo que ve el núcleo).
            session.setTouchButtons(up or l)
            session.setPhysicalButtons(down or r or 0x02)
            session.start()
            waitUntil { session.appliedButtons == (l or r or 0x02) }
        }
    }

    // ---- RTC del GBA (A9 + N8): syncRtc y reanudar ponen la hora local ----

    /** Desplazamientos del estado donde está el entero de 64 bits little-endian [value] (la base del RTC, `rtc_base`). */
    private fun offsetsOf(state: ByteArray, value: Long): List<Int> = (0..state.size - 8).filter { i ->
        var v = 0L
        for (b in 0 until 8) v = v or ((state[i + b].toLong() and 0xFF) shl (8 * b))
        v == value
    }

    private fun int64At(state: ByteArray, offset: Int): Long {
        var v = 0L
        for (b in 0 until 8) v = v or ((state[offset + b].toLong() and 0xFF) shl (8 * b))
        return v
    }

    private fun localSeconds(utc: Long): Long = utc + java.util.TimeZone.getDefault().getOffset(utc * 1000) / 1000

    @Test
    fun gbaRtcGoesToLocalTimeOnSyncAndOnResume() {
        val start = 1_700_000_000L
        EmulatorSession(Console.GBA).use { session ->
            session.loadGba(SyntheticGbaRom.sramCounter(), unixTimeSeconds = start, options = GbaOptions(rtc = GbaRtc.ON))
            // El estado guarda la base del RTC: la hora LOCAL de la carga (el puente convierte la UTC).
            val atLoad = session.saveStateParked()
            val found = offsetsOf(atLoad, localSeconds(start))
            assertEquals("la base del RTC aparece una vez en el estado: $found", 1, found.size)
            val offset = found.single()

            // A9: syncRtc con la sesión sin arrancar la lleva a la hora pedida, en hora local.
            val synced = start + 86_400
            session.syncRtc(synced)
            assertEquals(localSeconds(synced), int64At(session.saveStateParked(), offset))

            // Corriendo no se puede; al reanudar vuelve a la hora real.
            session.start()
            waitUntil { session.frameCount >= 3 }
            assertThrows(SessionError.NotParked::class.java) { session.syncRtc(synced) }
            session.pause()
            assertEquals("pausar no toca el reloj", localSeconds(synced), int64At(session.saveState().bytes, offset))
            val before = System.currentTimeMillis() / 1000
            session.resume()
            waitUntil { session.frameCount >= 6 }
            session.pause()
            val after = System.currentTimeMillis() / 1000
            assertTrue(
                "al reanudar, la base es la hora local actual",
                int64At(session.saveState().bytes, offset) in localSeconds(before)..localSeconds(after),
            )
            // El .sav solo lleva el desplazamiento del juego (0) y el estado, no la hora: el formato de iOS no cambia.
            assertArrayEquals(ByteArray(8), session.copySram().copyOfRange(32 * 1024, 32 * 1024 + 8))
        }
    }

    @Test
    fun parkedStatesOfA9WorkForGbaBeforeStarting() {
        EmulatorSession(Console.GBA).use { session ->
            session.loadGba(SyntheticGbaRom.sramWriter(0x21))
            val fresh = session.saveStateParked()
            session.start()
            waitUntil { session.sramDirtySequence() >= 1 }
            session.pause()
            val written = session.saveState().bytes
            session.stop()
            EmulatorSession(Console.GBA).use { other ->
                other.loadGba(SyntheticGbaRom.sramWriter(0x21))
                other.loadStateParked(written)
                assertEquals(0x21, other.copySram()[0].toInt())
                assertFalse(fresh.contentEquals(other.saveStateParked()))
                other.loadStateParked(fresh)
                assertArrayEquals(fresh, other.saveStateParked())
            }
        }
    }

    // ---- N8-H2: una carga por sesión; el núcleo suelto, nuevo en cada carga ----

    @Test
    fun aSessionLoadsOnceAndAFailedLoadLeavesItNew() {
        val idle = SyntheticGbaRom.idle()
        val gba = NativeLibrary.nativeSessionCreateConsole(1)
        val gb = NativeLibrary.nativeSessionCreateConsole(0)
        try {
            // Una carga fallida deja la sesión en NEW y admite otra.
            assertEquals(18, NativeLibrary.nativeSessionLoadGba(gba, SyntheticGbaRom.withBadFixedByte(idle), null, 0, 0, 0))
            assertEquals(0, NativeLibrary.nativeSessionLoadGba(gba, idle, null, 0, 0, 0))
            // Ya cargada: NS_BUSY y nada cambia (la cabecera y la partida siguen siendo las de la primera).
            assertEquals(-1, NativeLibrary.nativeSessionLoadGba(gba, SyntheticGbaRom.sramWriter(0x11), null, 0, 0, 0))
            val ints = IntArray(GBA_INFO_INTS)
            assertEquals(0, NativeLibrary.nativeSessionGbaRomInfo(gba, ints, ByteArray(32), ByteArray(13), ByteArray(8)))
            assertEquals(GbaSaveType.NONE.native, ints[1])
            assertEquals(0, NativeLibrary.nativeSessionSramSize(gba))
            NativeLibrary.nativeSessionStop(gba)
            assertEquals("detenida tampoco", -1, NativeLibrary.nativeSessionLoadGba(gba, idle, null, 0, 0, 0))

            assertEquals(0, NativeLibrary.nativeSessionLoad(gb, SyntheticRom.romOnly(), 0, 0, 0))
            assertEquals(-1, NativeLibrary.nativeSessionLoad(gb, SyntheticRom.sramWriter(0x22), 0, 0, 0))
            assertEquals(0, NativeLibrary.nativeSessionSramSize(gb))
        } finally {
            NativeLibrary.nativeSessionDestroy(gba)
            NativeLibrary.nativeSessionDestroy(gb)
        }
        EmulatorSession(Console.GBA).use { session ->
            session.loadGba(idle)
            assertThrows(SessionError.InvalidTransition::class.java) { session.loadGba(idle) }
        }
    }

    @Test
    fun coreBridgeUsesAFreshCoreForEachGbaLoad() {
        CoreBridge(Console.GBA).use { core ->
            core.loadGbaRom(GbaTestRoms.load("stripes.gba"))
            repeat(30) { core.runFrame() }
            val info = core.loadGbaRom(GbaTestRoms.load("shades.gba"))
            assertEquals("127ce348f17e4d5fd8a0821a7af757eda6109c66c9f7b8f7d1e0fa31831b407d", info.fingerprintHex)
            val cumulative = MessageDigest.getInstance("SHA-256")
            val pixels = IntArray(core.screen.pixelCount)
            repeat(60) {
                core.runFrame()
                core.copyFrame(pixels)
                cumulative.update(frameBytes(pixels))
            }
            assertEquals(
                "tras recargar, los 60 frames son los de un núcleo recién creado",
                SHADES_CUM_60,
                cumulative.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) },
            )
        }
    }

    // ---- BIOS opcional ----

    @Test
    fun nonOfficialBiosIsRejectedAndTheCoreUsesHle() {
        val fake = ByteArray(GbaBios.SIZE_BYTES) { 0x5A }
        assertEquals(GbaBiosStatus.INVALID, GbaBios.status(fake))
        assertFalse(NativeLibrary.nativeGbaBiosIsOfficial(fake))
        assertFalse(NativeLibrary.nativeGbaBiosIsOfficial(ByteArray(GbaBios.SIZE_BYTES)))
        assertFalse(NativeLibrary.nativeGbaBiosIsOfficial(ByteArray(1024 * 1024)))
        assertFalse(NativeLibrary.nativeGbaBiosIsOfficial(null))
        val rom = GbaTestRoms.load("arm.gba")
        for (bios in listOf(fake, ByteArray(1024 * 1024), ByteArray(0))) {
            EmulatorSession(Console.GBA).use { session ->
                val info = session.loadGba(rom, bios = bios)
                assertFalse("una BIOS que no es la oficial nunca se carga", info.biosLoaded)
                session.start()
                waitUntil { session.frameCount >= 10 }
                session.pause()
                assertEquals("con HLE arm.gba sigue pasando", JSMOLKA_PASS, sha256(frameBytes(session.copyFrame())))
            }
        }
        CoreBridge(Console.GBA).use { core -> assertFalse(core.loadGbaRom(rom, bios = fake).biosLoaded) }
    }

    // ---- Entrada hostil por JNI ----

    @Test
    fun hostileArgumentsAreRejectedBeforeTouchingTheCore() {
        assertEquals(0L, NativeLibrary.nativeSessionCreateConsole(2))
        assertEquals(0L, NativeLibrary.nativeSessionCreateConsole(-1))
        assertEquals((160 shl 16) or 144, NativeLibrary.nativeConsoleScreenSize(0))
        assertEquals((240 shl 16) or 160, NativeLibrary.nativeConsoleScreenSize(1))
        assertEquals(0, NativeLibrary.nativeConsoleScreenSize(7))
        val rom = SyntheticGbaRom.idle()
        val gba = NativeLibrary.nativeSessionCreateConsole(1)
        val gb = NativeLibrary.nativeSessionCreateConsole(0)
        try {
            assertEquals(4, NativeLibrary.nativeSessionLoadGba(gba, ByteArray(32 * 1024 * 1024 + 1), null, 0, 0, 0))
            assertEquals(17, NativeLibrary.nativeSessionLoadGba(gba, rom, null, 0, 7, 0))
            assertEquals(17, NativeLibrary.nativeSessionLoadGba(gba, rom, null, 0, -1, 0))
            assertEquals(17, NativeLibrary.nativeSessionLoadGba(gba, rom, null, 0, 0, 3))
            assertEquals(3, NativeLibrary.nativeSessionLoadGba(gba, ByteArray(0xBF), null, 0, 0, 0))
            assertEquals(18, NativeLibrary.nativeSessionLoadGba(gba, SyntheticGbaRom.withBadFixedByte(rom), null, 0, 0, 0))
            // Cada consola solo acepta su ROM y un GB > 8 MiB no se copia.
            assertEquals(17, NativeLibrary.nativeSessionLoad(gba, SyntheticRom.romOnly(), 0, 0, 0))
            assertEquals(17, NativeLibrary.nativeSessionLoadGba(gb, rom, null, 0, 0, 0))
            assertEquals(4, NativeLibrary.nativeSessionLoad(gb, ByteArray(8 * 1024 * 1024 + 1), 0, 0, 0))
            assertEquals(-3, NativeLibrary.nativeSessionGbaRomInfo(gb, IntArray(8), ByteArray(32), ByteArray(13), ByteArray(8)))
            // Tras los fallos la sesión GBA sigue usable y sin ROM; cargada, la cabecera no cabe en búferes cortos.
            assertEquals(-1, NativeLibrary.nativeSessionGbaRomInfo(gba, IntArray(8), ByteArray(32), ByteArray(13), ByteArray(8)))
            assertEquals(0, NativeLibrary.nativeSessionLoadGba(gba, rom, null, 0, 0, 0))
            assertEquals(16, NativeLibrary.nativeSessionGbaRomInfo(gba, IntArray(7), ByteArray(32), ByteArray(13), ByteArray(8)))
            assertEquals(-3, NativeLibrary.nativeSessionRomInfo(gba, IntArray(10), ByteArray(32), ByteArray(17)))
            assertEquals(16, NativeLibrary.nativeSessionCopyFrame(gba, IntArray(160 * 144)))
            // Un .sav enorme no se copia.
            assertEquals(11, NativeLibrary.nativeSessionSramLoad(gba, ByteArray(4 * 1024 * 1024)))
        } finally {
            NativeLibrary.nativeSessionDestroy(gba)
            NativeLibrary.nativeSessionDestroy(gb)
        }
        EmulatorSession(Console.GBA).use { session ->
            assertThrows(SessionError.WrongConsole::class.java) { session.load(SyntheticRom.romOnly()) }
            assertThrows(CoreError.RomTooLarge::class.java) { session.loadGba(ByteArray(32 * 1024 * 1024 + 1)) }
            assertThrows(CoreError.RomTooSmall::class.java) { session.loadGba(ByteArray(0xBF)) }
            assertThrows(CoreError.BadGbaHeader::class.java) { session.loadGba(SyntheticGbaRom.withBadFixedByte(rom)) }
            assertEquals(SessionState.New, session.state.value)
            assertEquals(Console.GBA, session.loadGba(rom).console)
        }
        EmulatorSession().use { session ->
            assertThrows(SessionError.WrongConsole::class.java) { session.loadGba(rom) }
        }
    }

    // ---- Rendimiento (orientativo: el dato real es el del teléfono de Joel) ----

    @Test
    fun millisecondsPerFrameOnThisDevice() {
        val results = mutableListOf<String>()
        for ((name, frames) in listOf("arm.gba" to 600, "ppu_scene_11.gba" to 600, "shades.gba" to 600)) {
            val rom = try { GbaTestRoms.load(name) } catch (_: org.junit.AssumptionViolatedException) { continue }
            CoreBridge(Console.GBA).use { core ->
                core.loadGbaRom(rom)
                repeat(30) { core.runFrame() } // calentamiento
                val begin = SystemClock.elapsedRealtimeNanos()
                repeat(frames) { core.runFrame() }
                val ms = (SystemClock.elapsedRealtimeNanos() - begin) / 1_000_000.0 / frames
                results += "$name ${"%.3f".format(ms)} ms/frame"
                assertTrue("$name: $ms ms/frame (tope de cordura 100)", ms < 100.0)
            }
        }
        Log.i(TAG, "BENCH ${android.os.Build.SUPPORTED_ABIS.first()} ${results.joinToString(" | ")}")
        assertTrue(results.isNotEmpty())
    }
}

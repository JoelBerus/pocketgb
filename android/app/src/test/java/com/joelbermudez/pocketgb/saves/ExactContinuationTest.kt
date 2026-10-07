package com.joelbermudez.pocketgb.saves

import com.joelbermudez.pocketgb.emulator.CoreError
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A9 · Continuación exacta (cambia J8, ND6): «Continuar» retoma el estado AUTO solo si es de esta partida; en cualquier
 * otro caso cae a la partida (`.sav`) SIN escribir nada. Se prueba con un núcleo simulado (formato propio: firma,
 * huella, modelo, RAM, pie del RTC, posición y suma de control) y los almacenes reales sobre una carpeta temporal.
 */
class ExactContinuationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** Núcleo simulado. Rechaza como el real: firma → StateMagic, suma/longitud → StateCorrupt, huella/modelo → StateRomMismatch. */
    private class FakeCore(
        val fingerprint: Byte = 1,
        val model: Byte = DMG,
        var ram: ByteArray = ByteArray(8) { 0x11 },
        var footer: ByteArray = ByteArray(0),
        var position: Int = 0,
    ) : ResumableCore {
        var clockSyncs = 0
        val restored = mutableListOf<ByteArray>()

        override fun cartridgeRam() = ram.copyOf()
        override fun captureState() = encode(fingerprint, model, ram, footer, position)
        override fun restoreState(state: ByteArray) {
            restored += state
            if (state.size < 4 || !state.copyOf(4).contentEquals(MAGIC)) throw CoreError.StateMagic()
            if (state.size < HEADER || state.last() != checksum(state.copyOf(state.size - 1))) throw CoreError.StateCorrupt()
            if (state[4] != fingerprint || state[5] != model) throw CoreError.StateRomMismatch()
            val ramSize = state[6].toInt()
            val footerSize = state[7].toInt()
            if (state.size != HEADER + ramSize + footerSize + 4 + 1) throw CoreError.StateCorrupt()
            ram = state.copyOfRange(HEADER, HEADER + ramSize)
            footer = state.copyOfRange(HEADER + ramSize, HEADER + ramSize + footerSize)
            val p = HEADER + ramSize + footerSize
            position = (state[p].toInt() and 0xFF) or ((state[p + 1].toInt() and 0xFF) shl 8)
        }
        override fun syncClockToNow() {
            clockSyncs++
        }

        companion object {
            const val DMG: Byte = 1
            const val CGB: Byte = 2
            const val HEADER = 8
            val MAGIC = "PGBS".toByteArray()
            fun checksum(bytes: ByteArray): Byte = bytes.fold(0) { acc, b -> (acc * 31 + b) and 0xFF }.toByte()
            fun encode(fp: Byte, model: Byte, ram: ByteArray, footer: ByteArray, position: Int): ByteArray {
                val body = MAGIC + byteArrayOf(fp, model, ram.size.toByte(), footer.size.toByte()) + ram + footer +
                    byteArrayOf(position.toByte(), (position shr 8).toByte(), 0, 0)
                return body + checksum(body)
            }
        }
    }

    private val fingerprint = "ab".repeat(32)
    private lateinit var states: StateStore
    private lateinit var saves: SaveStore
    private val ram = ByteArray(8) { 0x11 }

    @Before
    fun setUp() {
        states = StateStore(File(tmp.root, "states"), fingerprint)
        saves = SaveStore(File(tmp.root, "saves"), fingerprint)
        saves.recoverOrphans(setOf(8))
    }

    /** Estado AUTO de una salida: misma RAM que la partida, en la posición [position]. */
    private fun writeAuto(ramOfState: ByteArray = ram, position: Int = 300, model: Byte = FakeCore.DMG, fp: Byte = 1, footer: ByteArray = ByteArray(0), atMs: Long = 2_000_000L): ByteArray {
        val state = FakeCore.encode(fp, model, ramOfState, footer, position)
        states.save(state, thumbnail = byteArrayOf(1, 2, 3), slot = StateSlot.AUTO)
        states.stateFile(StateSlot.AUTO).setLastModified(atMs)
        return state
    }

    private fun writeSave(data: ByteArray = ram, atMs: Long = 1_000_000L) {
        saves.save(data)
        saves.saveFile.setLastModified(atMs)
    }

    /** Lo que hay en disco de la partida: principal y backups, para comprobar que nada cambia. */
    private fun savesOnDisk(): List<Pair<String, List<Byte>>> =
        File(tmp.root, "saves").walkTopDown().filter { it.isFile }.map { it.relativeTo(tmp.root).path to it.readBytes().toList() }
            .sortedBy { it.first }.toList()

    private fun resume(core: FakeCore) = ExactContinuation.resume(core, states, saves.modificationDateMs)

    @Test
    fun aValidAutomaticStateResumesTheExactPositionWithoutWritingTheSave() {
        writeSave()
        val auto = writeAuto(position = 300)
        val before = savesOnDisk()
        val core = FakeCore(position = 0)
        assertEquals(ExactContinuation.Outcome.Resumed, resume(core))
        assertEquals("retoma la posición del estado", 300, core.position)
        assertArrayEquals(auto, core.captureState())
        assertEquals(1, core.clockSyncs)
        assertEquals("la partida no se toca", before, savesOnDisk())
        assertTrue("el estado sigue", states.stateFile(StateSlot.AUTO).exists())
    }

    @Test
    fun withoutASaveOnDiskTheStateIsJudgedByContentOnly() {
        writeAuto()
        val core = FakeCore()
        assertEquals(ExactContinuation.Outcome.Resumed, ExactContinuation.resume(core, states, saveDateMs = null))
        assertEquals(300, core.position)
    }

    @Test
    fun aNewerSaveInvalidatesTheStateByDateAndFallsBackWithoutLoss() {
        writeAuto(atMs = 1_000_000L)
        writeSave(atMs = 5_000_000L) // el juego guardó después del AUTO
        val before = savesOnDisk()
        val core = FakeCore(position = 7)
        assertEquals(ExactContinuation.Outcome.Rejected(ResumeFailure.NOT_CURRENT), resume(core))
        assertEquals("ni siquiera se intenta cargar", 0, core.restored.size)
        assertEquals(7, core.position)
        assertArrayEquals(ram, core.cartridgeRam())
        assertEquals("la partida queda intacta", before, savesOnDisk())
        assertFalse("el AUTO obsoleto se retira (iOS D81V2-H2)", states.stateFile(StateSlot.AUTO).exists())
        assertFalse(states.thumbnailFile(StateSlot.AUTO).exists())
    }

    @Test
    fun aStateWhoseCartridgeRamDiffersIsRejectedAndTheCoreIsRolledBack() {
        // Fechas en regla pero otro contenido (p. ej. un espejo más nuevo instalado al abrir con la misma hora, o un
        // reloj atrasado): cargarlo devolvería una partida vieja.
        writeSave(atMs = 1_000_000L)
        writeAuto(ramOfState = ByteArray(8) { 0x22 }, atMs = 2_000_000L)
        val before = savesOnDisk()
        val core = FakeCore(position = 7)
        val previous = core.captureState()
        assertEquals(ExactContinuation.Outcome.Rejected(ResumeFailure.NOT_CURRENT), resume(core))
        assertArrayEquals("el núcleo vuelve a la partida", previous, core.captureState())
        assertArrayEquals(ram, core.cartridgeRam())
        assertEquals(0, core.clockSyncs)
        assertEquals(before, savesOnDisk())
        assertFalse(states.stateFile(StateSlot.AUTO).exists())
    }

    @Test
    fun aStateOfAnotherModelIsRejectedAndKept() {
        writeSave()
        writeAuto(model = FakeCore.CGB)
        val before = savesOnDisk()
        val core = FakeCore(model = FakeCore.DMG, position = 7)
        val previous = core.captureState()
        assertEquals(ExactContinuation.Outcome.Rejected(ResumeFailure.INCOMPATIBLE), resume(core))
        assertArrayEquals(previous, core.captureState())
        assertEquals(before, savesOnDisk())
        assertTrue("se conserva: vuelve a servir con el modelo anterior", states.stateFile(StateSlot.AUTO).exists())
    }

    @Test
    fun aStateOfAnotherRomIsRejectedAndKept() {
        writeSave()
        writeAuto(fp = 9)
        val core = FakeCore()
        assertEquals(ExactContinuation.Outcome.Rejected(ResumeFailure.INCOMPATIBLE), resume(core))
        assertTrue(states.stateFile(StateSlot.AUTO).exists())
    }

    @Test
    fun aCorruptStateFallsBackWithoutLoss() {
        writeSave()
        val auto = writeAuto()
        val damaged = auto.copyOf().also { it[10] = (it[10] + 1).toByte() } // la suma ya no cuadra
        states.save(damaged, null, StateSlot.AUTO)
        val before = savesOnDisk()
        val core = FakeCore(position = 7)
        assertEquals(ExactContinuation.Outcome.Rejected(ResumeFailure.CORRUPT), resume(core))
        assertEquals(7, core.position)
        assertArrayEquals(ram, core.cartridgeRam())
        assertEquals(before, savesOnDisk())
        assertTrue("un estado dañado no se borra", states.stateFile(StateSlot.AUTO).exists())
    }

    @Test
    fun aTruncatedStateFallsBackWithoutLoss() {
        writeSave()
        val auto = writeAuto()
        states.save(auto.copyOf(auto.size / 2), null, StateSlot.AUTO)
        val before = savesOnDisk()
        val core = FakeCore(position = 7)
        assertEquals(ExactContinuation.Outcome.Rejected(ResumeFailure.CORRUPT), resume(core))
        assertEquals(7, core.position)
        assertEquals(before, savesOnDisk())
    }

    @Test
    fun aStateWithoutThePocketGbSignatureIsNotEvenLoaded() {
        writeSave()
        states.save("BASURA".toByteArray(), null, StateSlot.AUTO)
        val core = FakeCore()
        assertEquals(ExactContinuation.Outcome.Rejected(ResumeFailure.CORRUPT), resume(core))
        assertEquals(0, core.restored.size)
    }

    @Test
    fun aMissingStateIsReportedAsMissing() {
        writeSave()
        assertEquals(ExactContinuation.Outcome.Rejected(ResumeFailure.MISSING), resume(FakeCore()))
    }

    @Test
    fun anUnreadableStateIsReportedWithoutTouchingAnything() {
        writeSave()
        // Un estado más grande que el tope de lectura (entrada no confiable) no se lee.
        File(tmp.root, "states/$fingerprint").mkdirs()
        states.stateFile(StateSlot.AUTO).writeBytes("PGBS".toByteArray() + ByteArray(StateStore.MAX_STATE_BYTES))
        val before = savesOnDisk()
        val core = FakeCore()
        assertEquals(ExactContinuation.Outcome.Rejected(ResumeFailure.UNREADABLE), resume(core))
        assertEquals(0, core.restored.size)
        assertEquals(before, savesOnDisk())
        assertTrue(states.stateFile(StateSlot.AUTO).exists())
    }

    @Test
    fun onlyTheRtcFooterChangingStillResumes() {
        // MBC3 con reloj: el pie del RTC del estado difiere del de disco, pero la RAM es la misma (iOS INT-H1).
        writeSave()
        writeAuto(footer = byteArrayOf(9, 9, 9, 9))
        val core = FakeCore(footer = byteArrayOf(1, 1, 1, 1))
        assertEquals(ExactContinuation.Outcome.Resumed, resume(core))
        assertEquals("el reloj vuelve a la hora real", 1, core.clockSyncs)
    }

    @Test
    fun theLibraryOffersContinueOnlyForAFreshSignedState() {
        assertNull("sin estado", states.automaticEntry(saveDateMs = null))
        writeAuto(atMs = 2_000_000L)
        assertNotNull(states.automaticEntry(saveDateMs = null))
        assertNotNull(states.automaticEntry(saveDateMs = 2_000_000L)) // misma fecha: vale (resolución de 1 s)
        assertNull("partida más nueva", states.automaticEntry(saveDateMs = 2_000_001L))
        states.save("XXXX".toByteArray(), null, StateSlot.AUTO)
        assertNull("sin firma", states.automaticEntry(saveDateMs = null))
    }

    @Test
    fun theLibraryCheckNeverReadsTheThumbnail() {
        writeAuto()
        val reads = mutableListOf<String>()
        val counting = object : SaveFileOps by PosixSaveFileOps {
            override fun readBytes(file: File, limit: Int): ByteArray {
                reads += file.name
                return PosixSaveFileOps.readBytes(file, limit)
            }
        }
        val entry = StateStore(File(tmp.root, "states"), fingerprint, counting).automaticEntry(saveDateMs = null)
        assertNotNull(entry)
        assertNull(entry!!.thumbnail)
        assertEquals("solo la fecha y la firma: ni la miniatura ni el estado entero", emptyList<String>(), reads)
    }

    @Test
    fun onlyAnObsoleteStateIsDiscarded() {
        assertTrue(ExactContinuation.discardsTheState(ResumeFailure.NOT_CURRENT))
        for (reason in ResumeFailure.entries - ResumeFailure.NOT_CURRENT) {
            assertFalse("$reason no borra", ExactContinuation.discardsTheState(reason))
        }
    }
}

package com.joelbermudez.pocketgb.saves

import com.joelbermudez.pocketgb.emulator.CoreError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * N8: un estado de GBA de otra configuración (`StateConfig`: tipo de partida, reloj, BIOS o una EEPROM que ya medía 8 KiB
 * sin `.sav` de 8 KiB) no retoma, se conserva y deja «Jugar desde el inicio»; nunca escribe nada.
 */
class ExactContinuationGbaTest {
    private class Core(var ram: ByteArray, val rejects: CoreError?) : ResumableCore {
        var restored = 0
        var synced = 0
        override fun cartridgeRam() = ram.copyOf()
        override fun captureState() = byteArrayOf(1)
        override fun restoreState(state: ByteArray) {
            restored++
            if (state.contentEquals(byteArrayOf(9))) rejects?.let { throw it }
        }
        override fun syncClockToNow() { synced++ }
    }

    @Test
    fun aStateOfAnotherConfigurationIsIncompatibleAndKept() {
        assertEquals(ResumeFailure.INCOMPATIBLE, ExactContinuation.reasonFor(CoreError.StateConfig()))
        assertFalse(ExactContinuation.discardsTheState(ResumeFailure.INCOMPATIBLE))
        val core = Core(ByteArray(8192) { 0xFF.toByte() }, CoreError.StateConfig())
        val outcome = ExactContinuation.apply(core, byteArrayOf(9))
        assertEquals(ExactContinuation.Outcome.Rejected(ResumeFailure.INCOMPATIBLE), outcome)
        assertEquals("se repone el estado anterior", 2, core.restored)
        assertEquals("el reloj no se toca", 0, core.synced)
    }
}

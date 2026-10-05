package com.joelbermudez.pocketgb.saves

import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Ranuras de save state en disco (port de StateStoreTests de iOS, más tope de lectura y fallos). */
class StateStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store(root: File = File(tmp.root, "states")) = StateStore(root, "abc")
    private fun state(extra: Int = 100) = "PGBS".toByteArray() + ByteArray(extra) { 7 }

    @Test fun fourManualSlotsAndOneAuto() {
        assertEquals(4, StateSlot.MANUAL.size)
        assertEquals(1, StateSlot.entries.count { it == StateSlot.AUTO })
        assertEquals(5, StateSlot.entries.map { it.fileStem }.toSet().size)
        assertEquals(listOf("auto", "slot1", "slot2", "slot3", "slot4"), StateSlot.entries.map { it.fileStem })
    }

    @Test fun saveListLoadAndDelete() {
        val store = store()
        val state = state()
        store.save(state, byteArrayOf(0x89.toByte(), 0x50), StateSlot.MANUAL2)
        val entries = store.entries()
        assertEquals(setOf(StateSlot.MANUAL2), entries.keys)
        assertFalse(entries.getValue(StateSlot.MANUAL2).corrupt)
        assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50), entries.getValue(StateSlot.MANUAL2).thumbnail)
        assertArrayEquals(state, store.load(StateSlot.MANUAL2))
        // Sin temporales sueltos: la escritura es tmp + rename.
        assertTrue(store.directory.names().none { it.endsWith(".tmp") })
        assertEquals(File(tmp.root, "states/abc/slot2.state"), store.stateFile(StateSlot.MANUAL2))
        store.delete(StateSlot.MANUAL2)
        assertTrue(store.entries().isEmpty())
        assertTrue(store.directory.names().isEmpty())
    }

    @Test fun replacingASlotKeepsOnlyTheNewState() {
        val store = store()
        store.save("PGBS-1".toByteArray(), null, StateSlot.AUTO)
        store.save("PGBS-2".toByteArray(), null, StateSlot.AUTO)
        assertArrayEquals("PGBS-2".toByteArray(), store.load(StateSlot.AUTO))
    }

    @Test fun foreignFileIsListedAsCorrupt() {
        val store = store()
        store.save("basura".toByteArray(), null, StateSlot.MANUAL4)
        assertTrue(store.entries().getValue(StateSlot.MANUAL4).corrupt)
        store.save("PG".toByteArray(), null, StateSlot.MANUAL3) // más corto que la firma
        assertTrue(store.entries().getValue(StateSlot.MANUAL3).corrupt)
    }

    @Test fun replacingWithoutThumbnailRemovesTheOldOne() {
        val store = store()
        store.save(state(), byteArrayOf(1, 2, 3), StateSlot.MANUAL1)
        assertNotNull(store.entries().getValue(StateSlot.MANUAL1).thumbnail)
        store.save(state(5), null, StateSlot.MANUAL1)
        assertNull(store.entries().getValue(StateSlot.MANUAL1).thumbnail)
    }

    @Test fun failedThumbnailDoesNotLoseTheState() {
        val d = File(tmp.root, "t")
        // 1 = mkdirs, 2 = tmp del estado, 3 = rename, 4 = fsync dir, 5 = tmp de la miniatura
        val ops = FaultInjectingFileOps(failAt = 5)
        StateStore(d, ops).save(state(), byteArrayOf(1), StateSlot.AUTO)
        assertArrayEquals(state(), StateStore(d).load(StateSlot.AUTO))
    }

    @Test fun failureWhileWritingTheStateKeepsThePreviousOne() {
        val d = File(tmp.root, "f")
        StateStore(d).save(state(10), null, StateSlot.AUTO)
        val probe = FaultInjectingFileOps()
        StateStore(File(tmp.root, "probe"), probe).save(state(), null, StateSlot.AUTO)
        for (k in 1..probe.count) {
            try {
                // Fallar el borrado de la miniatura (mejor esfuerzo) no es un error de la escritura.
                StateStore(d, FaultInjectingFileOps(failAt = k)).save(state(99), null, StateSlot.AUTO)
            } catch (_: IOException) {
            }
            val now = StateStore(d).load(StateSlot.AUTO)
            assertTrue("estado parcial tras fallo $k", now.contentEquals(state(10)) || now.contentEquals(state(99)))
        }
    }

    @Test fun loadHasAReadLimit() {
        val store = store()
        store.save(state(), null, StateSlot.AUTO)
        store.stateFile(StateSlot.AUTO).writeBytes(ByteArray(StateStore.MAX_STATE_BYTES + 1))
        try { store.load(StateSlot.AUTO); fail("debía respetar el tope") } catch (_: IOException) {}
        // El listado no lee el archivo entero: solo la cabecera.
        assertTrue(store.entries().getValue(StateSlot.AUTO).corrupt)
    }

    @Test fun loadOfMissingSlotThrows() {
        try { store().load(StateSlot.MANUAL1); fail() } catch (_: IOException) {}
    }
}

package com.joelbermudez.pocketgb.saves

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * N8: los estados de GBA empiezan por `PGBA` (`gba_state_save`), no por `PGBS`. Como iOS (`StateStore.signatures`, bug de
 * N3 iOS «estados GBA Dañado»), las dos firmas son válidas: sin esto «Continuar» nunca se ofrecería en GBA.
 */
class StateStoreGbaTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store() = StateStore(File(tmp.root, "states"), "gba")

    @Test
    fun gbaStatesAreNotCorruptAndCanBeContinued() {
        val store = store()
        store.save("PGBA".toByteArray() + ByteArray(64) { 1 }, null, StateSlot.AUTO)
        store.save("PGBS".toByteArray() + ByteArray(64) { 1 }, null, StateSlot.MANUAL1)
        store.save("XXXX".toByteArray() + ByteArray(64) { 1 }, null, StateSlot.MANUAL2)
        val entries = store.entries()
        assertFalse(entries.getValue(StateSlot.AUTO).corrupt)
        assertFalse(entries.getValue(StateSlot.MANUAL1).corrupt)
        assertTrue(entries.getValue(StateSlot.MANUAL2).corrupt)
        assertNotNull("«Continuar» ofrece el AUTO de GBA", store.automaticEntry(saveDateMs = null))
        assertTrue(StateStore.isKnownSignature("PGBA".toByteArray()))
        assertFalse(StateStore.isKnownSignature("PGB".toByteArray()))
    }

    @Test
    fun aGbaAutoStateIsStillInvalidatedByANewerSave() {
        val store = store()
        store.save("PGBA".toByteArray() + ByteArray(8), null, StateSlot.AUTO)
        val auto = store.entry(StateSlot.AUTO, withThumbnail = false)!!
        assertNull(store.automaticEntry(saveDateMs = auto.dateMs + 60_000))
        assertEquals(auto.dateMs, store.automaticEntry(saveDateMs = auto.dateMs)!!.dateMs)
    }
}

package com.joelbermudez.pocketgb.saves

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** N6 · momentos y anillo «Antes de cargar» en disco (regla dura 6: atómico, índice como confirmación, migración sin pérdida). */
class MomentStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private var clock = 1_000L
    private var ids = 0

    private fun store(dir: File = File(tmp.root, "moments/fp"), ops: SaveFileOps = PosixSaveFileOps) =
        MomentStore(dir, ops, now = { clock++ }, newId = { "id${ids++}" })

    private fun state(v: Int) = "PGBS".toByteArray() + ByteArray(64) { v.toByte() }

    @Test fun aMomentKeepsStateCartridgeRamThumbnailConfigAndMetadata() {
        val s = store()
        val m = s.create(
            MomentStore.Capture(state(1), version(9, 32), byteArrayOf(1, 2)), "  Antes del jefe  ",
            config = mapOf("console" to "GB", "model" to "CGB"), playTimeMs = 90_000,
            tags = listOf("jefe", " Jefe ", "", "rpg"), collection = " Experimentos ", note = "nota",
        )
        assertEquals("Antes del jefe", m.name)
        assertEquals(listOf("jefe", "rpg"), m.tags)
        assertEquals("Experimentos", m.collection)
        val snap = s.snapshot()
        assertEquals(listOf(m), snap.moments)
        assertArrayEquals(state(1), s.loadState(MomentStore.Kind.MOMENT, m.id))
        assertArrayEquals(version(9, 32), s.loadSram(MomentStore.Kind.MOMENT, m.id))
        assertArrayEquals(byteArrayOf(1, 2), s.thumbnail(MomentStore.Kind.MOMENT, m.id))
        assertEquals(mapOf("console" to "GB", "model" to "CGB"), snap.moments.single().config)
        assertEquals(90_000L, snap.moments.single().playTimeMs)
        assertTrue(s.directory.names().none { it.endsWith(".tmp") })
        // Otra instancia (reapertura) lee lo mismo.
        assertEquals(snap, store().snapshot())
    }

    @Test fun editingAndDeletingNeverTouchesOtherMoments() {
        val s = store()
        val a = s.create(MomentStore.Capture(state(1), null, null), "A")
        val b = s.create(MomentStore.Capture(state(2), version(2), null), "B")
        val edited = s.update(a.id, "A2", listOf("x"), "Principal", "hola")
        assertEquals("A2", edited.name)
        assertEquals("Principal", s.snapshot().find(MomentStore.Kind.MOMENT, a.id)!!.collection)
        s.delete(MomentStore.Kind.MOMENT, a.id)
        assertEquals(listOf(b.id), s.snapshot().moments.map { it.id })
        assertFalse(s.stateFile(MomentStore.Kind.MOMENT, a.id).exists())
        assertArrayEquals(state(2), s.loadState(MomentStore.Kind.MOMENT, b.id))
        assertThrows(NoSuchElementException::class.java) { s.update(a.id, "x", emptyList(), null, "") }
    }

    @Test fun theBeforeLoadRingKeepsTheThreeNewestOutsideTheBackups() {
        val s = store()
        val pushed = (1..5).map { s.pushBeforeLoad(MomentStore.Capture(state(it), version(it), null), "M$it") }
        val ring = s.snapshot().beforeLoad
        assertEquals(MomentStore.RING_SIZE, ring.size)
        assertEquals(listOf("M5", "M4", "M3"), ring.map { it.name })
        // Los expulsados no dejan archivos; los que quedan, sí.
        for (old in pushed.take(2)) assertFalse(s.stateFile(MomentStore.Kind.BEFORE_LOAD, old.id).exists())
        assertArrayEquals(version(5), s.loadSram(MomentStore.Kind.BEFORE_LOAD, ring.first().id))
        assertTrue("el anillo no son momentos", s.snapshot().moments.isEmpty())
    }

    @Test fun orphanFilesFromAnUnconfirmedWriteAreRemovedButConfirmedOnesStay() {
        val s = store()
        val kept = s.create(MomentStore.Capture(state(1), version(1), null), "Bueno")
        // Un momento cuyo índice nunca se escribió (muerte entre los archivos y el índice) y un temporal suelto.
        File(s.directory, "m-zombie.state").writeBytes(state(7))
        File(s.directory, "m-zombie.sav").writeBytes(version(7))
        File(s.directory, "b-zz.png.tmp").writeBytes(byteArrayOf(1))
        File(s.directory, "index.json.tmp").writeBytes(byteArrayOf(1))
        s.recoverOrphans()
        assertEquals(setOf("index.json", "m-${kept.id}.state", "m-${kept.id}.sav"), s.directory.names().toSet())
    }

    @Test fun aDamagedIndexIsSetAsideAndRebuiltFromTheFilesWithoutDeletingAnything() {
        val s = store()
        val m = s.create(MomentStore.Capture(state(1), version(3), null), "Uno")
        File(s.directory, "index.json").writeText("{ roto")
        assertThrows(java.io.IOException::class.java) { s.snapshot() }
        s.recoverOrphans()
        val rebuilt = s.snapshot().moments.single()
        assertEquals(m.id, rebuilt.id)
        assertTrue(rebuilt.hasState && rebuilt.hasSram)
        assertArrayEquals(version(3), s.loadSram(MomentStore.Kind.MOMENT, m.id))
        assertTrue("el índice dañado se conserva aparte", s.directory.names().any { it.startsWith("index.damaged-") })
    }

    @Test fun idsFromTheIndexCanNeverBecomePaths() {
        val s = store()
        assertThrows(IllegalArgumentException::class.java) { s.stateFile(MomentStore.Kind.MOMENT, "../../saves/x") }
        s.create(MomentStore.Capture(state(1), null, null), "ok")
        val text = File(s.directory, "index.json").readText().replace("\"id0\"", "\"../x\"")
        File(s.directory, "index.json").writeText(text)
        assertTrue("una entrada con id ajeno se ignora", s.snapshot().moments.isEmpty())
    }

    @Test fun slotsOneToFourAndRescueMigrateWithoutLoss() {
        val states = StateStore(File(tmp.root, "states"), "fp")
        val originals = mapOf(
            StateSlot.MANUAL1 to state(1), StateSlot.MANUAL3 to state(3), StateSlot.RESCUE to state(9),
        )
        for ((slot, bytes) in originals) states.save(bytes, byteArrayOf(slot.ordinal.toByte()), slot)
        File(states.directory, "rescue-123-abc.state").writeBytes(state(8))
        states.save(state(0), null, StateSlot.AUTO)
        val s = store()
        assertEquals(4, s.migrateSlots(states) { "N:$it" })
        val moments = s.snapshot().moments
        assertEquals(setOf("N:slot1", "N:slot3", "N:rescue", "N:rescue-123-abc"), moments.map { it.name }.toSet())
        for ((slot, bytes) in originals) {
            val m = moments.single { it.origin == slot.fileStem }
            assertArrayEquals("el estado de ${slot.fileStem} se conserva byte a byte", bytes, s.loadState(MomentStore.Kind.MOMENT, m.id))
            assertArrayEquals(byteArrayOf(slot.ordinal.toByte()), s.thumbnail(MomentStore.Kind.MOMENT, m.id))
            assertFalse(m.hasSram)
            assertFalse(states.stateFile(slot).exists())
        }
        assertTrue("el AUTO no se migra", states.stateFile(StateSlot.AUTO).exists())
        assertEquals(0, s.migrateSlots(states) { it })
        assertEquals(4, s.snapshot().moments.size)
    }

    @Test fun aMigrationInterruptedAfterTheIndexDoesNotDuplicate() {
        val states = StateStore(File(tmp.root, "states"), "fp")
        states.save(state(1), null, StateSlot.MANUAL2)
        val s = store()
        s.migrateSlots(states) { it }
        // Muerte entre la confirmación y el borrado: la ranura reaparece igual.
        states.save(state(1), null, StateSlot.MANUAL2)
        assertEquals(1, s.migrateSlots(states) { it })
        assertEquals(1, s.snapshot().moments.size)
        assertFalse(states.stateFile(StateSlot.MANUAL2).exists())
    }

    @Test fun aMigrationThatFailsKeepsTheSlot() {
        val states = StateStore(File(tmp.root, "states"), "fp")
        states.save(state(4), null, StateSlot.MANUAL4)
        val dir = File(tmp.root, "moments/fp")
        // Falla cada operación mutante, una a una: la ranura o el momento existen siempre (nunca ninguno).
        var failAt = 1
        while (true) {
            val ops = FaultInjectingFileOps(failAt = failAt)
            val ok = try {
                store(dir, ops).migrateSlots(states) { it }
                true
            } catch (_: java.io.IOException) {
                false
            }
            val s = store(dir)
            s.recoverOrphans()
            val migrated = s.snapshot().moments.any { it.origin == "slot4" }
            assertTrue("fallo en la operación $failAt: ni ranura ni momento", states.stateFile(StateSlot.MANUAL4).exists() || migrated)
            if (migrated) assertArrayEquals(state(4), s.loadState(MomentStore.Kind.MOMENT, s.snapshot().moments.single().id))
            if (ok && ops.count < failAt) break
            failAt++
            assertTrue(failAt < 40)
        }
        store(dir).migrateSlots(states) { it }
        assertFalse(states.stateFile(StateSlot.MANUAL4).exists())
        assertNotNull(store(dir).snapshot().moments.singleOrNull { it.origin == "slot4" })
    }

    @Test fun everyFailurePointWhileCreatingLeavesEitherNothingOrTheWholeMoment() {
        val dir = File(tmp.root, "moments/fp")
        var failAt = 1
        while (true) {
            dir.deleteRecursively()
            val ops = FaultInjectingFileOps(failAt = failAt)
            val created = try {
                store(dir, ops).create(MomentStore.Capture(state(5), version(5), byteArrayOf(7)), "X")
                true
            } catch (_: java.io.IOException) {
                false
            }
            val s = store(dir)
            s.recoverOrphans()
            val moments = s.snapshot().moments
            if (moments.isNotEmpty()) {
                val m = moments.single()
                assertArrayEquals(state(5), s.loadState(MomentStore.Kind.MOMENT, m.id))
                assertArrayEquals(version(5), s.loadSram(MomentStore.Kind.MOMENT, m.id))
            } else {
                assertTrue("sin momento no quedan restos: ${dir.names()}", dir.names().none { it.startsWith("m-") })
            }
            if (created && ops.count < failAt) break
            failAt++
            assertTrue(failAt < 40)
        }
    }
}

package com.joelbermudez.pocketgb.saves

import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SaveStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var dir: File
    private lateinit var store: SaveStore

    @Before fun setUp() {
        dir = File(tmp.newFolder(), "saves")
        store = SaveStore(dir, TEST_FP)
    }

    private fun backupCount() = (1..SaveStore.KEEP_BACKUPS).count { store.backupFile(it).exists() }

    // MARK: rutas y lectura

    @Test fun pathsFollowTheDocumentedLayout() {
        assertEquals(File(dir, "$TEST_FP.sav"), store.saveFile)
        assertEquals(File(dir, "backups/$TEST_FP.3.sav"), store.backupFile(3))
        assertEquals(File(dir, "$TEST_FP.mirror-history.json"), store.mirrorHistoryFile)
    }

    @Test fun loadReturnsNullWhenThereIsNoSaveAndRefusesAbsurdSizes() {
        assertNull(store.load())
        dir.mkdirs()
        store.saveFile.writeBytes(ByteArray(SaveStore.MAX_SAVE_BYTES + 1))
        try { store.load(); fail("debía rechazar un .sav enorme") } catch (_: IOException) {}
        // El archivo no se toca.
        assertEquals(SaveStore.MAX_SAVE_BYTES + 1L, store.saveFile.length())
    }

    // MARK: backups

    @Test fun addBackupDoesNotTouchCurrent() {
        store.save(bytes(1))
        val mtime = store.saveFile.lastModified()
        store.addBackup(bytes(8))
        assertArrayEquals(bytes(1), store.load())
        assertEquals(mtime, store.saveFile.lastModified())
        assertArrayEquals(bytes(8), store.backupFile(1).readBytes())
        for (i in 0 until 6) store.addBackup(bytes(20 + i))
        assertEquals(SaveStore.KEEP_BACKUPS, backupCount())
        assertArrayEquals(bytes(25), store.backupFile(1).readBytes())
        assertArrayEquals(bytes(1), store.load())
    }

    @Test fun addBackupDedupesWithoutShiftingTheHistory() {
        store.save(bytes(1))
        store.save(bytes(2)) // .1 = 1
        store.addBackup(bytes(7))
        repeat(5) { store.addBackup(bytes(7)) } // el mismo contenido, otra vez: no se añade
        store.addBackup(bytes(1)) // ya está entre los backups
        assertEquals(2, backupCount())
        assertArrayEquals(bytes(7), store.backupFile(1).readBytes())
        assertArrayEquals(bytes(1), store.backupFile(2).readBytes())
    }

    @Test fun backupsListsExistingOnesWithDates() {
        store.save(bytes(1)); store.save(bytes(2)); store.save(bytes(3))
        val list = store.backups()
        assertEquals(listOf(1, 2), list.map { it.index })
        assertTrue(list.all { it.dateMs != null })
    }

    @Test fun restoreBacksUpCurrentFirst() {
        store.save(bytes(1)); store.save(bytes(2)); store.save(bytes(3)) // actual 3, .1 = 2, .2 = 1
        store.restore(2)
        assertArrayEquals(bytes(1), store.load())
        assertArrayEquals(bytes(3), store.backupFile(1).readBytes()) // la actual no se pierde
        assertEquals(3, backupCount())
    }

    @Test fun restoreOfMissingBackupFailsAndKeepsCurrent() {
        store.save(bytes(1))
        try { store.restore(4); fail("no hay backup 4") } catch (_: IOException) {}
        assertArrayEquals(bytes(1), store.load())
    }

    // MARK: cuarentena

    @Test fun quarantineKeepsBytesWithUniqueNamesAndIsNeverRotatedOrDeleted() {
        store.save(bytes(9, 9, 9))
        store.quarantineCurrent(1_700_000_000L, "aaaaaaaa")
        store.quarantineCurrent(1_700_000_000L, "bbbbbbbb") // mismo segundo, sufijo distinto
        val q = File(dir, "backups").names().filter { it.contains(".wrong-size-") }
        assertEquals(listOf("$TEST_FP.wrong-size-1700000000-aaaaaaaa.sav", "$TEST_FP.wrong-size-1700000000-bbbbbbbb.sav"), q)
        for (name in q) assertArrayEquals(bytes(9, 9, 9), File(dir, "backups/$name").readBytes())
        // Ni la rotación ni addBackup ni restore las tocan.
        for (i in 0 until 12) store.save(version(i, 4))
        for (i in 0 until 12) store.addBackup(version(100 + i, 4))
        store.restore(3)
        for (name in q) assertArrayEquals(bytes(9, 9, 9), File(dir, "backups/$name").readBytes())
    }

    @Test fun quarantineDefaultsProduceDistinctNames() {
        store.save(bytes(1, 2))
        store.quarantineCurrent()
        store.quarantineCurrent()
        assertEquals(2, File(dir, "backups").names().count { it.contains(".wrong-size-") })
        assertArrayEquals(bytes(1, 2), store.load())
    }

    @Test fun quarantineWithoutSaveDoesNothing() {
        store.quarantineCurrent(1L, "00000000")
        assertFalse(File(dir, "backups").exists())
    }

    // MARK: recoverOrphans

    @Test fun recoverOrphansAcceptsEverySizeOfTheSet() {
        val sizes = SaveSizes.validSizes(hasRtc = true, sramBytes = 16)
        assertEquals(setOf(16, 64, 60), sizes)
        for (size in sizes) {
            val d = File(tmp.newFolder(), "s")
            val s = SaveStore(d, TEST_FP)
            d.mkdirs()
            File(d, "$TEST_FP.sav.tmp").writeBytes(ByteArray(size) { 5 })
            s.recoverOrphans(sizes)
            assertEquals(size.toLong(), s.saveFile.length())
            assertFalse(File(d, "$TEST_FP.sav.tmp").exists())
        }
        // Un tamaño fuera del conjunto se descarta.
        val d = File(tmp.newFolder(), "s")
        val s = SaveStore(d, TEST_FP)
        d.mkdirs()
        File(d, "$TEST_FP.sav.tmp").writeBytes(ByteArray(61))
        s.recoverOrphans(sizes)
        assertFalse(s.saveFile.exists())
        assertFalse(File(d, "$TEST_FP.sav.tmp").exists())
    }

    @Test fun recoverOrphansRemovesBackupTmpAndKeepsEverythingElse() {
        store.save(bytes(1)); store.save(bytes(2))
        File(dir, "backups/$TEST_FP.1.tmp").writeBytes(bytes(7))
        store.quarantineCurrent(5L, "cafebabe")
        store.recoverOrphans(setOf(1))
        assertFalse(File(dir, "backups/$TEST_FP.1.tmp").exists())
        assertArrayEquals(bytes(2), store.load())
        assertArrayEquals(bytes(1), store.backupFile(1).readBytes())
        assertTrue(File(dir, "backups/$TEST_FP.wrong-size-5-cafebabe.sav").exists())
    }

    @Test fun recoverOrphansOnEmptyDirectoryIsHarmless() {
        store.recoverOrphans(setOf(4))
        assertNull(store.load())
    }

    // MARK: tamaños

    @Test fun validSizesWithAndWithoutRtc() {
        assertEquals(setOf(8192), SaveSizes.validSizes(hasRtc = false, sramBytes = 8192))
        assertEquals(setOf(32768, 32816, 32812), SaveSizes.validSizes(hasRtc = true, sramBytes = 32768))
        assertEquals(setOf(48, 44), SaveSizes.validSizes(hasRtc = true, sramBytes = 0))
        assertEquals(emptySet<Int>(), SaveSizes.validSizes(hasRtc = false, sramBytes = 0))
    }

    // MARK: historial del espejo

    @Test fun pendingWriteAheadIsRecognizedWithoutDate() {
        val data = bytes(1, 2, 3, 4)
        assertFalse(store.recognizesOwnedMirror(data, null))
        store.recordMirrorAttempt(data)
        assertTrue(store.recognizesOwnedMirror(data, null))
        assertTrue(store.recognizesOwnedMirror(data, 5L))
        assertFalse(store.recognizesOwnedMirror(bytes(9), null))
    }

    @Test fun successfulWriteIsRecognizedOnlyWithItsExactDate() {
        val data = bytes(1, 2, 3, 4)
        store.recordMirrorAttempt(data)
        store.recordSuccessfulMirror(data, 1_000L)
        assertTrue(store.recognizesOwnedMirror(data, 1_000L))
        assertFalse("fecha distinta = restaurado a mano", store.recognizesOwnedMirror(data, 61_000L))
        assertFalse("sin fecha no prueba una escritura propia", store.recognizesOwnedMirror(data, null))
        // Ya no está pendiente: confirmada.
        assertEquals(emptyList<String>(), store.mirrorHistoryPending())
    }

    @Test fun successfulWithoutObservedDateNeverProvesOwnership() {
        val data = bytes(4, 3, 2, 1)
        store.recordMirrorAttempt(data)
        store.recordSuccessfulMirror(data, null)
        assertFalse(store.recognizesOwnedMirror(data, null))
        assertFalse(store.recognizesOwnedMirror(data, 10L))
    }

    @Test fun historyKeepsEightEntriesAndSurvivesANewStoreInstance() {
        for (i in 0 until 10) {
            store.recordMirrorAttempt(bytes(i))
            store.recordSuccessfulMirror(bytes(i), 100L + i)
        }
        val again = SaveStore(dir, TEST_FP)
        assertTrue(again.recognizesOwnedMirror(bytes(9), 109L))
        assertTrue(again.recognizesOwnedMirror(bytes(2), 102L))
        assertFalse("la más antigua se descartó", again.recognizesOwnedMirror(bytes(1), 101L))
    }

    @Test fun legacyHistoryWithoutDatesAndCorruptHistoryAreTolerated() {
        dir.mkdirs()
        store.mirrorHistoryFile.writeText("""{"successful":["abc","def"],"pending":[]}""")
        assertFalse(store.recognizesOwnedMirror(bytes(1), 1L))
        store.recordMirrorAttempt(bytes(1))
        assertTrue(store.recognizesOwnedMirror(bytes(1), null))
        store.mirrorHistoryFile.writeText("no es json")
        assertFalse(store.recognizesOwnedMirror(bytes(1), 1L))
        store.recordMirrorAttempt(bytes(2)) // se reescribe sin lanzar
        assertTrue(store.recognizesOwnedMirror(bytes(2), null))
    }
}

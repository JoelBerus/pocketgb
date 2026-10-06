package com.joelbermudez.pocketgb.saves

import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Correcciones de la 1ª vuelta de la auditoría de A5: H5 de Codex y H6, H7, H8, H11 de Opus. */
class SaveHardeningTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun dir() = File(tmp.newFolder(), "saves")
    private val sizes = setOf(4)

    // ---- Codex H5: fsync de directorio

    @Test fun syncDirectoryToleratesOnlyDocumentedUnsupportedErrors() {
        val d = tmp.newFolder()
        for (message in listOf("Invalid argument", "Operation not supported", "Read-only file system")) {
            DirectorySync.sync(d, force = { throw IOException(message) }) // no lanza
        }
        DirectorySync.sync(d, force = { throw java.nio.file.AccessDeniedException(d.path) })
    }

    @Test fun aRealIoErrorOnDirectorySyncIsPropagatedAsADurabilityFailure() {
        val d = tmp.newFolder()
        val error = assertThrows(IOException::class.java) {
            DirectorySync.sync(d, force = { throw IOException("Input/output error") })
        }
        assertEquals("Input/output error", error.message)
        // Fallo real, sin inyección: el directorio no existe.
        assertThrows(IOException::class.java) { PosixSaveFileOps.syncDirectory(File(d, "no-existe/nada")) }
        // Y uno que existe funciona.
        PosixSaveFileOps.syncDirectory(d)
    }

    @Test fun aDirectorySyncFailureMakesTheSaveFailButTheNextTryConverges() {
        // Cuenta las operaciones de dos guardados seguidos; la última es el fsync del directorio tras instalar.
        val counting = FaultInjectingFileOps()
        val countingStore = SaveStore(dir(), TEST_FP, counting)
        countingStore.save(version(1))
        countingStore.save(version(2))
        val total = counting.count
        assertEquals("syncDirectory:saves", counting.log.last())

        val failing = FaultInjectingFileOps(failAt = total)
        val dirFile = dir()
        val store = SaveStore(dirFile, TEST_FP, failing)
        store.save(version(1))
        assertThrows(FaultInjectingFileOps.Injected::class.java) { store.save(version(2)) }
        assertArrayEquals("el renombrado ya estaba hecho: el contenido es el nuevo", version(2), SaveStore(dirFile, TEST_FP).load())
        failing.failAt = null
        assertFalse("reintentar el mismo contenido no reescribe nada", store.save(version(2)))
    }

    // ---- Opus H8: .sav local demasiado grande o ilegible

    @Test fun anOversizeLocalSaveGoesToQuarantineInsteadOfAPermanentOpenError() {
        val d = dir().also { it.mkdirs() }
        val store = SaveStore(d, TEST_FP)
        val big = ByteArray(SaveStore.MAX_SAVE_BYTES + 1) { 3 }
        store.saveFile.writeBytes(big)
        val mirror = FakeSaveMirror(SaveMirror.Snapshot.Read(version(4), 1L)) // espejo válido y más viejo → igual la local no vale

        val outcome = SaveOpening.prepare(store, mirror, mirror.snapshot(), sizes, registry = MirrorChannelRegistry())

        assertArrayEquals(version(4), outcome.data)
        assertEquals(SaveLoadWarning.LocalQuarantined, outcome.warning)
        val quarantined = File(d, "backups").listFiles { f -> f.name.contains("wrong-size-") }!!.single()
        assertEquals("la cuarentena conserva el archivo entero", big.size.toLong(), quarantined.length())
        assertArrayEquals(version(4), store.load())
        assertFalse("lo apartado no se rota a .1", store.backupFile(1).exists())
    }

    @Test fun anOversizeLocalSaveWithoutMirrorIsLeftUntouchedAndOpensWithoutSaving() {
        val d = dir().also { it.mkdirs() }
        val store = SaveStore(d, TEST_FP)
        val big = ByteArray(SaveStore.MAX_SAVE_BYTES + 10) { 5 }
        store.saveFile.writeBytes(big)
        val outcome = SaveOpening.prepare(store, null, SaveMirror.Snapshot.Absent, sizes, registry = MirrorChannelRegistry())
        assertNull(outcome.data)
        assertNull("esta sesión no guarda", outcome.target)
        assertEquals(SaveLoadWarning.LocalWrongSize, outcome.warning)
        assertEquals(big.size.toLong(), store.saveFile.length())
    }

    // ---- Opus H7: restore valida y no rota lo apartado

    @Test fun restoreRejectsABackupWithAnInvalidSizeAndTouchesNothing() {
        val d = dir()
        val store = SaveStore(d, TEST_FP)
        store.save(version(1, 4))
        store.save(version(2, 4)) // actual 2, backup .1 = 1
        File(d, "backups/$TEST_FP.2.sav").writeBytes(version(9, 7)) // basura de tamaño inválido
        val error = assertThrows(SaveStore.InvalidBackupException::class.java) { store.restore(2, sizes) }
        assertEquals(7, error.size)
        assertArrayEquals(version(2), store.load())
        assertArrayEquals(version(1), store.backupFile(1).readBytes())
    }

    @Test fun restoreOverAnInvalidCurrentDoesNotRotateItIntoBackupOne() {
        val d = dir()
        val store = SaveStore(d, TEST_FP)
        store.save(version(1, 4))
        store.save(version(2, 4))
        store.recoverOrphans(sizes) // fija los tamaños conocidos (como al abrir el juego)
        // La actual pasó a ser un archivo de tamaño inválido (ya apartado en cuarentena al abrir).
        store.saveFile.writeBytes(version(7, 9))
        val backupOneBefore = store.backupFile(1).readBytes()
        store.restore(1, sizes)
        assertArrayEquals(backupOneBefore, store.load())
        assertTrue("el .1 sigue siendo una partida válida", store.backupFile(1).readBytes().size in sizes)
        assertTrue((1..5).all { n -> !store.backupFile(n).exists() || store.backupFile(n).length().toInt() in sizes })
    }

    @Test fun savesBrowserRestoreUsesTheSizesRecordedAtOpening() {
        val d = dir()
        val store = SaveStore(d, TEST_FP)
        store.save(version(1, 4))
        store.save(version(2, 4))
        File(d, "backups/$TEST_FP.3.sav").writeBytes(version(9, 6))
        SavesIndex(d).record(TEST_FP, "Juego", "juego.gb", sizes)
        assertEquals(listOf(4), SavesIndex(d).load()[TEST_FP]!!.validSizes)
        assertThrows(SaveStore.InvalidBackupException::class.java) { SavesBrowser(d).restore(TEST_FP, 3, null) }
        SavesBrowser(d).restore(TEST_FP, 1, null) // uno válido sí
        assertArrayEquals(version(1), store.load())
    }

    // ---- Opus H6 y H11: rescate y temporales de estados

    @Test fun aSecondRescueNeverOverwritesTheFirstOne() {
        val states = StateStore(File(tmp.newFolder(), "states"), TEST_FP)
        states.saveRescue(version(1, 8), null)
        assertTrue(states.hasRescue())
        states.saveRescue(version(2, 8), null)
        assertArrayEquals("la última queda en la ranura de rescate", version(2, 8), states.load(StateSlot.RESCUE))
        val archived = states.directory.listFiles { f -> f.name.startsWith("rescue-") && f.name.endsWith(".state") }!!
        assertEquals(1, archived.size)
        assertArrayEquals("la primera se conserva intacta", version(1, 8), archived.single().readBytes())
    }

    @Test fun stateRecoverOrphansRemovesTmpStatesAndThumbnails() {
        val states = StateStore(File(tmp.newFolder(), "states"), TEST_FP)
        states.save(bytes(0x50, 0x47, 0x42, 0x53, 1), null, StateSlot.MANUAL1)
        File(states.directory, "slot2.state.tmp").writeBytes(byteArrayOf(1))
        File(states.directory, "slot2.png.tmp").writeBytes(byteArrayOf(1))
        states.recoverOrphans()
        assertEquals(listOf("slot1.state"), states.directory.names())
    }

    @Test fun indexRecoverOrphansRemovesItsTmp() {
        val d = dir().also { it.mkdirs() }
        File(d, "index.json.tmp").writeBytes(byteArrayOf(1))
        SavesIndex(d).recoverOrphans()
        assertFalse(File(d, "index.json.tmp").exists())
        assertNotNull(d)
    }
}

package com.joelbermudez.pocketgb.saves

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Invariantes de SPEC §5.2 y docs/04 §Saves (port de AtomicFileTests de iOS) sobre un directorio temporal. */
class AtomicFileTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var dir: File
    private lateinit var store: SaveStore

    @Before fun setUp() {
        dir = tmp.newFolder("saves")
        store = SaveStore(dir, TEST_FP)
    }

    private fun tmpOf(file: File) = File(file.path + ".tmp")

    @Test fun firstSaveCreatesFileWithoutBackups() {
        store.save(bytes(1, 2, 3))
        assertArrayEquals(bytes(1, 2, 3), store.saveFile.readBytes())
        assertFalse(store.backupFile(1).exists())
    }

    @Test fun replaceKeepsPreviousInBackupOne() {
        store.save(bytes(1))
        store.save(bytes(2))
        assertArrayEquals(bytes(2), store.saveFile.readBytes())
        assertArrayEquals(bytes(1), store.backupFile(1).readBytes())
    }

    @Test fun sameContentDoesNotRotate() {
        store.save(bytes(1))
        store.save(bytes(2))
        store.save(bytes(2))
        assertArrayEquals(bytes(1), store.backupFile(1).readBytes())
        assertFalse(store.backupFile(2).exists())
    }

    @Test fun rotatesAtMostFiveBackups() {
        for (i in 0 until 8) store.save(bytes(i))
        assertArrayEquals(bytes(7), store.saveFile.readBytes())
        assertArrayEquals(bytes(6), store.backupFile(1).readBytes())
        assertArrayEquals(bytes(2), store.backupFile(5).readBytes())
        assertFalse(store.backupFile(6).exists())
    }

    @Test fun orphanTmpWithoutSaveIsInstalled() {
        val t = tmpOf(store.saveFile)
        t.writeBytes(bytes(9, 9))
        store.recoverOrphans(setOf(2))
        assertArrayEquals(bytes(9, 9), store.saveFile.readBytes())
        assertFalse(t.exists())
    }

    @Test fun orphanTmpWithExistingSaveIsDeleted() {
        store.save(bytes(1, 1))
        val t = tmpOf(store.saveFile)
        t.writeBytes(bytes(9, 9))
        store.recoverOrphans(setOf(2))
        assertArrayEquals(bytes(1, 1), store.saveFile.readBytes())
        assertFalse(t.exists())
    }

    @Test fun orphanTmpWithWrongSizeIsDeleted() {
        val t = tmpOf(store.saveFile)
        t.writeBytes(bytes(9))
        store.recoverOrphans(setOf(2))
        assertFalse(store.saveFile.exists())
        assertFalse(t.exists())
    }

    /** Port de `failureAfterStepKeepsPreviousSave`: fallo tras el paso 2, 3 y 4 (ver `AtomicSaveFaultInjectionTest` para cada operación). */
    @Test fun failureAfterEachStepKeepsPreviousSave() {
        for (failAt in 1..3) { // 1 = tmp, 2 = mkdirs, 3 = primera rotación (según el orden de operaciones)
            val d = tmp.newFolder("step$failAt")
            val s = SaveStore(d, TEST_FP)
            s.save(bytes(1, 1))
            s.save(bytes(2, 2)) // hay un .1 para que la rotación (paso 3) haga algo
            val faulty = SaveStore(d, TEST_FP, FaultInjectingFileOps(failAt = failAt))
            try {
                faulty.save(bytes(3, 3))
                throw AssertionError("debía fallar en la operación $failAt")
            } catch (_: FaultInjectingFileOps.Injected) {
            }
            assertArrayEquals(bytes(2, 2), s.saveFile.readBytes())
            s.recoverOrphans(setOf(2)) // el .tmp huérfano se descarta porque el .sav existe
            assertArrayEquals(bytes(2, 2), s.saveFile.readBytes())
            assertFalse(tmpOf(s.saveFile).exists())
        }
    }

    @Test fun interruptedFirstSaveIsRecoveredOnLaunch() {
        // Primera partida: se escribe el .tmp completo y muere antes del rename final.
        val ops = FaultInjectingFileOps(failAt = 2) // 1 = tmp, 2 = rename final
        try {
            SaveStore(dir, TEST_FP, ops).save(bytes(5, 5))
            throw AssertionError("debía fallar")
        } catch (_: FaultInjectingFileOps.Injected) {
        }
        assertFalse(store.saveFile.exists())
        store.recoverOrphans(setOf(2))
        assertArrayEquals(bytes(5, 5), store.saveFile.readBytes())
    }

    @Test fun sevenSavesKeepBackupsOneToFive() {
        for (i in 0 until 7) store.save(bytes(i))
        for (n in 1..5) assertArrayEquals(bytes(6 - n), store.backupFile(n).readBytes())
        assertFalse(store.backupFile(6).exists())
    }

    @Test fun writerReportsWhetherItWrote() {
        val writer = AtomicSaveWriter(PosixSaveFileOps)
        assertTrue(writer.write(bytes(1), store.saveFile, store::backupFile))
        assertFalse(writer.write(bytes(1), store.saveFile, store::backupFile))
        assertEquals(listOf(TEST_FP + ".sav"), dir.names().filter { it.endsWith(".sav") })
    }

    /** Muerte entre el paso 4 y el 5: el `.1` ya es copia del actual y la siguiente escritura no lo duplica. */
    @Test fun interruptedAfterBackupDoesNotDuplicateOnNextWrite() {
        for (i in 1..3) store.save(bytes(i, i)) // actual 3, .1 = 2, .2 = 1
        // Operaciones: 1 tmp, 2 rotación .2→.3? (.1→.2 y .2→.3), ... hasta el rename final: se falla ahí.
        val probe = FaultInjectingFileOps()
        SaveStore(File(tmp.newFolder(), "p").also { d ->
            val s = SaveStore(d, TEST_FP); for (i in 1..3) s.save(bytes(i, i))
        }, TEST_FP, probe).save(bytes(4, 4))
        val renameFinal = probe.log.indexOf("atomicReplace:$TEST_FP.sav.tmp->$TEST_FP.sav") + 1
        try {
            SaveStore(dir, TEST_FP, FaultInjectingFileOps(failAt = renameFinal)).save(bytes(4, 4))
            throw AssertionError("debía fallar")
        } catch (_: FaultInjectingFileOps.Injected) {
        }
        assertArrayEquals(bytes(3, 3), store.saveFile.readBytes())
        assertArrayEquals(bytes(3, 3), store.backupFile(1).readBytes()) // copia del actual
        store.recoverOrphans(setOf(2))
        store.save(bytes(4, 4))
        assertArrayEquals(bytes(4, 4), store.saveFile.readBytes())
        assertArrayEquals(bytes(3, 3), store.backupFile(1).readBytes())
        assertArrayEquals(bytes(2, 2), store.backupFile(2).readBytes())
        assertArrayEquals(bytes(1, 1), store.backupFile(3).readBytes())
        assertFalse(store.backupFile(4).exists())
    }
}

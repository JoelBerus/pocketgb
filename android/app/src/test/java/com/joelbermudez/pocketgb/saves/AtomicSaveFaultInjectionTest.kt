package com.joelbermudez.pocketgb.saves

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Regla dura 6: se inyecta un fallo en CADA operación individual de la escritura (tmp parcial, mkdirs,
 * cada rename de la rotación, `.1.tmp`, rename a `.1`, rename final, fsync de directorio) y tras cada una
 * se comprueba el invariante: el `.sav` anterior intacto o el nuevo completo, nunca parcial; y todos los
 * backups son versiones completas anteriores.
 */
class AtomicSaveFaultInjectionTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun isWholeVersion(data: ByteArray, known: Set<Int>) =
        data.size == 4 && data.all { it == data[0] } && data[0].toInt() in known

    private fun checkInvariant(store: SaveStore, known: Set<Int>, allowed: Set<Int>, label: String) {
        if (store.saveFile.exists()) {
            val sav = store.saveFile.readBytes()
            assertTrue("$label: .sav parcial o desconocido ${sav.toList()}", isWholeVersion(sav, known))
            assertTrue("$label: .sav ${sav[0]} no es ninguno de $allowed", sav[0].toInt() in allowed)
        } else {
            assertTrue("$label: sin .sav solo vale si nunca hubo uno", allowed.isEmpty())
        }
        for (n in 1..SaveStore.KEEP_BACKUPS) {
            val f = store.backupFile(n)
            if (f.exists()) assertTrue("$label: backup $n parcial", isWholeVersion(f.readBytes(), known))
        }
    }

    /** Prepara `prior` guardados (versiones 1..prior) y devuelve la carpeta. */
    private fun prepare(prior: Int, name: String): File {
        val d = File(tmp.newFolder(name), "saves").also { it.mkdirs() }
        val s = SaveStore(d, TEST_FP)
        for (v in 1..prior) s.save(version(v))
        return d
    }

    private fun scenario(prior: Int) {
        val newVersion = prior + 1
        val known = (1..newVersion).toSet()
        // 1) Ejecución sin fallo: cuenta las operaciones y confirma que se cubren las esperadas.
        val countingOps = FaultInjectingFileOps()
        SaveStore(prepare(prior, "count$prior"), TEST_FP, countingOps).save(version(newVersion))
        val total = countingOps.count
        assertTrue("el escritor no hace ninguna operación", total >= 2)
        val log = countingOps.log
        assertTrue(log.contains("writeSynced:$TEST_FP.sav.tmp"))
        assertTrue(log.contains("atomicReplace:$TEST_FP.sav.tmp->$TEST_FP.sav"))
        assertTrue(log.contains("syncDirectory:saves"))
        if (prior >= 1) {
            // El temporal del backup es único (`<huella>.1.<rand8>.tmp`): nunca lo comparten dos escritores.
            assertTrue(log.any { Regex("writeSynced:$TEST_FP\\.1\\.[0-9a-f]{8}\\.tmp").matches(it) })
            assertTrue(log.any { Regex("atomicReplace:$TEST_FP\\.1\\.[0-9a-f]{8}\\.tmp->$TEST_FP\\.1\\.sav").matches(it) })
            if (prior == 1) assertTrue(log.contains("mkdirs:backups")) // la primera rotación crea la carpeta
        }
        if (prior >= 6) { // cinco backups llenos: cuatro renames de rotación
            assertEquals(4, log.count { it.startsWith("atomicReplace:") && it.contains(".sav->") && !it.contains(".sav.tmp") && !it.contains(".tmp->") })
        }
        // 2) Fallo en cada una de las operaciones.
        for (k in 1..total) {
            val label = "prior=$prior, fallo en la operación $k (${log[k - 1]})"
            val d = prepare(prior, "fail${prior}_$k")
            val faulty = FaultInjectingFileOps(failAt = k)
            try {
                SaveStore(d, TEST_FP, faulty).save(version(newVersion))
                fail("$label: debía fallar")
            } catch (_: FaultInjectingFileOps.Injected) {
            }
            val store = SaveStore(d, TEST_FP)
            val allowed = if (prior == 0) setOf(newVersion) else setOf(prior, newVersion)
            // Tras el fallo, antes de recuperar: nunca parcial. (Primera partida: sin .sav o el nuevo.)
            if (prior == 0 && !store.saveFile.exists()) {
                checkInvariant(store, known, emptySet(), label)
            } else {
                checkInvariant(store, known, allowed, label)
            }
            // Arranque siguiente: recuperación y reintento.
            store.recoverOrphans(setOf(4))
            if (prior == 0) {
                // El tmp completo se instala si falta el .sav; uno parcial (2 bytes) se descarta.
                if (store.saveFile.exists()) checkInvariant(store, known, setOf(newVersion), "$label (recuperado)")
            } else {
                checkInvariant(store, known, allowed, "$label (recuperado)")
            }
            for (sub in listOf(d, File(d, "backups"))) {
                assertTrue("$label: quedan .tmp en ${sub.name}: ${sub.names()}", sub.names().none { it.endsWith(".tmp") })
            }
            store.save(version(newVersion))
            assertEquals("$label: el reintento no dejó la versión nueva", newVersion, store.saveFile.readBytes()[0].toInt())
            assertTrue((1..SaveStore.KEEP_BACKUPS).count { store.backupFile(it).exists() } <= 5)
            checkInvariant(store, known, setOf(newVersion), "$label (reintento)")
        }
    }

    @Test fun firstSave() = scenario(0)
    @Test fun replaceWithOnlyOneSave() = scenario(1)
    @Test fun replaceWithPartialBackups() = scenario(3)
    @Test fun replaceWithFullRotation() = scenario(7)

    @Test fun addBackupAndQuarantineFailuresNeverTouchTheCurrentSave() {
        val d = prepare(3, "extras")
        val before = File(d, "$TEST_FP.sav").readBytes()
        for (op in 1..8) {
            val s = SaveStore(d, TEST_FP, FaultInjectingFileOps(failAt = op))
            try { s.addBackup(version(50 + op)) } catch (_: FaultInjectingFileOps.Injected) {}
            try { s.quarantineCurrent(1L, "deadbeef") } catch (_: FaultInjectingFileOps.Injected) {}
            assertTrue(before.contentEquals(File(d, "$TEST_FP.sav").readBytes()))
        }
    }
}

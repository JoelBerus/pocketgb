package com.joelbermudez.pocketgb.saves

import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Bloquea la primera escritura de un temporal de backup `.1` hasta que el test la suelte. */
private class BackupGateOps : SaveFileOps by PosixSaveFileOps {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    @Volatile private var armed = true
    private val backupTmp = Regex("""[0-9a-f]+\.1\.([0-9a-f]+\.)?tmp""")

    override fun writeSynced(file: File, data: ByteArray) {
        if (armed && backupTmp.matches(file.name)) {
            armed = false
            entered.countDown()
            release.await(30, TimeUnit.SECONDS)
        }
        PosixSaveFileOps.writeSynced(file, data)
    }
}

/**
 * A5 auditoría Opus H1: el hilo del espejo (`addBackup`) y el de guardado/apertura/restaurar mutan `backups/` a
 * la vez. Con un lock por huella compartido entre instancias y temporales únicos ninguna interfoliación pierde
 * un contenido.
 */
class SaveConcurrencyTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun allCopies(store: SaveStore): List<ByteArray> =
        listOfNotNull(store.load()) + (1..SaveStore.KEEP_BACKUPS).mapNotNull { n ->
            store.backupFile(n).takeIf { it.exists() }?.readBytes()
        }

    @Test fun mirrorBackupInterleavedWithALocalSaveLosesNothing() {
        val dir = File(tmp.newFolder(), "saves")
        val seed = SaveStore(dir, TEST_FP)
        seed.save(version(1))
        seed.save(version(2)) // actual = 2, backup .1 = 1
        val gate = BackupGateOps()
        val mirrorThreadStore = SaveStore(dir, TEST_FP, gate) // el espejo usa su propia instancia (misma huella)
        val saveThreadStore = SaveStore(dir, TEST_FP, PosixSaveFileOps)

        val external = version(0x55)
        val failure = AtomicReference<Throwable?>()
        val mirrorThread = thread { runCatching { mirrorThreadStore.addBackup(external) }.onFailure { failure.set(it) } }
        assertTrue("el espejo llegó a escribir su temporal", gate.entered.await(5, TimeUnit.SECONDS))

        // El hilo de guardado entra MIENTRAS el espejo está a mitad de `addBackup`.
        val saveStarted = CountDownLatch(1)
        val saveThread = thread {
            saveStarted.countDown()
            runCatching { saveThreadStore.save(version(3)) }.onFailure { failure.set(it) }
        }
        assertTrue(saveStarted.await(5, TimeUnit.SECONDS))
        Thread.sleep(200) // da tiempo a que entre en su rotación (sin lock) o se quede esperando (con lock)
        gate.release.countDown()
        mirrorThread.join(10_000)
        saveThread.join(10_000)

        assertNull("ningún hilo falló: ${failure.get()}", failure.get())
        val copies = allCopies(SaveStore(dir, TEST_FP))
        for ((label, v) in listOf("el contenido externo" to external, "la partida 2" to version(2), "la partida 1" to version(1), "la nueva" to version(3))) {
            assertTrue("$label se conservó entre la actual y los backups", copies.any { it.contentEquals(v) })
        }
        assertArrayEquals(version(3), SaveStore(dir, TEST_FP).load())
        assertTrue("sin temporales", File(dir, "backups").names().none { it.endsWith(".tmp") })
    }

    @Test fun manyInterleavedBackupsAndSavesNeverFailNorLeaveTemporaries() {
        val dir = File(tmp.newFolder(), "saves")
        val a = SaveStore(dir, TEST_FP)
        val b = SaveStore(dir, TEST_FP)
        a.save(version(1))
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val start = CountDownLatch(1)
        val t1 = thread { start.await(); repeat(150) { i -> runCatching { a.addBackup(version(100 + i % 100, 8)) }.onFailure(errors::add) } }
        val t2 = thread { start.await(); repeat(150) { i -> runCatching { b.save(version(2 + i % 90, 4)) }.onFailure(errors::add) } }
        start.countDown()
        t1.join(60_000); t2.join(60_000)
        assertTrue("sin errores: ${errors.firstOrNull()}", errors.isEmpty())
        assertTrue(File(dir, "backups").names().none { it.endsWith(".tmp") })
        assertTrue((1..5).count { a.backupFile(it).exists() } <= 5)
    }

    @Test fun recoverOrphansRemovesEveryTemporaryOfThatSaveButNotOthers() {
        val dir = File(tmp.newFolder(), "saves")
        val store = SaveStore(dir, TEST_FP)
        store.save(version(1))
        val backups = File(dir, "backups").also { it.mkdirs() }
        val orphans = listOf(
            "$TEST_FP.1.tmp", "$TEST_FP.1.abcd1234.tmp", "$TEST_FP.wrong-size-17-deadbeef.sav.tmp",
        )
        orphans.forEach { File(backups, it).writeBytes(version(9)) }
        val other = File(backups, "ffffffffffffffffffffffffffffffff.1.abcd1234.tmp").also { it.writeBytes(version(8)) }
        File(dir, "$TEST_FP.mirror-history.json.tmp").writeBytes(byteArrayOf(1))
        store.recoverOrphans(setOf(4))
        orphans.forEach { assertFalse("$it se borra", File(backups, it).exists()) }
        assertFalse(File(dir, "$TEST_FP.mirror-history.json.tmp").exists())
        assertTrue("el de otra huella no se toca", other.exists())
    }
}

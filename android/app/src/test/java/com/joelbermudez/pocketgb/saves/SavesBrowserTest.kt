package com.joelbermudez.pocketgb.saves

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SavesBrowserTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun dir() = File(tmp.newFolder(), "saves")

    @Test fun listsGamesByTitleWithTheirBackups() {
        val d = dir()
        val store = SaveStore(d, TEST_FP)
        store.save(version(1))
        store.save(version(2))
        store.save(version(3))
        SavesIndex(d).record(TEST_FP, "POKEMON", "Pokemon.gb")
        val other = SaveStore(d, "ff".repeat(16))
        other.save(version(9))

        val games = SavesBrowser(d).list()
        assertEquals(2, games.size)
        val known = games.first { it.fingerprint == TEST_FP }
        assertEquals("POKEMON", known.title)
        assertEquals("Pokemon.gb", known.fileName)
        assertEquals(listOf(1, 2), known.backups.map { it.index })
        assertNull(games.first { it.fingerprint != TEST_FP }.title)
    }

    @Test fun restoreBacksUpTheCurrentSaveFirstSoNothingIsLost() {
        val d = dir()
        val store = SaveStore(d, TEST_FP)
        store.save(version(1))
        store.save(version(2)) // .1 = v1
        store.save(version(3)) // .1 = v2, .2 = v1
        SavesBrowser(d).restore(TEST_FP, 2, openFingerprint = null) // restaura v1
        assertArrayEquals(version(1), store.load())
        val contents = store.backups().map { store.backupFile(it.index).readBytes().first().toInt() }
        assertTrue("la actual (v3) quedó como backup: $contents", 3 in contents)
        assertTrue(File(d, "backups").list()!!.none { it.endsWith(".tmp") })
    }

    @Test fun restoreIsRefusedWhileThatGameIsOpen() {
        val d = dir()
        val store = SaveStore(d, TEST_FP)
        store.save(version(1))
        store.save(version(2))
        assertThrows(IllegalStateException::class.java) { SavesBrowser(d).restore(TEST_FP, 1, openFingerprint = TEST_FP) }
        assertArrayEquals(version(2), store.load())
    }

    @Test fun restoreIsRefusedWhileTheFingerprintHasAnOwner() {
        val d = dir()
        val store = SaveStore(d, TEST_FP)
        store.save(version(1))
        store.save(version(2))
        val ownership = FingerprintOwnership()
        val browser = SavesBrowser(d, ownership = ownership)
        val lease = ownership.tryAcquire(TEST_FP, "sesión huérfana")!! // guardado pendiente
        val error = assertThrows(SavePendingException::class.java) { browser.restore(TEST_FP, 1, openFingerprint = null) }
        assertTrue(error.message!!.startsWith("Guardado pendiente de esa partida"))
        assertArrayEquals("no se tocó nada", version(2), store.load())
        lease.close()
        browser.restore(TEST_FP, 1, openFingerprint = null) // ya se permite
        assertArrayEquals(version(1), store.load())
        assertTrue("el permiso corto se liberó", !ownership.isOwned(TEST_FP))
    }

    @Test fun restoreReleasesItsPermitEvenWhenItFails() {
        val d = dir()
        SaveStore(d, TEST_FP).save(version(1))
        val ownership = FingerprintOwnership()
        assertThrows(java.io.IOException::class.java) {
            SavesBrowser(d, ownership = ownership).restore(TEST_FP, 9, null)
        }
        assertTrue(!ownership.isOwned(TEST_FP))
    }

    /** Operaciones que paran la PRIMERA escritura/borrado hasta que el test las suelte (restauración en vuelo). */
    private class ParkingOps(private val delegate: SaveFileOps = PosixSaveFileOps) : SaveFileOps by delegate {
        val entered = java.util.concurrent.CountDownLatch(1)
        val proceed = java.util.concurrent.CountDownLatch(1)
        private val first = java.util.concurrent.atomic.AtomicBoolean(true)

        private fun park() {
            if (first.compareAndSet(true, false)) {
                entered.countDown()
                proceed.await()
            }
        }

        override fun writeSynced(file: File, data: ByteArray) { park(); delegate.writeSynced(file, data) }
        override fun copySynced(from: File, to: File) { park(); delegate.copySynced(from, to) }
        override fun atomicReplace(from: File, to: File) { park(); delegate.atomicReplace(from, to) }
        override fun delete(file: File) { park(); delegate.delete(file) }
    }

    /**
     * A5V3-H1 (a): con la restauración YA autorizada y a medias (dentro de la mutación), otro dueño (lo que haría
     * onCleared o repairPrevious) intenta adquirir la huella: tiene que fallar. Con el viejo "comprobar y luego actuar"
     * la adquisición tenía éxito y su escritura tardía pisaba la restauración.
     */
    @Test fun aRestoreInFlightCannotBeOvertakenByAnotherOwnerBetweenCheckAndMutation() {
        val d = dir()
        val store = SaveStore(d, TEST_FP)
        store.save(version(1)) // A
        store.save(version(2)) // B (A pasa a backup .1)
        val ownership = FingerprintOwnership()
        val ops = ParkingOps()
        val browser = SavesBrowser(d, ops, ownership)
        val restore = java.util.concurrent.Executors.newSingleThreadExecutor().submit {
            browser.restore(TEST_FP, 1, openFingerprint = null)
        }
        assertTrue("la restauración llegó a mutar", ops.entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        // Intercalado exacto: comprobación hecha, mutación sin terminar.
        assertNull("nadie más puede adquirir en este punto", ownership.tryAcquire(TEST_FP, "onCleared"))
        assertEquals("restauración", ownership.ownerOf(TEST_FP))
        ops.proceed.countDown()
        restore.get(5, java.util.concurrent.TimeUnit.SECONDS)
        assertArrayEquals(version(1), store.load())
        val late = ownership.tryAcquire(TEST_FP, "sesión")
        assertTrue("al terminar, la huella queda libre", late != null)
    }

    /** Al revés: si el dueño llegó antes, la restauración NO muta nada (ni siquiera arranca su mutación). */
    @Test fun anOwnerThatAcquiredFirstMakesRestoreFailWithoutTouchingFiles() {
        val d = dir()
        val store = SaveStore(d, TEST_FP)
        store.save(version(1))
        store.save(version(2))
        val ownership = FingerprintOwnership()
        val ops = ParkingOps()
        val lease = ownership.tryAcquire(TEST_FP, "reparación")!!
        assertThrows(SavePendingException::class.java) { SavesBrowser(d, ops, ownership).restore(TEST_FP, 1, null) }
        assertEquals("no hubo ninguna mutación", 1L, ops.entered.count)
        assertArrayEquals(version(2), store.load())
        lease.close()
    }

    @Test fun ownershipIsExclusiveAndLeasesReleaseIdempotently() {
        val o = FingerprintOwnership()
        val a = o.tryAcquire("a", "uno")!!
        assertNull(o.tryAcquire("a", "dos"))
        assertTrue(o.tryAcquire("b", "tres") != null)
        a.close()
        a.close() // idempotente
        val again = o.tryAcquire("a", "cuatro")!!
        a.close() // un close viejo no suelta al dueño nuevo
        assertTrue(o.isOwned("a"))
        assertNull(o.tryAcquire("a", "cinco"))
        again.close()
        assertTrue(!o.isOwned("a"))
    }

    @Test fun onlyOneOfManyConcurrentAcquirersWins() {
        val o = FingerprintOwnership()
        val threads = 16
        val start = java.util.concurrent.CountDownLatch(1)
        val winners = java.util.concurrent.atomic.AtomicInteger()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
        val futures = (0 until threads).map {
            pool.submit {
                start.await()
                if (o.tryAcquire("x", "t$it") != null) winners.incrementAndGet()
            }
        }
        start.countDown()
        futures.forEach { it.get(5, java.util.concurrent.TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals(1, winners.get())
    }

    @Test fun emptyDirectoryListsNothing() {
        assertTrue(SavesBrowser(dir()).list().isEmpty())
    }
}

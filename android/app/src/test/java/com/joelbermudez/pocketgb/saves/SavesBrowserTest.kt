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

    @Test fun restoreIsRefusedWhileAnOrphanSessionStillHasItsSavePending() {
        val d = dir()
        val store = SaveStore(d, TEST_FP)
        store.save(version(1))
        store.save(version(2))
        val blocked = BlockedFingerprints()
        val browser = SavesBrowser(d, blocked = blocked)
        blocked.acquire(TEST_FP) // sesión huérfana con guardado pendiente
        val error = assertThrows(SavePendingException::class.java) { browser.restore(TEST_FP, 1, openFingerprint = null) }
        assertTrue(error.message!!.startsWith("Guardado pendiente de esa partida"))
        assertArrayEquals("no se tocó nada", version(2), store.load())
        blocked.release(TEST_FP)
        browser.restore(TEST_FP, 1, openFingerprint = null) // ya se permite
        assertArrayEquals(version(1), store.load())
    }

    @Test fun blockedFingerprintsAreCountedPerOwner() {
        val blocked = BlockedFingerprints()
        blocked.acquire("a"); blocked.acquire("a")
        blocked.release("a")
        assertTrue(blocked.isBlocked("a"))
        blocked.release("a")
        assertTrue(!blocked.isBlocked("a"))
        blocked.release("a") // un release de más no deja el contador negativo
        blocked.acquire("a")
        assertTrue(blocked.isBlocked("a"))
    }

    @Test fun emptyDirectoryListsNothing() {
        assertTrue(SavesBrowser(dir()).list().isEmpty())
    }
}

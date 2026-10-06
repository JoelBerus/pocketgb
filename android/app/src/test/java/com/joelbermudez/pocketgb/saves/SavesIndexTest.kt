package com.joelbermudez.pocketgb.saves

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SavesIndexTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun savesIndexListsGamesWithTitles() {
        val dir = File(tmp.root, "saves")
        val store = SaveStore(dir, "aa")
        store.save(bytes(1))
        SaveStore(dir, "bb").save(bytes(2))
        val index = SavesIndex(dir)
        index.record("aa", "ALPHA", "alpha.gb")
        val games = index.savedGames()
        assertEquals(listOf("aa", "bb"), games.map { it.fingerprint })
        assertEquals("ALPHA", games[0].record?.title)
        assertEquals("alpha.gb", games[0].record?.fileName)
        assertNull(games[1].record)
    }

    @Test fun backupsAndTmpAreNotListedAsGames() {
        val dir = File(tmp.root, "saves")
        val store = SaveStore(dir, "aa")
        store.save(bytes(1)); store.save(bytes(2))
        File(dir, "aa.sav.tmp").writeBytes(bytes(3))
        assertEquals(listOf("aa"), SavesIndex(dir).savedGames().map { it.fingerprint })
    }

    @Test fun recordIsIdempotentAndCorruptIndexIsIgnored() {
        val dir = File(tmp.root, "saves").also { it.mkdirs() }
        File(dir, "index.json").writeText("{ roto")
        val index = SavesIndex(dir)
        assertEquals(emptyMap<String, SavesIndex.Record>(), index.load())
        index.record("aa", "A", "a.gb")
        index.record("aa", "A", "a.gb")
        index.record("bb", "B", "b.gb")
        assertEquals(setOf("aa", "bb"), index.load().keys)
        index.record("aa", "A2", "a.gb")
        assertEquals("A2", index.load().getValue("aa").title)
        assertEquals(emptyList<String>(), dir.names().filter { it.endsWith(".tmp") })
    }
}

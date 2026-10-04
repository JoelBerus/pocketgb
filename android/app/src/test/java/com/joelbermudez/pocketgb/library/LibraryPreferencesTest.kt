package com.joelbermudez.pocketgb.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryPreferencesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun rom(id: String, title: String = id, color: Boolean = false, problem: RomProblem? = null) =
        RomEntry(id, "content://$id", "$id.gb", title, color, 32768, true, problem)

    private val red = rom("Rojo", "POKEMON RED")
    private val yellow = rom("Amarillo", "POKEMON YELLOW", color = true)
    private val tetris = rom("Tetris", "TETRIS")
    private val all = listOf(red, yellow, tetris)

    @Test
    fun filtersByConsoleAndFavorites() {
        val prefs = LibraryPreferencesData().toggleFavorite(tetris)
        fun ids(filter: LibraryFilter) = LibraryQuery.visible(all, prefs, filter, "").map { it.id }
        assertEquals(listOf("Rojo", "Tetris", "Amarillo"), ids(LibraryFilter.ALL).sortedBy { it == "Amarillo" })
        assertEquals(setOf("Rojo", "Tetris"), ids(LibraryFilter.GB).toSet())
        assertEquals(listOf("Amarillo"), ids(LibraryFilter.GBC))
        assertEquals(listOf("Tetris"), ids(LibraryFilter.FAVORITES))
    }

    @Test
    fun searchIgnoresCaseAccentsAndMatchesFileName() {
        val accent = rom("Mundo", "PIKACHÚ WORLD")
        val prefs = LibraryPreferencesData()
        assertEquals(listOf("Mundo"), LibraryQuery.visible(listOf(accent, red), prefs, LibraryFilter.ALL, "pikachu").map { it.id })
        assertEquals(listOf("Rojo"), LibraryQuery.visible(listOf(accent, red), prefs, LibraryFilter.ALL, "ROJO.gb").map { it.id })
        assertEquals(2, LibraryQuery.visible(listOf(accent, red), prefs, LibraryFilter.ALL, "  ").size)
    }

    @Test
    fun sortsByTitleOrByRecentPlayWithTitleTiebreak() {
        val prefs = LibraryPreferencesData(sort = LibrarySort.RECENT)
            .recordPlayed("Tetris", "aa", at = 100)
            .recordPlayed("Rojo", "bb", at = 200)
        assertEquals(
            listOf("Rojo", "Tetris", "Amarillo"),
            LibraryQuery.visible(all, prefs, LibraryFilter.ALL, "").map { it.id },
        )
        assertEquals(
            listOf("Red", "Yellow", "Tetris"),
            LibraryQuery.visible(all, prefs.copy(sort = LibrarySort.TITLE), LibraryFilter.ALL, "").map { it.title }
                .map { it.removePrefix("POKEMON ").lowercase().replaceFirstChar(Char::uppercase) },
        )
        assertEquals(listOf("Rojo", "Tetris"), LibraryQuery.recent(all, prefs).map { it.id })
    }

    @Test
    fun hidingSurvivesRenameThroughFingerprintAndNeverTouchesFavorites() {
        var prefs = LibraryPreferencesData().recordFingerprint("Rojo", "fp-red").toggleFavorite(red)
        prefs = prefs.hide(red)
        assertTrue(prefs.isHidden(red))
        // El mismo ROM renombrado conserva la huella en preferencias solo si se vuelve a registrar:
        val renamed = red.copy(id = "Copia/Rojo")
        assertFalse(prefs.isHidden(renamed))
        assertTrue(prefs.recordFingerprint("Copia/Rojo", "fp-red").isHidden(renamed))
        assertTrue(LibraryQuery.visible(all, prefs, LibraryFilter.ALL, "").none { it.id == "Rojo" })
        assertTrue(LibraryQuery.recent(all, prefs.recordPlayed("Rojo", "fp-red", 5)).isEmpty())
        prefs = prefs.unhide(red)
        assertFalse(prefs.isHidden(red))
        assertTrue(prefs.isFavorite(red))
    }

    @Test
    fun hidingWithoutFingerprintUsesPath() {
        val prefs = LibraryPreferencesData().hide(tetris)
        assertTrue(prefs.isHidden(tetris))
        assertFalse(prefs.unhide(tetris).isHidden(tetris))
    }

    @Test
    fun fileRoundTripsAndWritesAtomically() {
        val file = File(tmp.root, "library/preferences.json")
        val store = LibraryPreferencesFile(file)
        assertEquals(LibraryPreferencesData(), store.load())
        val data = LibraryPreferencesData(layout = LibraryLayout.LIST, sort = LibrarySort.RECENT)
            .toggleFavorite(red).recordPlayed("Rojo", "fp", 42).hide(tetris)
        store.save(data)
        assertEquals(data, LibraryPreferencesFile(file).load())
        assertFalse(File(file.parentFile, "preferences.json.tmp").exists())
    }

    @Test
    fun corruptFileIsKeptAsideAndDefaultsAreUsed() {
        val file = File(tmp.root, "preferences.json").apply { writeText("{no es json") }
        assertEquals(LibraryPreferencesData(), LibraryPreferencesFile(file).load())
        assertFalse(file.exists())
        assertEquals("{no es json", File(tmp.root, "preferences.json.corrupt").readText())
    }

    @Test
    fun unknownKeysAndMissingKeysAreTolerated() {
        val file = File(tmp.root, "preferences.json").apply {
            writeText("""{"favorites":["Rojo"],"futuro":123,"layout":"LIST"}""")
        }
        val data = LibraryPreferencesFile(file).load()
        assertEquals(setOf("Rojo"), data.favorites)
        assertEquals(LibraryLayout.LIST, data.layout)
        assertEquals(LibrarySort.TITLE, data.sort)
    }
}

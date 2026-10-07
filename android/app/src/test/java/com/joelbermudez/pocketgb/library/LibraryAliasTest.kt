package com.joelbermudez.pocketgb.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A9 · Renombrar (alias) con la semántica de iOS D8.1 (`LibraryPreferences.setAlias`/`recordPlayed`): solo cambia la
 * presentación, ≤ 80 caracteres, vacío = título de la cabecera, por huella si se conoce y por ruta si no.
 */
class LibraryAliasTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun rom(id: String, title: String, color: Boolean = false) =
        RomEntry(id, "content://$id", id.substringAfterLast('/'), title, color, 32768, true, null)

    private val red = rom("Pokemon Red.gb", "POKEMON RED")
    private val yellow = rom("Amarillo/Pokemon Yellow.gbc", "POKEMON YELLOW", color = true)
    private val tetris = rom("Tetris.gb", "TETRIS")
    private val all = listOf(red, yellow, tetris)
    private val fpRed = "aa".repeat(32)

    @Test
    fun withoutFingerprintTheAliasIsKeptByPath() {
        val prefs = LibraryPreferencesData().setAlias(red, "Rojo de Joel")
        assertEquals(mapOf(red.id to "Rojo de Joel"), prefs.aliasesByPath)
        assertTrue(prefs.aliasesByFingerprint.isEmpty())
        assertEquals("Rojo de Joel", prefs.displayTitle(red))
        assertEquals("POKEMON YELLOW", prefs.displayTitle(yellow))
    }

    @Test
    fun withAKnownFingerprintTheAliasIsKeptByFingerprintAndTheProvisionalPathAliasIsDropped() {
        val prefs = LibraryPreferencesData()
            .setAlias(red, "Provisional")
            .recordFingerprint(red.id, fpRed)
            .setAlias(red, "Definitivo")
        assertEquals(mapOf(fpRed to "Definitivo"), prefs.aliasesByFingerprint)
        assertTrue(prefs.aliasesByPath.isEmpty())
        assertEquals("Definitivo", prefs.displayTitle(red))
    }

    @Test
    fun theFingerprintAliasFollowsTheRomWhenItMovesToAnotherFolder() {
        val prefs = LibraryPreferencesData().recordFingerprint(red.id, fpRed).setAlias(red, "Rojo")
        val moved = red.copy(id = "Pokémon/Pokemon Red.gb")
        assertEquals("POKEMON RED", prefs.displayTitle(moved)) // ruta nueva: aún sin huella conocida
        assertEquals("Rojo", prefs.recordFingerprint(moved.id, fpRed).displayTitle(moved))
    }

    @Test
    fun recordPlayedMigratesThePathAliasToTheFingerprint() {
        val prefs = LibraryPreferencesData().setAlias(red, "Rojo").recordPlayed(red.id, fpRed, at = 5)
        assertEquals(mapOf(fpRed to "Rojo"), prefs.aliasesByFingerprint)
        assertTrue(prefs.aliasesByPath.isEmpty())
        assertEquals("Rojo", prefs.displayTitle(red))
    }

    @Test
    fun recordFingerprintFromTheDetailsAlsoMigrates() {
        val prefs = LibraryPreferencesData().setAlias(red, "Rojo").recordFingerprint(red.id, fpRed)
        assertEquals(mapOf(fpRed to "Rojo"), prefs.aliasesByFingerprint)
        assertTrue(prefs.aliasesByPath.isEmpty())
    }

    @Test
    fun anEmptyOrBlankAliasGoesBackToTheHeaderTitle() {
        val byPath = LibraryPreferencesData().setAlias(red, "Rojo")
        assertEquals("POKEMON RED", byPath.setAlias(red, "").displayTitle(red))
        assertTrue(byPath.setAlias(red, "   \n ").aliasesByPath.isEmpty())
        val byFingerprint = LibraryPreferencesData().recordFingerprint(red.id, fpRed).setAlias(red, "Rojo")
        val cleared = byFingerprint.setAlias(red, "")
        assertTrue(cleared.aliasesByFingerprint.isEmpty())
        assertEquals("POKEMON RED", cleared.displayTitle(red))
    }

    @Test
    fun theHeaderTitleItselfIsNotStoredAsAnAlias() {
        val prefs = LibraryPreferencesData().setAlias(red, "  POKEMON RED ")
        assertTrue(prefs.aliasesByPath.isEmpty())
        assertNull(prefs.aliasOf(red))
    }

    @Test
    fun theAliasIsTrimmedAndCappedAtEightyCharacters() {
        val long = "x".repeat(120)
        val prefs = LibraryPreferencesData().setAlias(red, "  $long  ")
        assertEquals(80, prefs.displayTitle(red).length)
        assertEquals("Rojo", LibraryPreferencesData().setAlias(red, "\tRojo \n").displayTitle(red))
    }

    @Test
    fun theCapNeverSplitsAnEmojiOrAnAccentedLetter() {
        val emoji = "🎮" // 🎮: dos unidades UTF-16, un carácter visible
        val prefs = LibraryPreferencesData().setAlias(red, emoji.repeat(100))
        val alias = prefs.displayTitle(red)
        assertEquals(80, alias.codePointCount(0, alias.length))
        assertTrue(alias.endsWith(emoji))
        // «e» + acento combinante: un solo carácter visible que no se parte.
        val combined = "é"
        val accents = LibraryPreferencesData().setAlias(red, combined.repeat(90)).displayTitle(red)
        assertEquals(160, accents.length)
        assertTrue(accents.endsWith(combined))
    }

    @Test
    fun lineBreaksInsideTheAliasBecomeSpaces() {
        val prefs = LibraryPreferencesData().setAlias(red, "Rojo\nde\tJoel")
        assertEquals("Rojo de Joel", prefs.displayTitle(red))
    }

    @Test
    fun withAliasDecoratesTheEntryAndKeepsTheHeaderTitle() {
        val prefs = LibraryPreferencesData().setAlias(red, "Rojo")
        val shown = prefs.withAlias(red)
        assertEquals("Rojo", shown.displayTitle)
        assertEquals("POKEMON RED", shown.title)
        assertEquals(red.id, shown.id)
        assertEquals(tetris, prefs.withAlias(tetris))
    }

    @Test
    fun searchMatchesTheAliasTheHeaderTitleAndTheFileName() {
        val prefs = LibraryPreferencesData().setAlias(red, "Mi partida de Kanto")
        fun search(q: String) = LibraryQuery.visible(all, prefs, LibraryFilter.ALL, q).map { it.id }
        assertEquals(listOf(red.id), search("kanto"))
        assertEquals(listOf(red.id), search("MI PARTIDA"))
        assertEquals(listOf(red.id), search("pokemon red")) // la cabecera también encuentra el juego renombrado
        assertEquals(listOf(red.id), search("Red.gb"))
        assertEquals(listOf(yellow.id), search("yellow"))
    }

    @Test
    fun theVisibleListShowsAndSortsByTheAlias() {
        val prefs = LibraryPreferencesData().setAlias(tetris, "Alfa bloques")
        val shown = LibraryQuery.visible(all, prefs, LibraryFilter.ALL, "")
        assertEquals(listOf("Alfa bloques", "POKEMON RED", "POKEMON YELLOW"), shown.map { it.displayTitle })
        assertEquals("TETRIS", shown.first().title)
    }

    @Test
    fun recentFavoritesAndHiddenListsCarryTheAlias() {
        val prefs = LibraryPreferencesData()
            .recordPlayed(red.id, fpRed, at = 10)
            .setAlias(red, "Rojo")
            .toggleFavorite(red)
            .setAlias(tetris, "Bloques")
            .hide(tetris)
        assertEquals(listOf("Rojo"), LibraryQuery.recent(all, prefs).map { it.displayTitle })
        assertEquals(listOf("Rojo"), LibraryQuery.visible(all, prefs, LibraryFilter.FAVORITES, "").map { it.displayTitle })
        assertEquals(listOf("Bloques"), LibraryQuery.hidden(all, prefs).map { it.displayTitle })
    }

    @Test
    fun theCurrentA8PreferencesFileStillLoadsWithNoAliases() {
        val fixture = requireNotNull(javaClass.getResource("/library/preferences-a8.json")).readText()
        val file = File(tmp.root, "preferences.json").apply { writeText(fixture) }
        val data = LibraryPreferencesFile(file).load()
        assertTrue("el archivo no se aparta como corrupto", file.exists())
        assertEquals(setOf("Pokemon Red.gb"), data.favorites)
        assertEquals(1_759_700_000_000L, data.lastPlayed["Pokemon Red.gb"])
        assertEquals("9d4c1e07b3a85f26c0de91ab47f3825e6b10c9d7a2f45e83b6c1d09e7f2a4b58", data.fingerprints["Pokemon Red.gb"])
        assertEquals(setOf("Roto.gb"), data.hiddenPaths)
        assertEquals(LibraryLayout.LIST, data.layout)
        assertEquals(LibrarySort.RECENT, data.sort)
        assertEquals(3, data.knownIds.size)
        assertTrue(data.aliasesByFingerprint.isEmpty())
        assertTrue(data.aliasesByPath.isEmpty())
        assertEquals("POKEMON RED", data.displayTitle(red))
    }

    @Test
    fun aliasesSurviveARoundTripOnDisk() {
        val file = LibraryPreferencesFile(File(tmp.root, "p.json"))
        val data = LibraryPreferencesData()
            .recordFingerprint(red.id, fpRed)
            .setAlias(red, "Rojo de Joel")
            .setAlias(yellow, "Amarillo ✨")
        file.save(data)
        val loaded = file.load()
        assertEquals(data, loaded)
        assertEquals("Rojo de Joel", loaded.displayTitle(red))
        assertEquals("Amarillo ✨", loaded.displayTitle(yellow))
    }

    @Test
    fun anOlderAppReadingTheNewFileKeepsEverythingElse() {
        // Una versión anterior ignora las claves que no conoce (`ignoreUnknownKeys`): se simula con un JSON que lleva
        // claves nuevas y comprobando que el resto se lee igual.
        val file = File(tmp.root, "p.json").apply {
            writeText("""{"favorites":["Tetris.gb"],"aliasesByFingerprint":{"$fpRed":"Rojo"},"aliasesByPath":{"Tetris.gb":"B"},"futuro":1}""")
        }
        val data = LibraryPreferencesFile(file).load()
        assertEquals(setOf("Tetris.gb"), data.favorites)
        assertEquals("B", data.displayTitle(tetris))
    }
}

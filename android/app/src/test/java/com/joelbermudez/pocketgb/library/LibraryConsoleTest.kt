package com.joelbermudez.pocketgb.library

import com.joelbermudez.pocketgb.emulator.Console
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** N8: `RomEntry.console` (GB/GBC/GBA) en filtros, búsqueda y categorías, como iOS `ConsoleBadge`/`LibraryFilter`. */
class LibraryConsoleTest {
    private fun rom(id: String, title: String, console: RomConsole) =
        RomEntry(id, "content://$id", id.substringAfterLast('/'), title, console, 32768, true, null)

    private val red = rom("Pokémon/Pokemon Red.gb", "POKEMON RED", RomConsole.GB)
    private val gold = rom("Pokémon/Pokemon Gold.gbc", "POKEMON GOLD", RomConsole.GBC)
    private val emerald = rom("Pokémon/Pokemon Emerald.gba", "POKEMON EMER", RomConsole.GBA)
    private val kirby = rom("Kirby/Kirby - Nightmare in Dream Land.gba", "KIRBY DREAM", RomConsole.GBA)
    private val tetris = rom("Tetris.gb", "TETRIS", RomConsole.GB)
    private val all = listOf(red, gold, emerald, kirby, tetris)

    private fun ids(filter: LibraryFilter, query: String = "", category: LibraryCategory = LibraryCategory.All) =
        LibraryQuery.visible(all, LibraryPreferencesData(), filter, query, category).map { it.id }.toSet()

    @Test
    fun eachConsoleFilterShowsOnlyItsGames() {
        assertEquals(setOf(red.id, tetris.id), ids(LibraryFilter.GB))
        assertEquals(setOf(gold.id), ids(LibraryFilter.GBC))
        assertEquals(setOf(emerald.id, kirby.id), ids(LibraryFilter.GBA))
        assertEquals(all.map { it.id }.toSet(), ids(LibraryFilter.ALL))
        assertEquals("el filtro GBA va entre GBC y Favoritos, como iOS", listOf("Todos", "GB", "GBC", "GBA", "Favoritos"), LibraryFilter.entries.map { it.title })
    }

    @Test
    fun theConsoleFilterCombinesWithTheCategoryAndTheSearch() {
        assertEquals(setOf(emerald.id), ids(LibraryFilter.GBA, category = LibraryCategory.Folder("Pokémon")))
        assertEquals(setOf(kirby.id), ids(LibraryFilter.GBA, query = "kirby"))
        assertEquals(emptySet<String>(), ids(LibraryFilter.GB, query = "kirby"))
    }

    @Test
    fun theSearchFindsTheConsoleByShortOrFullName() {
        assertEquals(setOf(emerald.id, kirby.id), ids(LibraryFilter.ALL, query = "GBA"))
        assertEquals(setOf(emerald.id, kirby.id), ids(LibraryFilter.ALL, query = "advance"))
        assertEquals(setOf(gold.id), ids(LibraryFilter.ALL, query = "Color"))
        // Tres letras que no son un nombre corto no casan con la consola («boy» no da todo).
        assertEquals(emptySet<String>(), ids(LibraryFilter.ALL, query = "boy"))
    }

    @Test
    fun categoriesCountGbaGamesLikeAnyOther() {
        val options = LibraryCategory.options(all, LibraryPreferencesData()).associate { it.category to it.count }
        assertEquals(3, options[LibraryCategory.Folder("Pokémon")])
        assertEquals(1, options[LibraryCategory.Folder("Kirby")])
        assertEquals(1, options[LibraryCategory.Uncategorized])
    }

    @Test
    fun consoleByExtensionWhenTheHeaderIsUnknownAndTheCoreFollowsIt() {
        assertEquals(RomConsole.GBA, RomConsole.fromFileName("a.GBA"))
        assertEquals(RomConsole.GBC, RomConsole.fromFileName("a.gbc"))
        assertEquals(RomConsole.GB, RomConsole.fromFileName("a.gb"))
        assertEquals(RomConsole.GB, RomConsole.fromFileName("sin-extension"))
        assertEquals(Console.GB, RomConsole.GBC.core)
        assertEquals(Console.GBA, RomConsole.GBA.core)
        assertEquals(RomConsole.GBC, RomConsole.gameBoy(isColor = true))
        assertTrue(kirby.isGba)
        assertEquals(listOf("GB", "GBC", "GBA"), RomConsole.entries.map { it.shortName })
    }
}

package com.joelbermudez.pocketgb.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** N3b · categorías de primer nivel de `folderPath` y N3a/ND15 · carril «Continuar jugando» solo con reanudables. */
class LibraryCategoryTest {
    private fun rom(id: String, title: String = id.substringAfterLast('/'), color: Boolean = false, problem: RomProblem? = null) =
        RomEntry(id, "content://$id", id.substringAfterLast('/'), title, color, 32768, true, problem)

    private val red = rom("Pokémon/1ª generación/Pokemon Red.gb", "POKEMON RED")
    private val gold = rom("Pokémon/2ª generación/Johto/Pokemon Gold.gbc", "POKEMON GOLD", color = true)
    private val kirby = rom("Kirby/Kirby.gb", "KIRBY")
    private val tetris = rom("Tetris.gb", "TETRIS")
    private val zelda = rom("aventuras/Zelda.gb", "ZELDA")
    private val all = listOf(red, gold, kirby, tetris, zelda)

    @Test
    fun categoriesAreTheFirstLevelFoldersSortedNaturallyWithTheRootLast() {
        val options = LibraryCategory.options(all, LibraryPreferencesData())
        assertEquals(
            listOf(
                LibraryCategory.Folder("aventuras"),
                LibraryCategory.Folder("Kirby"),
                LibraryCategory.Folder("Pokémon"),
                LibraryCategory.Uncategorized,
            ),
            options.map { it.category },
        )
        assertEquals(listOf(1, 1, 2, 1), options.map { it.count })
    }

    @Test
    fun aFolderCategoryContainsItsWholeSubtree() {
        val pokemon = LibraryCategory.Folder("Pokémon")
        assertTrue(pokemon.contains(red))
        assertTrue(pokemon.contains(gold))
        assertFalse(pokemon.contains(kirby))
        assertFalse(pokemon.contains(tetris))
        assertTrue(LibraryCategory.Uncategorized.contains(tetris))
        assertFalse(LibraryCategory.Uncategorized.contains(red))
        assertTrue(all.all { LibraryCategory.All.contains(it) })
    }

    @Test
    fun hiddenGamesDoNotCountNorCreateCategories() {
        val prefs = LibraryPreferencesData().hide(kirby).hide(tetris)
        val options = LibraryCategory.options(all, prefs)
        assertEquals(listOf("aventuras", "Pokémon"), options.map { (it.category as LibraryCategory.Folder).name })
        assertFalse(options.any { it.category == LibraryCategory.Uncategorized })
    }

    @Test
    fun noFoldersMeansOnlyTheRootCategory() {
        val options = LibraryCategory.options(listOf(tetris), LibraryPreferencesData())
        assertEquals(listOf(LibraryCategory.Uncategorized), options.map { it.category })
        assertFalse(LibraryCategory.hasFolders(options))
        assertTrue(LibraryCategory.hasFolders(LibraryCategory.options(all, LibraryPreferencesData())))
    }

    @Test
    fun visibleCombinesCategoryFilterAndSearch() {
        val prefs = LibraryPreferencesData()
        fun ids(filter: LibraryFilter, query: String, category: LibraryCategory) =
            LibraryQuery.visible(all, prefs, filter, query, category).map { it.id }.toSet()
        assertEquals(setOf(red.id, gold.id), ids(LibraryFilter.ALL, "", LibraryCategory.Folder("Pokémon")))
        assertEquals(setOf(gold.id), ids(LibraryFilter.GBC, "", LibraryCategory.Folder("Pokémon")))
        assertEquals(setOf(red.id), ids(LibraryFilter.ALL, "red", LibraryCategory.Folder("Pokémon")))
        assertEquals(setOf(tetris.id), ids(LibraryFilter.ALL, "", LibraryCategory.Uncategorized))
        assertEquals(all.map { it.id }.toSet(), ids(LibraryFilter.ALL, "", LibraryCategory.All))
        assertTrue(ids(LibraryFilter.ALL, "", LibraryCategory.Folder("No existe")).isEmpty())
    }

    @Test
    fun aCopyInAnotherCategoryKeepsItsAlsoInInsideACategory() {
        val copy = rom("Kirby/Pokemon Red.gb", "POKEMON RED")
        val prefs = LibraryPreferencesData(fingerprints = mapOf(red.id to "a".repeat(64), copy.id to "a".repeat(64)))
        val shown = LibraryQuery.visible(all + copy, prefs, LibraryFilter.ALL, "", LibraryCategory.Folder("Kirby"))
        val shownCopy = shown.single { it.id == copy.id }
        assertEquals(listOf(red.location), shownCopy.alsoAt)
    }

    @Test
    fun onlyFolderCategoriesHaveAFolderName() {
        assertEquals(null, LibraryCategory.All.folderName)
        assertEquals("Pokémon", LibraryCategory.Folder("Pokémon").folderName)
        assertEquals(null, LibraryCategory.Uncategorized.folderName)
    }

    // ---- ND15 · carril «Continuar jugando» ----

    private fun played(vararg entries: RomEntry): LibraryPreferencesData =
        entries.foldIndexed(LibraryPreferencesData()) { i, prefs, entry ->
            prefs.recordPlayed(entry.id, "%064x".format(i + 1), at = (100 - i).toLong())
        }

    private fun fp(prefs: LibraryPreferencesData, entry: RomEntry) = prefs.fingerprints.getValue(entry.id)

    @Test
    fun theRailOnlyShowsResumableGames() {
        val prefs = played(red, gold, kirby, tetris)
        val resumable = setOf(fp(prefs, gold), fp(prefs, tetris))
        val rail = LibraryQuery.continueRail(
            all, prefs,
            isResumable = { prefs.fingerprints[it.id] in resumable },
            hasArtwork = { true },
        )
        assertEquals(listOf(gold.id, tetris.id), rail.map { it.id })
    }

    @Test
    fun theRailKeepsUpToFiveResumableGamesEvenIfOlderOnesAreNotResumable() {
        val games = (1..9).map { rom("G$it.gb") }
        val prefs = played(*games.toTypedArray())
        // Los tres más recientes no se pueden continuar: el carril sigue llenándose con los siguientes hasta 5.
        val resumable = games.drop(3).map { fp(prefs, it) }.toSet()
        val rail = LibraryQuery.continueRail(games, prefs, { prefs.fingerprints[it.id] in resumable }, { true })
        assertEquals(LibraryQuery.CONTINUE_LIMIT, rail.size)
        assertEquals(games.drop(3).take(5).map { it.id }, rail.map { it.id })
    }

    @Test
    fun theRailStillNeedsACapturedArtworkAndAPlayableRom() {
        val broken = rom("Roto.gb", problem = RomProblem.INVALID_HEADER)
        val prefs = played(broken, red, gold)
        val rail = LibraryQuery.continueRail(
            listOf(broken, red, gold), prefs,
            isResumable = { true },
            hasArtwork = { it == fp(prefs, gold) },
        )
        assertEquals(listOf(gold.id), rail.map { it.id })
    }

    @Test
    fun theRailIsEmptyWithoutResumableGames() {
        val prefs = played(red, gold)
        assertTrue(LibraryQuery.continueRail(all, prefs, { false }, { true }).isEmpty())
    }
}

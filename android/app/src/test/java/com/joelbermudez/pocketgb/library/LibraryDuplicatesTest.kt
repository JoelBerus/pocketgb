package com.joelbermudez.pocketgb.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** N1a · duplicados: la misma huella conocida en varias rutas se marca y cada copia sabe dónde están las demás. */
class LibraryDuplicatesTest {
    private fun rom(id: String, title: String = "POKEMON RED") =
        RomEntry(id, "content://$id", id.substringAfterLast('/'), title, com.joelbermudez.pocketgb.library.RomConsole.GB, 32768, true, null)

    private val fp = "ab".repeat(32)
    private val root = rom("Pokemon Red.gb")
    private val deep = rom("Pokémon/1ª generación/Rojo.gb")
    private val third = rom("Copias/Rojo (1).gb")
    private val other = rom("Tetris.gb", "TETRIS")

    private val prefs = LibraryPreferencesData()
        .recordFingerprint(root.id, fp)
        .recordFingerprint(deep.id, fp)
        .recordFingerprint(other.id, "cd".repeat(32))

    @Test
    fun copiesWithTheSameKnownFingerprintAreMarkedWithTheOtherLocations() {
        val shown = LibraryQuery.visible(listOf(root, deep, other), prefs, LibraryFilter.ALL, "").associateBy { it.id }
        assertTrue(shown.getValue(root.id).isDuplicate)
        assertEquals(listOf(RomLocation(listOf("Pokémon", "1ª generación"), "Rojo.gb")), shown.getValue(root.id).alsoAt)
        assertEquals(listOf(RomLocation(emptyList(), "Pokemon Red.gb")), shown.getValue(deep.id).alsoAt)
        assertFalse(shown.getValue(other.id).isDuplicate)
    }

    @Test
    fun anUnknownFingerprintIsNeverADuplicate() {
        // Mismo título y tamaño, pero sin huella conocida: no se adivina.
        val shown = LibraryQuery.visible(listOf(root, third), LibraryPreferencesData(), LibraryFilter.ALL, "")
        assertTrue(shown.none { it.isDuplicate })
    }

    @Test
    fun threeCopiesEachListTheOtherTwoInPathOrder() {
        val withThird = prefs.recordFingerprint(third.id, fp)
        val copies = LibraryQuery.copies(listOf(root, deep, third), withThird)
        assertEquals(2, copies.getValue(root.id).size)
        assertEquals(listOf(deep.id, third.id).map { it.substringAfterLast('/') }.sorted(), copies.getValue(root.id).map { it.fileName }.sorted())
        assertEquals(setOf(root.id, deep.id, third.id), copies.keys)
    }

    @Test
    fun copiesShareFavoriteAliasAndHiddenBecauseTheyGoByFingerprint() {
        val marked = prefs.toggleFavorite(root).setAlias(root, "Rojo de Joel")
        assertTrue(marked.isFavorite(deep))
        assertEquals("Rojo de Joel", marked.displayTitle(deep))
        assertTrue(marked.hide(deep).isHidden(root))
    }

    @Test
    fun continuePlayingShowsAPlayedGameOnceEvenWithTwoCopies() {
        val played = prefs.recordPlayed(deep.id, fp, at = 500L).recordPlayed(other.id, "cd".repeat(32), at = 100L)
        val recent = LibraryQuery.recent(listOf(root, deep, other), played)
        assertEquals(2, recent.size)
        assertEquals(1, recent.count { it.title == "POKEMON RED" })
        assertTrue(recent.first().isDuplicate)
    }

    @Test
    fun theDetailsEntryCarriesItsCopies() {
        val entry = LibraryQuery.presented(listOf(root, deep, other), prefs, deep.id)!!
        assertEquals(listOf(RomLocation(emptyList(), "Pokemon Red.gb")), entry.alsoAt)
        assertEquals(listOf("Pokémon", "1ª generación"), entry.folderPath)
    }
}

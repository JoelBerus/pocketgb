package com.joelbermudez.pocketgb.ui.library

import com.joelbermudez.pocketgb.library.LibraryCategory
import com.joelbermudez.pocketgb.library.LibraryFilter
import org.junit.Assert.assertEquals
import org.junit.Test

/** N3b (H8): el título de sección nombra la categoría y el filtro elegidos. */
class SectionTitleTest {
    private val labels = SectionLabels(allGames = "Todos los juegos", uncategorized = "Sin categoría") { a, b -> "$a · $b" }

    @Test
    fun withoutFilterNorCategoryItIsAllGames() {
        assertEquals("Todos los juegos", sectionTitle(LibraryFilter.ALL, LibraryCategory.All, labels))
    }

    @Test
    fun aFilterAloneNamesTheFilter() {
        assertEquals("GBC", sectionTitle(LibraryFilter.GBC, LibraryCategory.All, labels))
        assertEquals("Favoritos", sectionTitle(LibraryFilter.FAVORITES, LibraryCategory.All, labels))
    }

    @Test
    fun aCategoryAloneNamesTheFolderOrTheRoot() {
        assertEquals("Pokémon", sectionTitle(LibraryFilter.ALL, LibraryCategory.Folder("Pokémon"), labels))
        assertEquals("Sin categoría", sectionTitle(LibraryFilter.ALL, LibraryCategory.Uncategorized, labels))
    }

    @Test
    fun bothAreJoinedCategoryFirst() {
        assertEquals("Pokémon · GBC", sectionTitle(LibraryFilter.GBC, LibraryCategory.Folder("Pokémon"), labels))
        assertEquals("Sin categoría · GB", sectionTitle(LibraryFilter.GB, LibraryCategory.Uncategorized, labels))
    }
}

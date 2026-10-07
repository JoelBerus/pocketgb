package com.joelbermudez.pocketgb.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N4 · organización de la biblioteca sobre un árbol sintético: estanterías del inicio, subcategorías, migas y conteos;
 * categoría virtual (ND3) aplicada y revertida y su efecto en estanterías, búsqueda y filtros; etiquetas; ajustes del
 * inicio (orden, fijar, ocultar) y vista por categoría.
 */
class LibraryOrganizationTest {
    private fun rom(id: String, title: String, color: Boolean = false) =
        RomEntry(id, "content://$id", id.substringAfterLast('/'), title, color, 32768, true, null)

    // Roms/
    //   Pokémon/1ª generación/{Red, Yellow}   Pokémon/2ª generación/Gold   Pokémon/2ª generación/Johto/Crystal
    //   Kirby/Kirby   Puzles/Tetris   Tetra.gb (raíz)   _Revisar no llega (lo aparta el escáner)
    private val red = rom("Pokémon/1ª generación/Pokemon Red.gb", "POKEMON RED")
    private val yellow = rom("Pokémon/1ª generación/Pokemon Yellow.gbc", "POKEMON YELLOW", color = true)
    private val gold = rom("Pokémon/2ª generación/Pokemon Gold.gbc", "POKEMON GOLD", color = true)
    private val crystal = rom("Pokémon/2ª generación/Johto/Pokemon Crystal.gbc", "POKEMON CRYSTAL", color = true)
    private val kirby = rom("Kirby/Kirby.gb", "KIRBY")
    private val tetris = rom("Puzles/Tetris.gb", "TETRIS")
    private val tetra = rom("Tetra.gb", "TETRA")
    private val all = listOf(red, yellow, gold, crystal, kirby, tetris, tetra)

    private fun fp(entry: RomEntry) = "%064x".format(entry.id.hashCode().toLong() and 0xFFFFFFFFL)

    /** Todas las huellas confirmadas (leídas del ROM). */
    private val known = all.fold(LibraryPreferencesData()) { prefs, entry -> prefs.recordFingerprint(entry.id, fp(entry)) }

    private fun shown(prefs: LibraryPreferencesData = known) = LibraryQuery.visible(all, prefs, LibraryFilter.ALL, "")

    private fun pokemon(vararg rest: String) = listOf("Pokémon") + rest

    // ---- árbol: estanterías, subcategorías, migas y conteos ----

    @Test
    fun theHomeHasOneShelfPerFirstLevelFolderPlusUncategorizedLast() {
        val home = LibraryHome.sections(all, known)
        assertEquals(
            listOf(LibraryCategory.Folder("Kirby"), LibraryCategory.Folder("Pokémon"), LibraryCategory.Folder("Puzles"), LibraryCategory.Uncategorized),
            home.shelves.map { it.category },
        )
        assertEquals(listOf(1, 4, 1, 1), home.shelves.map { it.total })
        assertEquals(
            "la estantería de Pokémon lleva todo su subárbol",
            setOf(red.id, yellow.id, gold.id, crystal.id),
            home.shelves[1].games.map { it.id }.toSet(),
        )
    }

    @Test
    fun aShelfShowsAtMostTheLimitButCountsEverything() {
        val home = LibraryHome.sections(all, known, limit = 2)
        val shelf = home.shelves.single { it.category == LibraryCategory.Folder("Pokémon") }
        assertEquals(2, shelf.games.size)
        assertEquals(4, shelf.total)
    }

    @Test
    fun withoutFoldersThereAreNoShelvesBecauseTheyWouldRepeatAllGames() {
        val home = LibraryHome.sections(listOf(tetra), known)
        assertTrue(home.shelves.isEmpty())
    }

    @Test
    fun hiddenGamesNeitherCountNorAppearInShelves() {
        val prefs = known.hide(kirby).hide(red)
        val home = LibraryHome.sections(all, prefs)
        assertFalse(home.shelves.any { it.category == LibraryCategory.Folder("Kirby") })
        assertEquals(3, home.shelves.single { it.category == LibraryCategory.Folder("Pokémon") }.total)
    }

    @Test
    fun theFavoritesRowListsFavoritesOnceEvenWithCopies() {
        val copy = rom("Copias/Pokemon Red.gb", "POKEMON RED")
        val prefs = known.recordFingerprint(copy.id, fp(red)).toggleFavorite(red).toggleFavorite(tetra)
        val home = LibraryHome.sections(all + copy, prefs)
        assertEquals(listOf("POKEMON RED", "TETRA"), home.favorites.map { it.title })
        assertTrue(LibraryHome.sections(all + copy, prefs.copy(home = prefs.home.copy(showFavorites = false))).favorites.isEmpty())
    }

    @Test
    fun subcategoriesAreTheDirectChildFoldersWithTheGamesOfTheirSubtree() {
        val sub = LibraryTree.subcategories(shown(), pokemon())
        assertEquals(
            listOf(LibraryCategory.Folder(pokemon("1ª generación")), LibraryCategory.Folder(pokemon("2ª generación"))),
            sub.map { it.category },
        )
        assertEquals(listOf(2, 2), sub.map { it.count })
        assertEquals(listOf(LibraryCategory.Folder(pokemon("2ª generación", "Johto"))), LibraryTree.subcategories(shown(), pokemon("2ª generación")).map { it.category })
        assertTrue(LibraryTree.subcategories(shown(), pokemon("2ª generación", "Johto")).isEmpty())
        assertTrue("Sin categoría no tiene subcategorías", LibraryTree.subcategories(shown(), emptyList()).isEmpty())
    }

    @Test
    fun aCategoryScreenShowsTheGamesOfTheFolderAndItsSubfolders() {
        fun ids(category: LibraryCategory) = LibraryQuery.visible(all, known, LibraryFilter.ALL, "", category).map { it.id }.toSet()
        assertEquals(setOf(gold.id, crystal.id), ids(LibraryCategory.Folder(pokemon("2ª generación"))))
        assertEquals(setOf(crystal.id), ids(LibraryCategory.Folder(pokemon("2ª generación", "Johto"))))
        assertEquals("Sin categoría solo con los de la raíz", setOf(tetra.id), ids(LibraryCategory.Uncategorized))
        assertTrue("un prefijo de nombre no es la carpeta", ids(LibraryCategory.Folder(listOf("Poké"))).isEmpty())
    }

    @Test
    fun breadcrumbsGoFromTheFirstLevelToTheCurrentFolder() {
        assertEquals(
            listOf(pokemon(), pokemon("2ª generación"), pokemon("2ª generación", "Johto")),
            LibraryTree.breadcrumbs(pokemon("2ª generación", "Johto")),
        )
        assertTrue(LibraryTree.breadcrumbs(emptyList()).isEmpty())
        assertEquals("Pokémon › 2ª generación", CategoryPaths.display(pokemon("2ª generación")))
    }

    @Test
    fun knownPathsListEveryFolderAndItsParentsInNaturalOrder() {
        assertEquals(
            listOf(
                listOf("Kirby"), pokemon(), pokemon("1ª generación"), pokemon("2ª generación"),
                pokemon("2ª generación", "Johto"), listOf("Puzles"),
            ),
            LibraryTree.knownPaths(all, known),
        )
    }

    @Test
    fun categoryRoutesKeepTheirKeyAndComeBack() {
        val nested = LibraryCategory.Folder(pokemon("2ª generación"))
        assertEquals("Pokémon/2ª generación", nested.key)
        assertEquals(nested, LibraryCategory.fromPath(pokemon("2ª generación")))
        assertEquals(LibraryCategory.Uncategorized, LibraryCategory.fromPath(emptyList()))
        assertEquals(".", LibraryCategory.Uncategorized.key)
        // H12: «Todas» no tiene una clave que pueda chocar con una carpeta (ninguna se llama «»).
        assertEquals("", LibraryCategory.All.key)
        assertEquals("2ª generación", nested.name)
    }

    // ---- categoría virtual (ND3) ----

    @Test
    fun aVirtualCategoryShowsTheGameThereInsteadOfItsFolderWithoutTouchingTheFile() {
        val moved = known.moveToCategory(tetris, listOf("Favoritas"))
        assertEquals(listOf("Favoritas"), moved.virtualFolderOf(tetris))
        val home = LibraryHome.sections(all, moved)
        assertFalse("Puzles se queda sin juegos", home.shelves.any { it.category == LibraryCategory.Folder("Puzles") })
        val favoritas = home.shelves.single { it.category == LibraryCategory.Folder("Favoritas") }
        val shownTetris = favoritas.games.single()
        assertTrue(shownTetris.isMovedInApp)
        assertEquals(listOf("Favoritas"), shownTetris.categoryPath)
        assertEquals("la carpeta real no cambia", listOf("Puzles"), shownTetris.folderPath)
        assertEquals("Puzles/Tetris.gb", shownTetris.id)
    }

    @Test
    fun returningToItsFolderRevertsTheVirtualCategory() {
        val moved = known.moveToCategory(tetris, pokemon("1ª generación"))
        val firstGen = LibraryQuery.visible(all, moved, LibraryFilter.ALL, "", LibraryCategory.Folder(pokemon("1ª generación")))
        assertEquals(setOf(red.id, yellow.id, tetris.id), firstGen.map { it.id }.toSet())
        assertEquals(5, LibraryHome.sections(all, moved).shelves.single { it.category == LibraryCategory.Folder("Pokémon") }.total)
        val back = moved.returnToFolder(tetris)
        assertNull(back.virtualFolderOf(tetris))
        assertEquals(known, back)
        assertFalse(LibraryQuery.visible(all, back, LibraryFilter.ALL, "").single { it.id == tetris.id }.isMovedInApp)
    }

    @Test
    fun choosingItsOwnFolderIsTheSameAsReturning() {
        val moved = known.moveToCategory(tetris, listOf("Favoritas")).moveToCategory(tetris, listOf("Puzles"))
        assertNull(moved.virtualFolderOf(tetris))
    }

    @Test
    fun aGameCanBeMovedToTheRootAndThenItIsUncategorized() {
        val moved = known.moveToCategory(crystal, emptyList())
        assertEquals(emptyList<String>(), moved.virtualFolderOf(crystal))
        val ids = LibraryQuery.visible(all, moved, LibraryFilter.ALL, "", LibraryCategory.Uncategorized).map { it.id }.toSet()
        assertEquals(setOf(tetra.id, crystal.id), ids)
        assertTrue(LibraryTree.subcategories(LibraryQuery.visible(all, moved, LibraryFilter.ALL, ""), pokemon("2ª generación")).isEmpty())
    }

    @Test
    fun theVirtualCategoryGoesByFingerprintSoItFollowsTheRomWhenItMoves() {
        val moved = known.moveToCategory(kirby, listOf("Plataformas"))
        val relocated = rom("Otros/Kirby.gb", "KIRBY")
        val prefs = moved.recordFingerprint(relocated.id, fp(kirby))
        assertEquals(listOf("Plataformas"), prefs.virtualFolderOf(relocated))
    }

    @Test
    fun withoutAConfirmedFingerprintNothingIsWritten() {
        val unknown = rom("Nuevo/Juego.gb", "JUEGO")
        val inferred = LibraryPreferencesData(fingerprints = mapOf(unknown.id to "f".repeat(64)), inferredFingerprints = setOf(unknown.id))
        assertEquals(LibraryPreferencesData(), LibraryPreferencesData().moveToCategory(unknown, listOf("X")))
        assertEquals(LibraryPreferencesData(), LibraryPreferencesData().addTag(unknown, "rpg"))
        assertEquals("una huella heredada sin confirmar tampoco", inferred, inferred.moveToCategory(unknown, listOf("X")))
        assertEquals(inferred, inferred.addTag(unknown, "rpg"))
    }

    @Test
    fun searchFindsTheCategoryAndAMovedGameOnlyInItsNewCategory() {
        fun ids(prefs: LibraryPreferencesData, q: String) = LibraryQuery.visible(all, prefs, LibraryFilter.ALL, q).map { it.id }.toSet()
        assertEquals(setOf(gold.id, crystal.id), ids(known, "2ª gen"))
        assertEquals("sin acentos ni mayúsculas", setOf(tetris.id), ids(known, "puzles"))
        val moved = known.moveToCategory(tetris, listOf("Favoritas"))
        assertEquals(setOf(tetris.id), ids(moved, "favoritas"))
        assertTrue(ids(moved, "puzles").isEmpty())
    }

    @Test
    fun filtersKeepWorkingOnMovedGames() {
        val moved = known.moveToCategory(gold, listOf("Kirby"))
        val kirbyGbc = LibraryQuery.visible(all, moved, LibraryFilter.GBC, "", LibraryCategory.Folder("Kirby")).map { it.id }
        assertEquals(listOf(gold.id), kirbyGbc)
        val pokemonCount = LibraryCategory.options(all, moved).single { it.category == LibraryCategory.Folder("Pokémon") }.count
        assertEquals(3, pokemonCount)
    }

    @Test
    fun typedCategoryPathsAreNormalizedAndValidated() {
        assertEquals(CategoryPaths.Parsed.Valid(pokemon("Favoritas")), CategoryPaths.parse("  Pokémon /  Favoritas "))
        assertEquals(CategoryPaths.Parsed.Valid(pokemon("2ª generación")), CategoryPaths.parse("Pokémon › 2ª generación"))
        assertEquals(CategoryPaths.Parsed.Valid(listOf("Mis juegos")), CategoryPaths.parse("Mis   juegos//"))
        assertEquals(CategoryPaths.Parsed.Invalid(CategoryPaths.Problem.EMPTY), CategoryPaths.parse(" / "))
        assertEquals(CategoryPaths.Parsed.Invalid(CategoryPaths.Problem.RESERVED), CategoryPaths.parse("_Revisar"))
        assertEquals(CategoryPaths.Parsed.Invalid(CategoryPaths.Problem.RESERVED), CategoryPaths.parse("Pokémon/.oculta"))
        assertEquals(CategoryPaths.Parsed.Invalid(CategoryPaths.Problem.TOO_DEEP), CategoryPaths.parse("a/b/c/d/e/f"))
        assertEquals(CategoryPaths.Parsed.Valid(listOf("a", "b", "c", "d", "e")), CategoryPaths.parse("a/b/c/d/e"))
        val long = "x".repeat(200)
        assertEquals(CategoryPaths.MAX_SEGMENT_LENGTH, (CategoryPaths.parse(long) as CategoryPaths.Parsed.Valid).path.single().length)
    }

    // ---- etiquetas ----

    @Test
    fun tagsAreAddedOnceNormalizedAndSortedByFingerprint() {
        val prefs = known.addTag(red, "  RPG ").addTag(red, "rpg").addTag(red, "#Pendiente").addTag(red, "dos   jugadores")
        assertEquals(listOf("dos jugadores", "Pendiente", "RPG"), prefs.tagsOf(red))
        assertEquals(prefs.tagsOf(red), prefs.tagsByFingerprint[fp(red)])
        assertEquals("vacía no se añade", prefs, prefs.addTag(red, "   #  "))
    }

    @Test
    fun removingTheLastTagLeavesNoEntry() {
        val prefs = known.addTag(red, "rpg").addTag(red, "pendiente")
        val one = prefs.removeTag(red, "RPG")
        assertEquals(listOf("pendiente"), one.tagsOf(red))
        assertEquals(known, one.removeTag(red, "pendiente"))
    }

    @Test
    fun aGameHasAtMostTheTagLimit() {
        val prefs = (1..30).fold(known) { p, i -> p.addTag(red, "e$i") }
        assertEquals(Tags.MAX_PER_GAME, prefs.tagsOf(red).size)
    }

    @Test
    fun theTagFilterCombinesWithTheConsoleFilterAndTheCategory() {
        val prefs = known.addTag(red, "rpg").addTag(gold, "RPG").addTag(kirby, "plataformas")
        fun ids(filter: LibraryFilter = LibraryFilter.ALL, category: LibraryCategory = LibraryCategory.All, tag: String?) =
            LibraryQuery.visible(all, prefs, filter, "", category, tag).map { it.id }.toSet()
        assertEquals(setOf(red.id, gold.id), ids(tag = "rpg"))
        assertEquals(setOf(gold.id), ids(LibraryFilter.GBC, tag = "rpg"))
        assertEquals(setOf(gold.id), ids(category = LibraryCategory.Folder(pokemon("2ª generación")), tag = "Rpg"))
        assertEquals(all.map { it.id }.toSet(), ids(tag = null))
        assertEquals(
            listOf(TagOption("plataformas", 1), TagOption("rpg", 2)),
            LibraryQuery.tagOptions(all, prefs),
        )
    }

    @Test
    fun searchFindsTags() {
        val prefs = known.addTag(kirby, "Plataformas")
        assertEquals(setOf(kirby.id), LibraryQuery.visible(all, prefs, LibraryFilter.ALL, "plataf").map { it.id }.toSet())
        assertEquals(listOf("Plataformas"), LibraryQuery.visible(all, prefs, LibraryFilter.ALL, "plataf").single().tags)
    }

    @Test
    fun hiddenGamesDoNotOfferTheirTagsInTheFilter() {
        val prefs = known.addTag(kirby, "plataformas").hide(kirby)
        assertTrue(LibraryQuery.tagOptions(all, prefs).isEmpty())
    }

    // ---- ajustes del inicio (por dispositivo) ----

    private val keys = listOf("Kirby", "Pokémon", "Puzles", LibraryCategory.Uncategorized.key)

    @Test
    fun withoutChangesTheHomeUsesNaturalOrderWithUncategorizedLast() {
        assertEquals(keys, HomeSettings().arrange(keys))
    }

    @Test
    fun movingACategoryChangesTheShelfOrder() {
        val settings = HomeSettings().move("Puzles", -2, keys)
        assertEquals(listOf("Puzles", "Kirby", "Pokémon", "."), settings.arrange(keys))
        val prefs = known.copy(home = settings)
        assertEquals(
            listOf(LibraryCategory.Folder("Puzles"), LibraryCategory.Folder("Kirby"), LibraryCategory.Folder("Pokémon"), LibraryCategory.Uncategorized),
            LibraryHome.sections(all, prefs).shelves.map { it.category },
        )
        assertEquals("no pasa del principio", settings, settings.move("Puzles", -1, keys))
    }

    @Test
    fun pinnedCategoriesGoFirstAndCannotBeMovedBelowTheUnpinned() {
        val settings = HomeSettings().withPinned("Puzles", true).withPinned(".", true)
        assertEquals(listOf("Puzles", ".", "Kirby", "Pokémon"), settings.arrange(keys))
        assertEquals("dentro de las fijadas sí se ordena", listOf(".", "Puzles", "Kirby", "Pokémon"), settings.move(".", -1, keys).arrange(keys))
        assertEquals("la última fijada no baja entre las demás", settings.arrange(keys), settings.move(".", 1, keys).arrange(keys))
        assertEquals(listOf("Puzles", "Kirby", "Pokémon", "."), settings.withPinned(".", false).arrange(keys))
        assertTrue(LibraryHome.sections(all, known.copy(home = settings)).shelves.first().pinned)
    }

    @Test
    fun hiddenCategoriesLeaveTheHomeButStayInTheSettingsList() {
        val settings = HomeSettings().withHidden("Pokémon", true)
        val prefs = known.copy(home = settings)
        assertFalse(LibraryHome.sections(all, prefs).shelves.any { it.category == LibraryCategory.Folder("Pokémon") })
        val rows = LibraryHome.arrangement(all, prefs)
        assertEquals(keys, rows.map { it.key })
        assertTrue(rows.single { it.key == "Pokémon" }.hidden)
        assertEquals(4, rows.single { it.key == "Pokémon" }.count)
        assertEquals("los juegos siguen en «Todos los juegos»", all.size, shown(prefs).size)
    }

    @Test
    fun aNewFolderAppearsAfterTheArrangedOnesAndAMissingOneKeepsItsPlace() {
        val settings = HomeSettings().move("Puzles", -2, keys)
        // H11: una carpeta nueva va detrás de las ordenadas pero antes de «Sin categoría», que sigue al final.
        val withNew = listOf("Arcade") + keys
        assertEquals(listOf("Puzles", "Kirby", "Pokémon", "Arcade", "."), settings.arrange(withNew))
        val withoutKirby = keys - "Kirby"
        assertEquals(listOf("Puzles", "Pokémon", "."), settings.arrange(withoutKirby))
        assertEquals("al volver recupera su sitio", listOf("Puzles", "Kirby", "Pokémon", "."), settings.arrange(keys))
    }

    // ---- vista por categoría ----

    @Test
    fun eachCategoryRemembersItsOwnLayoutAndFallsBackToTheLibraryLayout() {
        val pokemonCategory = LibraryCategory.Folder("Pokémon")
        val nested = LibraryCategory.Folder(pokemon("2ª generación"))
        val prefs = known.copy(layout = LibraryLayout.GRID).withCategoryLayout(nested, LibraryLayout.LIST)
        assertEquals(LibraryLayout.LIST, prefs.layoutFor(nested))
        assertEquals(LibraryLayout.GRID, prefs.layoutFor(pokemonCategory))
        assertEquals(LibraryLayout.LIST, prefs.copy(layout = LibraryLayout.LIST).layoutFor(pokemonCategory))
        assertEquals(LibraryLayout.GRID, prefs.withCategoryLayout(LibraryCategory.Uncategorized, LibraryLayout.GRID).layoutFor(LibraryCategory.Uncategorized))
    }

    // ---- respuesta a la auditoría ----

    @Test
    fun theFavoritesRowCountsEveryFavoriteEvenBeyondTheLimit() {
        // H1: con 11 favoritos la fila muestra 10 pero dice 11.
        val many = (1..11).map { rom("Juegos/Juego $it.gb", "JUEGO $it") }
        val prefs = many.fold(LibraryPreferencesData()) { p, e -> p.recordFingerprint(e.id, fp(e)).toggleFavorite(e) }
        val home = LibraryHome.sections(many, prefs)
        assertEquals(LibraryHome.SHELF_LIMIT, home.favorites.size)
        assertEquals(11, home.favoritesTotal)
    }

    @Test
    fun aCopyShownInItsOwnFolderIsNotMarkedAsMoved() {
        // H10: dos copias del mismo juego; se muestran en la carpeta de una de ellas: esa no lleva la insignia.
        val copy = rom("Kirby/Pokemon Red.gb", "POKEMON RED")
        val prefs = known.recordFingerprint(copy.id, fp(red)).moveToCategory(copy, red.folderPath)
        val shown = LibraryQuery.visible(all + copy, prefs, LibraryFilter.ALL, "")
        assertFalse("la copia que ya está en esa carpeta", shown.single { it.id == red.id }.isMovedInApp)
        assertTrue("la otra copia sí se ve movida", shown.single { it.id == copy.id }.isMovedInApp)
        assertEquals(red.folderPath, shown.single { it.id == copy.id }.categoryPath)
    }

    @Test
    fun movingAPinnedCategoryKeepsTheRelativeOrderSoUnpinningReturnsItToItsPlace() {
        // H11: mover dentro de las fijadas no guarda las fijadas delante de todo.
        val pinned = HomeSettings().withPinned("Puzles", true).withPinned(".", true).move(".", -1, keys)
        assertEquals(listOf(".", "Puzles", "Kirby", "Pokémon"), pinned.arrange(keys))
        assertEquals("al soltar «Puzles» vuelve detrás de Pokémon", listOf(".", "Kirby", "Pokémon", "Puzles"), pinned.withPinned("Puzles", false).arrange(keys))
        assertEquals("al soltar las dos se respeta el orden relativo elegido", listOf("Kirby", "Pokémon", ".", "Puzles"), pinned.withPinned("Puzles", false).withPinned(".", false).arrange(keys))
    }

    @Test
    fun aTypedCategoryReusesAnExistingOneThatDiffersOnlyInCaseOrAccents() {
        // H13: «pokemon/para jugar» con «Pokémon» existente se guarda como «Pokémon/para jugar».
        val known = listOf(listOf("Pokémon"), listOf("Pokémon", "1ª generación"), listOf("Aventuras"))
        assertEquals(listOf("Pokémon", "1ª generación"), CategoryPaths.matchExisting(listOf("pokemon", "1ª GENERACIÓN"), known))
        assertEquals(listOf("Pokémon", "para jugar"), CategoryPaths.matchExisting(listOf("POKEMON", "para jugar"), known))
        assertEquals(listOf("Para jugar"), CategoryPaths.matchExisting(listOf("Para jugar"), known))
        assertEquals("un nivel igual en otra rama no cuenta", listOf("Kirby", "1ª generación"), CategoryPaths.matchExisting(listOf("Kirby", "1ª generación"), known))
    }
}

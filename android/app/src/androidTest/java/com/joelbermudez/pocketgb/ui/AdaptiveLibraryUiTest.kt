package com.joelbermudez.pocketgb.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.joelbermudez.pocketgb.library.DetailsLoad
import com.joelbermudez.pocketgb.library.GameDetails
import com.joelbermudez.pocketgb.library.LibraryCategory
import com.joelbermudez.pocketgb.library.LibraryFilter
import com.joelbermudez.pocketgb.library.LibraryPreferencesData
import com.joelbermudez.pocketgb.library.LibraryState
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.ui.details.GameDetailsContent
import com.joelbermudez.pocketgb.ui.library.GameActions
import com.joelbermudez.pocketgb.ui.library.LibraryContent
import com.joelbermudez.pocketgb.ui.theme.PocketGBTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * N3 Android: carril «Continuar jugando» solo con reanudables y del ancho de la cuadrícula (N3a, ND15), barra flotante
 * de la biblioteca en horizontal (N3b) y detalle a dos columnas con «Jugar» visible (N3a). El horizontal se simula con
 * `DeviceConfigurationOverride.ForcedSize` (la disposición se decide por el espacio disponible).
 */
@RunWith(AndroidJUnit4::class)
class AdaptiveLibraryUiTest {
    @get:Rule
    val compose = createComposeRule()

    private fun entry(id: String, title: String, color: Boolean = false) = RomEntry(
        id = id,
        uri = "content://t/$id",
        fileName = id.substringAfterLast('/'),
        title = title,
        isColor = color,
        sizeBytes = 32L * 1024,
        headerChecksumOk = true,
        problem = null,
    )

    private val red = entry("Pokémon/Red.gb", "RED")
    private val yellow = entry("Pokémon/Amarillo/Yellow.gbc", "YELLOW", color = true)
    private val kirby = entry("Kirby/Kirby.gb", "KIRBY")
    private val alpha = entry("Alpha.gb", "ALPHA")
    private val tetra = entry("Tetra.gb", "TETRA")
    private val games = listOf(red, yellow, kirby, alpha, tetra)

    private fun fingerprintOf(entry: RomEntry) = "%064x".format(entry.id.hashCode().toLong() and 0xFFFFFFFFL)

    private fun played(vararg entries: RomEntry) = entries.foldIndexed(LibraryPreferencesData()) { i, prefs, entry ->
        prefs.recordPlayed(entry.id, fingerprintOf(entry), at = 100L - i)
    }

    private val landscape = DpSize(640.dp, 360.dp)

    @Composable
    private fun Library(
        initial: LibraryPreferencesData = LibraryPreferencesData(),
        resumable: Set<String> = emptySet(),
        artwork: Set<String> = games.map(::fingerprintOf).toSet(),
    ) {
        var prefs by remember { mutableStateOf(initial) }
        var query by remember { mutableStateOf("") }
        var filter by remember { mutableStateOf(LibraryFilter.ALL) }
        var category by remember { mutableStateOf<LibraryCategory>(LibraryCategory.All) }
        PocketGBTheme {
            LibraryContent(
                state = LibraryState.Ready(games, "Roms"),
                prefs = prefs,
                query = query,
                filter = filter,
                onQueryChange = { query = it },
                onFilterChange = { filter = it },
                onLayoutChange = { prefs = prefs.copy(layout = it) },
                onSortChange = { prefs = prefs.copy(sort = it) },
                onChooseFolder = {},
                onRescan = {},
                actions = GameActions(
                    onOpenDetails = {},
                    onToggleFavorite = {},
                    onHide = {},
                    onPlay = {},
                    canResume = { it.id in resumable },
                ),
                artworkFingerprints = artwork,
                category = category,
                onCategoryChange = { category = it },
            )
        }
    }

    private fun show(size: DpSize? = null, content: @Composable () -> Unit) = compose.setContent {
        if (size == null) {
            content()
        } else {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) { content() }
        }
    }

    /** Rectángulo en pantalla (los paneles viven en su propia ventana emergente). */
    private fun SemanticsNode.screenRect(): Rect =
        Rect(positionOnScreen, androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat()))

    private fun SemanticsNodeInteraction.screenRect(): Rect = fetchSemanticsNode().screenRect()

    // ---- N3a · carril ----

    @Test
    fun theRailOnlyShowsGamesThatCanBeContinued() {
        show { Library(played(red, yellow, kirby), resumable = setOf(yellow.id)) }
        compose.onNodeWithTag("recent-row").assertIsDisplayed()
        compose.onAllNodesWithTag("continue-card").assertCountEquals(1)
    }

    @Test
    fun withoutResumableGamesThereIsNoRail() {
        show { Library(played(red, yellow, kirby), resumable = emptySet()) }
        compose.onAllNodesWithTag("recent-row").assertCountEquals(0)
    }

    @Test
    fun railCardsAreAsWideAsGridCellsAndAlignedWithThem() {
        show { Library(played(red, yellow, kirby), resumable = setOf(red.id, yellow.id, kirby.id)) }
        compose.waitForIdle()
        val rail = compose.onAllNodesWithTag("continue-card").fetchSemanticsNodes().map { it.screenRect() }
        val grid = compose.onAllNodesWithTag("game-card").fetchSemanticsNodes().map { it.screenRect() }
        assertTrue("hay tarjetas: ${rail.size} / ${grid.size}", rail.size >= 2 && grid.size >= 2)
        assertEquals("mismo ancho que una celda", grid[0].width, rail[0].width, 2f)
        assertEquals("primera columna alineada", grid[0].left, rail[0].left, 2f)
        assertEquals("segunda columna alineada", grid[1].left, rail[1].left, 2f)
    }

    @Test
    fun inLandscapeTheRailShowsExactlyAsManyCardsAsColumnsWithoutCuttingThem() {
        // 640 dp de ancho: 4 columnas. Con 5 reanudables se ven 4 enteras y la quinta empieza fuera de la pantalla.
        show(landscape) { Library(played(*games.toTypedArray()), resumable = games.map { it.id }.toSet()) }
        compose.waitForIdle()
        val root = compose.onNodeWithTag("continue-rail-row").screenRect()
        val cards = compose.onAllNodesWithTag("continue-card").fetchSemanticsNodes().map { it.screenRect() }
        val inside = cards.count { it.left >= root.left - 1f && it.right <= root.right + 1f }
        val cut = cards.count { it.left < root.right - 1f && it.right > root.right + 1f }
        assertEquals("tarjetas enteras = columnas", 4, inside)
        assertEquals("ninguna tarjeta cortada al borde", 0, cut)
    }

    // ---- N3b · barra flotante en horizontal ----

    @Test
    fun inLandscapeTheSearchFieldAndChipsGiveWayToTheFloatingToolbar() {
        show(landscape) { Library() }
        compose.onNodeWithTag("library-tools").assertIsDisplayed()
        compose.onAllNodesWithTag("library-search").assertCountEquals(0)
        compose.onAllNodesWithTag("filter-ALL").assertCountEquals(0)
        compose.onNodeWithTag("library-pinned-header").assertIsDisplayed()
    }

    @Test
    fun inPortraitTheLibraryKeepsItsSearchFieldAndChips() {
        show { Library() }
        compose.onNodeWithTag("library-search").assertIsDisplayed()
        compose.onNodeWithTag("filter-ALL").assertIsDisplayed()
        compose.onAllNodesWithTag("library-tools").assertCountEquals(0)
    }

    @Test
    fun eachToolbarButtonOpensItsPanelUpwardsWithoutCoveringTheSectionTitle() {
        show(landscape) { Library() }
        val toolbar = compose.onNodeWithTag("library-tools").screenRect()
        val header = compose.onNodeWithTag("library-pinned-header").screenRect()
        for (panel in listOf("filters", "categories", "view")) {
            compose.onNodeWithTag("tools-$panel").performClick()
            val rect = compose.onNodeWithTag("library-panel-$panel").assertIsDisplayed().screenRect()
            assertTrue("$panel se abre hacia arriba: $rect / $toolbar", rect.bottom <= toolbar.top + 1f)
            assertTrue("$panel no tapa el título de sección: $rect / $header", rect.top >= header.bottom - 1f)
            Espresso.pressBack()
            compose.onAllNodesWithTag("library-panel-$panel").assertCountEquals(0)
        }
    }

    @Test
    fun filtersCategoriesAndViewApplyFromTheirPanels() {
        show(landscape) { Library() }
        compose.onNodeWithTag("tools-filters").performClick()
        compose.onNodeWithTag("panel-filter-GBC").performClick()
        compose.onAllNodesWithTag("library-panel-filters").assertCountEquals(0)
        compose.onNodeWithTag("library-section-title", useUnmergedTree = true).assertTextEquals("GBC")
        compose.onAllNodesWithTag("game-card").assertCountEquals(1)

        compose.onNodeWithTag("tools-filters").performClick()
        compose.onNodeWithTag("panel-filter-ALL").performClick()
        compose.onNodeWithTag("tools-categories").performClick()
        compose.onNodeWithTag("panel-category-folder-Pokémon").performClick()
        compose.onNodeWithTag("library-section-title", useUnmergedTree = true).assertTextEquals("Pokémon")
        compose.onAllNodesWithTag("game-card").assertCountEquals(2)

        compose.onNodeWithTag("tools-categories").performClick()
        compose.onNodeWithTag("panel-category-root").performClick()
        compose.onNodeWithTag("library-section-title", useUnmergedTree = true).assertTextEquals("Sin categoría")
        compose.onAllNodesWithTag("game-card").assertCountEquals(2)

        compose.onNodeWithTag("tools-view").performClick()
        compose.onNodeWithTag("panel-layout-LIST").performClick()
        compose.onAllNodesWithTag("game-list-item").assertCountEquals(2)
    }

    @Test
    fun searchOpensAtTheTopAndClosingItRestoresTheLibrary() {
        show(landscape) { Library() }
        compose.onNodeWithTag("tools-search").performClick()
        compose.onNodeWithTag("library-landscape-search").assertIsDisplayed()
        compose.onAllNodesWithTag("library-tools").assertCountEquals(0)
        compose.onNodeWithTag("library-landscape-search").performTextInput("yel")
        compose.onAllNodesWithTag("game-list-item").assertCountEquals(1)
        compose.onNodeWithTag("library-search-close").performClick()
        compose.onAllNodesWithTag("library-landscape-search").assertCountEquals(0)
        compose.onNodeWithTag("library-tools").assertIsDisplayed()
        compose.onAllNodesWithTag("game-list-item").assertCountEquals(0)
        compose.onNodeWithTag("library-section-title", useUnmergedTree = true).assertTextEquals("Todos los juegos")
    }

    @Test
    fun backClosesTheLandscapeSearch() {
        show(landscape) { Library() }
        compose.onNodeWithTag("tools-search").performClick()
        compose.onNodeWithTag("library-landscape-search").performTextInput("kir")
        Espresso.closeSoftKeyboard()
        Espresso.pressBack()
        compose.onAllNodesWithTag("library-landscape-search").assertCountEquals(0)
        compose.onNodeWithTag("library-tools").assertIsDisplayed()
    }

    @Test
    fun inPortraitTheMoreMenuOffersTheCategories() {
        show { Library() }
        compose.onNodeWithTag("view-menu").performClick()
        compose.onNodeWithTag("menu-category-folder-Kirby").performClick()
        compose.onNodeWithTag("library-section-title", useUnmergedTree = true).assertTextEquals("Kirby")
        compose.onAllNodesWithTag("game-card").assertCountEquals(1)
    }

    // ---- N3a · detalle ----

    @Composable
    private fun Details(canResume: Boolean = false) {
        PocketGBTheme {
            GameDetailsContent(
                entry = red,
                load = DetailsLoad.Loaded(
                    GameDetails(red, "MBC3 + RAM + batería", 1024 * 1024, 32 * 1024, hasBattery = true, hasRtc = false,
                        headerChecksumOk = true, globalChecksumOk = true, fingerprint = fingerprintOf(red)),
                ),
                favorite = false,
                lastPlayedAt = 10L,
                onPlay = {},
                onToggleFavorite = {},
                onHide = {},
                onBack = {},
                canResume = canResume,
            )
        }
    }

    @Test
    fun detailsInLandscapeUseTwoColumnsWithPlayVisibleWithoutScrolling() {
        show(landscape) { Details(canResume = true) }
        val columns = compose.onNodeWithTag("game-details-two-columns").assertIsDisplayed().screenRect()
        val info = compose.onNodeWithTag("game-details-info").screenRect()
        val artwork = compose.onNodeWithTag("game-details-artwork").screenRect()
        val play = compose.onNodeWithTag("game-details-play").assertIsDisplayed().screenRect()
        assertTrue("imagen a la izquierda: $artwork / $play", artwork.right <= play.left)
        assertTrue("imagen dentro del alto: $artwork / $columns", artwork.bottom <= columns.bottom + 1f)
        assertTrue("«Continuar» entero sin desplazar: $play / $info", play.bottom <= info.bottom + 1f)
        compose.onNodeWithTag("game-details-play-from-start").assertIsDisplayed()
    }

    @Test
    fun detailsInPortraitKeepTheImageBelowFortyFivePercentOfTheHeight() {
        show { Details() }
        compose.onAllNodesWithTag("game-details-two-columns").assertCountEquals(0)
        val info = compose.onNodeWithTag("game-details-info").screenRect()
        val artwork = compose.onNodeWithTag("game-details-artwork").screenRect()
        assertTrue("≤ 45 %: ${artwork.height} de ${info.height}", artwork.height <= info.height * 0.45f + 2f)
        val play = compose.onNodeWithTag("game-details-play").screenRect()
        assertTrue("«Jugar» visible sin desplazar: $play / $info", play.bottom <= info.bottom + 1f)
    }

    @Test
    fun theTechnicalInformationCanBeFolded() {
        show { Details() }
        compose.onNodeWithTag("game-details-technical-toggle").performClick()
        compose.onAllNodesWithTag("game-details-technical").assertCountEquals(0)
        compose.onAllNodesWithTag("game-details-fingerprint").assertCountEquals(0)
        compose.onNodeWithTag("game-details-technical-toggle").performClick()
        compose.onAllNodesWithTag("game-details-technical").assertCountEquals(1)
    }
}

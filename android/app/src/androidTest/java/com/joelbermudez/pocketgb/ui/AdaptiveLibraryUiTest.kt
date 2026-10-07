package com.joelbermudez.pocketgb.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import com.joelbermudez.pocketgb.ui.library.LocalLibraryToolsPreset
import com.joelbermudez.pocketgb.ui.library.LibraryToolsPreset
import com.joelbermudez.pocketgb.library.RomLocation
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.then
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

    /** N4: la categoría que se pidió abrir (panel Categorías o «⋮»). */
    private var openedCategory: LibraryCategory? = null

    /** Teléfono girado (escalado para caber en el emulador vertical). */
    private val landscape = DpSize(640.dp, 360.dp)

    /**
     * Más ancho que alto sin escalar (cabe en los 360 dp del emulador vertical): los paneles viven en su propia ventana,
     * con la densidad real, y así sus medidas coinciden con las de la biblioteca.
     */
    private val landscapeUnscaled = DpSize(360.dp, 340.dp)

    @Composable
    private fun Library(
        initial: LibraryPreferencesData = LibraryPreferencesData(),
        resumable: Set<String> = emptySet(),
        artwork: Set<String> = games.map(::fingerprintOf).toSet(),
        preset: LibraryToolsPreset = LibraryToolsPreset(),
    ) {
        // N4: sin estanterías del inicio (estas pruebas son de N3: herramientas, paneles y carril; las del inicio, en
        // HomeUiTest).
        var prefs by remember { mutableStateOf(initial.copy(home = initial.home.copy(hidden = setOf("Pokémon", "Kirby", ".")))) }
        var query by remember { mutableStateOf("") }
        var filter by remember { mutableStateOf(LibraryFilter.ALL) }
        CompositionLocalProvider(LocalLibraryToolsPreset provides preset) {
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
                onOpenCategory = { openedCategory = it },
            )
        }
    }

    /**
     * El tema va fuera de `ForcedSize`: `PocketGBTheme` pide la ventana de la actividad y dentro del override el contexto
     * es un envoltorio. [size] se lee en cada composición (una prueba puede «girar» cambiándolo).
     */
    private fun show(size: () -> DpSize? = { null }, fontScale: Float = 1f, content: @Composable () -> Unit) = compose.setContent {
        PocketGBTheme {
            // Como `AppScaffold`: las barras del sistema ya se descontaron fuera de la pantalla.
            val framed: @Composable () -> Unit = {
                Box(Modifier.fillMaxSize().consumeWindowInsets(WindowInsets.safeDrawing)) { content() }
            }
            // Fuente fija (100 % salvo que se pida otra): el emulador compartido puede tener otra escala del sistema (se
            // vio 1,3) y cambiaría las columnas que se esperan.
            val forced = size()
            val override = if (forced == null) {
                DeviceConfigurationOverride.FontScale(fontScale)
            } else {
                DeviceConfigurationOverride.ForcedSize(forced) then DeviceConfigurationOverride.FontScale(fontScale)
            }
            DeviceConfigurationOverride(override) { framed() }
        }
    }

    private fun show(size: DpSize?, fontScale: Float = 1f, content: @Composable () -> Unit) = show({ size }, fontScale, content)

    /** Rectángulo en pantalla (los paneles viven en su propia ventana emergente). */
    private fun SemanticsNode.screenRect(): Rect =
        Rect(positionOnScreen, androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat()))

    private fun SemanticsNodeInteraction.screenRect(): Rect = fetchSemanticsNode().screenRect()

    // ---- N3a · carril ----

    @Test
    fun theRailOnlyShowsGamesThatCanBeContinued() {
        show(null) { Library(played(red, yellow, kirby), resumable = setOf(yellow.id)) }
        compose.onNodeWithTag("recent-row").assertIsDisplayed()
        compose.onAllNodesWithTag("continue-card").assertCountEquals(1)
    }

    @Test
    fun withoutResumableGamesThereIsNoRail() {
        show(null) { Library(played(red, yellow, kirby), resumable = emptySet()) }
        compose.onAllNodesWithTag("recent-row").assertCountEquals(0)
    }

    @Test
    fun railCardsAreAsWideAsGridCellsAndAlignedWithThem() {
        show(null) { Library(played(red, yellow, kirby), resumable = setOf(red.id, yellow.id, kirby.id)) }
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
        assertEquals("tarjetas enteras = columnas (fila $root, tarjetas $cards)", 4, inside)
        assertEquals("ninguna tarjeta cortada al borde (fila $root, tarjetas $cards)", 0, cut)
    }

    // ---- N3b · horizontal: iconos arriba en reposo, barra flotante al desplazar ----

    private fun headerRect() = compose.onNodeWithTag("library-pinned-header").screenRect()

    private fun assertPanelClearOfTitle(panel: Rect, header: Rect, what: String) {
        assertTrue("$what no tapa el título de sección: panel $panel / título $header", panel.bottom <= header.top + 1f || panel.top >= header.bottom - 1f)
    }

    @Test
    fun atRestTheToolsAreIconsInTheTopBarWithoutSearchFieldNorFloatingToolbar() {
        show(landscape) { Library(played(red, yellow, kirby), resumable = setOf(red.id, yellow.id, kirby.id)) }
        for (tool in listOf("search", "filters", "categories", "view")) compose.onNodeWithTag("bar-$tool").assertIsDisplayed()
        compose.onAllNodesWithTag("library-tools").assertCountEquals(0)
        compose.onAllNodesWithTag("library-search").assertCountEquals(0)
        compose.onAllNodesWithTag("filter-ALL").assertCountEquals(0)
        compose.onNodeWithTag("library-pinned-header").assertExists()
    }

    @Test
    fun inPortraitTheLibraryKeepsItsSearchFieldAndChips() {
        show(null) { Library() }
        compose.onNodeWithTag("library-search").assertIsDisplayed()
        compose.onNodeWithTag("filter-ALL").assertIsDisplayed()
        compose.onAllNodesWithTag("library-tools").assertCountEquals(0)
        compose.onAllNodesWithTag("bar-filters").assertCountEquals(0)
    }

    @Test
    fun atRestEachPanelOpensDownwardsBelowTheTopBarWithoutCoveringTheTitle() {
        // Con el carril (título más abajo) y sin él (título justo bajo la barra).
        var rail by mutableStateOf(true)
        show(landscapeUnscaled) {
            key(rail) {
                if (rail) Library(played(red, yellow, kirby), resumable = setOf(red.id, yellow.id, kirby.id)) else Library()
            }
        }
        for (withRail in listOf(true, false)) {
            rail = withRail
            compose.waitForIdle()
            val bar = compose.onNodeWithTag("library-bar-tools").screenRect()
            for (panel in listOf("filters", "categories", "view")) {
                compose.onNodeWithTag("bar-$panel").performClick()
                val rect = compose.onNodeWithTag("library-panel-$panel").assertIsDisplayed().screenRect()
                assertTrue("$panel cuelga de la barra superior: $rect / $bar", rect.top >= bar.bottom - 1f)
                assertPanelClearOfTitle(rect, headerRect(), "$panel (carril=$withRail)")
                Espresso.pressBack()
                compose.onAllNodesWithTag("library-panel-$panel").assertCountEquals(0)
            }
        }
    }

    @Test
    fun scrollingShowsTheFloatingToolbarAndScrollingBackUpHidesIt() {
        show(landscapeUnscaled) { Library(played(red, yellow, kirby), resumable = setOf(red.id, yellow.id, kirby.id)) }
        compose.onAllNodesWithTag("library-tools").assertCountEquals(0)
        compose.onNodeWithTag("library-collection").performTouchInput { swipeUp() }
        compose.onNodeWithTag("library-collection").performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.onNodeWithTag("library-tools").assertIsDisplayed()
        // Al subir un poco, enterAlways despliega la barra superior: vuelven sus iconos y la flotante se va.
        compose.onNodeWithTag("library-collection").performTouchInput { swipe(Offset(centerX, centerY), Offset(centerX, centerY + 300f), 300) }
        compose.waitForIdle()
        compose.onAllNodesWithTag("library-tools").assertCountEquals(0)
        compose.onNodeWithTag("bar-filters").assertIsDisplayed()
    }

    @Test
    fun scrolledEachFloatingPanelOpensUpwardsBelowThePinnedTitle() {
        show(landscapeUnscaled) {
            Library(played(red, yellow, kirby), resumable = setOf(red.id, yellow.id, kirby.id), preset = LibraryToolsPreset(scrolled = true))
        }
        val toolbar = compose.onNodeWithTag("library-tools").assertIsDisplayed().screenRect()
        for (panel in listOf("filters", "categories", "view")) {
            compose.onNodeWithTag("tools-$panel").performClick()
            val rect = compose.onNodeWithTag("library-panel-$panel").assertIsDisplayed().screenRect()
            assertTrue("$panel se abre hacia arriba: $rect / $toolbar", rect.bottom <= toolbar.top + 1f)
            assertPanelClearOfTitle(rect, headerRect(), panel)
            Espresso.pressBack()
            compose.onAllNodesWithTag("library-panel-$panel").assertCountEquals(0)
        }
    }

    @Test
    fun withAHalfFoldedBarAndLargeFontTheTopBarFoldsBeforeThePanelOpens() {
        // H2, caso intermedio: barra superior plegada al 60 % (la flotante ya se ve), fuente al 200 % y poco alto: entre el
        // título y la barra flotante no caben 112 dp hasta plegar del todo la barra superior.
        show(DpSize(360.dp, 260.dp), fontScale = 2f) {
            Library(
                played(red, yellow, kirby), resumable = setOf(red.id, yellow.id, kirby.id),
                preset = LibraryToolsPreset(scrolled = true, collapse = 0.6f),
            )
        }
        val barBefore = compose.onNodeWithTag("library-top-bar").screenRect()
        assertTrue("barra a medio plegar: $barBefore", barBefore.height > 1f)
        compose.onNodeWithTag("tools-filters").performClick()
        compose.waitForIdle()
        val barAfter = compose.onNodeWithTag("library-top-bar").screenRect()
        assertTrue("la barra superior se plegó: $barAfter", barAfter.height <= 1f)
        val rect = compose.onNodeWithTag("library-panel-filters").assertIsDisplayed().screenRect()
        assertPanelClearOfTitle(rect, headerRect(), "filtros con fuente al 200 %")
    }

    @Test
    fun theFloatingToolbarIsReadFirstByTalkBack() {
        show(landscapeUnscaled) { Library(preset = LibraryToolsPreset(scrolled = true)) }
        val index = compose.onNodeWithTag("library-tools").fetchSemanticsNode().config.getOrNull(SemanticsProperties.TraversalIndex)
        assertTrue("traversalIndex negativo: $index", index != null && index < 0f)
    }

    @Test
    fun filtersCategoriesAndViewApplyFromTheirPanels() {
        show(landscapeUnscaled) { Library() }
        compose.onNodeWithTag("bar-filters").performClick()
        compose.onNodeWithTag("panel-filter-GBC").performScrollTo().performClick()
        compose.onAllNodesWithTag("library-panel-filters").assertCountEquals(0)
        compose.onNodeWithTag("library-section-title", useUnmergedTree = true).assertTextEquals("GBC")
        compose.onAllNodesWithTag("game-card").assertCountEquals(1)

        compose.onNodeWithTag("bar-filters").performClick()
        compose.onNodeWithTag("panel-filter-ALL").performScrollTo().performClick()
        // N4: una categoría abre su pantalla (la biblioteca no se filtra).
        compose.onNodeWithTag("bar-categories").performClick()
        compose.onNodeWithTag("panel-category-folder-Pokémon").performScrollTo().performClick()
        compose.onAllNodesWithTag("library-panel-categories").assertCountEquals(0)
        assertEquals(LibraryCategory.Folder("Pokémon"), openedCategory)
        compose.onNodeWithTag("library-section-title", useUnmergedTree = true).assertTextEquals("Todos los juegos")

        compose.onNodeWithTag("bar-categories").performClick()
        compose.onNodeWithTag("panel-category-root").performScrollTo().performClick()
        assertEquals(LibraryCategory.Uncategorized, openedCategory)

        compose.onNodeWithTag("bar-view").performClick()
        compose.onNodeWithTag("panel-layout-LIST").performScrollTo().performClick()
        compose.onAllNodesWithTag("game-card").assertCountEquals(0)
        assertTrue(compose.onAllNodesWithTag("game-list-item").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun searchOpensAtTheTopAndClosingItRestoresTheLibrary() {
        show(landscape) { Library() }
        compose.onNodeWithTag("bar-search").performClick()
        compose.onNodeWithTag("library-landscape-search").assertIsDisplayed()
        compose.onAllNodesWithTag("bar-filters").assertCountEquals(0)
        compose.onNodeWithTag("library-landscape-search").performTextInput("yel")
        // Con el teclado abierto apenas queda alto para la lista: se cierra para ver el resultado.
        Espresso.closeSoftKeyboard()
        compose.onAllNodesWithTag("game-list-item").assertCountEquals(1)
        compose.onNodeWithTag("library-search-close").performClick()
        compose.onAllNodesWithTag("library-landscape-search").assertCountEquals(0)
        compose.onNodeWithTag("bar-filters").assertIsDisplayed()
        compose.onAllNodesWithTag("game-list-item").assertCountEquals(0)
        compose.onNodeWithTag("library-section-title", useUnmergedTree = true).assertTextEquals("Todos los juegos")
    }

    @Test
    fun backClosesTheLandscapeSearch() {
        show(landscape) { Library() }
        compose.onNodeWithTag("bar-search").performClick()
        compose.onNodeWithTag("library-landscape-search").performTextInput("kir")
        Espresso.closeSoftKeyboard()
        Espresso.pressBack()
        compose.onAllNodesWithTag("library-landscape-search").assertCountEquals(0)
        compose.onNodeWithTag("bar-search").assertIsDisplayed()
    }

    @Test
    fun closingTheSearchKeepsTheTopBarAsItWas() {
        // H7: con la barra superior a medio plegar, buscar y cerrar no la despliega (el contenido no salta).
        show(landscapeUnscaled) {
            Library(
                played(red, yellow, kirby), resumable = setOf(red.id, yellow.id, kirby.id),
                preset = LibraryToolsPreset(scrolled = true, collapse = 0.6f),
            )
        }
        val before = compose.onNodeWithTag("library-top-bar").screenRect()
        compose.onNodeWithTag("tools-search").performClick()
        compose.onNodeWithTag("library-landscape-search").assertIsDisplayed()
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("library-search-close").performClick()
        val after = compose.onNodeWithTag("library-top-bar").screenRect()
        assertEquals("misma altura de la barra superior", before.height, after.height, 1.5f)
    }

    @Test
    fun aSearchClearedInPortraitDoesNotReopenInLandscape() {
        // H7: buscar en horizontal, girar, borrar en vertical y volver a girar: la búsqueda horizontal no reaparece vacía.
        var size by mutableStateOf<DpSize?>(landscapeUnscaled)
        show({ size }) { Library() }
        compose.onNodeWithTag("bar-search").performClick()
        compose.onNodeWithTag("library-landscape-search").performTextInput("kir")
        Espresso.closeSoftKeyboard()
        size = DpSize(360.dp, 600.dp)
        compose.waitForIdle()
        compose.onNodeWithTag("library-search").performTextClearance()
        Espresso.closeSoftKeyboard()
        size = landscapeUnscaled
        compose.waitForIdle()
        compose.onAllNodesWithTag("library-landscape-search").assertCountEquals(0)
        compose.onNodeWithTag("bar-search").assertIsDisplayed()
    }

    @Test
    fun inPortraitTheMoreMenuOffersTheCategories() {
        show(null) { Library() }
        compose.onNodeWithTag("view-menu").performClick()
        compose.onNodeWithTag("menu-category-folder-Kirby").performClick()
        // N4: abre la pantalla de la categoría.
        assertEquals(LibraryCategory.Folder("Kirby"), openedCategory)
    }

    // ---- N3a · detalle ----

    @Composable
    private fun Details(canResume: Boolean = false, entry: RomEntry = red) {
        run {
            GameDetailsContent(
                entry = entry,
                load = DetailsLoad.Loaded(
                    GameDetails(entry, "MBC3 + RAM + batería", 1024 * 1024, 32 * 1024, hasBattery = true, hasRtc = false,
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
    fun withALongNameAndPathContinueStaysVisibleInTwoColumns() {
        // H3: alias de 80 caracteres, ruta de cinco carpetas y dos copias («También en»).
        val long = red.copy(
            id = "Clásicos de la consola/Nintendo y compañía/Pokémon/Primera generación/Kanto/Red.gb",
            folderPath = listOf("Clásicos de la consola", "Nintendo y compañía", "Pokémon", "Primera generación", "Kanto"),
            alias = "Pokémon Rojo — la partida principal con el equipo completo y todas las medallas.",
            alsoAt = listOf(RomLocation(listOf("Copias"), "Red (1).gb"), RomLocation(listOf("Copias", "Viejas"), "Red (2).gb")),
        )
        assertEquals(80, long.alias!!.length)
        show(landscape) { Details(canResume = true, entry = long) }
        compose.onNodeWithTag("game-details-two-columns").assertIsDisplayed()
        val info = compose.onNodeWithTag("game-details-info").screenRect()
        val play = compose.onNodeWithTag("game-details-play").assertIsDisplayed().screenRect()
        val fromStart = compose.onNodeWithTag("game-details-play-from-start").assertIsDisplayed().screenRect()
        assertTrue("«Continuar» entero: $play / $info", play.bottom <= info.bottom + 1f)
        assertTrue("«Jugar desde el inicio» entero: $fromStart / $info", fromStart.bottom <= info.bottom + 1f)
        val title = compose.onNodeWithTag("game-details-title").screenRect()
        assertTrue("el título va encima de «Continuar»: $title / $play", title.bottom <= play.top)
    }

    @Test
    fun withALongNameAndPathPlayStaysVisibleInPortrait() {
        // H3 también en vertical: «Jugar» va justo bajo el título, antes de la ruta y de «También en».
        val long = red.copy(
            id = "Clásicos de la consola/Nintendo y compañía/Pokémon/Primera generación/Kanto/Red.gb",
            folderPath = listOf("Clásicos de la consola", "Nintendo y compañía", "Pokémon", "Primera generación", "Kanto"),
            alias = "Pokémon Rojo — la partida principal con el equipo completo y todas las medallas.",
            alsoAt = listOf(RomLocation(listOf("Copias"), "Red (1).gb")),
        )
        show(null) { Details(canResume = true, entry = long) }
        val info = compose.onNodeWithTag("game-details-info").screenRect()
        val play = compose.onNodeWithTag("game-details-play").screenRect()
        assertTrue("«Continuar» visible sin desplazar: $play / $info", play.bottom <= info.bottom + 1f)
        val location = compose.onNodeWithTag("game-details-location", useUnmergedTree = true).fetchSemanticsNode().screenRect()
        assertTrue("la ruta va después: $location / $play", location.top >= play.bottom)
    }

    @Test
    fun detailsInPortraitKeepTheImageBelowFortyFivePercentOfTheHeight() {
        show(null) { Details() }
        compose.onAllNodesWithTag("game-details-two-columns").assertCountEquals(0)
        val info = compose.onNodeWithTag("game-details-info").screenRect()
        val artwork = compose.onNodeWithTag("game-details-artwork").screenRect()
        assertTrue("≤ 45 %: ${artwork.height} de ${info.height}", artwork.height <= info.height * 0.45f + 2f)
        val play = compose.onNodeWithTag("game-details-play").screenRect()
        assertTrue("«Jugar» visible sin desplazar: $play / $info", play.bottom <= info.bottom + 1f)
    }

    @Test
    fun theTechnicalInformationCanBeFolded() {
        show(null) { Details() }
        compose.onNodeWithTag("game-details-technical-toggle").performScrollTo().performClick()
        compose.onAllNodesWithTag("game-details-technical").assertCountEquals(0)
        compose.onAllNodesWithTag("game-details-fingerprint").assertCountEquals(0)
        compose.onNodeWithTag("game-details-technical-toggle").performScrollTo().performClick()
        compose.onAllNodesWithTag("game-details-technical").assertCountEquals(1)
    }
}

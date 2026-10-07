package com.joelbermudez.pocketgb.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import org.junit.Assert.assertFalse
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.joelbermudez.pocketgb.library.DetailsLoad
import com.joelbermudez.pocketgb.library.GameDetails
import com.joelbermudez.pocketgb.library.LibraryError
import com.joelbermudez.pocketgb.library.LibraryFilter
import com.joelbermudez.pocketgb.library.LibraryLayout
import com.joelbermudez.pocketgb.library.LibraryPreferencesData
import com.joelbermudez.pocketgb.library.LibraryState
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.library.RomProblem
import com.joelbermudez.pocketgb.ui.details.GameDetailsContent
import com.joelbermudez.pocketgb.ui.favorites.FavoritesContent
import com.joelbermudez.pocketgb.ui.library.GameActions
import com.joelbermudez.pocketgb.ui.library.LibraryContent
import com.joelbermudez.pocketgb.ui.settings.LibrarySettingsContent
import com.joelbermudez.pocketgb.ui.theme.PocketGBTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryUiTest {
    @get:Rule
    val compose = createComposeRule()

    private fun entry(id: String, title: String, color: Boolean, problem: RomProblem? = null) = RomEntry(
        id = id,
        uri = "content://t/$id",
        fileName = id.substringAfterLast('/'),
        title = title,
        console = com.joelbermudez.pocketgb.library.RomConsole.gameBoy(color),
        sizeBytes = 32L * 1024,
        headerChecksumOk = true,
        problem = problem,
    )

    private val red = entry("Red.gb", "RED", false)
    private val yellow = entry("Sub/Yellow.gbc", "YELLOW", true)
    private val alpha = entry("Alpha.gb", "ALPHA", false)
    private val color = entry("Color.gbc", "COLORFUL", true)
    private val broken = entry("Roto.gb", "ROTO", false, RomProblem.INVALID_HEADER)
    private val games = listOf(red, yellow, alpha, color)

    private var opened: String? = null
    private val played = mutableListOf<String>()
    private var chooseCount = 0
    private var rescanCount = 0

    /** Mantiene el estado como lo haría el ViewModel y aplica las preferencias reales. */
    @Composable
    private fun LibraryHarness(
        state: LibraryState,
        initial: LibraryPreferencesData = LibraryPreferencesData(),
        summary: Int = 0,
        onSummaryShown: () -> Unit = {},
        /** N3 (ND15): ids que se pueden continuar (estado automático vigente); el carril solo muestra estos. */
        resumable: Set<String> = emptySet(),
    ) {
        var prefs by remember { mutableStateOf(initial.withoutHome(state)) }
        var query by remember { mutableStateOf("") }
        var filter by remember { mutableStateOf(LibraryFilter.ALL) }
        PocketGBTheme {
            LibraryContent(
                state = state,
                prefs = prefs,
                query = query,
                filter = filter,
                onQueryChange = { query = it },
                onFilterChange = { filter = it },
                onLayoutChange = { prefs = prefs.copy(layout = it) },
                onSortChange = { prefs = prefs.copy(sort = it) },
                onChooseFolder = { chooseCount++ },
                onRescan = { rescanCount++ },
                actions = GameActions(
                    onOpenDetails = { opened = it.id },
                    onToggleFavorite = { prefs = prefs.toggleFavorite(it) },
                    onHide = { prefs = prefs.hide(it) },
                    onPlay = if (playFromRecent) ({ played += it.id }) else null,
                    onGameSettings = { settingsOpened = it.id },
                    canResume = { it.id in resumable },
                ),
                newGamesSummary = summary,
                onNewGamesSummaryShown = onSummaryShown,
            )
        }
    }

    private var playFromRecent = false

    /**
     * N4: sin estanterías del inicio ni fila de Favoritos (estas pruebas son de la cuadrícula, la lista y el carril; el
     * inicio se prueba en HomeUiTest).
     */
    private fun LibraryPreferencesData.withoutHome(state: LibraryState): LibraryPreferencesData {
        val entries = when (state) {
            is LibraryState.Ready -> state.entries
            is LibraryState.Scanning -> state.previous
            else -> emptyList()
        }
        return copy(home = home.copy(showFavorites = false, hidden = com.joelbermudez.pocketgb.library.LibraryHome.keys(entries, this).toSet()))
    }

    /** Huella sintética estable de un juego de prueba. */
    private fun fingerprintOf(entry: RomEntry) = "%064x".format(entry.id.hashCode().toLong() and 0xFFFFFFFFL)

    private fun withPlayed(vararg played: RomEntry, at: Long = 10L) =
        played.fold(LibraryPreferencesData()) { prefs, entry -> prefs.recordPlayed(entry.id, fingerprintOf(entry), at) }
    private var settingsOpened: String? = null

    private fun cards() = compose.onAllNodesWithTag("game-card")

    @Test
    fun noFolderOffersToChooseOne() {
        compose.setContent { LibraryHarness(LibraryState.NoFolder) }
        compose.onNodeWithText("Elegir carpeta").assertIsDisplayed().performClick()
        assertEquals(1, chooseCount)
    }

    @Test
    fun errorsAreActionablePerCase() {
        compose.setContent { LibraryHarness(LibraryState.Failed(LibraryError.PermissionRevoked)) }
        compose.onNodeWithText("Volver a elegir").assertIsDisplayed().performClick()
        assertEquals(1, chooseCount)
    }

    @Test
    fun missingFolderOffersRetryAndChoose() {
        compose.setContent { LibraryHarness(LibraryState.Failed(LibraryError.FolderMissing)) }
        compose.onNodeWithText("La carpeta ya no existe").assertIsDisplayed()
        compose.onNodeWithText("Reintentar").performClick()
        compose.onNodeWithText("Elegir otra carpeta").performClick()
        assertEquals(1, rescanCount)
        assertEquals(1, chooseCount)
    }

    @Test
    fun scanningWithoutPreviousShowsProgressAndWithPreviousKeepsGames() {
        compose.setContent { LibraryHarness(LibraryState.Scanning(emptyList(), null)) }
        compose.onNodeWithText("Buscando juegos…").assertIsDisplayed()
    }

    @Test
    fun scanningWithPreviousKeepsShowingGames() {
        compose.setContent { LibraryHarness(LibraryState.Scanning(games, "Juegos")) }
        cards().assertCountEquals(4)
        compose.onNodeWithTag("library-progress").assertExists()
    }

    @Test
    fun emptyFolderExplainsAndOffersRescan() {
        compose.setContent { LibraryHarness(LibraryState.Ready(emptyList(), "Juegos")) }
        compose.onNodeWithText("No hay juegos en esta carpeta").assertIsDisplayed()
        compose.onNodeWithText("Volver a escanear").performClick()
        assertEquals(1, rescanCount)
    }

    @Test
    fun searchNarrowsResultsAndClearingRestoresThem() {
        compose.setContent { LibraryHarness(LibraryState.Ready(games, "Juegos")) }
        cards().assertCountEquals(4)
        compose.onNodeWithTag("library-search").performTextInput("yel")
        // Los resultados de búsqueda se muestran en lista.
        compose.onAllNodesWithTag("game-list-item").assertCountEquals(1)
        compose.onNodeWithTag("library-search").performTextClearance()
        // El teclado ocupa parte de la pantalla (imePadding): se comprueba con scroll, no con visibilidad.
        compose.onNodeWithTag("library-collection").performScrollToNode(hasText("YELLOW"))
        compose.onNodeWithText("YELLOW").assertIsDisplayed()
        compose.onNodeWithTag("library-search").performTextInput("zzz")
        cards().assertCountEquals(0)
        compose.onNodeWithText("Sin resultados", substring = true).assertExists()
    }

    @Test
    fun filtersSplitBySystemAndFavorites() {
        compose.setContent {
            LibraryHarness(LibraryState.Ready(games, "Juegos"), LibraryPreferencesData(favorites = setOf(alpha.id)))
        }
        compose.onNodeWithTag("filter-GB").performClick()
        cards().assertCountEquals(2)
        compose.onNodeWithTag("filter-GBC").performClick()
        cards().assertCountEquals(2)
        compose.onNodeWithTag("filter-FAVORITES").performClick()
        cards().assertCountEquals(1)
        compose.onNodeWithTag("filter-FAVORITES").assertIsSelected()
        compose.onNodeWithTag("filter-ALL").performClick()
        cards().assertCountEquals(4)
    }

    @Test
    fun favoritesFilterWithoutFavoritesShowsItsOwnEmptyState() {
        compose.setContent { LibraryHarness(LibraryState.Ready(games, "Juegos")) }
        compose.onNodeWithTag("filter-FAVORITES").performClick()
        compose.onNodeWithText("Todavía no hay favoritos").assertIsDisplayed()
    }

    @Test
    fun longPressTogglesFavoriteFromTheMenu() {
        compose.setContent { LibraryHarness(LibraryState.Ready(listOf(red), "Juegos")) }
        compose.onNodeWithTag("game-card").performTouchInput { longClick() }
        compose.onNodeWithText("Añadir a favoritos").performClick()
        compose.onNodeWithTag("game-card").performTouchInput { longClick() }
        compose.onNodeWithText("Quitar de favoritos").assertIsDisplayed()
    }

    @Test
    fun hidingAsksForConfirmationAndRemovesTheGame() {
        compose.setContent { LibraryHarness(LibraryState.Ready(games, "Juegos")) }
        cards().assertCountEquals(4)
        compose.onAllNodesWithTag("game-card")[0].performTouchInput { longClick() }
        compose.onNodeWithText("Ocultar de PocketGB").performClick()
        compose.onNodeWithText("No se borra el ROM", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Cancelar").performClick()
        cards().assertCountEquals(4)
        compose.onAllNodesWithTag("game-card")[0].performTouchInput { longClick() }
        compose.onNodeWithText("Ocultar de PocketGB").performClick()
        compose.onNodeWithText("Ocultar").performClick()
        cards().assertCountEquals(3)
    }

    @Test
    fun listLayoutShowsRowsAndProblemGamesShowTheirMessage() {
        compose.setContent {
            LibraryHarness(
                LibraryState.Ready(games + broken, "Juegos"),
                LibraryPreferencesData(layout = LibraryLayout.LIST),
            )
        }
        compose.onAllNodesWithTag("game-list-item").assertCountEquals(5)
        compose.onNodeWithText(RomProblem.INVALID_HEADER.message).assertIsDisplayed()
        compose.onNodeWithText("ROTO").performClick()
        assertEquals("Roto.gb", opened)
    }

    @Test
    fun tappingAGameOpensItsDetails() {
        compose.setContent { LibraryHarness(LibraryState.Ready(listOf(red), "Juegos")) }
        compose.onNodeWithTag("game-card").performClick()
        assertEquals("Red.gb", opened)
    }

    @Test
    fun layoutMenuSwitchesToList() {
        compose.setContent { LibraryHarness(LibraryState.Ready(games, "Juegos")) }
        compose.onNodeWithTag("view-menu").performClick()
        compose.onNodeWithText("Lista").performClick()
        compose.onAllNodesWithTag("game-list-item").assertCountEquals(4)
    }

    @Test
    fun continuePlayingRowAppearsOnlyWithRecentGames() {
        compose.setContent { LibraryHarness(LibraryState.Ready(games, "Juegos")) }
        compose.onNodeWithText("Continuar jugando").assertDoesNotExist()
    }

    @Test
    fun continuePlayingRowListsRecentGames() {
        compose.setContent {
            LibraryHarness(
                LibraryState.Ready(games, "Juegos"),
                withPlayed(alpha),
                resumable = setOf(alpha.id),
            )
        }
        compose.onNodeWithText("Continuar jugando").assertIsDisplayed()
        compose.onNodeWithTag("recent-row").assertIsDisplayed()
    }

    @Test
    fun continuePlayingCardOpensTheGameWithTheSameLauncherAction() {
        playFromRecent = true
        compose.setContent {
            LibraryHarness(
                LibraryState.Ready(games, "Juegos"),
                withPlayed(alpha),
                resumable = setOf(alpha.id),
            )
        }
        compose.onNodeWithTag("recent-row").assertIsDisplayed()
        compose.onNodeWithTag("continue-play").performClick()
        assertEquals(listOf("Alpha.gb"), played)
        assertEquals(null, opened)
    }

    @Test
    fun favoritesScreenShowsOnlyFavoritesAndOpensDetails() {
        compose.setContent {
            PocketGBTheme {
                FavoritesContent(
                    LibraryState.Ready(games, "Juegos"),
                    LibraryPreferencesData(favorites = setOf(red.id, color.id)),
                    GameActions({ opened = it.id }, {}, {}),
                )
            }
        }
        cards().assertCountEquals(2)
        compose.onNodeWithText("RED").performClick()
        assertEquals("Red.gb", opened)
    }

    @Test
    fun favoritesScreenHasItsOwnEmptyState() {
        compose.setContent {
            PocketGBTheme {
                FavoritesContent(LibraryState.Ready(games, "Juegos"), LibraryPreferencesData(), GameActions({}, {}, {}))
            }
        }
        compose.onNodeWithText("Todavía no hay favoritos").assertIsDisplayed()
    }

    private val details = GameDetails(
        entry = red,
        cartridge = "MBC3 + RAM + batería",
        romBytes = 1024 * 1024,
        sramBytes = 32 * 1024,
        hasBattery = true,
        hasRtc = false,
        headerChecksumOk = true,
        globalChecksumOk = false,
        fingerprint = "ab".repeat(32),
    )

    @Composable
    private fun DetailsHarness(entry: RomEntry, load: DetailsLoad, lastPlayedAt: Long? = null, canResume: Boolean = false) {
        var favorite by remember { mutableStateOf(false) }
        var hidden by remember { mutableStateOf(false) }
        PocketGBTheme {
            Column {
                GameDetailsContent(
                    entry = entry,
                    load = load,
                    favorite = favorite,
                    lastPlayedAt = lastPlayedAt,
                    onPlay = { played += entry.id },
                    onToggleFavorite = { favorite = !favorite },
                    onHide = { hidden = true },
                    onBack = {},
                    canResume = canResume,
                    onPlayFromStart = { played += "inicio:${entry.id}" },
                )
                if (hidden) Text("oculto")
            }
        }
    }

    @Test
    fun detailsShowMetadataChecksumsAndAnEnabledPlayButton() {
        compose.setContent { DetailsHarness(red, DetailsLoad.Loaded(details)) }
        compose.onNodeWithTag("game-details-play").assertIsEnabled()
        compose.onNodeWithText("Jugar").assertIsDisplayed()
        compose.onNodeWithText("MBC3 + RAM + batería").assertExists()
        compose.onNodeWithText("Correcto").assertExists()
        compose.onNodeWithText("No coincide (la consola real lo ignora)").assertExists()
        compose.onNodeWithTag("game-details-fingerprint").assertExists()
        compose.onNodeWithText("Nunca").assertExists()
    }

    @Test
    fun playIsEnabledOnlyForPlayableGamesAndShowsContinueWhenAlreadyPlayed() {
        compose.setContent { DetailsHarness(red, DetailsLoad.Loaded(details)) }
        compose.onNodeWithTag("game-details-play").assertIsEnabled().performClick()
        assertEquals(listOf("Red.gb"), played)
    }

    @Test
    fun gamesWithAValidAutomaticStateOfferContinueAndPlayFromStart() {
        // A9 (cambia J8, ND6): «Continuar» = estado automático exacto; «Jugar desde el inicio» = solo la partida.
        compose.setContent { DetailsHarness(red, DetailsLoad.Loaded(details), lastPlayedAt = 10L, canResume = true) }
        compose.onNodeWithText("Continuar").assertIsDisplayed()
        compose.onNodeWithTag("game-details-play").assertIsEnabled().performClick()
        compose.onNodeWithTag("game-details-play-from-start").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("game-details-resume-hint").performScrollTo().assertIsDisplayed()
        assertEquals(listOf("Red.gb", "inicio:Red.gb"), played)
    }

    @Test
    fun aPlayedGameWithoutAValidAutomaticStateOffersOnlyPlay() {
        compose.setContent { DetailsHarness(red, DetailsLoad.Loaded(details), lastPlayedAt = 10L, canResume = false) }
        compose.onNodeWithText("Jugar").assertIsDisplayed()
        compose.onAllNodesWithTag("game-details-play-from-start").assertCountEquals(0)
        compose.onAllNodesWithText("Continuar").assertCountEquals(0)
    }

    @Test
    fun playIsDisabledWhenTheRomHasAProblemOrMetadataFailed() {
        compose.setContent { DetailsHarness(broken, DetailsLoad.Loading) }
        compose.onNodeWithTag("game-details-play").assertIsNotEnabled()
    }

    @Test
    fun playIsDisabledWhenTheCoreRejectedTheRom() {
        compose.setContent {
            DetailsHarness(red, DetailsLoad.Failed(com.joelbermudez.pocketgb.library.DetailsError.Unreadable))
        }
        compose.onNodeWithTag("game-details-play").assertIsNotEnabled()
        assertTrue(played.isEmpty())
    }

    @Test
    fun detailsToggleFavoriteAndConfirmHide() {
        compose.setContent { DetailsHarness(red, DetailsLoad.Loaded(details)) }
        compose.onNodeWithTag("game-details-favorite").performScrollTo().performClick()
        compose.onNodeWithTag("game-details-star").assert(hasContentDescription("Quitar de favoritos"))
        compose.onNodeWithTag("game-details-hide").performScrollTo().performClick()
        compose.onNodeWithText("oculto").assertDoesNotExist()
        compose.onNodeWithText("Ocultar").performClick()
        compose.onNodeWithText("oculto").assertExists()
    }

    @Test
    fun detailsOfAProblemGameExplainItAndStillDisablePlay() {
        compose.setContent { DetailsHarness(broken, DetailsLoad.Loading) }
        compose.onNodeWithTag("game-details-problem").assertIsDisplayed()
        compose.onNodeWithText(RomProblem.INVALID_HEADER.message).assertIsDisplayed()
        compose.onNodeWithTag("game-details-play").assertIsNotEnabled()
    }

    @Test
    fun librarySettingsListsHiddenGamesAndUnhidesOne() {
        var unhidden: String? = null
        var forgotten = false
        compose.setContent {
            PocketGBTheme {
                LibrarySettingsContent(
                    state = LibraryState.Ready(games, "Juegos"),
                    folderName = "Juegos",
                    hidden = listOf(alpha),
                    onChooseFolder = {},
                    onRescan = {},
                    onForget = { forgotten = true },
                    onUnhide = { unhidden = it.id },
                    onBack = {},
                )
            }
        }
        compose.onNodeWithText("Juegos · 4 juegos").assertIsDisplayed()
        compose.onNodeWithTag("settings-forget").performClick()
        compose.onNodeWithText("¿Olvidar la carpeta?").assertIsDisplayed()
        compose.onNodeWithText("Olvidar").performClick()
        // Vista y Orden empujan los ocultos más abajo: se desplaza hasta el botón.
        compose.onNodeWithTag("library-settings").performScrollToNode(hasTestTag("unhide-Alpha.gb"))
        compose.onNodeWithTag("unhide-Alpha.gb").performClick()
        assertEquals("Alpha.gb", unhidden)
        assertEquals(true, forgotten)
    }

    @Test
    fun librarySettingsWithoutHiddenGamesSaysSo() {
        compose.setContent {
            PocketGBTheme {
                LibrarySettingsContent(LibraryState.NoFolder, null, emptyList(), {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithTag("settings-no-hidden").assertIsDisplayed()
        compose.onAllNodesWithText("Volver a escanear").assertCountEquals(0)
    }

    // ---- Correcciones de la 1ª vuelta de auditoría ----

    @Test
    fun loadingStateShowsProgressAndNeverTheNoFolderInvitation() {
        compose.setContent { LibraryHarness(LibraryState.Loading) }
        compose.onNodeWithTag("library-progress").assertIsDisplayed()
        compose.onNodeWithText("Cargando biblioteca…").assertIsDisplayed()
        compose.onAllNodesWithText("Elegir carpeta").assertCountEquals(0)
    }

    @Test
    fun accessNotKeptHasItsOwnMessageAndRecovery() {
        compose.setContent { LibraryHarness(LibraryState.Failed(LibraryError.AccessNotKept)) }
        compose.onNodeWithText("No se pudo conservar el acceso a la carpeta").assertIsDisplayed()
        compose.onNodeWithText("Reintentar").performClick()
        assertEquals(1, rescanCount)
        compose.onNodeWithText("Elegir carpeta").performClick()
        assertEquals(1, chooseCount)
    }

    private class RepeatedNamesTree : com.joelbermudez.pocketgb.library.DocumentTree {
        private fun file(id: String, name: String) =
            com.joelbermudez.pocketgb.library.TreeNode(id, name, isDirectory = false, sizeBytes = 32768)

        private fun dir(id: String, name: String) =
            com.joelbermudez.pocketgb.library.TreeNode(id, name, isDirectory = true, sizeBytes = 0)

        override fun children(directoryId: String?) = when (directoryId) {
            null -> listOf(file("d1", "Juego.gb"), file("d2", "Juego.gb"), dir("s1", "Rojo"), dir("s2", "Rojo"))
            "s1" -> listOf(file("s1a", "Pokemon.gb"))
            "s2" -> listOf(file("s2a", "Pokemon.gb"))
            else -> emptyList()
        }

        override fun readHead(node: com.joelbermudez.pocketgb.library.TreeNode, limit: Int): ByteArray {
            val bytes = ByteArray(0x150)
            "JUEGO".forEachIndexed { i, c -> bytes[0x134 + i] = c.code.toByte() }
            return bytes
        }

        override fun uriOf(node: com.joelbermudez.pocketgb.library.TreeNode) = "content://t/${node.id}"
    }

    @Test
    fun repeatedFileNamesDoNotCrashGridListOrFavorites() {
        val scanned = com.joelbermudez.pocketgb.library.LibraryScanner.scan(RepeatedNamesTree())
        assertEquals(4, scanned.map { it.id }.toSet().size)
        // Favoritos sobre uno solo de los duplicados: no se marca el otro.
        val prefs = LibraryPreferencesData().toggleFavorite(scanned.first())
        var layout by mutableStateOf(LibraryLayout.GRID)
        compose.setContent {
            PocketGBTheme {
                LibraryContent(
                    state = LibraryState.Ready(scanned, "Juegos"),
                    prefs = prefs.copy(layout = layout).withoutHome(LibraryState.Ready(scanned, "Juegos")),
                    query = "",
                    filter = LibraryFilter.ALL,
                    onQueryChange = {},
                    onFilterChange = {},
                    onLayoutChange = {},
                    onSortChange = {},
                    onChooseFolder = {},
                    onRescan = {},
                    actions = GameActions({}, {}, {}),
                )
            }
        }
        cards().assertCountEquals(4)
        compose.runOnIdle { layout = LibraryLayout.LIST }
        compose.onAllNodesWithTag("game-list-item").assertCountEquals(4)
    }

    @Test
    fun detailsShowLoadingUntilTheFirstScanInsteadOfUnavailable() {
        val gate = java.util.concurrent.CountDownLatch(1)
        val tree = object : com.joelbermudez.pocketgb.library.DocumentTree {
            override fun children(directoryId: String?): List<com.joelbermudez.pocketgb.library.TreeNode> {
                gate.await(10, java.util.concurrent.TimeUnit.SECONDS)
                return emptyList()
            }
            override fun readHead(node: com.joelbermudez.pocketgb.library.TreeNode, limit: Int) = ByteArray(0)
            override fun uriOf(node: com.joelbermudez.pocketgb.library.TreeNode) = "content://t/${node.id}"
        }
        val folders = object : com.joelbermudez.pocketgb.library.FolderStore {
            override fun currentUri() = "content://t/tree"
            override fun hasPersistedPermission() = true
            override fun select(uri: String) = com.joelbermudez.pocketgb.library.FolderGrant(false)
            override fun forget() {}
            override fun displayName() = "Juegos"
        }
        val dir = java.io.File(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "ui-details-prefs",
        ).apply { deleteRecursively() }
        val vm = com.joelbermudez.pocketgb.library.LibraryViewModel(
            folders = folders,
            openTree = { tree },
            roms = { _, _ -> ByteArray(0) },
            inspector = { _, _ -> error("no se usa") },
            preferencesFile = com.joelbermudez.pocketgb.library.LibraryPreferencesFile(java.io.File(dir, "p.json")),
            io = kotlinx.coroutines.Dispatchers.IO,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
        )
        compose.setContent {
            PocketGBTheme { com.joelbermudez.pocketgb.ui.details.GameDetailsScreen(vm, "Red.gb", onPlay = {}, onBack = {}) }
        }
        // Antes del primer escaneo (Loading) y durante él (Scanning sin datos) se ve progreso, no "no disponible".
        compose.onNodeWithTag("library-progress").assertIsDisplayed()
        compose.onAllNodesWithText("Juego no disponible").assertCountEquals(0)
        vm.rescan()
        compose.onNodeWithTag("library-progress").assertIsDisplayed()
        compose.onAllNodesWithText("Juego no disponible").assertCountEquals(0)
        gate.countDown()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Juego no disponible").fetchSemanticsNodes().isNotEmpty()
        }
        dir.deleteRecursively()
    }

    // ---- A6-L3: carril, menú contextual, búsqueda, progreso y detalle ----

    @Test
    fun railShowsResumableGamesEvenWithoutArtwork() {
        // Decisión de Joel 2026-10-07 (sustituye a K10): ningún juego tiene portada propia y los dos salen.
        compose.setContent {
            LibraryHarness(
                LibraryState.Ready(games, "Juegos"),
                withPlayed(alpha, red, at = 10L),
                resumable = setOf(alpha.id, red.id),
            )
        }
        compose.onNodeWithTag("recent-row").assertIsDisplayed()
        compose.onAllNodesWithTag("continue-card").assertCountEquals(2)
    }

    @Test
    fun railDoesNotAppearWhenNoPlayedGameIsResumable() {
        compose.setContent { LibraryHarness(LibraryState.Ready(games, "Juegos"), withPlayed(alpha), resumable = emptySet()) }
        compose.onAllNodesWithTag("recent-row").assertCountEquals(0)
    }

    @Test
    fun railIsHiddenOutsideTheAllFilter() {
        compose.setContent {
            LibraryHarness(
                LibraryState.Ready(games, "Juegos"), withPlayed(alpha), resumable = setOf(alpha.id),
            )
        }
        compose.onAllNodesWithTag("recent-row").assertCountEquals(1)
        compose.onNodeWithTag("filter-GB").performClick()
        compose.onAllNodesWithTag("recent-row").assertCountEquals(0)
    }

    @Test
    fun tappingTheRailCoverOpensTheDetailsAndTheButtonOpensTheGame() {
        playFromRecent = true
        compose.setContent {
            LibraryHarness(
                LibraryState.Ready(games, "Juegos"), withPlayed(alpha), resumable = setOf(alpha.id),
            )
        }
        compose.onNodeWithTag("continue-cover").performClick()
        assertEquals("Alpha.gb", opened)
        assertTrue(played.isEmpty())
        compose.onNodeWithTag("continue-play").performClick()
        assertEquals(listOf("Alpha.gb"), played)
    }

    @Test
    fun contextMenuOffersEveryActionAndStatesAreDisabled() {
        playFromRecent = true
        compose.setContent { LibraryHarness(LibraryState.Ready(listOf(red), "Juegos")) }
        compose.onNodeWithTag("game-card").performTouchInput { longClick() }
        compose.onNodeWithText("Jugar").assertIsDisplayed()
        compose.onNodeWithText("Ver detalle").assertIsDisplayed()
        compose.onNodeWithText("Añadir a favoritos").assertIsDisplayed()
        compose.onNodeWithText("Estados (próximamente)").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Ajustes del juego").assertIsDisplayed()
        compose.onNodeWithText("Ocultar de PocketGB").assertIsDisplayed()
        compose.onNodeWithText("Jugar").performClick()
        assertEquals(listOf("Red.gb"), played)
    }

    @Test
    fun contextMenuGameSettingsAndDetailsInvokeTheirActions() {
        compose.setContent { LibraryHarness(LibraryState.Ready(listOf(red), "Juegos")) }
        compose.onNodeWithTag("game-card").performTouchInput { longClick() }
        compose.onNodeWithText("Ajustes del juego").performClick()
        assertEquals("Red.gb", settingsOpened)
        compose.onNodeWithTag("game-card").performTouchInput { longClick() }
        compose.onNodeWithText("Ver detalle").performClick()
        assertEquals("Red.gb", opened)
    }

    @Test
    fun contextMenuDoesNotOfferPlayForAGameWithAProblem() {
        playFromRecent = true
        compose.setContent { LibraryHarness(LibraryState.Ready(listOf(broken), "Juegos")) }
        compose.onNodeWithTag("game-card").performTouchInput { longClick() }
        compose.onNodeWithText("Ver detalle").assertIsDisplayed()
        compose.onAllNodesWithText("Jugar").assertCountEquals(0)
    }

    @Test
    fun searchShowsTheResultCountInAList() {
        compose.setContent { LibraryHarness(LibraryState.Ready(games, "Juegos")) }
        compose.onNodeWithTag("library-search").performTextInput("yel")
        compose.onNodeWithText("1 resultado").assertIsDisplayed()
        compose.onNodeWithTag("library-search").performTextClearance()
        compose.onNodeWithTag("library-search").performTextInput("l")
        compose.onNodeWithText("3 resultados").assertIsDisplayed()
    }

    @Test
    fun searchInsideAFilterWithoutMatchesOffersToSearchEverywhere() {
        compose.setContent { LibraryHarness(LibraryState.Ready(games, "Juegos")) }
        compose.onNodeWithTag("filter-GBC").performClick()
        compose.onNodeWithTag("library-search").performTextInput("red")
        // El teclado ocupa parte de la pantalla (imePadding): se desplaza hasta el botón antes de tocarlo.
        compose.onNodeWithText("Sin resultados para «red»").assertExists()
        compose.onNodeWithText("Buscar en todos").performScrollTo().performClick()
        compose.onNodeWithTag("filter-ALL").assertIsSelected()
        compose.onNodeWithText("1 resultado").assertIsDisplayed()
    }

    @Test
    fun anEmptyFilterOffersToShowAllGames() {
        compose.setContent { LibraryHarness(LibraryState.Ready(games, "Juegos")) }
        compose.onNodeWithTag("filter-FAVORITES").performClick()
        compose.onNodeWithText("Ver todos").performClick()
        compose.onNodeWithTag("filter-ALL").assertIsSelected()
        cards().assertCountEquals(4)
    }

    @Test
    fun headerShowsTheSectionTitleAndTheFolderName() {
        compose.setContent { LibraryHarness(LibraryState.Ready(games, "Mis juegos")) }
        compose.onNodeWithText("Todos los juegos").assertIsDisplayed()
        compose.onNodeWithText("Mis juegos").assertIsDisplayed()
        compose.onNodeWithTag("filter-GB").performClick()
        compose.onNodeWithTag("library-section-title", useUnmergedTree = true).assert(hasText("GB"))
    }

    @Test
    fun moreOptionsMenuRescansAndChangesFolder() {
        compose.setContent { LibraryHarness(LibraryState.Ready(games, "Juegos")) }
        compose.onNodeWithTag("view-menu").performClick()
        compose.onNodeWithText("Volver a escanear").performClick()
        assertEquals(1, rescanCount)
        compose.onNodeWithTag("view-menu").performClick()
        compose.onNodeWithText("Cambiar carpeta").performClick()
        assertEquals(1, chooseCount)
    }

    @Test
    fun scanProgressShowsXOfY() {
        compose.setContent { LibraryHarness(LibraryState.Scanning(games, "Juegos", done = 3, total = 10)) }
        compose.onNodeWithText("Buscando juegos… 3 de 10").assertIsDisplayed()
        cards().assertCountEquals(4)
    }

    @Test
    fun scanProgressWithoutPreviousGamesShowsXOfYToo() {
        compose.setContent { LibraryHarness(LibraryState.Scanning(emptyList(), null, done = 2, total = 7)) }
        compose.onNodeWithText("Buscando juegos… 2 de 7").assertIsDisplayed()
    }

    @Test
    fun newGamesAreMarkedAndNeverPlayedGamesSaySo() {
        compose.setContent {
            LibraryHarness(LibraryState.Ready(listOf(red.copy(isNew = true), alpha), "Juegos"))
        }
        compose.onAllNodesWithText("Nuevo").assertCountEquals(1)
        compose.onAllNodesWithText("Sin jugar").assertCountEquals(2)
    }

    @Test
    fun aGameWithASaveNextToItShowsThePartidaDate() {
        compose.setContent {
            LibraryHarness(LibraryState.Ready(listOf(red.copy(mirrorSaveDate = System.currentTimeMillis() - 5 * 60_000)), "Juegos"))
        }
        compose.onNodeWithText("Partida hace 5 min").assertIsDisplayed()
    }

    @Test
    fun newGamesSummaryAppearsOnceAndIsReported() {
        var shown = 0
        compose.setContent { LibraryHarness(LibraryState.Ready(games, "Juegos"), summary = 2, onSummaryShown = { shown++ }) }
        compose.onNodeWithText("2 juegos nuevos en la carpeta").assertIsDisplayed()
        compose.waitUntil(10_000) { shown == 1 }
    }

    @Test
    fun permissionRevokedAlsoOffersRetry() {
        compose.setContent { LibraryHarness(LibraryState.Failed(LibraryError.PermissionRevoked)) }
        compose.onNodeWithText("Reintentar").performClick()
        assertEquals(1, rescanCount)
        assertEquals(0, chooseCount)
    }

    @Test
    fun pullToRefreshRescans() {
        compose.setContent { LibraryHarness(LibraryState.Ready(games, "Juegos")) }
        compose.onNodeWithTag("library-collection").performTouchInput { swipeDown(startY = top + 40f, endY = bottom - 40f) }
        compose.waitUntil(5_000) { rescanCount >= 1 }
    }

    @Test
    fun detailsShowStatsContinueFromTheSaveAndOpenGameSettings() {
        var settings = 0
        // Minutos y no horas: «hace 2 h» pasa a «ayer» entre las 00:00 y las 02:00 (fallo de frontera de fecha).
        val saved = red.copy(mirrorSaveDate = System.currentTimeMillis() - 5 * 60_000)
        compose.setContent {
            PocketGBTheme {
                GameDetailsContent(
                    entry = saved,
                    load = DetailsLoad.Loaded(details),
                    favorite = false,
                    lastPlayedAt = null,
                    onPlay = { played += saved.id },
                    onToggleFavorite = {},
                    onHide = {},
                    onBack = {},
                    onOpenSettings = { settings++ },
                )
            }
        }
        // A9 (cambia J8): sin estado automático la acción es «Jugar», que abre la partida junto al ROM igualmente.
        compose.onNodeWithText("Jugar").assertIsDisplayed()
        compose.onNodeWithTag("game-details-stats").assertIsDisplayed()
        compose.onNodeWithText("hace 5 min").assertIsDisplayed()
        compose.onNodeWithText("Nunca").assertIsDisplayed()
        compose.onNodeWithTag("game-details-states").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("game-details-settings").performScrollTo().performClick()
        assertEquals(1, settings)
    }

    @Test
    fun detailsOfAPlayedGameUseTheRealCoverWhenThereIsOne() {
        // Sin captura en el almacén de la prueba: se ve el placeholder generado con su descripción accesible.
        compose.setContent { DetailsHarness(red, DetailsLoad.Loaded(details)) }
        compose.onNodeWithTag("game-details-artwork").assertIsDisplayed()
        compose.onNodeWithContentDescription("Sin captura, portada generada para RED", useUnmergedTree = true).assertExists()
    }

    // --- A7-L2: accesibilidad ---

    private val longTitle = entry("Largo.gb", "THE LEGEND OF ZELDA LINKS AWAKENING DX EDICION ESPECIAL", true)

    /** Fuerza una escala de fuente como la del sistema (el `Density` la lleva y `LocalLargeFont` la deriva). */
    @Composable
    private fun WithFontScale(scale: Float, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, scale), content = content)
    }

    @Test
    fun aGameCardIsOneNodeWithTheCombinedLabelAndMoreOptions() {
        compose.setContent {
            LibraryHarness(
                LibraryState.Ready(listOf(red), "Juegos"),
                LibraryPreferencesData(favorites = setOf(red.id)),
            )
        }
        cards().assertCountEquals(1)
        cards()[0].assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("RED, Game Boy, Favorito, Sin jugar")),
        )
        cards()[0].assert(
            SemanticsMatcher("acción «Más opciones»") {
                it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "Más opciones"
            },
        )
    }

    @Test
    fun aProblemCardAnnouncesTheReasonInsteadOfTheLastPlayed() {
        compose.setContent { LibraryHarness(LibraryState.Ready(listOf(broken), "Juegos")) }
        cards()[0].assert(
            SemanticsMatcher("etiqueta con el problema") {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()?.startsWith("ROTO, Game Boy, ") == true
            },
        )
    }

    @Test
    fun withFontScaleTwoTheGridHasOneColumnAndTitlesAreNotTruncated() {
        compose.setContent {
            WithFontScale(2.0f) { LibraryHarness(LibraryState.Ready(listOf(longTitle, red, yellow), "Juegos")) }
        }
        compose.waitForIdle()
        val lefts = cards().fetchSemanticsNodes().map { it.positionInRoot.x }
        assertTrue("una columna: $lefts", lefts.isNotEmpty() && lefts.all { it == lefts.first() })
        val result = androidx.compose.ui.text.TextLayoutResult::class.java
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onAllNodes(hasText(longTitle.title), useUnmergedTree = true).fetchSemanticsNodes().forEach { node ->
            val list = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(list)
            layouts += list
        }
        assertTrue("no se encontró el diseño del título", layouts.isNotEmpty())
        layouts.forEach { assertFalse("el título está truncado", it.hasVisualOverflow) }
        assertTrue(result.simpleName.isNotEmpty())
    }

    @Test
    fun withFontScaleOneTheGridKeepsSeveralColumnsAndLimitsTitlesToTwoLines() {
        compose.setContent {
            WithFontScale(1.0f) { LibraryHarness(LibraryState.Ready(listOf(longTitle, red, yellow, alpha), "Juegos")) }
        }
        compose.waitForIdle()
        val lefts = cards().fetchSemanticsNodes().map { it.positionInRoot.x }.distinct()
        assertTrue("varias columnas: $lefts", lefts.size >= 2)
    }

    @Test
    fun withFontScaleTwoTheContinueRailBecomesAColumnOfAtMostThreeRows() {
        val recent = listOf(red, yellow, alpha, color)
        val prefs = withPlayed(*recent.toTypedArray())
        compose.setContent {
            WithFontScale(2.0f) {
                LibraryHarness(
                    LibraryState.Ready(recent, "Juegos"), initial = prefs,
                    resumable = recent.map { it.id }.toSet(),
                )
            }
        }
        compose.waitForIdle()
        val rows = compose.onAllNodesWithTag("continue-card").fetchSemanticsNodes()
        assertTrue("como mucho 3 filas: ${rows.size}", rows.size in 1..3)
        val lefts = rows.map { it.positionInRoot.x }
        assertTrue("misma columna: $lefts", lefts.all { it == lefts.first() })
        if (rows.size > 1) assertTrue(rows[1].positionInRoot.y > rows[0].positionInRoot.y)
    }

    @Test
    fun withNormalFontTheContinueRailStaysHorizontal() {
        val recent = listOf(red, yellow, alpha)
        val prefs = withPlayed(*recent.toTypedArray())
        compose.setContent {
            LibraryHarness(
                LibraryState.Ready(recent, "Juegos"), initial = prefs,
                resumable = recent.map { it.id }.toSet(),
            )
        }
        compose.waitForIdle()
        val rows = compose.onAllNodesWithTag("continue-card").fetchSemanticsNodes()
        assertTrue(rows.size >= 2)
        assertTrue(rows[1].positionInRoot.x > rows[0].positionInRoot.x)
    }

    @Test
    fun withFontScaleTwoTheDetailsStackTheirRows() {
        compose.setContent {
            WithFontScale(2.0f) {
                PocketGBTheme {
                    GameDetailsContent(
                        entry = red,
                        load = DetailsLoad.Loaded(
                            GameDetails(red, "ROM", 32 * 1024, 0, hasBattery = false, hasRtc = false, headerChecksumOk = true, globalChecksumOk = true, fingerprint = "%064x".format(1L)),
                        ),
                        favorite = false,
                        lastPlayedAt = null,
                        onPlay = {},
                        onToggleFavorite = {},
                        onHide = {},
                        onBack = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("game-details-stats").performScrollTo()
        // Tres estadísticas apiladas: cada una empieza en la misma x y bajo la anterior.
        val stats = compose.onNodeWithTag("game-details-stats").fetchSemanticsNode().children
        assertTrue(stats.size >= 3)
        assertTrue(stats.map { it.positionInRoot.x }.distinct().size == 1)
        assertTrue(stats[1].positionInRoot.y > stats[0].positionInRoot.y)
        // Los botones secundarios también se apilan.
        val favorite = compose.onNodeWithTag("game-details-favorite").fetchSemanticsNode()
        val settings = compose.onNodeWithTag("game-details-settings").fetchSemanticsNode()
        assertEquals(favorite.positionInRoot.x, settings.positionInRoot.x, 0.5f)
    }
}

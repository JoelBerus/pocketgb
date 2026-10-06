package com.joelbermudez.pocketgb.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
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
        isColor = color,
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
    private fun LibraryHarness(state: LibraryState, initial: LibraryPreferencesData = LibraryPreferencesData()) {
        var prefs by remember { mutableStateOf(initial) }
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
                ),
            )
        }
    }

    private var playFromRecent = false

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
        cards().assertCountEquals(1)
        compose.onNodeWithTag("library-search").performTextClearance()
        // El teclado ocupa parte de la pantalla (imePadding): se comprueba con scroll, no con visibilidad.
        compose.onNodeWithTag("library-collection").performScrollToNode(hasText("YELLOW"))
        compose.onNodeWithText("YELLOW").assertIsDisplayed()
        compose.onNodeWithTag("library-search").performTextInput("zzz")
        cards().assertCountEquals(0)
        compose.onNodeWithText("Sin resultados").assertIsDisplayed()
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
                LibraryPreferencesData(lastPlayed = mapOf(alpha.id to 10L)),
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
                LibraryPreferencesData(lastPlayed = mapOf(alpha.id to 10L)),
            )
        }
        compose.onNodeWithTag("recent-row").assertIsDisplayed()
        compose.onNode(hasTestTag("game-card") and hasAnyAncestor(hasTestTag("recent-row"))).performClick()
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
    private fun DetailsHarness(entry: RomEntry, load: DetailsLoad, lastPlayedAt: Long? = null) {
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
    fun playedGamesOfferContinue() {
        compose.setContent { DetailsHarness(red, DetailsLoad.Loaded(details), lastPlayedAt = 10L) }
        compose.onNodeWithText("Continuar").assertIsDisplayed()
        compose.onNodeWithTag("game-details-play").assertIsEnabled()
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
        compose.onNodeWithText("Quitar de favoritos").assertExists()
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
        compose.onNodeWithTag("unhide-Alpha.gb").performClick()
        assertEquals("Alpha.gb", unhidden)
        compose.onNodeWithTag("settings-forget").performClick()
        compose.onNodeWithText("¿Olvidar la carpeta?").assertIsDisplayed()
        compose.onNodeWithText("Olvidar").performClick()
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
                    prefs = prefs.copy(layout = layout),
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
            inspector = { error("no se usa") },
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
}

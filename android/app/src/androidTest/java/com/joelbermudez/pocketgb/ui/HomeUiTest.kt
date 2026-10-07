package com.joelbermudez.pocketgb.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.joelbermudez.pocketgb.library.HomeSettings
import com.joelbermudez.pocketgb.library.LibraryCategory
import com.joelbermudez.pocketgb.library.LibraryFilter
import com.joelbermudez.pocketgb.library.LibraryHome
import com.joelbermudez.pocketgb.library.LibraryPreferencesData
import com.joelbermudez.pocketgb.library.LibraryState
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.ui.library.CategoryContent
import com.joelbermudez.pocketgb.ui.library.GameActions
import com.joelbermudez.pocketgb.ui.library.LibraryContent
import com.joelbermudez.pocketgb.ui.settings.HomeSettingsContent
import com.joelbermudez.pocketgb.ui.theme.PocketGBTheme
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * N4 · inicio (Favoritos y estanterías), pantalla de categoría (migas, subcategorías, vista por categoría), filtro por
 * etiqueta, insignia «Movido en la app», Ajustes › Biblioteca › Inicio y TalkBack/fuente al 200 % en lo nuevo.
 */
@RunWith(AndroidJUnit4::class)
class HomeUiTest {
    @get:Rule
    val compose = createComposeRule()

    private fun entry(id: String, title: String, color: Boolean = false) = RomEntry(
        id = id, uri = "content://n4/$id", fileName = id.substringAfterLast('/'), title = title, isColor = color,
        sizeBytes = 32L * 1024, headerChecksumOk = true, problem = null,
    )

    private val red = entry("Pokémon/1ª generación/Red.gb", "RED")
    private val gold = entry("Pokémon/2ª generación/Gold.gbc", "GOLD", color = true)
    private val crystal = entry("Pokémon/2ª generación/Johto/Crystal.gbc", "CRYSTAL", color = true)
    private val kirby = entry("Kirby/Kirby.gb", "KIRBY")
    private val tetra = entry("Tetra.gb", "TETRA")
    private val games = listOf(red, gold, crystal, kirby, tetra)

    private fun fp(entry: RomEntry) = "%064x".format(entry.id.hashCode().toLong() and 0xFFFFFFFFL)

    private val base = games.fold(LibraryPreferencesData()) { p, e -> p.recordFingerprint(e.id, fp(e)) }
        .toggleFavorite(kirby)
        .addTag(red, "rpg").addTag(gold, "rpg")
        .moveToCategory(tetra, listOf("Favoritas"))

    private var opened: LibraryCategory? = null
    private var openedFavorites = 0

    @Composable
    private fun Home(initial: LibraryPreferencesData = base) {
        var prefs by remember { mutableStateOf(initial) }
        var tag by remember { mutableStateOf<String?>(null) }
        run {
            LibraryContent(
                state = LibraryState.Ready(games, "Roms"),
                prefs = prefs,
                query = "",
                filter = LibraryFilter.ALL,
                onQueryChange = {},
                onFilterChange = {},
                onLayoutChange = { prefs = prefs.copy(layout = it) },
                onSortChange = {},
                onChooseFolder = {},
                onRescan = {},
                actions = GameActions({}, {}, {}),
                onOpenCategory = { opened = it },
                onOpenFavorites = { openedFavorites++ },
                tag = tag,
                onTagChange = { tag = it },
            )
        }
    }

    private fun shelfTitles() = compose.onAllNodesWithTag("home-shelf-title", useUnmergedTree = true)
        .fetchSemanticsNodes().map { it.config[SemanticsProperties.Text].joinToString("") }

    @Test
    fun theHomeHasFavoritesAndOneShelfPerCategoryAndSeeAllOpensIt() {
        compose.setContent { PocketGBTheme { Home() } }
        compose.onNodeWithTag("home-shelf-favorites").assertExists()
        // Favoritas (virtual), Kirby y Pokémon; Tetra se movió a Favoritas, así que no hay estantería «Sin categoría».
        compose.onNodeWithTag("library-collection").performScrollToNode(hasTestTag("home-shelf-folder-Pokémon"))
        compose.onNodeWithTag("home-see-all-folder-Pokémon").performClick()
        assertEquals(LibraryCategory.Folder("Pokémon"), opened)
        compose.onNodeWithTag("library-collection").performScrollToNode(hasTestTag("home-shelf-favorites"))
        compose.onNodeWithTag("home-see-all-favorites").performClick()
        assertEquals(1, openedFavorites)
    }

    @Test
    fun shelvesFollowTheHomeSettingsOrderPinsAndHiddenCategories() {
        val keys = LibraryHome.keys(games, base)
        assertEquals(listOf("Favoritas", "Kirby", "Pokémon"), keys)
        val arranged = base.copy(home = HomeSettings().withPinned("Pokémon", true).withHidden("Kirby", true).copy(showFavorites = false))
        compose.setContent { PocketGBTheme { Home(arranged) } }
        compose.onAllNodesWithTag("home-shelf-favorites").assertCountEquals(0)
        compose.onAllNodesWithTag("home-shelf-folder-Kirby").assertCountEquals(0)
        assertEquals("Pokémon (fijada) es la primera estantería", "Pokémon", shelfTitles().first())
        compose.onNodeWithTag("library-collection").performScrollToNode(hasTestTag("home-shelf-folder-Favoritas"))
        // Kirby sigue en «Todos los juegos».
        compose.onNodeWithTag("library-collection").performScrollToNode(hasText("KIRBY"))
    }

    @Test
    fun theHomeSettingsScreenPinsMovesHidesAndShowsFavorites() {
        var prefs by mutableStateOf(base)
        compose.setContent {
            PocketGBTheme {
                val keys = LibraryHome.keys(games, prefs)
                HomeSettingsContent(
                    rows = LibraryHome.arrangement(games, prefs),
                    showFavorites = prefs.home.showFavorites,
                    onShowFavorites = { prefs = prefs.copy(home = prefs.home.copy(showFavorites = it)) },
                    onMove = { key, offset -> prefs = prefs.copy(home = prefs.home.move(key, offset, keys)) },
                    onPinned = { key, value -> prefs = prefs.copy(home = prefs.home.withPinned(key, value)) },
                    onHidden = { key, value -> prefs = prefs.copy(home = prefs.home.withHidden(key, value)) },
                    onReset = { prefs = prefs.copy(home = HomeSettings()) },
                    onBack = {},
                    changed = prefs.home != HomeSettings(),
                )
            }
        }
        compose.onNodeWithTag("home-row-Kirby-up").assert(hasContentDescription("Subir Kirby")).performClick()
        compose.runOnIdle { assertEquals(listOf("Kirby", "Favoritas", "Pokémon"), prefs.home.arrange(LibraryHome.keys(games, prefs))) }
        compose.onNodeWithTag("home-row-Pokémon-pin").assert(hasContentDescription("Fijar arriba Pokémon")).performClick()
        compose.runOnIdle { assertEquals("Pokémon", prefs.home.arrange(LibraryHome.keys(games, prefs)).first()) }
        compose.onNodeWithTag("home-row-Favoritas-show").performScrollTo().performClick()
        compose.runOnIdle { assertTrue("Favoritas" in prefs.home.hidden) }
        compose.onNodeWithTag("home-row-Favoritas-show").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Oculta en el inicio"),
        )
        compose.onNodeWithTag("home-show-favorites").performClick()
        compose.runOnIdle { assertTrue(!prefs.home.showFavorites) }
        compose.onNodeWithTag("home-reset").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(HomeSettings(), prefs.home) }
    }

    @Composable
    private fun Category(category: LibraryCategory, initial: LibraryPreferencesData = base, onCrumb: (LibraryCategory?) -> Unit = {}) {
        var prefs by remember { mutableStateOf(initial) }
        run {
            CategoryContent(
                state = LibraryState.Ready(games, "Roms"),
                prefs = prefs,
                category = category,
                actions = GameActions({}, {}, {}),
                onBack = {},
                onOpenCategory = { opened = it },
                onOpenCrumb = onCrumb,
                onLayoutChange = { prefs = prefs.withCategoryLayout(category, it) },
            )
        }
    }

    @Test
    fun aCategoryShowsBreadcrumbsSubcategoriesWithCountsAndItsGames() {
        var crumb: LibraryCategory? = LibraryCategory.All
        compose.setContent { PocketGBTheme { Category(LibraryCategory.Folder(listOf("Pokémon", "2ª generación"))) { crumb = it } } }
        compose.onNodeWithTag("category-title", useUnmergedTree = true).assertTextEquals("2ª generación")
        // TalkBack: la ruta entera y cada nivel con su acción.
        compose.onNodeWithTag("category-breadcrumbs").assert(hasContentDescription("Estás en: Biblioteca › Pokémon › 2ª generación"))
        compose.onNodeWithTag("crumb-Pokémon").assert(hasContentDescription("Ir a Pokémon")).performClick()
        assertEquals(LibraryCategory.Folder("Pokémon"), crumb)
        compose.onNodeWithTag("crumb-root").performClick()
        assertEquals(null, crumb)
        compose.onNodeWithTag("subcategory-folder-Johto")
            .assert(hasContentDescription("Subcategoría Johto, 1 juego"))
            .performClick()
        assertEquals(LibraryCategory.Folder(listOf("Pokémon", "2ª generación", "Johto")), opened)
        assertEquals(2, compose.onAllNodesWithTag("game-card").fetchSemanticsNodes().size)
    }

    @Test
    fun eachCategoryRemembersItsLayout() {
        compose.setContent { PocketGBTheme { Category(LibraryCategory.Folder("Pokémon")) } }
        compose.onNodeWithTag("category-layout-toggle").assert(hasContentDescription("Ver en lista")).performClick()
        compose.onNodeWithTag("library-collection").performScrollToNode(hasTestTag("game-list-item"))
        compose.onAllNodesWithTag("game-card").assertCountEquals(0)
        compose.onNodeWithTag("category-layout-toggle").assert(hasContentDescription("Ver en cuadrícula"))
    }

    @Test
    fun aMovedGameAppearsInItsVirtualCategoryWithTheBadgeAndNotInItsFolder() {
        compose.setContent { PocketGBTheme { Category(LibraryCategory.Folder("Favoritas")) } }
        compose.onNode(hasTestTag("game-card") and hasContentDescription("Movido en la app", substring = true)).assertIsDisplayed()
        compose.onNodeWithTag("game-moved-badge", useUnmergedTree = true).assertExists()
    }

    @Test
    fun theTagFilterInPortraitKeepsOnlyTaggedGamesAndNamesTheTag() {
        compose.setContent { PocketGBTheme { Home() } }
        compose.onNodeWithTag("filter-tag").performScrollTo().performClick()
        compose.onNodeWithTag("menu-tag-rpg").performClick()
        compose.onNodeWithTag("library-section-title", useUnmergedTree = true).assertTextEquals("Etiqueta «rpg»")
        compose.onAllNodesWithTag("home-shelf-favorites").assertCountEquals(0)
        assertEquals(2, compose.onAllNodesWithTag("game-card").fetchSemanticsNodes().size)
        compose.onNodeWithTag("filter-tag").assert(hasText("Etiqueta: rpg", substring = true))
    }

    @Test
    fun atTwoHundredPercentShelfHeadersAndBreadcrumbsAreWholeAndReachable() {
        var showCategory by mutableStateOf(false)
        compose.setContent {
            // El tema va fuera del override (pide la actividad; dentro el contexto es un envoltorio).
            PocketGBTheme {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                    if (showCategory) Category(LibraryCategory.Folder(listOf("Pokémon", "2ª generación"))) else Home()
                }
            }
        }
        compose.onNodeWithTag("library-collection").performScrollToNode(hasTestTag("home-shelf-folder-Pokémon"))
        compose.onNodeWithTag("home-see-all-folder-Pokémon").assertIsDisplayed()
        val seeAll = compose.onNodeWithTag("home-see-all-folder-Pokémon").fetchSemanticsNode()
        assertTrue("«Ver todo» mide al menos 48 dp de alto", seeAll.size.height >= with(compose.density) { 48.dp.roundToPx() })
        assertEquals("«Ver todo» no se corta", "Ver todo", seeAll.config[SemanticsProperties.Text].joinToString(""))
        compose.runOnIdle { showCategory = true }
        compose.onNodeWithTag("crumb-Pokémon").assertIsDisplayed()
        compose.onNodeWithTag("crumb-current", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Subcategorías").assertIsDisplayed()
    }

    @Test
    fun shelfHeadersAreHeadingsForTalkBack() {
        compose.setContent { PocketGBTheme { Home() } }
        compose.onNodeWithTag("library-collection").performScrollToNode(hasTestTag("home-shelf-folder-Kirby"))
        compose.onNode(hasText("Kirby") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading), useUnmergedTree = false).assertExists()
        compose.onNodeWithTag("home-see-all-folder-Kirby").assert(hasContentDescription("Ver todo Kirby, 1 juego"))
    }
}

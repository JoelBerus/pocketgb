package com.joelbermudez.pocketgb.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.joelbermudez.pocketgb.app.AppNavigationState
import com.joelbermudez.pocketgb.app.LibraryRoute
import com.joelbermudez.pocketgb.app.LibraryRouteContent
import com.joelbermudez.pocketgb.app.LibraryRouteDeps
import com.joelbermudez.pocketgb.emulator.CoreBridge
import com.joelbermudez.pocketgb.library.DocumentTree
import com.joelbermudez.pocketgb.library.FolderGrant
import com.joelbermudez.pocketgb.library.FolderStore
import com.joelbermudez.pocketgb.library.LibraryPreferencesFile
import com.joelbermudez.pocketgb.library.LibraryState
import com.joelbermudez.pocketgb.library.LibraryViewModel
import com.joelbermudez.pocketgb.library.RomInspector
import com.joelbermudez.pocketgb.library.RomSource
import com.joelbermudez.pocketgb.library.TreeNode
import com.joelbermudez.pocketgb.testing.SyntheticRom
import com.joelbermudez.pocketgb.ui.theme.PocketGBTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * N4 · navegación de la pestaña Biblioteca con el ViewModel real y las pantallas de la app ([LibraryRouteContent]):
 * inicio → «Ver todo» Pokémon → subcategoría «2ª generación» → juego → atrás, con la pila de Navigation 3 restaurada tras
 * recrear la actividad (estado guardado) en mitad del camino.
 */
@RunWith(AndroidJUnit4::class)
class CategoryNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val roms = mapOf(
        "Pokémon/1ª generación/Red.gb" to SyntheticRom.romOnly("RED"),
        "Pokémon/2ª generación/Gold.gb" to SyntheticRom.romOnly("GOLD"),
        "Kirby/Kirby.gb" to SyntheticRom.romOnly("KIRBY"),
        "Tetra.gb" to SyntheticRom.romOnly("TETRA"),
    )
    private lateinit var dir: File
    private lateinit var scope: CoroutineScope
    private lateinit var vm: LibraryViewModel

    /** Árbol de carpetas: los ids son las rutas. */
    private val tree = object : DocumentTree {
        override fun children(directoryId: String?): List<TreeNode> {
            val prefix = directoryId?.let { "$it/" } ?: ""
            val below = roms.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
            val folders = below.filter { '/' in it }.map { it.substringBefore('/') }.distinct()
                .map { TreeNode(prefix + it, it, isDirectory = true, sizeBytes = 0) }
            val files = below.filter { '/' !in it }.map { TreeNode(prefix + it, it, isDirectory = false, sizeBytes = 32768) }
            return folders + files
        }

        override fun readHead(node: TreeNode, limit: Int) = roms.getValue(node.id).let { it.copyOf(minOf(limit, it.size)) }
        override fun uriOf(node: TreeNode) = "content://n4nav/${node.id}"
    }

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "n4-navigation").apply { deleteRecursively(); mkdirs() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        vm = LibraryViewModel(
            folders = object : FolderStore {
                override fun currentUri() = "content://n4nav/tree"
                override fun hasPersistedPermission() = true
                override fun select(uri: String) = FolderGrant(false)
                override fun forget() {}
                override fun displayName() = "Roms"
            },
            openTree = { tree },
            roms = RomSource { uri, _ -> roms.getValue(uri.removePrefix("content://n4nav/")) },
            inspector = RomInspector { rom, _ -> CoreBridge().use { it.loadRom(rom) } },
            preferencesFile = LibraryPreferencesFile(File(dir, "preferences.json")),
            io = Dispatchers.IO,
            scope = scope,
        )
        vm.rescan()
        compose.waitUntil(10_000) { vm.state.value is LibraryState.Ready }
    }

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private val deps get() = LibraryRouteDeps(
        library = vm,
        play = {},
        playFromStart = {},
        resumable = emptySet(),
        gameplaySettings = null,
        saves = { _, _ -> },
    )

    private lateinit var navigation: AppNavigationState

    @Test
    fun homeSeeAllSubcategoryGameAndBackSurviveRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            PocketGBTheme {
                val state = rememberSaveable(saver = AppNavigationState.Saver) { AppNavigationState() }
                navigation = state
                BackHandler(enabled = state.currentBackStack.size > 1) { state.pop() }
                NavDisplay(
                    backStack = state.currentBackStack,
                    onBack = { state.pop() },
                    entryProvider = { route -> NavEntry(route) { LibraryRouteContent(route as LibraryRoute, state, deps) } },
                )
            }
        }
        // Inicio: estantería «Pokémon» con «Ver todo».
        compose.onNodeWithTag("library-collection").performScrollToNode(hasTestTag("home-shelf-folder-Pokémon"))
        compose.onNodeWithTag("home-see-all-folder-Pokémon").performClick()
        compose.onNodeWithTag("category-title", useUnmergedTree = true).assertTextEquals("Pokémon")
        compose.onNodeWithTag("subcategory-folder-2ª generación").performClick()
        compose.onNodeWithTag("category-title", useUnmergedTree = true).assertTextEquals("2ª generación")

        // Recreación (como al girar con «No conservar actividades»): la pila vuelve igual.
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("category-title", useUnmergedTree = true).assertTextEquals("2ª generación")
        compose.onNodeWithTag("crumb-Pokémon").assertIsDisplayed()

        // Juego → detalle → recreación → atrás.
        compose.onNodeWithTag("library-collection").performScrollToNode(hasText("GOLD", substring = true))
        compose.onNode(hasTestTag("game-card") and hasText("GOLD", substring = true)).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("game-details-title").fetchSemanticsNodes().isNotEmpty() }
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("game-details-title").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle {
            assertEquals(
                listOf(
                    LibraryRoute.Root,
                    LibraryRoute.Category(listOf("Pokémon")),
                    LibraryRoute.Category(listOf("Pokémon", "2ª generación")),
                    LibraryRoute.Details("Pokémon/2ª generación/Gold.gb"),
                ),
                navigation.currentBackStack.toList(),
            )
        }
        Espresso.pressBack()
        compose.onNodeWithTag("category-title", useUnmergedTree = true).assertTextEquals("2ª generación")
        Espresso.pressBack()
        compose.onNodeWithTag("category-title", useUnmergedTree = true).assertTextEquals("Pokémon")
        // Miga «Biblioteca»: de vuelta al inicio.
        compose.onNodeWithTag("crumb-root").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("category-screen").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { assertEquals(listOf<LibraryRoute>(LibraryRoute.Root), navigation.currentBackStack.toList()) }
    }
}

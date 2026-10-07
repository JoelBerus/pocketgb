package com.joelbermudez.pocketgb.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import com.joelbermudez.pocketgb.ui.details.GameDetailsScreen
import com.joelbermudez.pocketgb.ui.library.LibraryScreen
import com.joelbermudez.pocketgb.ui.theme.PocketGBTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A9 · Renombrar en la UI de extremo a extremo (ViewModel real y preferencias en disco): desde el menú contextual de la
 * tarjeta, desde el menú de la barra superior del detalle y desde los ajustes del juego. El alias se ve en la
 * biblioteca, se encuentra con la búsqueda, persiste en `preferences.json` y vacío vuelve al título del cartucho.
 */
@RunWith(AndroidJUnit4::class)
class RenameUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val roms = mapOf(
        "Alpha.gb" to SyntheticRom.romOnly("ALPHA"),
        "Beta.gb" to SyntheticRom.romOnly("BETA"),
    )
    private lateinit var dir: File
    private lateinit var prefsFile: File
    private lateinit var scope: CoroutineScope
    private lateinit var vm: LibraryViewModel

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "a9-rename").apply { deleteRecursively(); mkdirs() }
        prefsFile = File(dir, "preferences.json")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tree = object : DocumentTree {
            override fun children(directoryId: String?): List<TreeNode> =
                if (directoryId == null) roms.keys.map { TreeNode(it, it, isDirectory = false, sizeBytes = 32768) } else emptyList()
            override fun readHead(node: TreeNode, limit: Int) = roms.getValue(node.id).let { it.copyOf(minOf(limit, it.size)) }
            override fun uriOf(node: TreeNode) = "content://a9/${node.id}"
        }
        val folders = object : FolderStore {
            override fun currentUri() = "content://a9/tree"
            override fun hasPersistedPermission() = true
            override fun select(uri: String) = FolderGrant(false)
            override fun forget() {}
            override fun displayName() = "Juegos"
        }
        vm = LibraryViewModel(
            folders = folders,
            openTree = { tree },
            roms = RomSource { uri, _ -> roms.getValue(uri.substringAfterLast('/')) },
            inspector = RomInspector { rom, _ -> CoreBridge().use { it.loadRom(rom) } },
            preferencesFile = LibraryPreferencesFile(prefsFile),
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

    private fun onDisk() = runBlocking { vm.flushPreferences() }.let { LibraryPreferencesFile(prefsFile).load() }

    private fun showLibrary() = compose.setContent {
        PocketGBTheme { LibraryScreen(viewModel = vm, onOpenDetails = {}, onPlay = {}) }
    }

    private fun typeName(name: String) {
        compose.onNodeWithTag("rename-dialog").assertIsDisplayed()
        compose.onNodeWithTag("rename-field").performTextClearance()
        if (name.isNotEmpty()) compose.onNodeWithTag("rename-field").performTextInput(name)
        compose.onNodeWithTag("rename-save").performClick()
        compose.waitForIdle()
    }

    private fun longClickCard(title: String) {
        compose.onNode(hasTestTag("game-card").and(hasText(title, substring = true))).performTouchInput { longClick() }
    }

    @Test
    fun renamingFromTheContextMenuShowsTheAliasSearchesItAndPersists() {
        showLibrary()
        longClickCard("ALPHA")
        compose.onNodeWithTag("menu-rename").performClick()
        compose.onNodeWithTag("rename-footer").assertTextContains("ALPHA", substring = true)
        typeName("Mi juego favorito")
        compose.onNodeWithText("Mi juego favorito").assertIsDisplayed()
        compose.onAllNodesWithText("ALPHA").assertCountEquals(0)
        // Sin abrir el juego aún no se conoce su huella: el alias queda por ruta (se migra al abrirlo).
        assertEquals("Mi juego favorito", onDisk().aliasesByPath["Alpha.gb"])
        // La búsqueda encuentra el alias, y también el título del cartucho.
        compose.onNodeWithTag("library-search").performTextInput("favorito")
        compose.onNodeWithText("1 resultado").assertIsDisplayed()
        compose.onNodeWithTag("library-search").performTextClearance()
        compose.onNodeWithTag("library-search").performTextInput("alpha")
        compose.onNodeWithText("1 resultado").assertIsDisplayed()
        compose.onNodeWithText("Mi juego favorito").assertIsDisplayed()
    }

    @Test
    fun anEmptyNameGoesBackToTheCartridgeTitle() {
        showLibrary()
        longClickCard("BETA")
        compose.onNodeWithTag("menu-rename").performClick()
        typeName("Beta renombrado")
        compose.onNodeWithText("Beta renombrado").assertIsDisplayed()
        longClickCard("Beta renombrado")
        compose.onNodeWithTag("menu-rename").performClick()
        compose.onNodeWithTag("rename-field").assertTextContains("Beta renombrado")
        typeName("")
        compose.onNodeWithText("BETA").assertIsDisplayed()
        val saved = onDisk()
        assertTrue(saved.aliasesByPath.isEmpty() && saved.aliasesByFingerprint.isEmpty())
    }

    @Test
    fun theFieldNeverAcceptsMoreThanEightyCharacters() {
        showLibrary()
        longClickCard("ALPHA")
        compose.onNodeWithTag("menu-rename").performClick()
        compose.onNodeWithTag("rename-field").performTextClearance()
        compose.onNodeWithTag("rename-field").performTextInput("a".repeat(100))
        compose.onNodeWithText("80/80").assertIsDisplayed()
        compose.onNodeWithTag("rename-save").performClick()
        compose.waitForIdle()
        assertEquals(80, onDisk().aliasesByPath["Alpha.gb"]?.length)
    }

    @Test
    fun renamingFromTheDetailsTopBarMenu() {
        compose.setContent { PocketGBTheme { GameDetailsScreen(vm, "Alpha.gb", onPlay = {}, onBack = {}) } }
        compose.onNodeWithTag("game-details-title").assertTextEquals("ALPHA")
        compose.onNodeWithTag("game-details-more").performClick()
        compose.onNodeWithTag("game-details-rename").performClick()
        typeName("Alfa de Joel")
        compose.onNodeWithTag("game-details-title").assertTextEquals("Alfa de Joel")
        // Con el detalle abierto ya se conoce la huella: el alias queda por huella (sobrevive a mover el ROM).
        compose.waitUntil(5_000) { onDisk().aliasesByFingerprint.values.contains("Alfa de Joel") }
    }

    @Test
    fun renamingFromTheGameSettingsSheet() {
        showLibrary()
        longClickCard("ALPHA")
        compose.onNodeWithText("Ajustes del juego").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("game-settings-rename").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("game-settings-rename").performClick()
        typeName("Alfa ajustado")
        compose.onNodeWithText("Ajustes de Alfa ajustado").assertIsDisplayed()
        compose.onNodeWithText("Cartucho: ALPHA").assertIsDisplayed()
        assertTrue(onDisk().aliasesByFingerprint.values.contains("Alfa ajustado"))
    }
}

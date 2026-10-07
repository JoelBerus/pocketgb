package com.joelbermudez.pocketgb.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
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
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.library.RomInspector
import com.joelbermudez.pocketgb.library.RomSource
import com.joelbermudez.pocketgb.library.TreeNode
import com.joelbermudez.pocketgb.settings.GameplaySettingsFile
import com.joelbermudez.pocketgb.settings.GameplaySettingsRepository
import com.joelbermudez.pocketgb.testing.SyntheticRom
import com.joelbermudez.pocketgb.ui.theme.PocketGBTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * N4 · centro de ajustes del juego de extremo a extremo (ViewModel real, núcleo real para la huella y `preferences.json`
 * en disco): renombrar, etiquetas (y filtrar por ellas), «Mostrar en categoría…» con una categoría nueva y volver a su
 * carpeta, mover desde el detalle a una categoría que ya existe, ocultar, y las filas «Próximamente» para TalkBack.
 */
@RunWith(AndroidJUnit4::class)
class GameCenterUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val roms = mapOf(
        "Pokémon/Red.gb" to SyntheticRom.romOnly("RED"),
        "Kirby/Kirby.gb" to SyntheticRom.romOnly("KIRBY"),
        "Tetra.gb" to SyntheticRom.romOnly("TETRA"),
    )
    private lateinit var dir: File
    private lateinit var prefsFile: File
    private lateinit var scope: CoroutineScope
    private lateinit var vm: LibraryViewModel
    private lateinit var repository: GameplaySettingsRepository

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
        override fun uriOf(node: TreeNode) = "content://n4center/${node.id}"
    }

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "n4-center").apply { deleteRecursively(); mkdirs() }
        prefsFile = File(dir, "preferences.json")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        vm = LibraryViewModel(
            folders = object : FolderStore {
                override fun currentUri() = "content://n4center/tree"
                override fun hasPersistedPermission() = true
                override fun select(uri: String) = FolderGrant(false)
                override fun forget() {}
                override fun displayName() = "Roms"
            },
            openTree = { tree },
            roms = RomSource { uri, _ -> roms.getValue(uri.removePrefix("content://n4center/")) },
            inspector = RomInspector { rom, _ -> CoreBridge().use { it.loadRom(rom) } },
            preferencesFile = LibraryPreferencesFile(prefsFile),
            io = Dispatchers.IO,
            scope = scope,
        )
        repository = GameplaySettingsRepository(GameplaySettingsFile(File(dir, "gameplay-settings.json")), scope)
        vm.rescan()
        compose.waitUntil(10_000) { vm.state.value is LibraryState.Ready }
    }

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private lateinit var navigation: AppNavigationState

    private fun show(start: LibraryRoute = LibraryRoute.Root) = compose.setContent {
        PocketGBTheme {
            val state = androidx.compose.runtime.remember {
                AppNavigationState().also { if (start != LibraryRoute.Root) it.push(start) }
            }
            navigation = state
            val deps = LibraryRouteDeps(vm, {}, {}, emptySet(), repository, saves = { _, _ -> })
            NavDisplay(
                backStack = state.currentBackStack,
                onBack = { state.pop() },
                entryProvider = { route -> NavEntry(route) { LibraryRouteContent(route as LibraryRoute, state, deps) } },
            )
        }
    }

    private fun entry(id: String): RomEntry = (vm.state.value as LibraryState.Ready).entries.single { it.id == id }

    private fun onDisk() = runBlocking { vm.flushPreferences() }.let { LibraryPreferencesFile(prefsFile).load() }

    private fun openCenterFor(title: String) {
        compose.onNodeWithTag("library-collection").performScrollToNode(hasTestTag("game-card") and hasText(title, substring = true))
        compose.onNode(hasTestTag("game-card") and hasText(title, substring = true)).performTouchInput { longClick() }
        compose.onNodeWithText("Ajustes del juego").performClick()
        // La huella se confirma leyendo el ROM: entonces se pueden cambiar categoría y etiquetas.
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("game-center-category-change") and SemanticsMatcher.keyNotDefined(SemanticsProperties.Disabled))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun renameTagMoveToANewCategoryAndReturnFromTheCenter() {
        show()
        openCenterFor("KIRBY")
        val kirby = entry("Kirby/Kirby.gb")
        // Nombre.
        compose.onNodeWithTag("game-settings-rename").performClick()
        compose.onNodeWithTag("rename-field").performTextClearance()
        compose.onNodeWithTag("rename-field").performTextInput("Mi Kirby")
        compose.onNodeWithTag("rename-save").performClick()
        compose.onNodeWithText("Ajustes de Mi Kirby").assertIsDisplayed()
        // Etiquetas.
        compose.onNodeWithTag("game-center-tags-edit").performClick()
        compose.onNodeWithTag("tag-editor-field").performTextInput("plataformas")
        compose.onNodeWithTag("tag-editor-add").performClick()
        compose.onNodeWithTag("tag-chip-plataformas").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Quitar la etiqueta plataformas")),
        )
        compose.onNodeWithTag("tag-editor-done").performClick()
        // ListItem fusiona su contenido: la fila de etiquetas está en el árbol sin fusionar.
        compose.onNodeWithTag("game-center-tags-value", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("game-center-tags").assertTextContains("plataformas", substring = true)
        // Mostrar en una categoría nueva.
        compose.onNodeWithTag("game-center-category-change").performClick()
        compose.onNodeWithTag("category-picker-field").performScrollTo().performTextInput("Favoritas")
        compose.onNodeWithTag("category-picker-apply").assertIsEnabled().performClick()
        compose.onNodeWithTag("game-center-category-value", useUnmergedTree = true).assertTextEquals("Favoritas")
        assertTrue(compose.onAllNodesWithTag("game-moved-badge", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        val saved = onDisk()
        val fingerprint = saved.fingerprints.getValue(kirby.id)
        assertTrue("confirmada", saved.hasConfirmedFingerprint(kirby))
        assertEquals(listOf("plataformas"), saved.tagsByFingerprint[fingerprint])
        assertEquals(listOf("Favoritas"), saved.virtualFoldersByFingerprint[fingerprint])
        assertEquals("Mi Kirby", saved.aliasesByFingerprint[fingerprint])
        assertTrue(prefsFile.readText().contains("\"formatVersion\":4"))
        // Volver a su carpeta.
        compose.onNodeWithTag("game-center-category-return").performClick()
        compose.onNodeWithTag("game-center-category-value", useUnmergedTree = true).assertTextEquals("Kirby · su carpeta")
        assertNull(onDisk().virtualFoldersByFingerprint[fingerprint])
        compose.onNodeWithTag("game-settings-done").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("game-settings").fetchSemanticsNodes().isEmpty() }
        // Filtrar por la etiqueta (vertical: chip «Etiqueta»).
        compose.onNodeWithTag("filter-tag").performScrollTo().performClick()
        compose.onNodeWithTag("menu-tag-plataformas").performClick()
        compose.onNodeWithTag("library-section-title", useUnmergedTree = true).assertTextEquals("Etiqueta «plataformas»")
        assertEquals(1, compose.onAllNodesWithTag("game-card").fetchSemanticsNodes().size)
        compose.onNode(hasTestTag("game-card") and hasText("Mi Kirby", substring = true)).assertExists()
    }

    @Test
    fun fromTheDetailsAGameIsShownInAnExistingCategoryWithTheBadge() {
        show(LibraryRoute.Details("Tetra.gb"))
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("game-details-settings").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("game-details-settings").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("game-center-category-change") and SemanticsMatcher.keyNotDefined(SemanticsProperties.Disabled))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("game-center-category-change").performClick()
        // H13: «pokemon» escrita a mano reutiliza la categoría «Pokémon» que ya existe.
        compose.onNodeWithTag("category-picker-field").performScrollTo().performTextInput("pokemon")
        compose.onNodeWithText("Se usará la que ya existe: Pokémon", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("category-picker-apply").performClick()
        compose.onNodeWithTag("game-settings-done").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("game-settings").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("game-details-shown-in").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Se ve en «Pokémon»", useUnmergedTree = true).assertExists()
        // Lo que se ve en el detalle es el archivo: sigue en la raíz.
        compose.onNodeWithTag("game-details-location", useUnmergedTree = true).assertTextEquals("Tetra.gb")
        compose.runOnIdle {
            assertEquals(listOf("Pokémon"), vm.prefs.value.virtualFolderOf(entry("Tetra.gb")))
        }
    }

    @Test
    fun progressOpensItsEditorAndHidingAsksFirst() {
        show()
        openCenterFor("TETRA")
        // N5: «Portada» ya funciona (su fila tiene «Cambiar»); N6: «Progreso» también (abre hitos y lector).
        compose.onNodeWithTag("game-center-cover-change").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("game-center-progress").performScrollTo()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Disabled))
            .performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("progress-dialog").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("progress-template-pokemon").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("progress-template-pokemon").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("progress-milestone-Liga Pokémon").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("progress-dialog-close").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("progress-dialog").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("game-center-hide").performScrollTo().performClick()
        compose.onNodeWithText("Ocultar", substring = false).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("game-settings").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { assertTrue(vm.prefs.value.isHidden(entry("Tetra.gb"))) }
    }
}

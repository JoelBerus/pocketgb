package com.joelbermudez.pocketgb.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.joelbermudez.pocketgb.library.DetailsLoad
import com.joelbermudez.pocketgb.library.GameDetails
import com.joelbermudez.pocketgb.library.LibraryFilter
import com.joelbermudez.pocketgb.library.LibraryPreferencesData
import com.joelbermudez.pocketgb.library.LibraryState
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.settings.GameplaySettingsData
import com.joelbermudez.pocketgb.ui.details.GameDetailsContent
import com.joelbermudez.pocketgb.ui.gameplay.GameplayHud
import com.joelbermudez.pocketgb.ui.library.GameActions
import com.joelbermudez.pocketgb.ui.library.LibraryContent
import com.joelbermudez.pocketgb.ui.settings.AudioSettingsContent
import com.joelbermudez.pocketgb.ui.settings.ControlsSettingsContent
import com.joelbermudez.pocketgb.ui.settings.SettingsScreen
import com.joelbermudez.pocketgb.ui.theme.PocketGBTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** R16: todo elemento con acción de clic de las pantallas principales tiene un área táctil de al menos 48 × 48 dp. */
@RunWith(AndroidJUnit4::class)
class TouchTargetTest {
    @get:Rule val compose = createComposeRule()

    private fun entry(id: String, title: String, color: Boolean) = RomEntry(
        id = id, uri = "content://t/$id", fileName = id, title = title, isColor = color,
        sizeBytes = 32L * 1024, headerChecksumOk = true, problem = null,
    )

    private val games = listOf(entry("Red.gb", "RED", false), entry("Yellow.gbc", "YELLOW", true))

    private fun clickableNodes(): List<SemanticsNode> =
        compose.onRoot().fetchSemanticsNode().let { root -> collect(root) }.filter { SemanticsActions.OnClick in it.config }

    private fun collect(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::collect)

    /**
     * Anfitrión alto: las pantallas que se desplazan se componen enteras y ningún elemento queda recortado por el
     * visor (un nodo fuera de pantalla tendría un área táctil de 0 × 0).
     */
    private fun show(content: @Composable () -> Unit) = compose.setContent {
        Box(Modifier.requiredSize(412.dp, 3000.dp)) { content() }
    }

    private fun assertAllTargetsAtLeast48(screen: String, fontScale: Float = 1f) {
        val nodes = clickableNodes()
        assertTrue("$screen: no hay elementos clicables", nodes.isNotEmpty())
        val density = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        nodes.forEach { node ->
            val bounds = node.touchBoundsInRoot
            val width = bounds.width / density
            val height = bounds.height / density
            val name = node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)
                ?: node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)?.firstOrNull()
                ?: node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.firstOrNull()?.text
            assertTrue("$screen (fuente $fontScale): «$name» mide $width × $height dp", width >= 47.5f && height >= 47.5f)
        }
    }

    @Composable
    private fun Library(prefs: LibraryPreferencesData) {
        PocketGBTheme {
            LibraryContent(
                state = LibraryState.Ready(games, "Juegos"),
                prefs = prefs,
                query = "",
                filter = LibraryFilter.ALL,
                onQueryChange = {},
                onFilterChange = {},
                onLayoutChange = {},
                onSortChange = {},
                onChooseFolder = {},
                onRescan = {},
                actions = GameActions(onOpenDetails = {}, onToggleFavorite = {}, onHide = {}, onPlay = {}),
                newGamesSummary = 0,
                onNewGamesSummaryShown = {},
                artworkFingerprints = emptySet(),
            )
        }
    }

    @Test
    fun libraryTargetsAreBigEnough() {
        val prefs = LibraryPreferencesData().recordPlayed(games[0].id, "%064x".format(1L), 10L)
        show { Library(prefs) }
        compose.waitForIdle()
        assertAllTargetsAtLeast48("Biblioteca")
    }

    @Test
    fun detailsTargetsAreBigEnough() {
        show {
            PocketGBTheme {
                GameDetailsContent(
                    entry = games[0],
                    load = DetailsLoad.Loaded(GameDetails(games[0], "ROM", 32 * 1024, 0, hasBattery = false, hasRtc = false, headerChecksumOk = true, globalChecksumOk = true, fingerprint = "%064x".format(1L))),
                    favorite = false,
                    lastPlayedAt = null,
                    onPlay = {},
                    onToggleFavorite = {},
                    onHide = {},
                    onBack = {},
                )
            }
        }
        compose.waitForIdle()
        assertAllTargetsAtLeast48("Detalle")
    }

    @Test
    fun settingsTargetsAreBigEnough() {
        show {
            PocketGBTheme {
                SettingsScreen(onAppearance = {}, onLibrary = {}, onSaves = {}, onAbout = {})
            }
        }
        compose.waitForIdle()
        assertAllTargetsAtLeast48("Ajustes")
    }

    @Test
    fun controlsSettingsTargetsAreBigEnough() {
        show {
            PocketGBTheme { ControlsSettingsContent(data = GameplaySettingsData(), onUpdate = {}, onBack = {}) }
        }
        compose.waitForIdle()
        assertAllTargetsAtLeast48("Ajustes de controles")
    }

    @Test
    fun audioSettingsTargetsAreBigEnough() {
        show {
            PocketGBTheme { AudioSettingsContent(data = GameplaySettingsData(), onUpdate = {}, onBack = {}) }
        }
        compose.waitForIdle()
        assertAllTargetsAtLeast48("Ajustes de audio")
    }

    @Test
    fun hudTargetsAreBigEnough() {
        show {
            PocketGBTheme(forceDark = true) {
                Column { GameplayHud(speed = 2, onPause = {}, onCycleSpeed = {}) }
            }
        }
        compose.waitForIdle()
        assertAllTargetsAtLeast48("HUD")
    }
}

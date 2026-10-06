package com.joelbermudez.pocketgb

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.joelbermudez.pocketgb.app.AppNavigationState
import com.joelbermudez.pocketgb.app.AppScaffold
import com.joelbermudez.pocketgb.app.TopLevelDestination
import com.joelbermudez.pocketgb.ui.theme.PocketGBTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppShellTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun showsThreeLabeledDestinations() {
        compose.onNode(hasText("Biblioteca") and hasClickAction()).assertIsDisplayed()
        compose.onNode(hasText("Favoritos") and hasClickAction()).assertIsDisplayed()
        compose.onNode(hasText("Ajustes") and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun settingsOpensAppearanceAndBackReturns() {
        compose.onNode(hasText("Ajustes") and hasClickAction()).performClick()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Apariencia"))
        compose.onNode(hasText("Apariencia") and hasClickAction()).performClick()
        compose.onNodeWithText("Color dinámico").assertIsDisplayed()

        compose.activityRule.scenario.onActivity {
            it.onBackPressedDispatcher.onBackPressed()
        }

        compose.onNodeWithTag("settings-list").assertIsDisplayed()
    }

    private fun openSettings(label: String) {
        compose.onNode(hasText("Ajustes") and hasClickAction()).performClick()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText(label))
        // El texto también puede estar en la barra inferior (Biblioteca): se limita a la lista de ajustes.
        compose.onNode(hasText(label) and hasClickAction() and hasAnyAncestor(hasTestTag("settings-list"))).performClick()
        // Espera a estar dentro de la subpantalla (botón Volver) antes de seguir.
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Volver").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun back() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Volver").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    fun controllerMappingIsReachableAndAssignsAButton() {
        openSettings("Controles")
        compose.onNodeWithTag("controller-assign").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Botones del mando").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("pad-row-A").assertIsDisplayed()
        compose.onNodeWithTag("pad-key-A", useUnmergedTree = true).assertTextEquals("Derecho (B)")
        compose.onNodeWithTag("pad-reset").assertIsNotEnabled()
        try {
            compose.onNodeWithTag("pad-row-A").performClick()
            compose.onNodeWithText("Pulsa un botón del mando").assertIsDisplayed()
            compose.onNodeWithTag("assign-dialog").performKeyInput { keyDown(androidx.compose.ui.input.key.Key.ButtonX) }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Pulsa un botón del mando").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("pad-key-A", useUnmergedTree = true).assertTextEquals("Izquierdo (X)")
            compose.onNodeWithTag("pad-reset").assertIsEnabled()
        } finally {
            compose.onNodeWithTag("pad-reset").performScrollTo().performClick()
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("pad-key-A") and hasText("Derecho (B)"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("pad-key-A", useUnmergedTree = true).assertTextEquals("Derecho (B)")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("controller-show-touch").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun settingsListHasThreeGroupsWithoutPlaceholders() {
        compose.onNode(hasText("Ajustes") and hasClickAction()).performClick()
        compose.onNodeWithText("Juego").assertIsDisplayed()
        compose.onNodeWithText("Biblioteca y partidas").assertIsDisplayed()
        compose.onAllNodesWithText("Disponible en próximos hitos").assertCountEquals(0)
    }

    @Test
    fun controlsScreenShowsRowsAndResetIsDisabledAtFactory() {
        openSettings("Controles")
        compose.onNodeWithText("Opacidad en horizontal").assertIsDisplayed()
        compose.onNodeWithText("Háptica").assertExists()
        compose.onNodeWithTag("reset-layout-portrait").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("reset-layout-landscape").assertIsNotEnabled()
        back()
        compose.onNodeWithTag("settings-list").assertIsDisplayed()
    }

    @Test
    fun displayScreenShowsScaleAndFilter() {
        openSettings("Pantalla")
        compose.onNodeWithText("Escala entera en horizontal").assertIsDisplayed()
        compose.onNodeWithText("Píxeles nítidos").assertIsDisplayed()
        back()
    }

    @Test
    fun emulationScreenShowsColorAndGbcRow() {
        openSettings("Emulación")
        compose.onNodeWithText("Color en juegos de Game Boy").assertIsDisplayed()
        compose.onNodeWithText("Siempre en color").assertExists()
        back()
    }

    @Test
    fun audioScreenShowsVolumeAndSystemFooter() {
        openSettings("Audio")
        compose.onNodeWithTag("audio-volume").assertIsDisplayed()
        compose.onNodeWithText("El sonido sigue el volumen multimedia del teléfono.").assertIsDisplayed()
        back()
    }

    @Test
    fun storageScreenShowsThreeRowsAndClearIsDisabledWithoutArtwork() {
        openSettings("Almacenamiento")
        compose.onNodeWithText("Partidas y copias").assertIsDisplayed()
        compose.onNodeWithText("Estados guardados").assertIsDisplayed()
        compose.onNodeWithText("Portadas").assertIsDisplayed()
        compose.onNodeWithTag("storage-clear-artwork").performScrollTo().assertIsNotEnabled()
        back()
    }

    @Test
    fun libraryAndSavesScreensOpen() {
        openSettings("Biblioteca")
        compose.onNodeWithTag("library-settings").assertIsDisplayed()
        compose.onNodeWithTag("library-settings").performScrollToNode(hasText("Presentación"))
        back()
        openSettings("Partidas")
        back()
        compose.onNodeWithTag("settings-list").assertIsDisplayed()
    }

    @Test
    fun aboutShowsVersionAndOpensLicenses() {
        openSettings("Acerca de")
        compose.onNodeWithTag("about-version").assertIsDisplayed()
        compose.onNodeWithTag("about-licenses").performScrollTo().performClick()
        compose.onNodeWithText("SameBoy").assertIsDisplayed()
        compose.onNodeWithTag("licenses-body").assertIsDisplayed()
        compose.onNodeWithText("Expat License", substring = true).assertExists()
        // Licencias → Acerca de (sigue habiendo botón Volver, así que no se usa back()).
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("licenses-body").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("about-version").assertExists()
    }

    // ---- A7 R14: barra inferior en compacta, rail en ≥ medium, mismo estado

    private val size = mutableStateOf(DpSize(360.dp, 640.dp))

    @OptIn(ExperimentalTestApi::class)
    private fun showShell(state: AppNavigationState) {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                PocketGBTheme {
                    DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size.value)) {
                        AppScaffold(state, Modifier.fillMaxSize()) { Text("contenido", Modifier.testTag("shell-content")) }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun itemBounds() = TopLevelDestination.entries.map {
        compose.onNodeWithTag("nav-item-${it.name}").getUnclippedBoundsInRoot()
    }

    @Test
    fun compactWindowUsesTheBottomBar() {
        size.value = DpSize(360.dp, 640.dp)
        showShell(AppNavigationState())
        compose.onNodeWithTag("app-nav-bar").assertExists()
        compose.onAllNodesWithTag("app-nav-rail").assertCountEquals(0)
        val bounds = itemBounds()
        assertEquals("la barra reparte los destinos en horizontal", 1, bounds.map { it.top }.distinct().size)
        assertEquals(3, bounds.map { it.left }.distinct().size)
        compose.onNodeWithTag("shell-content").assertIsDisplayed()
    }

    @Test
    fun wideWindowUsesTheRailWithTheSameDestinations() {
        size.value = DpSize(1000.dp, 700.dp)
        showShell(AppNavigationState())
        compose.onNodeWithTag("app-nav-rail").assertExists()
        compose.onAllNodesWithTag("app-nav-bar").assertCountEquals(0)
        val bounds = itemBounds()
        assertEquals("el rail apila los destinos en vertical", 1, bounds.map { it.left }.distinct().size)
        assertEquals(3, bounds.map { it.top }.distinct().size)
        for (label in listOf("Biblioteca", "Favoritos", "Ajustes")) {
            compose.onNode(hasText(label) and hasClickAction()).assertIsDisplayed()
        }
        compose.onNodeWithTag("shell-content").assertIsDisplayed()
    }

    @Test
    fun selectedDestinationSurvivesResizingBetweenBarAndRail() {
        val state = AppNavigationState()
        size.value = DpSize(360.dp, 640.dp)
        showShell(state)
        compose.onNodeWithTag("nav-item-SETTINGS").performClick()
        assertEquals(TopLevelDestination.SETTINGS, state.selected)
        compose.runOnUiThread { size.value = DpSize(1000.dp, 700.dp) }
        compose.waitForIdle()
        compose.onNodeWithTag("app-nav-rail").assertExists()
        assertEquals(TopLevelDestination.SETTINGS, state.selected)
        compose.onNodeWithTag("nav-item-SETTINGS").assertIsSelected()
        compose.onNodeWithTag("nav-item-FAVORITES").performClick()
        compose.runOnUiThread { size.value = DpSize(360.dp, 640.dp) }
        compose.waitForIdle()
        compose.onNodeWithTag("app-nav-bar").assertExists()
        compose.onNodeWithTag("nav-item-FAVORITES").assertIsSelected()
    }
}

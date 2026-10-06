package com.joelbermudez.pocketgb

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
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
import androidx.test.ext.junit.runners.AndroidJUnit4
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
}

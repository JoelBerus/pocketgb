package com.joelbermudez.pocketgb

import android.content.Intent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DebugCatalogTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun knownScreenShowsExpectedSemantics() {
        launch("library-grid").use {
            compose.onNodeWithTag("debug-screen-library-grid").assertIsDisplayed()
            // Hay más de cuatro juegos: la cuadrícula perezosa pinta los que caben bajo el carril.
            assertTrue(compose.onAllNodesWithTag("game-card").fetchSemanticsNodes().size >= 4)
            compose.onNodeWithText("Continuar jugando").assertIsDisplayed()
        }
    }

    @Test
    fun libraryCatalogScreensShowTheirStates() {
        launch("library-empty").use { compose.onNodeWithText("Elegir carpeta").assertIsDisplayed() }
        launch("library-error").use { compose.onNodeWithText("Volver a elegir").assertIsDisplayed() }
        launch("library-search").use { compose.onNodeWithText("Sin resultados", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Buscar en todos").assertIsDisplayed() }
        launch("library-list").use {
            compose.onAllNodesWithTag("game-list-item").assertCountEquals(5)
        }
        launch("library-detail").use { compose.onNodeWithTag("game-details-play").assertIsEnabled() }
        launch("library-detail-problem").use {
            compose.onNodeWithTag("game-details-problem").assertIsDisplayed()
        }
        launch("favorites-empty").use { compose.onNodeWithText("Todavía no hay favoritos").assertIsDisplayed() }
        launch("favorites").use { compose.onNodeWithTag("favorites-recent").assertIsDisplayed() }
        launch("settings-library").use { compose.onNodeWithText("Juegos ocultos").assertIsDisplayed() }
    }

    @Test
    fun saveAndStateCatalogScreensShowTheirContent() {
        launch("pause-sheet").use {
            compose.onNodeWithTag("pause-continue").assertIsDisplayed()
            compose.onNodeWithTag("pause-states").assertIsDisplayed()
            compose.onNodeWithTag("pause-exit").assertIsDisplayed()
        }
        launch("pause-dialog").use { compose.onNodeWithTag("pause-continue").assertIsDisplayed() }
        launch("states-sheet").use {
            compose.onNodeWithTag("state-row-auto").assertIsDisplayed()
            compose.onNodeWithTag("state-save-slot1").assertIsDisplayed()
            compose.onNodeWithText("Dañado").assertExists()
        }
        launch("exit-save-failed").use {
            compose.onNodeWithText("No se pudo guardar la partida en este teléfono").assertIsDisplayed()
            compose.onNodeWithTag("exit-failed-retry").assertIsDisplayed()
            compose.onNodeWithTag("exit-failed-leave").assertIsDisplayed()
        }
        launch("exit-risk").use { compose.onNodeWithTag("exit-risk-confirm").assertIsDisplayed() }
        launch("saves-settings").use { compose.onNodeWithText("POKÉMON RED").assertIsDisplayed() }
        launch("library-detail-played").use { compose.onNodeWithText("Continuar").assertIsDisplayed() }
        launch("save-warning").use { compose.onNodeWithTag("warning-ok").assertIsDisplayed() }
        launch("save-problem").use {
            compose.onNodeWithTag("save-problem-indicator").assertIsDisplayed()
            compose.onNodeWithText("Guardado pendiente", substring = true).assertExists()
        }
        launch("states-rescue").use {
            compose.onNodeWithTag("state-row-rescue").assertExists()
            compose.onNodeWithTag("state-load-rescue").assertExists()
            compose.onNodeWithText("estado de rescate", substring = true).assertExists()
        }
        launch("open-error").use { compose.onNodeWithTag("open-error-ok").assertIsDisplayed() }
    }

    @Test
    fun unknownScreenIsVisibleFailure() {
        launch("missing").use {
            compose.onNodeWithText("Pantalla desconocida").assertIsDisplayed()
            compose.onNodeWithText("missing").assertIsDisplayed()
        }
    }

    @Test
    fun nativeVideoScreenShowsRunningSurface() {
        launch("native-video").use {
            compose.onNodeWithTag("debug-screen-native-video").assertIsDisplayed()
            compose.onNodeWithTag("native-video-surface").assertIsDisplayed()
            compose.onNodeWithText("Vídeo nativo").assertIsDisplayed()
        }
    }

    @Test
    fun gameplayControlsScreenShowsSurfaceControlsAndSpeed() {
        launch("gameplay-controls").use {
            compose.onNodeWithTag("debug-screen-gameplay-controls").assertIsDisplayed()
            compose.onNodeWithTag("gameplay-surface").assertIsDisplayed()
            compose.onNodeWithTag("game-controls").assertIsDisplayed()
            compose.onNodeWithContentDescription(
                "Controles del juego: cruceta, A, B, Start, Select y Menú",
            ).assertIsDisplayed()
            compose.onNodeWithText("×1").assertIsDisplayed()
        }
    }

    @Test
    fun gameplayShowsPauseAfterGoingToBackground() {
        launch("gameplay-controls").use { scenario ->
            compose.onNodeWithText("Juego en pausa").assertDoesNotExist()

            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)

            compose.onNodeWithText("Juego en pausa").assertIsDisplayed()
        }
    }

    @Test
    fun fastForwardCatalogStartsAtFourTimesSpeed() {
        launch("gameplay-fast-forward").use {
            compose.onNodeWithText("×4").assertIsSelected()
        }
    }

    private fun launch(screen: String): ActivityScenario<MainActivity> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, MainActivity::class.java)
            .putExtra("screen", screen)
            .putExtra("theme", "light")
            .putExtra("dynamicColor", false)
        return ActivityScenario.launch(intent)
    }
}

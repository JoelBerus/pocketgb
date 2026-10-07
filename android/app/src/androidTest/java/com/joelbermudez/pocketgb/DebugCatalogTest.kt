package com.joelbermudez.pocketgb

import android.content.Intent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.Lifecycle
import android.view.View
import android.view.ViewGroup
import com.joelbermudez.pocketgb.input.GameControlsView
import com.joelbermudez.pocketgb.settings.ControlsVisibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
            compose.onNodeWithText("Continuar jugando").assertIsDisplayed()
        }
    }

    @Test
    fun libraryCatalogScreensShowTheirStates() {
        launch("library-empty").use { compose.onNodeWithText("Elegir carpeta").assertIsDisplayed() }
        launch("library-error").use { compose.onNodeWithText("Volver a elegir").assertIsDisplayed() }
        launch("library-search").use {
            // H9 (N3): con «mostrar teclado con teclado físico» activado, el teclado empujaba el botón fuera de la vista.
            Espresso.closeSoftKeyboard()
            compose.onNodeWithText("Sin resultados", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Buscar en todos").assertIsDisplayed()
        }
        launch("library-list").use {
            compose.onAllNodesWithTag("game-list-item").assertCountEquals(5)
        }
        launch("library-detail").use { compose.onNodeWithTag("game-details-play").assertIsEnabled() }
        launch("library-detail-problem").use {
            compose.onNodeWithTag("game-details-problem").assertIsDisplayed()
        }
        launch("favorites-empty").use { compose.onNodeWithText("Todavía no hay favoritos").assertIsDisplayed() }
        launch("favorites").use { compose.onNodeWithTag("favorites-recent").assertIsDisplayed() }
        launch("settings-library").use { compose.onNodeWithText("Juegos ocultos").assertExists() }
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
        // A9 (cambia J8): jugado sin estado automático vigente = «Jugar»; con él, «Continuar» y «Jugar desde el inicio».
        launch("library-detail-played").use { compose.onNodeWithText("Jugar").assertIsDisplayed() }
        launch("details-resume-exact").use {
            compose.onNodeWithText("Continuar").assertIsDisplayed()
            compose.onNodeWithText("Jugar desde el inicio").assertIsDisplayed()
            compose.onNodeWithTag("game-details-title").assertTextEquals("Rojo de Joel")
        }
        launch("details-rename").use { compose.onNodeWithTag("rename-field").assertTextContains("Rojo de Joel") }
        launch("resume-failed").use { compose.onNodeWithTag("resume-failed-play").assertIsDisplayed() }
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
            // Desde A7-L2 la vista no tiene una descripción única: cada control es un nodo virtual (TalkBack).
            it.onActivity { activity ->
                val controls = findControls(activity.window.decorView)
                assertNotNull("falta GameControlsView", controls)
                assertNull(controls!!.contentDescription)
                val host = controls.accessibilityNodeProvider!!.createAccessibilityNodeInfo(View.NO_ID)!!
                assertEquals(5, host.childCount)
            }
            compose.onNodeWithTag("hud-speed").assertIsDisplayed()
        }
    }

    @Test
    fun controllerScreensHideTheTouchControlsUnlessTheSettingKeepsThem() {
        launch("gameplay-controller", "controller" to "1").use {
            compose.onNodeWithTag("hud-speed").assertIsDisplayed()
            it.onActivity { a -> assertEquals(ControlsVisibility.HIDDEN, findControls(a.window.decorView)!!.controlsVisibility) }
        }
        launch("gameplay-controller-touch", "controller" to "1", "showTouch" to "1").use {
            it.onActivity { a -> assertEquals(ControlsVisibility.ALWAYS, findControls(a.window.decorView)!!.controlsVisibility) }
        }
    }

    @Test
    fun controllerMappingScreensShowTheDefaultsAndTheAssignDialog() {
        launch("settings-controller-mapping").use {
            compose.onNodeWithTag("pad-row-A").assertIsDisplayed()
            compose.onNodeWithTag("pad-key-A", useUnmergedTree = true).assertTextEquals("Derecho (B)")
            compose.onNodeWithTag("pad-reset").assertExists()
        }
        launch("controller-assign-dialog").use {
            compose.onNodeWithTag("assign-dialog").assertIsDisplayed()
            compose.onNodeWithText("Pulsa un botón del mando").assertIsDisplayed()
        }
    }

    @Test
    fun largeFontAndHighContrastArgumentsReachTheScreens() {
        launch("library-ax5", "fontScale" to "2.0").use {
            // Con fuente al 200 % la biblioteca pasa a una columna (columnsFor): una sola tarjeta por fila.
            compose.onNodeWithTag("debug-screen-library-ax5").assertIsDisplayed()
            compose.onNodeWithText("Continuar jugando").assertIsDisplayed()
        }
        launch("library-high-contrast", "contrast" to "high").use {
            compose.onNodeWithTag("debug-screen-library-high-contrast").assertIsDisplayed()
        }
    }

    private fun findControls(root: View): GameControlsView? {
        if (root is GameControlsView) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) findControls(root.getChildAt(i))?.let { return it }
        return null
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
            compose.onNodeWithText("×4").assertIsDisplayed()
        }
    }

    private fun launch(screen: String, vararg extras: Pair<String, String>): ActivityScenario<MainActivity> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, MainActivity::class.java)
            .putExtra("screen", screen)
            .putExtra("theme", "light")
            .putExtra("dynamicColor", false)
        extras.forEach { (key, value) -> intent.putExtra(key, value) }
        return ActivityScenario.launch(intent)
    }
}

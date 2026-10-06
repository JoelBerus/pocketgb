package com.joelbermudez.pocketgb.game

import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.joelbermudez.pocketgb.settings.AppearanceState
import com.joelbermudez.pocketgb.settings.ThemeMode
import com.joelbermudez.pocketgb.testing.waitUntil
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Pantalla completa y tema forzado oscuro del juego con la app en claro (K4, K5). */
@RunWith(AndroidJUnit4::class)
class ImmersiveModeTest {
    private val h = GameplayHarness(AppearanceState(ThemeMode.LIGHT, dynamicColor = false))

    @get:Rule
    val rules = h.rules

    @After
    fun tearDown() = h.close()

    private fun statusBarsVisible(): Boolean {
        var visible = true
        h.compose.activityRule.scenario.onActivity { activity ->
            val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
            visible = insets?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true
        }
        return visible
    }

    private fun lightStatusIcons(): Boolean {
        var light = false
        h.compose.activityRule.scenario.onActivity { activity ->
            light = WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars
        }
        return light
    }

    @Test
    fun barsAreHiddenWithAGameAndRestoredOnExit() {
        h.compose.waitForIdle()
        assertTrue("antes del juego las barras se ven", waitUntil(5_000) { statusBarsVisible() })
        assertTrue("app en claro: iconos de barra oscuros", lightStatusIcons())

        val game = h.openGame()
        assertTrue("barras ocultas con partida", waitUntil(5_000) { !statusBarsVisible() })
        assertFalse("juego oscuro: iconos de barra claros", lightStatusIcons())

        h.pressBackViaDispatcher()
        h.waitTag("pause-sheet")
        h.compose.onNodeWithTag("pause-exit").performClick()
        h.compose.waitUntil(10_000) { h.vm.game.value == null }
        h.waitTag("no-game")
        assertTrue(game.isClosed)
        assertTrue("barras restauradas al salir", waitUntil(5_000) { statusBarsVisible() })
        assertTrue("iconos restaurados al tema de la app", waitUntil(5_000) { lightStatusIcons() })
    }

    @Test
    fun theGameSheetsAreDarkEvenWithALightApp() {
        h.openGame()
        h.pressBackViaDispatcher()
        h.waitTag("pause-sheet")
        val image = h.compose.onNodeWithTag("pause-sheet").captureToImage()
        val pixels = IntArray(image.width * image.height).also { image.readPixels(it) }
        // Esquina superior izquierda de la hoja: su fondo, sin texto.
        val argb = pixels[2 * image.width + 2]
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        assertTrue("el fondo de la hoja es oscuro (luminancia $luminance)", luminance < 0.3)
    }
}

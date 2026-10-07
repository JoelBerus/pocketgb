package com.joelbermudez.pocketgb.covers

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.joelbermudez.pocketgb.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** N5 · las pantallas del catálogo de portadas muestran la fuente esperada (las mismas que se capturan). */
@RunWith(AndroidJUnit4::class)
class N5CatalogTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun launch(screen: String): ActivityScenario<MainActivity> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, MainActivity::class.java)
            .putExtra("screen", screen)
            .putExtra("theme", "light")
            .putExtra("dynamicColor", false)
        return ActivityScenario.launch(intent)
    }

    private fun waitForTag(tag: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun detailsShowEachSource() {
        mapOf(
            "n5-details-imported" to "cover-imported",
            "n5-details-folder" to "cover-sidecar",
            "n5-details-capture" to "cover-capture",
            "n5-details-generated" to "cover-generated",
        ).forEach { (screen, tag) -> launch(screen).use { waitForTag(tag) } }
    }

    @Test
    fun libraryShowsTheContinueRailWithOwnCovers() {
        launch("n5-library").use {
            compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Continuar jugando")).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test
    fun centerPauseAndSettingsShowTheNewControls() {
        launch("n5-game-center").use { compose.onNodeWithTag("game-center-cover-value", useUnmergedTree = true).assertIsDisplayed() }
        launch("n5-cover-dialog").use { compose.onNodeWithTag("cover-dialog").assertIsDisplayed() }
        launch("n5-pause").use { compose.onNodeWithTag("pause-use-as-cover").assertIsDisplayed() }
        launch("n5-settings-library").use { compose.onNodeWithText("Preferir capturas").assertExists() }
    }
}

package com.joelbermudez.pocketgb

import android.content.Intent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
            compose.onAllNodesWithTag("game-card").assertCountEquals(4)
        }
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

    private fun launch(screen: String): ActivityScenario<MainActivity> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, MainActivity::class.java)
            .putExtra("screen", screen)
            .putExtra("theme", "light")
            .putExtra("dynamicColor", false)
        return ActivityScenario.launch(intent)
    }
}

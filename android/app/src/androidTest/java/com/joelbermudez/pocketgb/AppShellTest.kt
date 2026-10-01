package com.joelbermudez.pocketgb

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
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
}

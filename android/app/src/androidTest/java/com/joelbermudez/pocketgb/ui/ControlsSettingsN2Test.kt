package com.joelbermudez.pocketgb.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.joelbermudez.pocketgb.settings.DiagonalMode
import com.joelbermudez.pocketgb.settings.GameplaySettingsData
import com.joelbermudez.pocketgb.ui.settings.ControlsSettingsContent
import com.joelbermudez.pocketgb.ui.theme.PocketGBTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** N2: Ajustes › Controles ofrece «Diagonales: Normales / Reducidas / Desactivadas» y arranca en «Reducidas». */
@RunWith(AndroidJUnit4::class)
class ControlsSettingsN2Test {
    @get:Rule val compose = createComposeRule()

    @Test
    fun diagonalsDefaultToReducedAndEachOptionUpdatesTheSetting() {
        var data by mutableStateOf(GameplaySettingsData())
        compose.setContent {
            PocketGBTheme {
                ControlsSettingsContent(data = data, onUpdate = { change -> data = change(data) }, onBack = {})
            }
        }
        compose.onNode(hasText("Diagonales")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("controls-diagonals-1").performScrollTo().assertIsSelected() // Reducidas
        compose.onNodeWithTag("controls-diagonals-2").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(DiagonalMode.DISABLED, data.diagonalMode)
        compose.onNodeWithTag("controls-diagonals-2").assertIsSelected()
        compose.onNodeWithTag("controls-diagonals-0").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(DiagonalMode.NORMAL, data.diagonalMode)
        compose.onNodeWithTag("controls-diagonals-1").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(DiagonalMode.REDUCED, data.diagonalMode)
    }
}

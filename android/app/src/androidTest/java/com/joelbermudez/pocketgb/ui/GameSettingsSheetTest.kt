package com.joelbermudez.pocketgb.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertCountEquals
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.joelbermudez.pocketgb.settings.GameOverrides
import com.joelbermudez.pocketgb.settings.GameplaySettingsData
import com.joelbermudez.pocketgb.ui.details.GameSettingsSheet
import com.joelbermudez.pocketgb.ui.theme.PocketGBTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GameSettingsSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private val fingerprint = "ab".repeat(32)
    private var stored = GameplaySettingsData()
    private var dismissed = 0

    @Composable
    private fun Harness(isColor: Boolean, initial: GameplaySettingsData = GameplaySettingsData()) {
        var settings by remember { mutableStateOf(initial) }
        stored = settings
        PocketGBTheme {
            GameSettingsSheet(
                title = "TETRIS",
                console = com.joelbermudez.pocketgb.library.RomConsole.gameBoy(isColor),
                global = settings,
                overrides = settings.perGame[fingerprint] ?: GameOverrides(),
                onOverridesChange = { next ->
                    settings = settings.setOverrides(fingerprint, next)
                    stored = settings
                },
                onDismiss = { dismissed++ },
            )
        }
    }

    @Test
    fun globalToCustomizedAndBackToGlobalRemovesTheEntry() {
        compose.setContent { Harness(isColor = false) }
        compose.onNodeWithText("Global (sin color)").assertIsDisplayed()
        compose.onAllNodesWithText("Personalizado").assertCountEquals(0)
        compose.onNodeWithTag("game-settings-reset").assertDoesNotExistCompat()

        compose.onNodeWithTag("game-setting-color").performClick()
        compose.onNodeWithTag("game-setting-color-1").performClick() // En color
        compose.waitForIdle()
        assertEquals(GameOverrides(colorForGameBoy = true), stored.perGame[fingerprint])
        compose.onNodeWithText("Personalizado").assertIsDisplayed()

        compose.onNodeWithTag("game-settings-reset").performClick()
        compose.waitForIdle()
        assertTrue("volver a global borra la entrada", stored.perGame.isEmpty())
        compose.onAllNodesWithText("Personalizado").assertCountEquals(0)
    }

    @Test
    fun choosingGlobalForEachSettingAlsoClearsTheEntry() {
        compose.setContent {
            Harness(
                isColor = false,
                initial = GameplaySettingsData().setOverrides(fingerprint, GameOverrides(colorForGameBoy = true)),
            )
        }
        compose.onNodeWithTag("game-setting-color").performClick()
        compose.onNodeWithTag("game-setting-color-0").performClick() // Global
        compose.waitForIdle()
        assertTrue(stored.perGame.isEmpty())
    }

    @Test
    fun paletteIsDisabledWithoutColorAndEnabledOnceTheGameIsInColor() {
        compose.setContent { Harness(isColor = false) }
        compose.onNodeWithTag("game-setting-palette").assertIsNotEnabled()
        compose.onNodeWithTag("game-setting-color").performClick()
        compose.onNodeWithTag("game-setting-color-1").performClick()
        compose.onNodeWithTag("game-setting-palette").assertIsEnabled().performClick()
        compose.onNodeWithTag("game-setting-palette-5").performClick()
        compose.waitForIdle()
        assertEquals(GameOverrides(colorForGameBoy = true, compatPalette = 4), stored.perGame[fingerprint])
    }

    @Test
    fun gameBoyColorGamesAreAlwaysInColorAndTheSectionIsDisabled() {
        compose.setContent { Harness(isColor = true, initial = GameplaySettingsData(colorForGameBoy = true)) }
        compose.onNodeWithText("Juego de Game Boy Color: siempre en color").assertIsDisplayed()
        compose.onNodeWithTag("game-setting-color").assertIsNotEnabled()
        compose.onNodeWithTag("game-setting-palette").assertIsNotEnabled()
        assertTrue(stored.perGame.isEmpty())
    }

    @Test
    fun footerSaysTheChangeAppliesNextTimeAndDoneDismisses() {
        compose.setContent { Harness(isColor = false) }
        compose.onNodeWithText("Se aplica la próxima vez que abras el juego.").assertIsDisplayed()
        compose.onNodeWithTag("game-settings-done").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun loadingAndUnavailableStatesExplainThemselves() {
        compose.setContent {
            PocketGBTheme {
                GameSettingsSheet("X", com.joelbermudez.pocketgb.library.RomConsole.GB, GameplaySettingsData(), GameOverrides(), {}, {}, loading = true)
            }
        }
        compose.onNodeWithText("Leyendo el juego…").assertIsDisplayed()
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistCompat() = this.assertDoesNotExist()

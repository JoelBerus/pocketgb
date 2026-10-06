package com.joelbermudez.pocketgb.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.joelbermudez.pocketgb.saves.SaveStore
import com.joelbermudez.pocketgb.saves.SavedGameUi
import com.joelbermudez.pocketgb.ui.settings.SavesSettingsContent
import com.joelbermudez.pocketgb.ui.theme.PocketGBTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SavesSettingsUiTest {
    @get:Rule val compose = createComposeRule()

    private val fp = "ab".repeat(32)
    private val games = listOf(
        SavedGameUi(fp, "POKEMON RED", "Pokemon Red.gb", listOf(SaveStore.BackupInfo(1, 1_700_000_000_000), SaveStore.BackupInfo(2, null))),
    )
    private val restored = mutableListOf<Pair<String, Int>>()

    private fun show(open: String?) = compose.setContent {
        PocketGBTheme {
            SavesSettingsContent(games, open, SnackbarHostState(), { f, n -> restored += f to n }, {})
        }
    }

    @Test fun restoreAsksForConfirmationThenRestores() {
        show(open = null)
        compose.onNodeWithText("POKEMON RED").assertIsDisplayed()
        compose.onNodeWithTag("restore-${fp.take(8)}-1").assertIsEnabled().performClick()
        compose.onNodeWithText("¿Restaurar la copia 1?").assertIsDisplayed()
        compose.onNodeWithText("Cancelar").performClick()
        assertEquals(emptyList<Pair<String, Int>>(), restored)
        compose.onNodeWithTag("restore-${fp.take(8)}-2").performClick()
        compose.onNodeWithTag("restore-confirm").performClick()
        assertEquals(listOf(fp to 2), restored)
    }

    @Test fun restoreIsDisabledForTheGameThatIsOpen() {
        show(open = fp)
        compose.onNodeWithTag("restore-${fp.take(8)}-1").assertIsNotEnabled()
        compose.onNodeWithText("No se puede restaurar mientras el juego está abierto.").assertIsDisplayed()
    }
}

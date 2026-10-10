package com.joelbermudez.pocketgb.travel

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.joelbermudez.pocketgb.library.DetailsLoad
import com.joelbermudez.pocketgb.library.RomConsole
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.ui.details.GameDetailsContent
import com.joelbermudez.pocketgb.ui.theme.PocketGBTheme
import com.joelbermudez.pocketgb.ui.travel.ChooseDialog
import com.joelbermudez.pocketgb.ui.travel.ImportedDialog
import com.joelbermudez.pocketgb.ui.travel.TravelActions
import com.joelbermudez.pocketgb.ui.travel.importedText
import androidx.test.platform.app.InstrumentationRegistry
import com.joelbermudez.pocketgb.saves.SaveLineage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** N7 · menú de viaje del detalle, estado de la partida y diálogos de importación. */
@RunWith(AndroidJUnit4::class)
class TravelUiTest {
    @get:Rule val compose = createComposeRule()

    private val entry = RomEntry(
        id = "Rojo.gb", uri = "content://demo/Rojo.gb", fileName = "Rojo.gb", title = "ROJO", console = RomConsole.GB,
        sizeBytes = 1_048_576, headerChecksumOk = true, problem = null,
    )

    @Test fun detailMenuOffersEveryTravelActionAndShowsWhereTheSaveComesFrom() {
        val calls = ArrayList<String>()
        val actions = TravelActions(
            onSend = { calls += "send" }, onSharePackage = { calls += "share" }, onSavePackage = { calls += "save" },
            onExportSav = { calls += "sav" }, onImport = { calls += "import" }, importBytes = {},
        )
        compose.setContent {
            PocketGBTheme {
                GameDetailsContent(
                    entry = entry, load = DetailsLoad.Loading, favorite = false, lastPlayedAt = null, onPlay = {}, onToggleFavorite = {},
                    onHide = {}, onBack = {}, onRename = {}, travel = actions,
                    saveStatus = SaveStatus("iPhone de Joel", "ios", System.currentTimeMillis() - 2 * 3_600_000L),
                )
            }
        }
        compose.onNodeWithText("iPhone de Joel", substring = true).assertIsDisplayed()
        for ((tag, call) in listOf(
            "game-details-send" to "send", "game-details-share" to "share", "game-details-save-package" to "save",
            "game-details-export-sav" to "sav", "game-details-import" to "import",
        )) {
            compose.onNodeWithTag("game-details-more").performClick()
            compose.onNodeWithTag(tag).performClick()
            compose.waitForIdle()
            assertEquals(call, calls.last())
        }
    }

    @Test fun continueFromAnotherDeviceAndDivergenceChoice() {
        var continued = 0
        compose.setContent {
            PocketGBTheme {
                ImportedDialog("Se instaló la partida.", "iPhone de Joel", onContinue = { continued++ }, onDismiss = {})
            }
        }
        compose.onNodeWithText("Continuar donde lo dejaste en iPhone de Joel").assertIsDisplayed()
        compose.onNodeWithTag("travel-continue").performClick()
        assertEquals(1, continued)
    }

    /** ND21: el estado de otra configuración se instala y el resultado nombra los ajustes a cambiar. */
    @Test fun aStateFromAnotherConfigurationNamesTheSettingsToChange() {
        val res = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val done = SaveImporter.Result.Done(
            SaveLineage.Incoming.INSTALL, installed = true, continueFrom = "iPhone de Joel",
            configDifferences = listOf(PgbmConfig.Key.MODEL, PgbmConfig.Key.GBA_BIOS),
        )
        val text = importedText(res, done)
        assertTrue(text, text.contains("en iPhone de Joel se jugó con otra configuración (el color de Game Boy, la BIOS)"))
        assertFalse(importedText(res, done.copy(configDifferences = emptyList())).contains("Ojo"))
        assertFalse("sin estado instalado no se avisa", importedText(res, done.copy(continueFrom = null)).contains("Ojo"))
        compose.setContent { PocketGBTheme { ImportedDialog(text, done.continueFrom, onContinue = {}, onDismiss = {}) } }
        compose.onNodeWithText("el color de Game Boy", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("travel-continue").assertIsDisplayed()
    }

    @Test fun divergenceAsksWhichSaveToKeep() {
        var choice = ""
        compose.setContent {
            PocketGBTheme { ChooseDialog("iPhone de Joel", onIncoming = { choice = "in" }, onLocal = { choice = "local" }, onDismiss = {}) }
        }
        compose.onNodeWithText("iPhone de Joel", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("travel-keep-local").performClick()
        assertEquals("local", choice)
    }
}

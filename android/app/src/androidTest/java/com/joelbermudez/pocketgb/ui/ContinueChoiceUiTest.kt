package com.joelbermudez.pocketgb.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.joelbermudez.pocketgb.library.LibraryFilter
import com.joelbermudez.pocketgb.library.LibraryPreferencesData
import com.joelbermudez.pocketgb.library.LibraryState
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.saves.ResumeFailure
import com.joelbermudez.pocketgb.ui.gameplay.ResumeFailedDialog
import com.joelbermudez.pocketgb.ui.library.GameActions
import com.joelbermudez.pocketgb.ui.library.LibraryContent
import com.joelbermudez.pocketgb.ui.theme.PocketGBTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A9 · «Continuar» / «Jugar desde el inicio» en el menú contextual y el aviso «No se pudo continuar». */
@RunWith(AndroidJUnit4::class)
class ContinueChoiceUiTest {
    @get:Rule
    val compose = createComposeRule()

    private fun entry(id: String, title: String) =
        RomEntry(id, "content://t/$id", id, title, com.joelbermudez.pocketgb.library.RomConsole.GB, 32768, true, null)

    private val red = entry("Red.gb", "RED")
    private val blue = entry("Blue.gb", "BLUE")
    private val calls = mutableListOf<String>()

    private fun show(resumable: Set<String>) = compose.setContent {
        PocketGBTheme {
            LibraryContent(
                state = LibraryState.Ready(listOf(red, blue), "Juegos"),
                prefs = LibraryPreferencesData(),
                query = "",
                filter = LibraryFilter.ALL,
                onQueryChange = {},
                onFilterChange = {},
                onLayoutChange = {},
                onSortChange = {},
                onChooseFolder = {},
                onRescan = {},
                actions = GameActions(
                    onOpenDetails = {},
                    onToggleFavorite = {},
                    onHide = {},
                    onPlay = { calls += "play:${it.id}" },
                    onPlayFromStart = { calls += "inicio:${it.id}" },
                    onRename = { calls += "rename:${it.id}" },
                    canResume = { it.id in resumable },
                ),
            )
        }
    }

    private fun longClick(title: String) =
        compose.onNode(hasTestTag("game-card").and(hasText(title))).performTouchInput { longClick() }

    @Test
    fun aResumableGameOffersContinueAndPlayFromTheStart() {
        show(resumable = setOf(red.id))
        longClick("RED")
        compose.onNodeWithTag("menu-play").assertIsDisplayed()
        compose.onNodeWithText("Continuar").assertIsDisplayed()
        compose.onNodeWithTag("menu-play-from-start").performClick()
        longClick("RED")
        compose.onNodeWithText("Continuar").performClick()
        assertEquals(listOf("inicio:Red.gb", "play:Red.gb"), calls)
    }

    @Test
    fun aGameWithoutAValidStateOffersOnlyPlay() {
        show(resumable = setOf(red.id))
        longClick("BLUE")
        compose.onNodeWithText("Jugar").assertIsDisplayed()
        compose.onAllNodesWithTag("menu-play-from-start").assertCountEquals(0)
        compose.onNodeWithTag("menu-rename").performClick()
        assertEquals(listOf("rename:Blue.gb"), calls)
    }

    @Test
    fun theFailedContinueDialogExplainsAndOffersPlayFromTheStart() {
        var dismissed = false
        compose.setContent {
            PocketGBTheme {
                ResumeFailedDialog(ResumeFailure.NOT_CURRENT, onPlayFromStart = { calls += "inicio" }, onDismiss = { dismissed = true })
            }
        }
        compose.onNodeWithText("No se pudo continuar").assertIsDisplayed()
        compose.onNodeWithText("el juego guardó después", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Puedes conservar tu partida y jugar desde el inicio", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("resume-failed-play").performClick()
        compose.onNodeWithTag("resume-failed-cancel").performClick()
        assertEquals(listOf("inicio"), calls)
        assertEquals(true, dismissed)
    }
}

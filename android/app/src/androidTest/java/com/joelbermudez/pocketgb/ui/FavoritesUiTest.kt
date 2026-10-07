package com.joelbermudez.pocketgb.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.joelbermudez.pocketgb.library.LibraryPreferencesData
import com.joelbermudez.pocketgb.library.LibraryState
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.ui.favorites.FavoritesContent
import com.joelbermudez.pocketgb.ui.library.GameActions
import com.joelbermudez.pocketgb.ui.theme.PocketGBTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Favoritos (A6-L3): carril de jugados recientemente, pies explicativos y menú de los recientes. */
@RunWith(AndroidJUnit4::class)
class FavoritesUiTest {
    @get:Rule
    val compose = createComposeRule()

    private fun entry(id: String, title: String) = RomEntry(
        id = id, uri = "content://t/$id", fileName = id, title = title, console = com.joelbermudez.pocketgb.library.RomConsole.GB,
        sizeBytes = 32L * 1024, headerChecksumOk = true, problem = null,
    )

    private val red = entry("Red.gb", "RED")
    private val alpha = entry("Alpha.gb", "ALPHA")
    private val games = listOf(red, alpha)
    private var opened: String? = null
    private var played: String? = null

    private fun show(prefs: LibraryPreferencesData) {
        compose.setContent {
            PocketGBTheme {
                FavoritesContent(
                    LibraryState.Ready(games, "Juegos"),
                    prefs,
                    GameActions({ opened = it.id }, {}, {}, onPlay = { played = it.id }, onGameSettings = {}),
                )
            }
        }
    }

    @Test
    fun recentlyPlayedGamesAppearInTheirOwnStripEvenWithoutFavorites() {
        show(LibraryPreferencesData().recordPlayed(alpha.id, "%064x".format(1), System.currentTimeMillis()))
        compose.onNodeWithText("Jugados recientemente").assertIsDisplayed()
        compose.onAllNodesWithTag("recent-item").assertCountEquals(1)
        // Sin favoritos, la sección lo explica en vez de quedar vacía.
        compose.onNodeWithText("Mantén pulsado un juego y elige «Añadir a favoritos».").assertIsDisplayed()
        compose.onNodeWithTag("recent-item").performClick()
        assertEquals("Alpha.gb", opened)
    }

    @Test
    fun favoritesShowTheRemoveHintAndKeepTheStrip() {
        show(
            LibraryPreferencesData(favorites = setOf(red.id))
                .recordPlayed(alpha.id, "%064x".format(1), System.currentTimeMillis()),
        )
        compose.onAllNodesWithTag("game-card").assertCountEquals(1)
        compose.onNodeWithText("Mantén pulsado un juego para quitarlo de favoritos.").assertIsDisplayed()
    }

    @Test
    fun longPressOnARecentGameOpensTheFullMenu() {
        show(LibraryPreferencesData().recordPlayed(alpha.id, "%064x".format(1), System.currentTimeMillis()))
        compose.onNodeWithTag("recent-item").performTouchInput { longClick() }
        compose.onNodeWithText("Jugar").performClick()
        assertEquals("Alpha.gb", played)
    }

    @Test
    fun withoutFavoritesNorRecentsTheEmptyStateShows() {
        show(LibraryPreferencesData())
        compose.onNodeWithText("Todavía no hay favoritos").assertIsDisplayed()
        compose.onAllNodesWithTag("recent-item").assertCountEquals(0)
    }
}

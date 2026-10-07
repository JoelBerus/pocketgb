package com.joelbermudez.pocketgb.ui

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.joelbermudez.pocketgb.library.DetailsLoad
import com.joelbermudez.pocketgb.library.LibraryFilter
import com.joelbermudez.pocketgb.library.LibraryLayout
import com.joelbermudez.pocketgb.library.LibraryPreferencesData
import com.joelbermudez.pocketgb.library.LibraryQuery
import com.joelbermudez.pocketgb.library.LibraryState
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.ui.details.GameDetailsContent
import com.joelbermudez.pocketgb.ui.library.GameActions
import com.joelbermudez.pocketgb.ui.library.LibraryContent
import com.joelbermudez.pocketgb.ui.theme.PocketGBTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** N1 · la ruta profunda en el detalle y la insignia «Duplicado» / «También en», con su texto para TalkBack. */
@RunWith(AndroidJUnit4::class)
class FoldersUiTest {
    @get:Rule val compose = createComposeRule()

    private fun entry(id: String, title: String) = RomEntry(
        id = id, uri = "content://t/$id", fileName = id.substringAfterLast('/'), title = title, isColor = false,
        sizeBytes = 32L * 1024, headerChecksumOk = true, problem = null,
    )

    private val root = entry("Pokemon Red.gb", "RED")
    private val deep = entry("Clásicos/Nintendo/Pokémon/Gen 1/Kanto/Rojo.gb", "RED")
    private val other = entry("Tetris.gb", "TETRIS")
    private val all = listOf(root, deep, other)
    private val prefs = LibraryPreferencesData()
        .recordFingerprint(root.id, "ab".repeat(32))
        .recordFingerprint(deep.id, "ab".repeat(32))
        .recordFingerprint(other.id, "cd".repeat(32))
        .copy(layout = LibraryLayout.LIST)

    @Test
    fun duplicatesShowADiscreetBadgeThatTalkBackAlsoReads() {
        compose.setContent {
            PocketGBTheme(dynamicColor = false) {
                LibraryContent(
                    state = LibraryState.Ready(all, "Roms"),
                    prefs = prefs,
                    query = "",
                    filter = LibraryFilter.ALL,
                    onQueryChange = {},
                    onFilterChange = {},
                    onLayoutChange = {},
                    onSortChange = {},
                    onChooseFolder = {},
                    onRescan = {},
                    actions = GameActions(onOpenDetails = {}, onToggleFavorite = {}, onHide = {}),
                )
            }
        }
        assertEquals(2, compose.onAllNodes(hasTestTag("game-duplicate-badge"), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(2, compose.onAllNodes(hasContentDescription("Duplicado", substring = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun theDetailsShowTheWholeFolderPathAndWhereTheOtherCopiesAre() {
        val shown = LibraryQuery.presented(all, prefs, deep.id)!!
        compose.setContent {
            PocketGBTheme(dynamicColor = false) {
                GameDetailsContent(
                    entry = shown,
                    load = DetailsLoad.Loading,
                    favorite = false,
                    lastPlayedAt = null,
                    onPlay = {},
                    onToggleFavorite = {},
                    onHide = {},
                    onBack = {},
                )
            }
        }
        compose.onNodeWithText("Clásicos › Nintendo › Pokémon › Gen 1 › Kanto · Rojo.gb").assertIsDisplayed()
        compose.onNodeWithTag("game-details-location", useUnmergedTree = true)
            .assert(hasContentDescription("Ubicación: Clásicos › Nintendo › Pokémon › Gen 1 › Kanto · Rojo.gb"))
        compose.onNodeWithTag("game-details-also-in").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Carpeta principal · Pokemon Red.gb", useUnmergedTree = true).assertExists()
    }
}

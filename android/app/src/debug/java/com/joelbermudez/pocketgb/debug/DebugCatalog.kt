package com.joelbermudez.pocketgb.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.joelbermudez.pocketgb.ui.about.AboutScreen
import com.joelbermudez.pocketgb.ui.components.GameCard
import com.joelbermudez.pocketgb.ui.favorites.FavoritesScreen
import com.joelbermudez.pocketgb.ui.library.LibraryScreen
import com.joelbermudez.pocketgb.ui.settings.AppearanceScreen
import com.joelbermudez.pocketgb.ui.settings.SettingsScreen

internal data class DemoGame(
    val id: String,
    val title: String,
    val subtitle: String,
    val favorite: Boolean,
)

internal val demoGames = listOf(
    DemoGame("red", "POKÉMON RED", "Game Boy · MBC3", true),
    DemoGame("yellow", "POKÉMON YELLOW", "Game Boy Color · MBC5", true),
    DemoGame("demo-a", "DEMO ADVENTURE", "Game Boy · ROM", false),
    DemoGame("demo-b", "COLOR DEMO", "Game Boy Color · MBC5", false),
)

@Composable
internal fun DebugCatalog(intent: DebugIntent) {
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, intent.fontScale),
    ) {
        Box(Modifier.fillMaxSize().testTag("debug-screen-${intent.screen}")) {
            when (intent.screen) {
                "library-empty" -> LibraryScreen()
                "library-grid" -> LibraryGrid()
                "favorites-empty" -> FavoritesScreen()
                "settings-main" -> SettingsScreen(onAppearance = {}, onAbout = {})
                "appearance" -> AppearanceScreen(intent.appearance, {}, {}, {})
                "about" -> AboutScreen(onBack = {})
                "native-video" -> NativeVideoScreen()
                "gameplay-controls" -> GameplayDebugScreen()
                "gameplay-fast-forward" -> GameplayDebugScreen(initialSpeed = 4)
                else -> UnknownScreen(intent.screen)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryGrid() {
    Scaffold(topBar = { TopAppBar(title = { Text("Biblioteca") }) }) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = padding.calculateTopPadding() + 12.dp,
                end = 16.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(demoGames, key = { it.id }) { game ->
                GameCard(game.title, game.subtitle, game.favorite)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnknownScreen(id: String) {
    Scaffold(topBar = { TopAppBar(title = { Text("Pantalla desconocida") }) }) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            Text(id, modifier = Modifier.testTag("unknown-screen-id"))
        }
    }
}

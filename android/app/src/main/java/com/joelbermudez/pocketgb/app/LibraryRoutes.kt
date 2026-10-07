package com.joelbermudez.pocketgb.app

import androidx.compose.runtime.Composable
import com.joelbermudez.pocketgb.library.LibraryCategory
import com.joelbermudez.pocketgb.library.LibraryViewModel
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.settings.GameplaySettingsRepository
import com.joelbermudez.pocketgb.ui.details.GameDetailsScreen
import com.joelbermudez.pocketgb.ui.library.CategoryScreen
import com.joelbermudez.pocketgb.ui.library.LibraryScreen

/** Lo que necesitan las pantallas de la pestaña Biblioteca (la app y las pruebas de navegación usan lo mismo). */
internal class LibraryRouteDeps(
    val library: LibraryViewModel,
    val play: (RomEntry) -> Unit,
    val playFromStart: (RomEntry) -> Unit,
    val resumable: Set<String>,
    val gameplaySettings: GameplaySettingsRepository?,
    /** Ajustes › Partidas de una huella (necesita la partida abierta: lo pinta la app). */
    val saves: @Composable (fingerprint: String, onBack: () -> Unit) -> Unit,
    /** N6: «Momentos» de un juego; `null` = el botón deshabilitado de antes. */
    val moments: (@Composable (gameId: String, onBack: () -> Unit) -> Unit)? = null,
)

/**
 * N4 · pantallas de la pestaña Biblioteca: el inicio, las categorías (con sus migas), el detalle y las partidas de un
 * juego. Las categorías apilan su ruta; una miga vuelve a su nivel si está en la pila (si no, la apila).
 */
@Composable
internal fun LibraryRouteContent(route: LibraryRoute, navigation: AppNavigationState, deps: LibraryRouteDeps) {
    val openCategory: (LibraryCategory) -> Unit = { navigation.push(LibraryRoute.Category(it.path)) }
    val openDetails: (String) -> Unit = { navigation.push(LibraryRoute.Details(it)) }
    val openSaves: (String) -> Unit = { navigation.push(LibraryRoute.GameSaves(it)) }
    when (route) {
        LibraryRoute.Root -> LibraryScreen(
            viewModel = deps.library,
            onOpenDetails = openDetails,
            onPlay = deps.play,
            gameplaySettings = deps.gameplaySettings,
            onPlayFromStart = deps.playFromStart,
            resumable = deps.resumable,
            onOpenCategory = openCategory,
            onOpenFavorites = { navigation.select(TopLevelDestination.FAVORITES) },
            onOpenSaves = openSaves,
        )
        is LibraryRoute.Category -> CategoryScreen(
            viewModel = deps.library,
            category = LibraryCategory.fromPath(route.path),
            onBack = { navigation.pop() },
            onOpenCategory = openCategory,
            onOpenCrumb = { target ->
                val crumb = if (target == null) LibraryRoute.Root else LibraryRoute.Category(target.path)
                if (!navigation.popTo(crumb)) navigation.push(crumb)
            },
            onOpenDetails = openDetails,
            onPlay = deps.play,
            gameplaySettings = deps.gameplaySettings,
            onPlayFromStart = deps.playFromStart,
            resumable = deps.resumable,
            onOpenSaves = openSaves,
        )
        is LibraryRoute.Details -> GameDetailsScreen(
            deps.library, route.gameId, onPlay = deps.play, onBack = { navigation.pop() },
            gameplaySettings = deps.gameplaySettings,
            onPlayFromStart = deps.playFromStart,
            resumable = deps.resumable,
            onOpenSaves = openSaves,
            onOpenMoments = deps.moments?.let { { id: String -> navigation.push(LibraryRoute.Moments(id)) } },
        )
        is LibraryRoute.GameSaves -> deps.saves(route.fingerprint) { navigation.pop() }
        is LibraryRoute.Moments -> deps.moments?.invoke(route.gameId) { navigation.pop() }
    }
}

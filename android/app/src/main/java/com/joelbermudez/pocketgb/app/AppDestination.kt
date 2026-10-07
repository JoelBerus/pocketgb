package com.joelbermudez.pocketgb.app

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

enum class TopLevelDestination {
    LIBRARY,
    FAVORITES,
    SETTINGS,
}

@Serializable
sealed interface AppRoute : NavKey {
    val topLevel: TopLevelDestination
}

@Serializable
sealed interface LibraryRoute : AppRoute {
    override val topLevel: TopLevelDestination
        get() = TopLevelDestination.LIBRARY

    @Serializable
    data object Root : LibraryRoute

    @Serializable
    data class Details(val gameId: String) : LibraryRoute

    /** N4: pantalla de una categoría; [path] desde la raíz (vacía = «Sin categoría»). */
    @Serializable
    data class Category(val path: List<String>) : LibraryRoute

    /** N4: Ajustes › Partidas de una huella, desde el centro de ajustes del juego. */
    @Serializable
    data class GameSaves(val fingerprint: String) : LibraryRoute
}

@Serializable
sealed interface FavoritesRoute : AppRoute {
    override val topLevel: TopLevelDestination
        get() = TopLevelDestination.FAVORITES

    @Serializable
    data object Root : FavoritesRoute

    @Serializable
    data class Details(val gameId: String) : FavoritesRoute

    /** N4: Ajustes › Partidas de una huella, desde el centro de ajustes del juego. */
    @Serializable
    data class GameSaves(val fingerprint: String) : FavoritesRoute
}

@Serializable
sealed interface SettingsRoute : AppRoute {
    override val topLevel: TopLevelDestination
        get() = TopLevelDestination.SETTINGS

    @Serializable
    data object Root : SettingsRoute

    @Serializable
    data object Appearance : SettingsRoute

    @Serializable
    data object Library : SettingsRoute

    /** N4: Ajustes › Biblioteca › Inicio. */
    @Serializable
    data object LibraryHome : SettingsRoute

    @Serializable
    data object Saves : SettingsRoute

    @Serializable
    data object About : SettingsRoute

    @Serializable
    data object SettingsControls : SettingsRoute

    @Serializable
    data object SettingsController : SettingsRoute

    @Serializable
    data object SettingsDisplay : SettingsRoute

    @Serializable
    data object SettingsEmulation : SettingsRoute

    @Serializable
    data object SettingsAudio : SettingsRoute

    @Serializable
    data object SettingsStorage : SettingsRoute

    @Serializable
    data object SettingsLicenses : SettingsRoute
}

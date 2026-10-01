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
}

@Serializable
sealed interface FavoritesRoute : AppRoute {
    override val topLevel: TopLevelDestination
        get() = TopLevelDestination.FAVORITES

    @Serializable
    data object Root : FavoritesRoute
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
    data object About : SettingsRoute
}

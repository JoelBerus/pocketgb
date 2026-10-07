package com.joelbermudez.pocketgb.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.VideogameAsset
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.window.core.layout.WindowSizeClass

internal data class NavigationItem(
    val destination: TopLevelDestination,
    val label: String,
    val selectedIcon: ImageVector,
    val icon: ImageVector,
)

/** Los mismos tres destinos para la barra inferior y para el rail (A7 R14). */
internal val topLevelNavigationItems = listOf(
    NavigationItem(TopLevelDestination.LIBRARY, "Biblioteca", Icons.Filled.VideogameAsset, Icons.Outlined.VideogameAsset),
    NavigationItem(TopLevelDestination.FAVORITES, "Favoritos", Icons.Filled.Star, Icons.Outlined.StarBorder),
    NavigationItem(TopLevelDestination.SETTINGS, "Ajustes", Icons.Filled.Settings, Icons.Outlined.Settings),
)

/** Barra inferior con anchura compacta (< 600 dp); rail lateral desde medium. */
internal fun navigationTypeFor(widthDp: Int): NavigationSuiteType =
    if (widthDp >= WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) NavigationSuiteType.NavigationRail
    else NavigationSuiteType.NavigationBar

/** Anchura expanded (≥ 840 dp): la única donde el lista-detalle tiene sitio (A7 R14). */
internal fun isExpandedWidth(widthDp: Int): Boolean = widthDp >= WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND

/**
 * Marco de la app: barra inferior en compacta, `NavigationRail` en ≥ medium, con el mismo [AppNavigationState]
 * (el destino seleccionado se conserva al rotar o cambiar de tamaño). La partida no vive aquí: `GameplayRoot` la
 * pinta fuera de este marco, sin barra ni rail.
 */
@Composable
internal fun AppScaffold(
    navigationState: AppNavigationState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val widthDp = currentWindowAdaptiveInfo().windowSizeClass.minWidthDp
    val type = navigationTypeFor(widthDp)
    val rail = type == NavigationSuiteType.NavigationRail
    val clicks = topLevelNavigationItems.map { item -> dropUnlessResumed { navigationState.select(item.destination) } }
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            topLevelNavigationItems.forEachIndexed { index, item ->
                val selected = navigationState.selected == item.destination
                item(
                    selected = selected,
                    onClick = clicks[index],
                    icon = {
                        Icon(
                            imageVector = if (selected) item.selectedIcon else item.icon,
                            contentDescription = item.label,
                        )
                    },
                    label = { Text(item.label) },
                    modifier = Modifier.testTag("nav-item-${item.destination.name}"),
                )
            }
        },
        modifier = modifier.testTag(if (rail) "app-nav-rail" else "app-nav-bar"),
        layoutType = type,
    ) {
        // El contenido recibe los insets que no cubre la barra o el rail; después se consumen (menos el teclado)
        // para que las pantallas hijas no los vuelvan a aplicar.
        val sides = if (rail) WindowInsetsSides.Vertical + WindowInsetsSides.End
        else WindowInsetsSides.Top + WindowInsetsSides.Horizontal
        Box(
            Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(sides))
                .consumeWindowInsets(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
        ) { content() }
    }
}

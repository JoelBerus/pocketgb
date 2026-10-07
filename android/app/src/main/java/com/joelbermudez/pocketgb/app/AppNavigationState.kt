package com.joelbermudez.pocketgb.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class NavigationSnapshot(
    val selected: TopLevelDestination,
    val stacks: Map<TopLevelDestination, List<AppRoute>>,
)

class AppNavigationState(snapshot: NavigationSnapshot = initialSnapshot()) {
    private val stacks = mutableStateMapOf<TopLevelDestination, SnapshotStateList<AppRoute>>()

    var selected by mutableStateOf(snapshot.selected)
        private set

    init {
        TopLevelDestination.entries.forEach { destination ->
            val restored = snapshot.stacks[destination].orEmpty()
            val routes = if (restored.firstOrNull() == rootFor(destination)) {
                restored
            } else {
                listOf(rootFor(destination))
            }
            require(routes.all { it.topLevel == destination }) {
                "El stack de $destination contiene una ruta de otra pestaña"
            }
            stacks[destination] = mutableStateListOf<AppRoute>().apply { addAll(routes) }
        }
    }

    val currentBackStack: SnapshotStateList<AppRoute>
        get() = checkNotNull(stacks[selected])

    fun select(destination: TopLevelDestination) {
        selected = destination
    }

    fun push(route: AppRoute) {
        require(route.topLevel == selected) {
            "La ruta pertenece a ${route.topLevel}, pero la pestaña activa es $selected"
        }
        // Un doble toque durante la transición no apila dos veces la misma pantalla (equivalente a singleTop).
        if (currentBackStack.lastOrNull() == route) return
        currentBackStack.add(route)
    }

    /**
     * N4 (migas): vuelve a [route] si está en la pila de la pestaña activa, quitando lo que haya encima. `false` si no
     * está (quien llama puede apilarla).
     */
    fun popTo(route: AppRoute): Boolean {
        val index = currentBackStack.lastIndexOf(route)
        if (index < 0) return false
        while (currentBackStack.lastIndex > index) currentBackStack.removeAt(currentBackStack.lastIndex)
        return true
    }

    fun pop(): Boolean {
        if (currentBackStack.size == 1) return false
        currentBackStack.removeAt(currentBackStack.lastIndex)
        return true
    }

    fun snapshot(): NavigationSnapshot = NavigationSnapshot(
        selected = selected,
        stacks = TopLevelDestination.entries.associateWith { destination ->
            checkNotNull(stacks[destination]).toList()
        },
    )

    companion object {
        val Saver: Saver<AppNavigationState, Any> = listSaver(
            save = { state -> listOf(Json.encodeToString(state.snapshot())) },
            restore = { values -> AppNavigationState(Json.decodeFromString(values.single())) },
        )

        private fun initialSnapshot() = NavigationSnapshot(
            selected = TopLevelDestination.LIBRARY,
            stacks = TopLevelDestination.entries.associateWith { listOf(rootFor(it)) },
        )

        private fun rootFor(destination: TopLevelDestination): AppRoute = when (destination) {
            TopLevelDestination.LIBRARY -> LibraryRoute.Root
            TopLevelDestination.FAVORITES -> FavoritesRoute.Root
            TopLevelDestination.SETTINGS -> SettingsRoute.Root
        }
    }
}

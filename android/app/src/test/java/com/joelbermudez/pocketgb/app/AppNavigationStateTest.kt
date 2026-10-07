package com.joelbermudez.pocketgb.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNavigationStateTest {
    @Test
    fun tabsKeepIndependentStacks() {
        val state = AppNavigationState()
        state.push(LibraryRoute.Details("demo-red"))
        state.select(TopLevelDestination.SETTINGS)
        state.push(SettingsRoute.Appearance)

        state.select(TopLevelDestination.LIBRARY)
        assertEquals(LibraryRoute.Details("demo-red"), state.currentBackStack.last())

        state.select(TopLevelDestination.SETTINGS)
        assertEquals(SettingsRoute.Appearance, state.currentBackStack.last())
    }

    @Test
    fun pushingTheSameRouteTwiceStacksItOnce() {
        val state = AppNavigationState()
        state.push(LibraryRoute.Details("demo-red"))
        state.push(LibraryRoute.Details("demo-red"))
        assertEquals(2, state.currentBackStack.size)
        state.push(LibraryRoute.Details("demo-yellow"))
        assertEquals(3, state.currentBackStack.size)
    }

    @Test
    fun popAtRootReturnsFalseAndKeepsRoot() {
        val state = AppNavigationState()

        assertFalse(state.pop())
        assertEquals(listOf(LibraryRoute.Root), state.currentBackStack)
    }

    @Test
    fun snapshotRoundTripPreservesSelectionAndStacks() {
        val original = AppNavigationState().apply {
            push(LibraryRoute.Details("demo-yellow"))
            select(TopLevelDestination.FAVORITES)
        }

        val restored = AppNavigationState(original.snapshot())

        assertEquals(original.snapshot(), restored.snapshot())
        assertEquals(TopLevelDestination.FAVORITES, restored.selected)
    }

    @Test
    fun routeFromAnotherTabIsRejected() {
        val state = AppNavigationState()

        val error = assertThrows(IllegalArgumentException::class.java) {
            state.push(SettingsRoute.Appearance)
        }

        assertTrue(error.message.orEmpty().contains("SETTINGS"))
    }

    @Test
    fun everySettingsScreenCanBeStackedAndSurvivesSerialization() {
        val screens = listOf(
            SettingsRoute.Appearance,
            SettingsRoute.Library,
            SettingsRoute.Saves,
            SettingsRoute.About,
            SettingsRoute.SettingsControls,
            SettingsRoute.SettingsDisplay,
            SettingsRoute.SettingsEmulation,
            SettingsRoute.SettingsAudio,
            SettingsRoute.SettingsStorage,
            SettingsRoute.SettingsLicenses,
        )
        val state = AppNavigationState()
        state.select(TopLevelDestination.SETTINGS)
        screens.forEach { state.push(it) }
        assertEquals(1 + screens.size, state.currentBackStack.size)

        val json = kotlinx.serialization.json.Json.encodeToString(NavigationSnapshot.serializer(), state.snapshot())
        val restored = AppNavigationState(kotlinx.serialization.json.Json.decodeFromString(NavigationSnapshot.serializer(), json))
        assertEquals(state.snapshot(), restored.snapshot())
        assertEquals(SettingsRoute.SettingsLicenses, restored.currentBackStack.last())
    }

    /** A7 R14: barra inferior y rail comparten el mismo estado; cambiar de tamaño no pierde pestaña ni pila. */
    @Test
    fun selectionAndStacksSurviveARestoreLikeAResize() {
        val state = AppNavigationState()
        state.select(TopLevelDestination.SETTINGS)
        state.push(SettingsRoute.Appearance)
        val restored = AppNavigationState(state.snapshot())
        assertEquals(TopLevelDestination.SETTINGS, restored.selected)
        assertEquals(listOf(SettingsRoute.Root, SettingsRoute.Appearance), restored.currentBackStack.toList())
    }

    @Test
    fun navigationItemsCoverEveryTopLevelDestinationOnce() {
        assertEquals(TopLevelDestination.entries, topLevelNavigationItems.map { it.destination })
    }
}

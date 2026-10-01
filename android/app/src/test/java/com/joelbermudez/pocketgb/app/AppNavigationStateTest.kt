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
}

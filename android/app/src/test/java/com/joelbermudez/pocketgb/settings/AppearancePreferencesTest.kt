package com.joelbermudez.pocketgb.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearancePreferencesTest {
    @Test
    fun systemModeFollowsDevice() {
        assertFalse(ThemeMode.SYSTEM.resolveDark(systemDark = false))
        assertTrue(ThemeMode.SYSTEM.resolveDark(systemDark = true))
    }

    @Test
    fun explicitModesIgnoreDevice() {
        assertFalse(ThemeMode.LIGHT.resolveDark(systemDark = true))
        assertTrue(ThemeMode.DARK.resolveDark(systemDark = false))
    }

    @Test
    fun defaultsUseSystemAndDynamicColor() {
        assertEquals(
            AppearanceState(themeMode = ThemeMode.SYSTEM, dynamicColor = true),
            AppearanceState.DEFAULT,
        )
    }
}

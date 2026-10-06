package com.joelbermudez.pocketgb.ui.a11y

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.joelbermudez.pocketgb.ui.theme.PocketDarkHighContrastColorScheme
import com.joelbermudez.pocketgb.ui.theme.PocketDarkMediumContrastColorScheme
import com.joelbermudez.pocketgb.ui.theme.PocketLightHighContrastColorScheme
import com.joelbermudez.pocketgb.ui.theme.PocketLightMediumContrastColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContrastLevelTest {
    @Test
    fun systemValuesMapToLevels() {
        assertEquals(ContrastLevel.STANDARD, ContrastLevel.fromValue(0f))
        assertEquals(ContrastLevel.MEDIUM, ContrastLevel.fromValue(0.5f))
        assertEquals(ContrastLevel.HIGH, ContrastLevel.fromValue(1f))
        assertTrue(ContrastLevel.HIGH.isHigh)
        assertTrue(!ContrastLevel.MEDIUM.isHigh)
    }

    private fun ratio(a: Color, b: Color): Float {
        val (hi, lo) = if (a.luminance() > b.luminance()) a to b else b to a
        return (hi.luminance() + 0.05f) / (lo.luminance() + 0.05f)
    }

    private fun pairs(s: androidx.compose.material3.ColorScheme) = listOf(
        "onPrimary/primary" to ratio(s.onPrimary, s.primary),
        "onPrimaryContainer/primaryContainer" to ratio(s.onPrimaryContainer, s.primaryContainer),
        "onSecondary/secondary" to ratio(s.onSecondary, s.secondary),
        "onSecondaryContainer/secondaryContainer" to ratio(s.onSecondaryContainer, s.secondaryContainer),
        "onTertiary/tertiary" to ratio(s.onTertiary, s.tertiary),
        "onTertiaryContainer/tertiaryContainer" to ratio(s.onTertiaryContainer, s.tertiaryContainer),
        "onSurface/surface" to ratio(s.onSurface, s.surface),
        "onSurfaceVariant/surfaceVariant" to ratio(s.onSurfaceVariant, s.surfaceVariant),
        "onError/error" to ratio(s.onError, s.error),
        "onErrorContainer/errorContainer" to ratio(s.onErrorContainer, s.errorContainer),
        "primary/surface" to ratio(s.primary, s.surface),
        "outline/surface" to ratio(s.outline, s.surface),
    )

    @Test
    fun mediumSchemesReachAA() {
        listOf(PocketLightMediumContrastColorScheme, PocketDarkMediumContrastColorScheme).forEach { scheme ->
            pairs(scheme).forEach { (name, value) -> assertTrue("$name = $value", value >= 4.5f) }
        }
    }

    @Test
    fun highSchemesReachAAA() {
        listOf(PocketLightHighContrastColorScheme, PocketDarkHighContrastColorScheme).forEach { scheme ->
            pairs(scheme).forEach { (name, value) -> assertTrue("$name = $value", value >= 7f) }
        }
    }
}

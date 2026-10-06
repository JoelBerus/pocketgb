package com.joelbermudez.pocketgb.ui.a11y

import android.os.Build
import androidx.compose.material3.ColorScheme
import com.joelbermudez.pocketgb.ui.theme.PocketDarkColorScheme
import com.joelbermudez.pocketgb.ui.theme.PocketDarkHighContrastColorScheme
import com.joelbermudez.pocketgb.ui.theme.PocketLightColorScheme
import com.joelbermudez.pocketgb.ui.theme.PocketLightHighContrastColorScheme
import com.joelbermudez.pocketgb.ui.theme.PocketLightMediumContrastColorScheme
import com.joelbermudez.pocketgb.ui.theme.pickColorScheme
import org.junit.Assert.assertSame
import org.junit.Test

class ThemeSchemeTest {
    private val boom: () -> ColorScheme = { error("el color dinámico no debe usarse") }

    private fun pick(dark: Boolean, contrast: ContrastLevel, dynamic: Boolean, sdk: Int = Build.VERSION_CODES.UPSIDE_DOWN_CAKE) =
        pickColorScheme(dark, contrast, dynamic, sdk, boom, boom)

    @Test
    fun highContrastIgnoresDynamicColor() {
        assertSame(PocketLightHighContrastColorScheme, pick(false, ContrastLevel.HIGH, dynamic = true))
        assertSame(PocketDarkHighContrastColorScheme, pick(true, ContrastLevel.HIGH, dynamic = true))
    }

    @Test
    fun mediumContrastUsesTheMediumScheme() {
        assertSame(PocketLightMediumContrastColorScheme, pick(false, ContrastLevel.MEDIUM, dynamic = true))
    }

    @Test
    fun standardContrastKeepsTheFixedSchemesWithoutDynamicColor() {
        assertSame(PocketLightColorScheme, pick(false, ContrastLevel.STANDARD, dynamic = false))
        assertSame(PocketDarkColorScheme, pick(true, ContrastLevel.STANDARD, dynamic = false))
    }

    @Test
    fun standardContrastUsesDynamicColorWhenAvailable() {
        val dynamic = PocketLightColorScheme.copy(primary = androidx.compose.ui.graphics.Color.Red)
        assertSame(dynamic, pickColorScheme(false, ContrastLevel.STANDARD, true, Build.VERSION_CODES.S, boom) { dynamic })
        assertSame(PocketLightColorScheme, pick(false, ContrastLevel.STANDARD, dynamic = true, sdk = Build.VERSION_CODES.R))
    }
}

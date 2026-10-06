package com.joelbermudez.pocketgb.ui.theme

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.joelbermudez.pocketgb.settings.AppearanceState
import com.joelbermudez.pocketgb.ui.a11y.ContrastLevel
import com.joelbermudez.pocketgb.ui.a11y.LocalContrastLevel
import com.joelbermudez.pocketgb.ui.a11y.ProvideAccessibilitySignals

/**
 * Esquema que corresponde (R10). Con contraste medio o alto del sistema se usan los esquemas fijos de contraste y el
 * color dinámico no se aplica (el dinámico no sabe de contraste); con contraste estándar, el dinámico si está activado.
 */
internal fun pickColorScheme(
    darkTheme: Boolean,
    contrast: ContrastLevel,
    dynamicColor: Boolean,
    sdk: Int,
    dynamicDark: () -> ColorScheme,
    dynamicLight: () -> ColorScheme,
): ColorScheme = when {
    contrast == ContrastLevel.HIGH -> if (darkTheme) PocketDarkHighContrastColorScheme else PocketLightHighContrastColorScheme
    contrast == ContrastLevel.MEDIUM -> if (darkTheme) PocketDarkMediumContrastColorScheme else PocketLightMediumContrastColorScheme
    dynamicColor && sdk >= Build.VERSION_CODES.S -> if (darkTheme) dynamicDark() else dynamicLight()
    darkTheme -> PocketDarkColorScheme
    else -> PocketLightColorScheme
}

// El color dinámico (API 31+) solo se invoca desde `pickColorScheme` tras comprobar el SDK.
@SuppressLint("NewApi")
@Composable
fun PocketGBTheme(
    appearance: AppearanceState = AppearanceState.DEFAULT,
    /** El juego siempre se muestra sobre fondo oscuro (K4); las barras del sistema las gestiona `ImmersiveMode`. */
    forceDark: Boolean = false,
    dynamicColor: Boolean = appearance.dynamicColor,
    content: @Composable () -> Unit,
) {
    // Publica reducir movimiento, contraste y fuente grande para toda la jerarquía (y permite forzarlos en pruebas).
    ProvideAccessibilitySignals {
        val darkTheme = forceDark || appearance.themeMode.resolveDark(isSystemInDarkTheme())
        val context = LocalContext.current
        val view = LocalView.current
        val colorScheme = pickColorScheme(
            darkTheme = darkTheme,
            contrast = LocalContrastLevel.current,
            dynamicColor = dynamicColor,
            sdk = Build.VERSION.SDK_INT,
            dynamicDark = { dynamicDarkColorScheme(context) },
            dynamicLight = { dynamicLightColorScheme(context) },
        )

        if (!view.isInEditMode && !forceDark) {
            SideEffect {
                val window = (context as Activity).window
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }

        MaterialTheme(
            colorScheme = colorScheme,
            typography = PocketTypography,
            content = content,
        )
    }
}

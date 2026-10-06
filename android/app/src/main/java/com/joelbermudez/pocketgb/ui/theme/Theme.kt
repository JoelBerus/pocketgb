package com.joelbermudez.pocketgb.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.joelbermudez.pocketgb.settings.AppearanceState

@Composable
fun PocketGBTheme(
    appearance: AppearanceState = AppearanceState.DEFAULT,
    /** El juego siempre se muestra sobre fondo oscuro (K4); las barras del sistema las gestiona `ImmersiveMode`. */
    forceDark: Boolean = false,
    dynamicColor: Boolean = appearance.dynamicColor,
    content: @Composable () -> Unit,
) {
    val darkTheme = forceDark || appearance.themeMode.resolveDark(isSystemInDarkTheme())
    val context = LocalContext.current
    val view = LocalView.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> {
            dynamicDarkColorScheme(context)
        }
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            dynamicLightColorScheme(context)
        }
        darkTheme -> PocketDarkColorScheme
        else -> PocketLightColorScheme
    }

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

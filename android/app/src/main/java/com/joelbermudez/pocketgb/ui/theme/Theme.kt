package com.joelbermudez.pocketgb.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.joelbermudez.pocketgb.settings.AppearanceState

@Composable
fun PocketGBTheme(
    appearance: AppearanceState = AppearanceState.DEFAULT,
    content: @Composable () -> Unit,
) {
    val darkTheme = appearance.themeMode.resolveDark(isSystemInDarkTheme())
    val context = LocalContext.current
    val colorScheme = when {
        appearance.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> {
            dynamicDarkColorScheme(context)
        }
        appearance.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            dynamicLightColorScheme(context)
        }
        darkTheme -> PocketDarkColorScheme
        else -> PocketLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = PocketTypography,
        content = content,
    )
}

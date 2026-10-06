package com.joelbermudez.pocketgb.ui.a11y

import android.app.UiModeManager
import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.joelbermudez.pocketgb.ui.library.isLargeFont

/** Nivel de contraste pedido al sistema (R10). */
enum class ContrastLevel {
    STANDARD,
    MEDIUM,
    HIGH,
    ;

    val isHigh: Boolean get() = this == HIGH

    companion object {
        /** Valor de `UiModeManager.getContrast()` (0..1): `< 0,33` estándar, `< 0,66` medio, resto alto (los saltos de Android son 0, 0,5 y 1). */
        fun fromValue(value: Float): ContrastLevel = when {
            value >= 0.66f -> HIGH
            value >= 0.33f -> MEDIUM
            else -> STANDARD
        }
    }
}

/** Valores forzados (pruebas y catálogo de capturas): cualquier campo `null` se lee del sistema. */
data class AccessibilityOverrides(
    val reduceMotion: Boolean? = null,
    val contrast: ContrastLevel? = null,
    val largeFont: Boolean? = null,
) {
    companion object {
        val NONE = AccessibilityOverrides()
    }
}

/** Punto de entrada para forzar valores desde fuera de `PocketGBTheme` (tests instrumentados, `DebugIntent`). */
val LocalAccessibilityOverrides = compositionLocalOf { AccessibilityOverrides.NONE }

/** Reducir movimiento (R11): `ANIMATOR_DURATION_SCALE == 0`. Las animaciones propias usan `snap()` si es `true`. */
val LocalReduceMotion = compositionLocalOf { false }

/** Contraste alto (R10): controles táctiles sólidos y esquema de contraste alto sin color dinámico. */
val LocalHighContrast = compositionLocalOf { false }

val LocalContrastLevel = compositionLocalOf { ContrastLevel.STANDARD }

/** Fuente grande (R9): escala de fuente ≥ 1,5. */
val LocalLargeFont = compositionLocalOf { false }

/** Lectores del estado del sistema; separados de Compose para poder usarlos desde vistas. */
object AccessibilityReaders {
    private const val HIGH_TEXT_CONTRAST_KEY = "high_text_contrast_enabled"

    fun isReduceMotion(context: Context): Boolean = try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (_: RuntimeException) {
        false
    }

    /**
     * API 34+: `UiModeManager.contrast`. Antes solo existe el «texto de alto contraste» (`high_text_contrast_enabled`),
     * que se trata como contraste alto: es una aproximación.
     */
    fun contrastLevel(context: Context): ContrastLevel {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val manager = context.getSystemService(UiModeManager::class.java)
            val fromUiMode = manager?.contrast?.let { ContrastLevel.fromValue(it) } ?: ContrastLevel.STANDARD
            if (fromUiMode != ContrastLevel.STANDARD) return fromUiMode
        }
        return if (isHighTextContrast(context)) ContrastLevel.HIGH else ContrastLevel.STANDARD
    }

    private fun isHighTextContrast(context: Context): Boolean = try {
        Settings.Secure.getInt(context.contentResolver, HIGH_TEXT_CONTRAST_KEY, 0) == 1
    } catch (_: RuntimeException) {
        false
    }

    /** Registra [onChange] para los cambios de movimiento y contraste; devuelve la función que lo cancela. */
    fun observe(context: Context, onChange: () -> Unit): () -> Unit {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChange()
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        resolver.registerContentObserver(Settings.Secure.getUriFor(HIGH_TEXT_CONTRAST_KEY), false, observer)
        var removeContrastListener: () -> Unit = {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val manager = context.getSystemService(UiModeManager::class.java)
            val listener = UiModeManager.ContrastChangeListener { onChange() }
            manager?.addContrastChangeListener(context.mainExecutor, listener)
            removeContrastListener = { manager?.removeContrastChangeListener(listener) }
        }
        return {
            resolver.unregisterContentObserver(observer)
            removeContrastListener()
        }
    }
}

/**
 * Lee movimiento, contraste y escala de fuente del sistema (se actualizan en vivo) y los publica en los `Local*`.
 * [LocalAccessibilityOverrides] fuerza cualquiera de ellos. Lo usa `PocketGBTheme`, así que toda pantalla los tiene.
 */
@Composable
fun ProvideAccessibilitySignals(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val overrides = LocalAccessibilityOverrides.current
    var reduceMotion by remember { mutableStateOf(AccessibilityReaders.isReduceMotion(context)) }
    var contrast by remember { mutableStateOf(AccessibilityReaders.contrastLevel(context)) }
    DisposableEffect(context) {
        val stop = AccessibilityReaders.observe(context) {
            reduceMotion = AccessibilityReaders.isReduceMotion(context)
            contrast = AccessibilityReaders.contrastLevel(context)
        }
        onDispose(stop)
    }
    val fontScale = LocalDensity.current.fontScale
    val effectiveContrast = overrides.contrast ?: contrast
    CompositionLocalProvider(
        LocalReduceMotion provides (overrides.reduceMotion ?: reduceMotion),
        LocalContrastLevel provides effectiveContrast,
        LocalHighContrast provides effectiveContrast.isHigh,
        LocalLargeFont provides (overrides.largeFont ?: isLargeFont(fontScale)),
        content = content,
    )
}

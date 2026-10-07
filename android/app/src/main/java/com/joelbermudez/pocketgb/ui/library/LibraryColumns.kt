package com.joelbermudez.pocketgb.ui.library

import kotlin.math.floor

/** Anchura mínima de una celda de la cuadrícula con fuente normal. */
const val GRID_MIN_CELL_DP = 156f

/** Anchura (dp) hasta la que se aplican las reglas de teléfono de R9; por encima la celda crece con la fuente. */
private const val PHONE_MAX_WIDTH_DP = 600f

/** Escala de fuente desde la que la accesibilidad pide reflujo del contenido (R9): títulos sin límite, filas apiladas. */
const val LARGE_FONT_SCALE = 1.5f

/** `true` si la escala de fuente del sistema equivale a «fuente grande». */
fun isLargeFont(fontScale: Float): Boolean = fontScale >= LARGE_FONT_SCALE

/**
 * Columnas de la cuadrícula de la biblioteca según el ancho disponible y la escala de fuente (R9, equivalente a AX5
 * de iOS). En teléfono (< 600 dp): `< 1,3` ⇒ las que quepan; `1,3–1,7` ⇒ 2; `≥ 1,8` ⇒ 1. En ventanas anchas no se
 * fuerza una sola columna: la celda mínima crece con la escala y se reparte el ancho.
 */
fun columnsFor(widthDp: Float, fontScale: Float): Int {
    val adaptive = { scale: Float -> floor(widthDp / (GRID_MIN_CELL_DP * scale)).toInt().coerceAtLeast(1) }
    if (widthDp >= PHONE_MAX_WIDTH_DP) return adaptive(maxOf(1f, fontScale))
    return when {
        fontScale >= 1.8f -> 1
        fontScale >= 1.3f -> 2
        else -> adaptive(1f)
    }
}

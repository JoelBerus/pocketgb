package com.joelbermudez.pocketgb.ui.library

import com.joelbermudez.pocketgb.ui.components.ARTWORK_RATIO

/**
 * N3b: la biblioteca usa su disposición horizontal (barra superior que se pliega, sin buscador ni chips arriba y la
 * barra flotante de herramientas a la derecha) cuando el espacio disponible es más ancho que alto. Se decide por el
 * espacio (teléfono girado, ventana ancha, plegable desplegado), nunca por el modelo.
 */
fun isLandscapeLibrary(widthDp: Float, heightDp: Float): Boolean = heightDp > 0f && widthDp > heightDp

/** Portada mínima del carril aunque el alto no dé para más (el botón «Continuar» va encima). */
const val RAIL_MIN_ARTWORK_DP = 96f

/** Ancho de la portada de las tarjetas con portada a la izquierda (fuente grande, A7 R9). */
const val RAIL_SIDE_COVER_DP = 170f

/** Con portada a la izquierda, la portada no pasa de esta fracción del ancho de la tarjeta (el texto necesita sitio). */
private const val SIDE_COVER_MAX_FRACTION = 0.45f

// Alturas que ocupa el carril además de la portada (dp; las de texto en sp, que crecen con la escala de fuente).
private const val TOP_PADDING_DP = 12f // margen superior de la cuadrícula
private const val TITLE_LINE_SP = 24f // «Continuar jugando» (titleMedium)
private const val TITLE_GAP_DP = 8f
private const val CARD_TITLE_LINE_SP = 20f // título del juego (titleSmall)
private const val CARD_DATE_LINE_SP = 16f // «Jugado hace…» (bodySmall)
private const val CARD_TEXT_GAPS_DP = 8f // portada–título y título–fecha
private const val BUTTON_BLOCK_DP = 56f // botón «Continuar» bajo la tarjeta (48) y su separación (8)

/**
 * Medidas del carril «Continuar jugando» (N3a). Cada tarjeta mide lo mismo que una columna de la cuadrícula
 * ([columnsFor], [gridCellWidth]) con el mismo margen y separación, así se ven tantas tarjetas como columnas y alineadas
 * con ellas. La portada puede ser más estrecha que la tarjeta si el alto no da (horizontal).
 */
data class RailMetrics(
    val columns: Int,
    /** Ancho de cada tarjeta = ancho de una celda de la cuadrícula. */
    val cardWidthDp: Float,
    /** Ancho de la portada (10:9): el de la tarjeta, o menos para que la tarjeta quepa en el alto disponible. */
    val artworkWidthDp: Float,
    /** Tarjetas en columna en vez de fila (fuente grande en vertical con una sola columna, como A7). */
    val stacked: Boolean,
    /** Portada a la izquierda, texto a la derecha y «Continuar» debajo (fuente grande). */
    val horizontalCards: Boolean,
    /** Alto estimado del carril con una fila de tarjetas (margen superior, título y tarjeta entera). */
    val estimatedHeightDp: Float,
) {
    val artworkHeightDp: Float
        get() = artworkWidthDp / ARTWORK_RATIO
}

/**
 * [widthDp] = ancho de la cuadrícula (el mismo que recibe [columnsFor]); [viewportHeightDp] = alto visible de la lista
 * con la barra superior desplegada (0 = desconocido: sin límite). Las líneas de texto se estiman con la escala de
 * fuente lineal (Android 14+ escala menos las fuentes grandes, así que la estimación es un máximo).
 */
fun railMetricsFor(widthDp: Float, viewportHeightDp: Float, fontScale: Float, landscape: Boolean): RailMetrics {
    val scale = fontScale.coerceAtLeast(1f)
    val columns = columnsFor(widthDp, fontScale)
    val card = gridCellWidth(widthDp, columns)
    val largeFont = isLargeFont(fontScale)
    val header = TOP_PADDING_DP + TITLE_LINE_SP * scale + TITLE_GAP_DP
    val limit = { reserved: Float -> if (viewportHeightDp > 0f) (viewportHeightDp - reserved) * ARTWORK_RATIO else Float.MAX_VALUE }
    return if (largeFont) {
        val textHeight = (CARD_TITLE_LINE_SP + CARD_DATE_LINE_SP) * scale + CARD_TEXT_GAPS_DP / 2
        val reserved = header + BUTTON_BLOCK_DP
        val cover = minOf(card, minOf(RAIL_SIDE_COVER_DP, card * SIDE_COVER_MAX_FRACTION, limit(reserved)).coerceAtLeast(RAIL_MIN_ARTWORK_DP))
        RailMetrics(
            columns = columns,
            cardWidthDp = card,
            artworkWidthDp = cover,
            stacked = columns == 1 && !landscape,
            horizontalCards = true,
            estimatedHeightDp = reserved + maxOf(cover / ARTWORK_RATIO, textHeight),
        )
    } else {
        val reserved = header + CARD_TEXT_GAPS_DP + (CARD_TITLE_LINE_SP + CARD_DATE_LINE_SP) * scale
        val artwork = minOf(card, limit(reserved).coerceAtLeast(RAIL_MIN_ARTWORK_DP))
        RailMetrics(
            columns = columns,
            cardWidthDp = card,
            artworkWidthDp = artwork,
            stacked = false,
            horizontalCards = false,
            estimatedHeightDp = reserved + artwork / ARTWORK_RATIO,
        )
    }
}

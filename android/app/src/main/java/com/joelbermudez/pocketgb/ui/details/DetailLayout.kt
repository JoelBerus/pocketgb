package com.joelbermudez.pocketgb.ui.details

import com.joelbermudez.pocketgb.emulator.Console
import com.joelbermudez.pocketgb.library.RomEntry

/** Margen lateral del detalle. */
const val DETAIL_MARGIN_DP = 16f

/** Separación entre la imagen y la información en dos columnas. */
const val DETAIL_COLUMN_SPACING_DP = 24f

/** Aire por encima y por debajo de la imagen en dos columnas (12 + 12). */
const val DETAIL_VERTICAL_PADDING_DP = 24f

/** Desde este ancho el detalle va siempre en dos columnas (plegable desplegado, tablet). */
const val DETAIL_WIDE_MIN_WIDTH_DP = 600f

/** En una columna, la imagen ocupa como mucho esta fracción del alto. */
const val DETAIL_PORTRAIT_ARTWORK_FRACTION = 0.45f

/** En dos columnas, la imagen no pasa de esta fracción del ancho útil: la información necesita sitio. */
const val DETAIL_MAX_ARTWORK_WIDTH_FRACTION = 0.5f

/**
 * Disposición del detalle (N3a), decidida por el espacio disponible (el que deja la barra superior), nunca por el
 * modelo de teléfono: dos columnas si el ancho supera al alto o mide ≥ 600 dp (imagen a la izquierda, entera y
 * limitada en altura; información con su propio scroll a la derecha), o una columna con la imagen como mucho al 45 %
 * del alto. La imagen conserva siempre la proporción de la consola. Mismo criterio que `DetailLayout` de iOS.
 */
data class DetailLayout(val twoColumns: Boolean, val artworkWidthDp: Float, val artworkHeightDp: Float)

fun isWideDetail(widthDp: Float, heightDp: Float): Boolean = widthDp > heightDp || widthDp >= DETAIL_WIDE_MIN_WIDTH_DP

/** [aspectRatio] = ancho / alto de la pantalla de la consola ([screenAspectRatio]). */
fun detailLayoutFor(widthDp: Float, heightDp: Float, aspectRatio: Float): DetailLayout {
    val ratio = aspectRatio.coerceAtLeast(0.1f)
    val contentWidth = (widthDp - 2 * DETAIL_MARGIN_DP).coerceAtLeast(0f)
    if (widthDp <= 0f || heightDp <= 0f) {
        return DetailLayout(twoColumns = false, artworkWidthDp = contentWidth, artworkHeightDp = contentWidth / ratio)
    }
    if (isWideDetail(widthDp, heightDp)) {
        val maxWidth = (contentWidth - DETAIL_COLUMN_SPACING_DP).coerceAtLeast(0f) * DETAIL_MAX_ARTWORK_WIDTH_FRACTION
        val maxHeight = (heightDp - DETAIL_VERTICAL_PADDING_DP).coerceAtLeast(0f)
        val width = minOf(maxWidth, maxHeight * ratio)
        return DetailLayout(twoColumns = true, artworkWidthDp = width, artworkHeightDp = width / ratio)
    }
    val maxHeight = heightDp * DETAIL_PORTRAIT_ARTWORK_FRACTION
    val width = minOf(contentWidth, maxHeight * ratio)
    return DetailLayout(twoColumns = false, artworkWidthDp = width, artworkHeightDp = width / ratio)
}

/**
 * Proporción (ancho / alto) de la pantalla de la consola del juego ([Console] por la extensión, como el núcleo): 10:9 en
 * Game Boy y Game Boy Color, 3:2 en Game Boy Advance (la biblioteca aún no lista `.gba`: llegará con N8 Kotlin, y el
 * detalle ya la recibe como parámetro).
 */
val RomEntry.screenAspectRatio: Float
    get() = Console.fromFileName(fileName).screen.aspectRatio

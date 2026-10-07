package com.joelbermudez.pocketgb.ui.library

import com.joelbermudez.pocketgb.library.LibraryCategory
import com.joelbermudez.pocketgb.library.LibraryFilter
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

/** Textos del título de sección (de los recursos; aquí sin Android para probarlo en JVM). */
data class SectionLabels(
    val allGames: String,
    val uncategorized: String,
    /** N4: «Etiqueta «rpg»». */
    val tag: (String) -> String = { it },
    val join: (category: String, filter: String) -> String,
)

/** «Todos los juegos», el filtro, la categoría, la etiqueta (N4) o varios unidos («Categoría · filtro · etiqueta», N3b). */
fun sectionTitle(filter: LibraryFilter, category: LibraryCategory, labels: SectionLabels, tag: String? = null): String {
    val filterTitle = if (filter == LibraryFilter.ALL) null else filter.title
    val categoryTitle = when (category) {
        LibraryCategory.All -> null
        LibraryCategory.Uncategorized -> labels.uncategorized
        is LibraryCategory.Folder -> category.name
    }
    val parts = listOfNotNull(categoryTitle, filterTitle, tag?.let(labels.tag))
    return if (parts.isEmpty()) labels.allGames else parts.reduce(labels.join)
}

/**
 * N3b (respuesta a la auditoría, H1): la barra flotante solo aparece al desplazar en horizontal, con la barra superior
 * plegada al menos a la mitad y el título de sección ya fijado arriba. En reposo las herramientas están en la barra
 * superior (con `enterAlways` vuelven al subir y la flotante se va).
 */
fun showFloatingToolbar(landscape: Boolean, searching: Boolean, collapsedFraction: Float, titlePinned: Boolean): Boolean =
    landscape && !searching && collapsedFraction >= FLOATING_TOOLBAR_MIN_COLLAPSE && titlePinned

/** Fracción plegada de la barra superior desde la que aparece la barra flotante. */
const val FLOATING_TOOLBAR_MIN_COLLAPSE = 0.5f

/** El título de sección está fijado arriba: no hay carril delante o el carril ya salió por arriba. */
fun isTitlePinned(railShown: Boolean, firstVisibleItemIndex: Int): Boolean = isTitlePinned(if (railShown) 1 else 0, firstVisibleItemIndex)

/**
 * N4: con [leadingItems] filas antes del título de sección (carril, Favoritos y estanterías del inicio), el título está
 * fijado cuando todas ellas ya salieron por arriba.
 */
fun isTitlePinned(leadingItems: Int, firstVisibleItemIndex: Int): Boolean = firstVisibleItemIndex >= leadingItems

package com.joelbermudez.pocketgb.ui.library

/** Bordes (px de ventana) del título de sección mientras está en pantalla. */
data class HeaderBounds(val top: Int, val bottom: Int)

/** Dónde empieza un panel (px de ventana) y cuánto puede medir como mucho. */
data class PanelSpan(val top: Int, val maxHeight: Int)

/**
 * N3b (respuesta a la auditoría, H1/H2/H6): colocación de los paneles de la biblioteca en horizontal para que **nunca
 * tapen el título de sección** (todo el encabezado, también el nombre de la carpeta a la derecha) si hay sitio.
 * - En reposo los paneles cuelgan **hacia abajo** de los iconos de la barra superior ([below]).
 * - Al desplazar cuelgan **hacia arriba** de la barra flotante ([above]); si entre el título fijado y la barra no cabe el
 *   mínimo ([needsRoom]), quien abre el panel pliega antes la barra superior para dejar sitio.
 * Todo en px de ventana, como `boundsInWindow`.
 */
object PanelPlacement {
    /**
     * Panel bajo [anchorBottom] (los iconos de la barra superior), dentro de la lista ([viewportBottom]). Si el título se
     * ve, el panel acaba encima si allí cabe [minHeight]; si no, empieza debajo del título; si no cabe en ninguno de los
     * dos lados, va en el más grande (y solo entonces podría tapar el título).
     */
    fun below(anchorBottom: Int, viewportBottom: Int, header: HeaderBounds?, gap: Int, minHeight: Int): PanelSpan {
        val start = anchorBottom + gap
        val end = viewportBottom - gap
        val title = header?.takeIf { it.bottom > anchorBottom && it.top < viewportBottom }
            ?: return PanelSpan(start, maxOf(end - start, minHeight))
        val aboveTitle = title.top - gap - start
        if (aboveTitle >= minHeight) return PanelSpan(start, aboveTitle)
        val belowStart = title.bottom + gap
        val belowTitle = end - belowStart
        if (belowTitle >= minHeight) return PanelSpan(belowStart, belowTitle)
        return if (aboveTitle >= belowTitle) PanelSpan(start, maxOf(aboveTitle, minHeight)) else PanelSpan(belowStart, maxOf(belowTitle, minHeight))
    }

    /**
     * Alto disponible para un panel que acaba sobre [anchorTop] (la barra flotante): hasta debajo del título de sección si
     * se ve por encima de la barra; si no, hasta arriba de la lista ([viewportTop]). Puede ser menor que el mínimo.
     */
    fun above(anchorTop: Int, viewportTop: Int, header: HeaderBounds?, gap: Int): Int {
        val end = anchorTop - gap
        val title = header?.takeIf { it.bottom > viewportTop && it.bottom <= end }
        val limit = (title?.bottom ?: viewportTop) + gap
        return end - limit
    }

    /** `true` si el panel no cabe sin tapar el título: hay que plegar la barra superior antes de abrirlo. */
    fun needsRoom(available: Int, minHeight: Int): Boolean = available < minHeight
}

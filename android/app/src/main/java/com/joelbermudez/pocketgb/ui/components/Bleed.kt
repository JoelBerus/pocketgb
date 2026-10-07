package com.joelbermudez.pocketgb.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * N3a: mide el contenido [start] más ancho por la izquierda y [end] por la derecha, así llega al borde de la pantalla
 * aunque su contenedor (la cabecera de la cuadrícula o de la lista) tenga margen: el carril se desliza hasta el borde
 * izquierdo sin recortarse en el margen y el título de sección fijado tapa todo el ancho. Sin ancho acotado no hace nada.
 */
internal fun Modifier.bleedHorizontally(start: Dp, end: Dp = start): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val left = start.roundToPx()
    val width = constraints.maxWidth + left + end.roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-left, 0) }
}

/** Sin sangrado a la derecha (atajo legible para el carril). */
internal val NoBleed: Dp = 0.dp

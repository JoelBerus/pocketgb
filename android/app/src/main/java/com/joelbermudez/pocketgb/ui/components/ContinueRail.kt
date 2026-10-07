package com.joelbermudez.pocketgb.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.library.LibraryPreferencesData
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.ui.library.GRID_MARGIN_DP
import com.joelbermudez.pocketgb.ui.library.GRID_SPACING_DP
import com.joelbermudez.pocketgb.ui.library.RailMetrics

/** Cuántas filas muestra el carril cuando pasa a columna por fuente grande (R9). */
const val RAIL_LARGE_FONT_ROWS = 3

/**
 * «Continuar jugando» (K10, N3a): hasta cinco juegos que se pueden continuar (ND15) con portada capturada. El botón
 * «Continuar» abre el juego donde se dejó; tocar la portada abre el detalle.
 *
 * Cada tarjeta mide una columna de la cuadrícula ([metrics], mismo margen y separación): se ven tantas como columnas,
 * alineadas con ellas, y al deslizar se ajustan a la columna (sin tarjetas cortadas al soltar). La fila ocupa todo el
 * ancho hasta el borde izquierdo (el margen es relleno de la fila) y acaba en el margen derecho, como la cuadrícula.
 * Con fuente grande la portada va a la izquierda y «Continuar» debajo; en vertical con una sola columna las tarjetas
 * se apilan (hasta [RAIL_LARGE_FONT_ROWS], A7 R9).
 */
@Composable
fun ContinueRail(
    entries: List<RomEntry>,
    prefs: LibraryPreferencesData,
    onOpenDetails: (RomEntry) -> Unit,
    onContinue: (RomEntry) -> Unit,
    metrics: RailMetrics,
    modifier: Modifier = Modifier,
) {
    Column(modifier.testTag("recent-row"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.continue_playing), style = MaterialTheme.typography.titleMedium)
        if (metrics.stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                entries.take(RAIL_LARGE_FONT_ROWS).forEach { entry ->
                    key(entry.id) {
                        ContinueCard(entry, prefs, onOpenDetails, onContinue, metrics, Modifier.fillMaxWidth())
                    }
                }
            }
        } else {
            val state = rememberLazyListState()
            // Solo sangra a la izquierda: a la derecha la fila acaba en el margen, como la cuadrícula, así en reposo no
            // asoma un trozo de la siguiente tarjeta; al deslizar, las tarjetas salen por el borde izquierdo.
            LazyRow(
                state = state,
                modifier = Modifier.bleedHorizontally(start = GRID_MARGIN_DP.dp, end = NoBleed).testTag("continue-rail-row"),
                contentPadding = PaddingValues(start = GRID_MARGIN_DP.dp),
                horizontalArrangement = Arrangement.spacedBy(GRID_SPACING_DP.dp),
                flingBehavior = rememberSnapFlingBehavior(state, SnapPosition.Start),
            ) {
                items(entries, key = { it.id }) { entry ->
                    ContinueCard(entry, prefs, onOpenDetails, onContinue, metrics, Modifier.width(metrics.cardWidthDp.dp))
                }
            }
        }
    }
}

@Composable
private fun ContinueCard(
    entry: RomEntry,
    prefs: LibraryPreferencesData,
    onOpenDetails: (RomEntry) -> Unit,
    onContinue: (RomEntry) -> Unit,
    metrics: RailMetrics,
    modifier: Modifier,
) {
    val horizontal = metrics.horizontalCards
    val fingerprint = prefs.fingerprints[entry.id]
    val lastPlayed = prefs.lastPlayedAt(entry)
    // Misma etiqueta combinada que la tarjeta de la cuadrícula (R8): título, sistema, favorito, nuevo y última partida.
    val description = rememberGameDescription(entry, prefs.isFavorite(entry), lastPlayed)
    val detailsLabel = stringResource(R.string.game_details_action)
    val continueDescription = stringResource(R.string.continue_button_description, entry.displayTitle)
    val cover: @Composable (Modifier) -> Unit = { coverModifier ->
        Box(
            coverModifier
                .testTag("continue-cover")
                .semantics { contentDescription = description }
                .clickable(onClickLabel = detailsLabel, role = Role.Button) { onOpenDetails(entry) },
        ) {
            GameArtwork(entry, fingerprint, Modifier.fillMaxWidth(), decorative = true)
            if (!horizontal) {
                ContinueButton(entry, continueDescription, onContinue, Modifier.align(Alignment.BottomStart).padding(8.dp))
            }
        }
    }
    val texts: @Composable (Modifier) -> Unit = { textModifier ->
        Column(textModifier.clearAndSetSemantics {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                entry.displayTitle,
                maxLines = if (horizontal) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (lastPlayed != null) {
                Text(
                    stringResource(R.string.continue_played, relativeDateText(lastPlayed)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (horizontal) {
        // Fuente grande: el botón ocupa todo el ancho bajo portada y texto (en la columna de texto no cabe «Continuar»).
        Column(modifier.testTag("continue-card"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                cover(Modifier.width(metrics.artworkWidthDp.dp))
                texts(Modifier.weight(1f))
            }
            // Apiladas (vertical) a todo el ancho; en fila (horizontal) a su medida y a la izquierda, lejos de la barra
            // flotante de la derecha.
            ContinueButton(
                entry, continueDescription, onContinue,
                if (metrics.stacked) Modifier.fillMaxWidth() else Modifier,
            )
        }
    } else {
        // La portada puede ser más estrecha que la columna si el alto no da (horizontal): se alinea a la izquierda.
        Column(modifier.testTag("continue-card"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            cover(Modifier.width(metrics.artworkWidthDp.dp))
            texts(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ContinueButton(entry: RomEntry, description: String, onContinue: (RomEntry) -> Unit, modifier: Modifier = Modifier) {
    FilledTonalButton(
        onClick = { onContinue(entry) },
        modifier = modifier
            .heightIn(min = 48.dp)
            .testTag("continue-play")
            .semantics { contentDescription = description },
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.continue_button), modifier = Modifier.padding(start = 6.dp), maxLines = 1)
    }
}

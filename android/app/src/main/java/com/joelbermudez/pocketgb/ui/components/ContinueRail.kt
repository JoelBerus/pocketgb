package com.joelbermudez.pocketgb.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.joelbermudez.pocketgb.ui.a11y.LocalLargeFont

/** Anchos del carril (K10): la primera tarjeta, más grande; el resto, estrechas. */
const val RAIL_FIRST_WIDTH_DP = 240
const val RAIL_OTHER_WIDTH_DP = 170

/** Cuántas filas muestra el carril cuando pasa a columna por fuente grande (R9). */
const val RAIL_LARGE_FONT_ROWS = 3

/**
 * «Continuar jugando» (K10): hasta cinco juegos recientes con portada capturada. El botón «Continuar» flota sobre
 * la portada y abre el juego; tocar la portada abre el detalle. Con fuente grande (R9) deja de ser una fila
 * horizontal y pasa a una columna de hasta [RAIL_LARGE_FONT_ROWS] filas con portada a la izquierda.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContinueRail(
    entries: List<RomEntry>,
    prefs: LibraryPreferencesData,
    onOpenDetails: (RomEntry) -> Unit,
    onContinue: (RomEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val largeFont = LocalLargeFont.current
    Column(modifier.testTag("recent-row"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.continue_playing), style = MaterialTheme.typography.titleMedium)
        if (largeFont) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                entries.take(RAIL_LARGE_FONT_ROWS).forEach { entry ->
                    key(entry.id) {
                        ContinueCard(entry, prefs, onOpenDetails, onContinue, Modifier.fillMaxWidth(), horizontal = true)
                    }
                }
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
                    ContinueCard(
                        entry, prefs, onOpenDetails, onContinue,
                        Modifier.width((if (index == 0) RAIL_FIRST_WIDTH_DP else RAIL_OTHER_WIDTH_DP).dp),
                        horizontal = false,
                    )
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
    modifier: Modifier,
    horizontal: Boolean,
) {
    val fingerprint = prefs.fingerprints[entry.id]
    val lastPlayed = prefs.lastPlayedAt(entry)
    // Misma etiqueta combinada que la tarjeta de la cuadrícula (R8): título, sistema, favorito, nuevo y última partida.
    val description = rememberGameDescription(entry, prefs.isFavorite(entry), lastPlayed)
    val detailsLabel = stringResource(R.string.game_details_action)
    val continueDescription = stringResource(R.string.continue_button_description, entry.title)
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
                entry.title,
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
                cover(Modifier.width(RAIL_OTHER_WIDTH_DP.dp))
                texts(Modifier.weight(1f))
            }
            ContinueButton(entry, continueDescription, onContinue, Modifier.fillMaxWidth())
        }
    } else {
        Column(modifier.testTag("continue-card"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            cover(Modifier)
            texts(Modifier)
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

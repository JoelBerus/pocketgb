package com.joelbermudez.pocketgb.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.library.LibraryPreferencesData
import com.joelbermudez.pocketgb.library.RomEntry

/** Anchos del carril (K10): la primera tarjeta, más grande; el resto, estrechas. */
const val RAIL_FIRST_WIDTH_DP = 240
const val RAIL_OTHER_WIDTH_DP = 170

/**
 * «Continuar jugando» (K10): hasta cinco juegos recientes con portada capturada. El botón «Continuar» flota sobre
 * la portada y abre el juego; tocar la portada abre el detalle.
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
    Column(modifier.testTag("recent-row"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.continue_playing), style = MaterialTheme.typography.titleMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
                val fingerprint = prefs.fingerprints[entry.id]
                val lastPlayed = prefs.lastPlayedAt(entry)
                Column(
                    Modifier.width((if (index == 0) RAIL_FIRST_WIDTH_DP else RAIL_OTHER_WIDTH_DP).dp).testTag("continue-card"),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val detailsLabel = stringResource(R.string.game_details_action)
                    Box(
                        Modifier
                            .testTag("continue-cover")
                            .clickable(onClickLabel = detailsLabel, role = Role.Button) { onOpenDetails(entry) },
                    ) {
                        GameArtwork(entry, fingerprint, Modifier.fillMaxWidth())
                        val continueDescription = stringResource(R.string.continue_button_description, entry.title)
                        FilledTonalButton(
                            onClick = { onContinue(entry) },
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(8.dp)
                                .heightIn(min = 48.dp)
                                .testTag("continue-play")
                                .semantics { contentDescription = continueDescription },
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.continue_button), modifier = Modifier.padding(start = 6.dp), maxLines = 1)
                        }
                    }
                    Text(
                        entry.title,
                        maxLines = 1,
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
        }
    }
}

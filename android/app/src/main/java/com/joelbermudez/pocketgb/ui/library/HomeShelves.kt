package com.joelbermudez.pocketgb.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.library.LibraryPreferencesData
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.ui.a11y.LocalLargeFont
import com.joelbermudez.pocketgb.ui.components.GameArtwork
import com.joelbermudez.pocketgb.ui.components.GameMetaLine
import com.joelbermudez.pocketgb.ui.components.NoBleed
import com.joelbermudez.pocketgb.ui.components.bleedHorizontally
import com.joelbermudez.pocketgb.ui.components.rememberGameDescription
import kotlin.math.floor

/**
 * N4 · una estantería del inicio (o la fila de Favoritos): título con el número de juegos y «Ver todo», y hasta
 * [com.joelbermudez.pocketgb.library.LibraryHome.SHELF_LIMIT] juegos en fila horizontal. Las tarjetas miden una columna
 * de la cuadrícula ([metrics], como el carril «Continuar jugando», N3a), se deslizan hasta el borde izquierdo y se
 * ajustan a la columna al soltar. Tocar una tarjeta abre el detalle y la pulsación larga, su menú (en [place], para no
 * abrir a la vez el de la misma tarjeta en la cuadrícula).
 */
@Composable
internal fun ShelfRow(
    title: String,
    total: Int,
    games: List<RomEntry>,
    prefs: LibraryPreferencesData,
    actions: GameActions,
    menu: GameMenuController,
    metrics: RailMetrics,
    place: String,
    seeAllDescription: String,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
    pinned: Boolean = false,
) {
    val count = pluralStringResource(R.plurals.n4_games, total, total)
    val largeFont = LocalLargeFont.current
    Column(modifier.testTag("home-shelf-$place"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val heading: @Composable (Modifier) -> Unit = { headingModifier ->
            Row(
                headingModifier.semantics(mergeDescendants = true) { heading() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (pinned) {
                    Icon(
                        Icons.Filled.PushPin,
                        contentDescription = stringResource(R.string.n4_shelf_pinned),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = if (largeFont) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).testTag("home-shelf-title"),
                )
                Text(
                    count,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        val seeAll: @Composable () -> Unit = {
            TextButton(
                onClick = onSeeAll,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("home-see-all-$place")
                    .semantics { contentDescription = seeAllDescription },
            ) {
                Text(stringResource(R.string.n4_see_all), maxLines = if (largeFont) Int.MAX_VALUE else 1)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        if (largeFont) {
            // Fuente grande: el título entero en su línea y «Ver todo» debajo, a la derecha (no se corta ninguno).
            heading(Modifier.fillMaxWidth())
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { seeAll() }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                heading(Modifier.weight(1f))
                seeAll()
            }
        }
        val state = rememberLazyListState()
        val cardWidth = with(LocalDensity.current) { floor(metrics.cardWidthDp * density).toDp() }
        LazyRow(
            state = state,
            modifier = Modifier.bleedHorizontally(start = GRID_MARGIN_DP.dp, end = NoBleed).testTag("home-shelf-row-$place"),
            contentPadding = PaddingValues(start = GRID_MARGIN_DP.dp),
            horizontalArrangement = Arrangement.spacedBy(GRID_SPACING_DP.dp),
            flingBehavior = rememberSnapFlingBehavior(state, SnapPosition.Start),
        ) {
            items(games, key = { it.id }) { entry ->
                val favorite = prefs.isFavorite(entry)
                Box(Modifier.width(cardWidth)) {
                    ShelfCard(
                        entry = entry,
                        prefs = prefs,
                        favorite = favorite,
                        metrics = metrics,
                        onClick = { actions.onOpenDetails(entry) },
                        onLongClick = { menu.open(entry, place) },
                    )
                    menu.Menu(entry, favorite, place)
                }
            }
        }
    }
}

/** Tarjeta de estantería: portada (limitada en alto como el carril), título y la línea de metadatos (sistema, insignias). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfCard(
    entry: RomEntry,
    prefs: LibraryPreferencesData,
    favorite: Boolean,
    metrics: RailMetrics,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val lastPlayed = prefs.lastPlayedAt(entry)
    val description = rememberGameDescription(entry, favorite, lastPlayed)
    val clickLabel = stringResource(R.string.game_details_action)
    val longLabel = stringResource(R.string.game_a11y_more_options)
    val modifier = Modifier
        .testTag("shelf-card")
        .semantics(mergeDescendants = true) {
            role = Role.Button
            contentDescription = description
        }
        .combinedClickable(onClickLabel = clickLabel, onLongClickLabel = longLabel, onLongClick = onLongClick, onClick = onClick)
    val cover: @Composable () -> Unit = {
        GameArtwork(entry, prefs.fingerprints[entry.id], Modifier.width(metrics.artworkWidthDp.dp), decorative = true)
    }
    val texts: @Composable (Modifier) -> Unit = { textModifier ->
        Column(textModifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                entry.displayTitle,
                maxLines = if (metrics.horizontalCards) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            GameMetaLine(entry, favorite, lastPlayed, announce = false, compactBadges = true)
        }
    }
    if (metrics.horizontalCards) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            cover()
            texts(Modifier.weight(1f))
        }
    } else {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            cover()
            texts(Modifier.fillMaxWidth().padding(end = 2.dp))
        }
    }
}

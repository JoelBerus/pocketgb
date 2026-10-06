package com.joelbermudez.pocketgb.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.library.RomEntry

/** Chip GB/GBC: texto, nunca solo color. */
@Composable
fun ConsoleChip(isColor: Boolean, modifier: Modifier = Modifier) {
    val description = stringResource(if (isColor) R.string.game_system_gbc else R.string.game_system_gb)
    Text(
        text = if (isColor) "GBC" else "GB",
        modifier = modifier
            .width(38.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .padding(vertical = 2.dp)
            .semantics { contentDescription = description },
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Una línea corta: motivo, cuándo se jugó, fecha de la partida junto al ROM o «Sin jugar». */
@Composable
fun gameDetailText(entry: RomEntry, lastPlayedAt: Long?): String = when {
    entry.problem != null -> stringResource(R.string.game_cannot_open)
    lastPlayedAt != null -> relativeDateText(lastPlayedAt)
    entry.mirrorSaveDate != null -> stringResource(R.string.game_save_date, relativeDateText(entry.mirrorSaveDate))
    else -> stringResource(R.string.game_never_played)
}

/** Chip, favorito, «Nuevo» y estado en una línea propia: un título largo no los solapa. */
@Composable
fun GameMetaLine(entry: RomEntry, favorite: Boolean, lastPlayedAt: Long?, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ConsoleChip(entry.isColor)
        if (favorite) {
            Icon(
                Icons.Filled.Star,
                contentDescription = stringResource(R.string.game_favorite),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        if (entry.isNew) {
            Text(
                stringResource(R.string.game_new),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            gameDetailText(entry, lastPlayedAt),
            modifier = Modifier.weight(1f, fill = false),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Texto único para lectores de pantalla: título, sistema, favorito, nuevo, problema y última partida. */
@Composable
private fun rememberGameDescription(entry: RomEntry, favorite: Boolean, lastPlayedAt: Long?): String {
    val system = stringResource(if (entry.isColor) R.string.game_system_gbc else R.string.game_system_gb)
    val parts = mutableListOf(entry.title, system)
    if (favorite) parts += stringResource(R.string.game_favorite)
    if (entry.isNew) parts += stringResource(R.string.game_new)
    parts += entry.problem?.message ?: if (lastPlayedAt != null) {
        stringResource(R.string.game_status_played, relativeDateText(lastPlayedAt))
    } else {
        gameDetailText(entry, null)
    }
    return parts.joinToString(", ")
}

/**
 * Tarjeta de cuadrícula: portada 10:9, título en dos líneas y una línea de metadatos. Un juego con problema
 * sigue siendo tocable porque abre el detalle, donde se explica qué hacer; jugar nunca se ofrece desde aquí.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GameCard(
    entry: RomEntry,
    fingerprint: String?,
    favorite: Boolean,
    lastPlayedAt: Long?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val description = rememberGameDescription(entry, favorite, lastPlayedAt)
    val clickLabel = stringResource(R.string.game_details_action)
    val longLabel = stringResource(R.string.game_more_actions)
    val interaction = if (onClick != null) {
        Modifier.combinedClickable(
            onClickLabel = clickLabel,
            onLongClickLabel = longLabel,
            onLongClick = onLongClick,
            onClick = onClick,
        )
    } else {
        Modifier
    }
    Column(
        modifier = modifier
            .testTag("game-card")
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = description
            }
            .then(interaction),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            GameArtwork(entry, fingerprint, Modifier.fillMaxWidth(), decorative = true)
            if (entry.problem != null) StatusBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
        Text(
            entry.title,
            modifier = Modifier.fillMaxWidth(),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleSmall,
            color = if (entry.problem == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        GameMetaLine(entry, favorite, lastPlayedAt)
    }
}

/** Símbolo sobre fondo oscuro fijo para que se lea sobre cualquier portada. */
@Composable
private fun StatusBadge(modifier: Modifier = Modifier) {
    Box(
        modifier.size(32.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.WarningAmber,
            contentDescription = stringResource(R.string.game_problem),
            modifier = Modifier.size(20.dp),
            tint = Color.White,
        )
    }
}

/** Fila de la lista: miniatura, título, sistema y última partida; con problema, el motivo completo. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GameListItem(
    entry: RomEntry,
    fingerprint: String?,
    favorite: Boolean,
    lastPlayedAt: Long?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val description = rememberGameDescription(entry, favorite, lastPlayedAt)
    val clickLabel = stringResource(R.string.game_details_action)
    val longLabel = stringResource(R.string.game_more_actions)
    val interaction = if (onClick != null) {
        Modifier.combinedClickable(
            onClickLabel = clickLabel,
            onLongClickLabel = longLabel,
            onLongClick = onLongClick,
            onClick = onClick,
        )
    } else {
        Modifier
    }
    Row(
        modifier = modifier
            .testTag("game-list-item")
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = description
            }
            .then(interaction)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GameArtwork(entry, fingerprint, Modifier.width(ThumbnailWidth), compact = true, decorative = true)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                entry.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (entry.problem == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val problem = entry.problem
            if (problem != null) {
                Text(
                    problem.message,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                GameMetaLine(entry, favorite, lastPlayedAt)
            }
        }
        if (entry.problem != null) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = stringResource(R.string.game_problem),
                tint = MaterialTheme.colorScheme.error,
            )
        } else {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

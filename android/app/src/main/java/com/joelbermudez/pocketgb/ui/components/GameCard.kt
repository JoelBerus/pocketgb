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
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
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
import com.joelbermudez.pocketgb.ui.a11y.LocalLargeFont

/** Chip GB/GBC: texto, nunca solo color. */
@Composable
fun ConsoleChip(isColor: Boolean, modifier: Modifier = Modifier, announce: Boolean = true) {
    val description = stringResource(if (isColor) R.string.game_system_gbc else R.string.game_system_gb)
    Text(
        text = if (isColor) "GBC" else "GB",
        modifier = modifier
            .width(38.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .padding(vertical = 2.dp)
            // Dentro de una tarjeta el sistema ya va en la etiqueta combinada: sin descripción propia no se lee dos veces.
            .then(if (announce) Modifier.semantics { contentDescription = description } else Modifier),
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
fun GameMetaLine(
    entry: RomEntry,
    favorite: Boolean,
    lastPlayedAt: Long?,
    modifier: Modifier = Modifier,
    /** `false` dentro de una tarjeta con etiqueta combinada (R8): chip y favorito no se anuncian aparte. */
    announce: Boolean = true,
    /** N4: insignias solo con icono (tarjetas estrechas de la cuadrícula y de las estanterías). */
    compactBadges: Boolean = false,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ConsoleChip(entry.isColor, announce = announce)
        if (favorite) {
            Icon(
                Icons.Filled.Star,
                contentDescription = if (announce) stringResource(R.string.game_favorite) else null,
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
        if (entry.isDuplicate) DuplicateBadge()
        if (entry.isMovedInApp) MovedBadge(compact = compactBadges)
        Text(
            gameDetailText(entry, lastPlayedAt),
            modifier = Modifier.weight(1f, fill = false),
            maxLines = if (LocalLargeFont.current) 2 else 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** N1a: otra copia con la misma huella en la carpeta. Discreta: contorno fino y texto, sin color de alerta. */
@Composable
fun DuplicateBadge(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.n1_duplicate),
        modifier = modifier
            .testTag("game-duplicate-badge")
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .padding(horizontal = 6.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * N4 (ND3): el juego se ve en otra categoría que la de su carpeta. Discreta, como «Duplicado»: contorno fino, icono de
 * mover y, si cabe, el texto «Movido en la app»; [compact] = solo el icono (el texto va en la etiqueta de la tarjeta).
 */
@Composable
fun MovedBadge(modifier: Modifier = Modifier, compact: Boolean = false) {
    val text = stringResource(R.string.n4_moved_badge)
    Row(
        modifier
            .testTag("game-moved-badge")
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .padding(horizontal = if (compact) 4.dp else 6.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.DriveFileMove,
            contentDescription = if (compact) text else null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!compact) {
            Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Texto único para lectores de pantalla: título, sistema, favorito, nuevo, duplicado, movido (N4), problema y última partida. */
@Composable
fun rememberGameDescription(entry: RomEntry, favorite: Boolean, lastPlayedAt: Long?): String {
    val system = stringResource(if (entry.isColor) R.string.game_system_gbc else R.string.game_system_gb)
    val parts = mutableListOf(entry.displayTitle, system)
    if (favorite) parts += stringResource(R.string.game_favorite)
    if (entry.isNew) parts += stringResource(R.string.game_new)
    if (entry.isDuplicate) parts += stringResource(R.string.n1_duplicate)
    if (entry.isMovedInApp) parts += stringResource(R.string.n4_moved_badge)
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
    /** Una sola columna con fuente grande (R9): portada a la izquierda y texto a la derecha. */
    horizontal: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val description = rememberGameDescription(entry, favorite, lastPlayedAt)
    val clickLabel = stringResource(R.string.game_details_action)
    // En TalkBack la pulsación larga se anuncia como la acción «Más opciones» (el menú contextual).
    val longLabel = stringResource(R.string.game_a11y_more_options)
    val largeFont = LocalLargeFont.current
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
    val semanticsModifier = Modifier
        .testTag("game-card")
        .semantics(mergeDescendants = true) {
            role = Role.Button
            contentDescription = description
        }
        .then(interaction)
    val titleColor = if (entry.problem == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    // Con fuente grande el título no se trunca: ocupa las líneas que necesite (R9).
    val titleLines = if (largeFont) Int.MAX_VALUE else 2
    if (horizontal) {
        Row(
            modifier = modifier.then(semanticsModifier),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.width(HorizontalCardArtworkWidth)) {
                GameArtwork(entry, fingerprint, Modifier.fillMaxWidth(), decorative = true)
                if (entry.problem != null) StatusBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    entry.displayTitle,
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = titleLines,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    color = titleColor,
                )
                GameMetaLine(entry, favorite, lastPlayedAt, announce = false, compactBadges = true)
            }
        }
    } else {
        Column(
            modifier = modifier.then(semanticsModifier),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box {
                GameArtwork(entry, fingerprint, Modifier.fillMaxWidth(), decorative = true)
                if (entry.problem != null) StatusBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
            Text(
                entry.displayTitle,
                modifier = Modifier.fillMaxWidth(),
                maxLines = titleLines,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
                color = titleColor,
            )
            GameMetaLine(entry, favorite, lastPlayedAt, announce = false, compactBadges = true)
        }
    }
}

private val HorizontalCardArtworkWidth = 140.dp

/** Símbolo sobre fondo oscuro fijo para que se lea sobre cualquier portada. */
@Composable
private fun StatusBadge(modifier: Modifier = Modifier) {
    Box(
        modifier.size(32.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.WarningAmber,
            contentDescription = null, // el motivo ya va en la etiqueta combinada de la tarjeta
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
    val longLabel = stringResource(R.string.game_a11y_more_options)
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
                entry.displayTitle,
                maxLines = if (LocalLargeFont.current) Int.MAX_VALUE else 2,
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
                GameMetaLine(entry, favorite, lastPlayedAt, announce = false)
            }
        }
        if (entry.problem != null) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = null,
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

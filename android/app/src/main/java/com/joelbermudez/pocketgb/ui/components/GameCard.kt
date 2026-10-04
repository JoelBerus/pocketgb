package com.joelbermudez.pocketgb.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.joelbermudez.pocketgb.library.ByteFormat
import com.joelbermudez.pocketgb.library.RomEntry

/** "GBC · 1,5 MiB", o el mensaje del problema si el archivo no se puede jugar. */
fun RomEntry.summary(): String = problem?.message ?: "$systemShort · ${ByteFormat.format(sizeBytes)}"

/**
 * Tarjeta de cuadrícula. Un juego con [problem] muestra su mensaje y sigue siendo tocable
 * porque abre el detalle, donde se explica qué hacer; jugar nunca se ofrece desde aquí.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GameCard(
    title: String,
    subtitle: String,
    favorite: Boolean,
    modifier: Modifier = Modifier,
    hasProblem: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val interaction = if (onClick != null) {
        Modifier.combinedClickable(
            onClickLabel = "Ver detalle",
            onLongClickLabel = "Más acciones",
            onLongClick = onLongClick,
            onClick = onClick,
        )
    } else {
        Modifier
    }
    Card(modifier = modifier.testTag("game-card").semantics(mergeDescendants = true) { role = Role.Button }.then(interaction)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.6f)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.SportsEsports,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (favorite) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = "Favorito",
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (hasProblem) {
                    Icon(
                        Icons.Outlined.ErrorOutline,
                        contentDescription = "Problema",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    subtitle,
                    maxLines = if (hasProblem) 3 else 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GameListItem(
    title: String,
    subtitle: String,
    favorite: Boolean,
    modifier: Modifier = Modifier,
    hasProblem: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val interaction = if (onClick != null) {
        Modifier.combinedClickable(
            onClickLabel = "Ver detalle",
            onLongClickLabel = "Más acciones",
            onLongClick = onLongClick,
            onClick = onClick,
        )
    } else {
        Modifier
    }
    ListItem(
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                subtitle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (hasProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (hasProblem) Icons.Outlined.ErrorOutline else Icons.Outlined.SportsEsports,
                    contentDescription = if (hasProblem) "Problema" else null,
                    tint = if (hasProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        },
        trailingContent = if (favorite) {
            {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = "Favorito",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            null
        },
        modifier = modifier.testTag("game-list-item").semantics(mergeDescendants = true) { role = Role.Button }.then(interaction),
    )
}

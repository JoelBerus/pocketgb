package com.joelbermudez.pocketgb.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joelbermudez.pocketgb.library.ByteFormat
import com.joelbermudez.pocketgb.library.DetailsLoad
import com.joelbermudez.pocketgb.library.GameDetails
import com.joelbermudez.pocketgb.library.LibraryState
import com.joelbermudez.pocketgb.library.LibraryViewModel
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.ui.components.EmptyState
import com.joelbermudez.pocketgb.ui.components.HideGameDialog
import com.joelbermudez.pocketgb.ui.library.ScanningPane
import java.text.DateFormat
import java.util.Date

const val PLAY_DISABLED_LABEL = "Jugar se activa en A5 (partidas seguras)"

@Composable
fun GameDetailsScreen(viewModel: LibraryViewModel, gameId: String, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val entries = when (val current = state) {
        is LibraryState.Ready -> current.entries
        is LibraryState.Scanning -> current.previous
        else -> emptyList()
    }
    val entry = entries.firstOrNull { it.id == gameId }?.takeUnless { prefs.isHidden(it) }
    if (entry == null) {
        if (state is LibraryState.Loading || state is LibraryState.Scanning && entries.isEmpty()) {
            ScanningPane(
                message = if (state is LibraryState.Loading) "Cargando biblioteca…" else "Buscando juegos…",
            )
        } else {
            GameUnavailable(onBack)
        }
        return
    }
    val load by produceState<DetailsLoad>(DetailsLoad.Loading, entry.uri) {
        value = DetailsLoad.Loading
        value = viewModel.loadDetails(entry.id)
    }
    GameDetailsContent(
        entry = entry,
        load = load,
        favorite = prefs.isFavorite(entry),
        lastPlayedAt = prefs.lastPlayedAt(entry),
        onToggleFavorite = { viewModel.toggleFavorite(entry) },
        onHide = {
            viewModel.hide(entry)
            onBack()
        },
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GameUnavailable(onBack: () -> Unit) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Detalle") }, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        EmptyState(
            icon = Icons.Outlined.VisibilityOff,
            title = "Juego no disponible",
            message = "Ya no está en la carpeta o se ocultó.",
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
    }
}

/** Detalle sin ViewModel: reutilizable por el catálogo debug y las pruebas Compose. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameDetailsContent(
    entry: RomEntry,
    load: DetailsLoad,
    favorite: Boolean,
    lastPlayedAt: Long?,
    onToggleFavorite: () -> Unit,
    onHide: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmHide by remember { mutableStateOf(false) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Artwork()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.title, style = MaterialTheme.typography.headlineSmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ) {
                        Text(
                            entry.systemShort,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    Text(
                        if (entry.subfolder.isEmpty()) entry.fileName else "${entry.subfolder} · ${entry.fileName}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
            }

            val problemMessage = entry.problem?.message ?: (load as? DetailsLoad.Failed)?.error?.message
            if (problemMessage != null) ProblemCard(problemMessage)

            // Regla dura 6: no se abre un juego sin la ruta de guardado atómica de A5.
            Button(
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("game-details-play"),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(PLAY_DISABLED_LABEL, modifier = Modifier.padding(start = 8.dp))
            }

            Facts(entry, load, lastPlayedAt)

            OutlinedButton(
                onClick = onToggleFavorite,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("game-details-favorite"),
            ) {
                Icon(
                    if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    if (favorite) "Quitar de favoritos" else "Añadir a favoritos",
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            OutlinedButton(
                onClick = { confirmHide = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("game-details-hide"),
            ) {
                Icon(Icons.Outlined.VisibilityOff, contentDescription = null, modifier = Modifier.size(20.dp))
                Text("Ocultar de PocketGB", modifier = Modifier.padding(start = 8.dp))
            }
            Text(
                "Ocultar no borra el ROM ni la partida.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    if (confirmHide) {
        HideGameDialog(
            title = entry.title,
            onConfirm = {
                confirmHide = false
                onHide()
            },
            onDismiss = { confirmHide = false },
        )
    }
}

@Composable
private fun Artwork() {
    // Portada local: hasta A6 es un marcador de posición con el icono del sistema.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(2.2f)
            .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.large)
            .testTag("game-details-artwork"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.SportsEsports,
            contentDescription = "Portada no disponible",
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun ProblemCard(message: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().testTag("game-details-problem"),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = "Problema",
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun Facts(entry: RomEntry, load: DetailsLoad, lastPlayedAt: Long?) {
    val lastPlayed = remember(lastPlayedAt) {
        lastPlayedAt?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) }
            ?: "Nunca"
    }
    Column {
        HorizontalDivider()
        Fact("Sistema", entry.system)
        Fact("Tamaño del archivo", ByteFormat.format(entry.sizeBytes))
        Fact("Última partida", lastPlayed)
        when (load) {
            DetailsLoad.Loading -> if (entry.problem == null) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Leyendo metadatos…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            is DetailsLoad.Failed -> Unit
            is DetailsLoad.Loaded -> LoadedFacts(load.details)
        }
    }
}

@Composable
private fun LoadedFacts(details: GameDetails) {
    Fact("Cartucho", details.cartridge)
    Fact("ROM", ByteFormat.format(details.romBytes.toLong()))
    Fact(
        "Partida guardada",
        buildString {
            append(if (details.sramBytes == 0) "Sin RAM" else ByteFormat.format(details.sramBytes.toLong()))
            if (details.hasBattery) append(" · batería")
            if (details.hasRtc) append(" · reloj")
        },
    )
    ChecksumFact("Checksum de cabecera", details.headerChecksumOk, "Correcto", "Incorrecto")
    ChecksumFact("Checksum global", details.globalChecksumOk, "Correcto", "No coincide (la consola real lo ignora)")
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text("Huella SHA-256", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(
                details.fingerprint,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp).testTag("game-details-fingerprint"),
            )
        }
    }
    HorizontalDivider()
}

/** Etiqueta a la izquierda y valor a la derecha; apilados si el texto no cabe (fuentes grandes). */
@Composable
private fun Fact(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            modifier = Modifier.weight(1.4f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
        )
    }
    HorizontalDivider()
}

@Composable
private fun ChecksumFact(label: String, ok: Boolean, okText: String, badText: String) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.weight(1.4f),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (ok) Icons.Filled.CheckCircle else Icons.Outlined.Warning,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Text(
                if (ok) okText else badText,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.End,
            )
        }
    }
    HorizontalDivider()
}

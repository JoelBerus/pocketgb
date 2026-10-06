package com.joelbermudez.pocketgb.ui.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.ui.a11y.LocalLargeFont
import com.joelbermudez.pocketgb.library.ByteFormat
import com.joelbermudez.pocketgb.library.DetailsLoad
import com.joelbermudez.pocketgb.library.GameDetails
import com.joelbermudez.pocketgb.library.LibraryState
import com.joelbermudez.pocketgb.library.LibraryViewModel
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.ui.components.EmptyState
import com.joelbermudez.pocketgb.settings.GameplaySettingsRepository
import com.joelbermudez.pocketgb.ui.components.ConsoleChip
import com.joelbermudez.pocketgb.ui.components.GameArtwork
import com.joelbermudez.pocketgb.ui.components.HideGameDialog
import com.joelbermudez.pocketgb.ui.components.relativeDateText
import com.joelbermudez.pocketgb.ui.library.ScanningPane

@Composable
fun GameDetailsScreen(
    viewModel: LibraryViewModel,
    gameId: String,
    onPlay: (RomEntry) -> Unit,
    onBack: () -> Unit,
    gameplaySettings: GameplaySettingsRepository? = null,
) {
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
    var showSettings by remember { mutableStateOf(false) }
    GameDetailsContent(
        entry = entry,
        load = load,
        favorite = prefs.isFavorite(entry),
        lastPlayedAt = prefs.lastPlayedAt(entry),
        onPlay = { onPlay(entry) },
        onToggleFavorite = { viewModel.toggleFavorite(entry) },
        onHide = {
            viewModel.hide(entry)
            onBack()
        },
        onBack = onBack,
        fingerprint = prefs.fingerprints[entry.id],
        onOpenSettings = { showSettings = true },
    )
    GameSettingsHost(
        entry = entry.takeIf { showSettings },
        library = viewModel,
        repository = gameplaySettings ?: GameplaySettingsRepository.shared(LocalContext.current),
        onDismiss = { showSettings = false },
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
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.details_back))
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
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onHide: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Huella del ROM si ya se conoce (portada real); si no, se usa la de [load] o el placeholder por ruta. */
    fingerprint: String? = null,
    onOpenSettings: () -> Unit = {},
) {
    var confirmHide by remember { mutableStateOf(false) }
    val artworkKey = fingerprint ?: (load as? DetailsLoad.Loaded)?.details?.fingerprint
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = onToggleFavorite, modifier = Modifier.testTag("game-details-star")) {
                        Icon(
                            if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = stringResource(
                                if (favorite) R.string.menu_favorite_remove else R.string.menu_favorite_add,
                            ),
                        )
                    }
                },
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
            GameArtwork(
                entry,
                artworkKey,
                Modifier.fillMaxWidth().testTag("game-details-artwork"),
                shape = MaterialTheme.shapes.large,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.title, style = MaterialTheme.typography.headlineSmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ConsoleChip(entry.isColor)
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

            // Jugable solo si la biblioteca no vio problemas y los metadatos no fallaron. "Continuar" NO carga el
            // estado AUTO (J8): abre la partida (SRAM) donde se dejó, como iOS.
            Button(
                onClick = onPlay,
                enabled = entry.isPlayable && load !is DetailsLoad.Failed,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("game-details-play"),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                val played = lastPlayedAt != null || entry.mirrorSaveDate != null
                Text(
                    stringResource(if (played) R.string.details_continue else R.string.details_play),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            Stats(entry, lastPlayedAt)
            SecondaryActions(favorite, onToggleFavorite, onOpenSettings)
            Facts(entry, load)

            OutlinedButton(
                onClick = { confirmHide = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("game-details-hide"),
            ) {
                Icon(Icons.Outlined.VisibilityOff, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.details_hide), modifier = Modifier.padding(start = 8.dp))
            }
            Text(
                stringResource(R.string.details_hide_footer),
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

/** Jugado, Partida (fecha del `.sav` junto al ROM) y Tamaño, en tres columnas. */
@Composable
private fun Stats(entry: RomEntry, lastPlayedAt: Long?) {
    Column {
        HorizontalDivider()
        val played = stringResource(R.string.details_played)
        val playedValue = lastPlayedAt?.let { relativeDateText(it) } ?: stringResource(R.string.details_never)
        val save = stringResource(R.string.details_save)
        val saveValue = entry.mirrorSaveDate?.let { relativeDateText(it) } ?: stringResource(R.string.details_none)
        val size = stringResource(R.string.details_size)
        val sizeValue = ByteFormat.format(entry.sizeBytes)
        if (LocalLargeFont.current) {
            // Fuente grande (R9): una estadística por fila en vez de tres columnas estrechas.
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("game-details-stats"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat(played, playedValue, Modifier.fillMaxWidth())
                Stat(save, saveValue, Modifier.fillMaxWidth())
                Stat(size, sizeValue, Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("game-details-stats")) {
                Stat(played, playedValue, Modifier.weight(1f))
                Stat(save, saveValue, Modifier.weight(1f))
                Stat(size, sizeValue, Modifier.weight(1f))
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 4.dp).semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall, maxLines = 2)
    }
}

/** Favorito, Estados (aún no) y Ajustes del juego. */
@Composable
private fun SecondaryActions(favorite: Boolean, onToggleFavorite: () -> Unit, onOpenSettings: () -> Unit) {
    val favoriteLabel = stringResource(if (favorite) R.string.menu_favorite_remove else R.string.menu_favorite_add)
    val soon = stringResource(R.string.details_states_soon)
    val favoriteButton: @Composable (Modifier) -> Unit = { modifier ->
        OutlinedButton(
            onClick = onToggleFavorite,
            modifier = modifier.heightIn(min = 48.dp).testTag("game-details-favorite")
                .semantics { contentDescription = favoriteLabel },
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        ) {
            Icon(
                if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(stringResource(R.string.game_favorite), modifier = Modifier.padding(start = 6.dp), maxLines = 1)
        }
    }
    val statesButton: @Composable (Modifier) -> Unit = { modifier ->
        OutlinedButton(
            onClick = {},
            enabled = false,
            modifier = modifier.heightIn(min = 48.dp).testTag("game-details-states")
                .semantics { stateDescription = soon },
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        ) {
            Icon(Icons.Outlined.ViewAgenda, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.details_states), modifier = Modifier.padding(start = 6.dp), maxLines = 1)
        }
    }
    val settingsButton: @Composable (Modifier) -> Unit = { modifier ->
        OutlinedButton(
            onClick = onOpenSettings,
            modifier = modifier.heightIn(min = 48.dp).testTag("game-details-settings")
                .semantics { contentDescription = "Ajustes del juego" },
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        ) {
            Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.details_settings), modifier = Modifier.padding(start = 6.dp), maxLines = 1)
        }
    }
    if (LocalLargeFont.current) {
        // Con fuente grande (R9) los botones se apilan: en una fila sus textos no caben.
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            favoriteButton(Modifier.fillMaxWidth())
            statesButton(Modifier.fillMaxWidth())
            settingsButton(Modifier.fillMaxWidth())
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            favoriteButton(Modifier.weight(1f))
            statesButton(Modifier.weight(1f))
            settingsButton(Modifier.weight(1f))
        }
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
private fun Facts(entry: RomEntry, load: DetailsLoad) {
    Column {
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
            is DetailsLoad.Loaded -> {
                HorizontalDivider()
                LoadedFacts(load.details)
            }
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
    if (LocalLargeFont.current) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 8.dp).semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
        HorizontalDivider()
        return
    }
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
    val large = LocalLargeFont.current
    if (large) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 8.dp).semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (ok) Icons.Filled.CheckCircle else Icons.Outlined.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Text(if (ok) okText else badText, style = MaterialTheme.typography.bodyMedium)
            }
        }
        HorizontalDivider()
        return
    }
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

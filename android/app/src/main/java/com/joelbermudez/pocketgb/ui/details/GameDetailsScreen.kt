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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.outlined.Edit
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.joelbermudez.pocketgb.library.LibraryQuery
import com.joelbermudez.pocketgb.library.LibraryState
import com.joelbermudez.pocketgb.library.LibraryViewModel
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.library.RomLocation
import com.joelbermudez.pocketgb.ui.components.DuplicateBadge
import com.joelbermudez.pocketgb.ui.components.EmptyState
import com.joelbermudez.pocketgb.settings.GameplaySettingsRepository
import com.joelbermudez.pocketgb.ui.components.ConsoleChip
import com.joelbermudez.pocketgb.ui.components.GameArtwork
import com.joelbermudez.pocketgb.ui.components.HideGameDialog
import com.joelbermudez.pocketgb.ui.components.RenameGameHost
import com.joelbermudez.pocketgb.ui.components.relativeDateText
import com.joelbermudez.pocketgb.ui.library.ScanningPane

@Composable
fun GameDetailsScreen(
    viewModel: LibraryViewModel,
    gameId: String,
    /** Acción principal: «Continuar» si hay estado automático vigente, si no «Jugar» (quien llama elige el modo). */
    onPlay: (RomEntry) -> Unit,
    onBack: () -> Unit,
    gameplaySettings: GameplaySettingsRepository? = null,
    /** A9: «Jugar desde el inicio» (solo la partida). */
    onPlayFromStart: (RomEntry) -> Unit = {},
    /** A9: huellas con «Continuar» exacto disponible. */
    resumable: Set<String> = emptySet(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val entries = when (val current = state) {
        is LibraryState.Ready -> current.entries
        is LibraryState.Scanning -> current.previous
        else -> emptyList()
    }
    // Con el alias aplicado (A9): barra superior, título y diálogos muestran el nombre que eligió el usuario. Con las
    // otras copias de la misma huella (N1a, «También en»).
    val entry = remember(entries, prefs, gameId) { LibraryQuery.presented(entries, prefs, gameId) }?.takeUnless { prefs.isHidden(it) }
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
    var renaming by remember { mutableStateOf(false) }
    val fingerprint = prefs.fingerprints[entry.id]
    GameDetailsContent(
        entry = entry,
        load = load,
        favorite = prefs.isFavorite(entry),
        lastPlayedAt = prefs.lastPlayedAt(entry),
        onPlay = { onPlay(entry) },
        canResume = entry.isPlayable && fingerprint != null && fingerprint in resumable,
        onPlayFromStart = { onPlayFromStart(entry) },
        onRename = { renaming = true },
        onToggleFavorite = { viewModel.toggleFavorite(entry) },
        onHide = {
            viewModel.hide(entry)
            onBack()
        },
        onBack = onBack,
        fingerprint = fingerprint,
        onOpenSettings = { showSettings = true },
    )
    RenameGameHost(
        entry = entry.takeIf { renaming },
        prefs = prefs,
        onSetAlias = viewModel::setAlias,
        onDismiss = { renaming = false },
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
        topBar = { TopAppBar(title = { Text(stringResource(R.string.details_title)) }, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        EmptyState(
            icon = Icons.Outlined.VisibilityOff,
            title = stringResource(R.string.details_unavailable_title),
            message = stringResource(R.string.details_unavailable_message),
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
    /** A9: hay estado automático vigente: «Continuar» lo retoma y se ofrece también «Jugar desde el inicio». */
    canResume: Boolean = false,
    onPlayFromStart: () -> Unit = {},
    /** A9: «Renombrar» en el menú de la barra superior; `null` lo oculta. */
    onRename: (() -> Unit)? = null,
    /** Solo el catálogo de capturas: el menú de la barra superior arranca abierto. */
    initialMenuOpen: Boolean = false,
) {
    var confirmHide by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(initialMenuOpen) }
    val artworkKey = fingerprint ?: (load as? DetailsLoad.Loaded)?.details?.fingerprint
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(entry.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                    if (onRename != null) {
                        Box {
                            IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("game-details-more")) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.a9_details_more))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.a9_rename)) },
                                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        onRename()
                                    },
                                    modifier = Modifier.testTag("game-details-rename"),
                                )
                            }
                        }
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
                Text(entry.displayTitle, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("game-details-title"))
                // N1b: ruta completa («Pokémon › 2ª generación · archivo»); con fuente grande, entera y bajo el chip.
                val location = locationText(entry.location, inRoot = null)
                val locationDescription = stringResource(R.string.n1_location_description, location)
                val locationText: @Composable () -> Unit = {
                    Text(
                        location,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (LocalLargeFont.current) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("game-details-location").semantics { contentDescription = locationDescription },
                    )
                }
                if (LocalLargeFont.current) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ConsoleChip(entry.isColor)
                        locationText()
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ConsoleChip(entry.isColor)
                        locationText()
                    }
                }
                if (entry.isDuplicate) AlsoIn(entry.alsoAt)
            }

            val problemMessage = entry.problem?.message ?: (load as? DetailsLoad.Failed)?.error?.message
            if (problemMessage != null) ProblemCard(problemMessage)

            // Jugable solo si la biblioteca no vio problemas y los metadatos no fallaron. A9 (cambia J8, ND6), como iOS:
            // «Continuar» retoma el estado automático exacto si sigue siendo el de la partida; si no hay, «Jugar» abre la
            // partida. Con «Continuar» también se ofrece «Jugar desde el inicio» (solo la partida, sin el estado).
            val playable = entry.isPlayable && load !is DetailsLoad.Failed
            Button(
                onClick = onPlay,
                enabled = playable,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("game-details-play"),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(
                    stringResource(if (canResume) R.string.details_continue else R.string.details_play),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (canResume) {
                OutlinedButton(
                    onClick = onPlayFromStart,
                    enabled = playable,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("game-details-play-from-start"),
                ) {
                    Icon(Icons.Filled.Replay, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.a9_play_from_start), modifier = Modifier.padding(start = 8.dp))
                }
                Text(
                    stringResource(R.string.a9_resume_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().testTag("game-details-resume-hint"),
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
            title = entry.displayTitle,
            onConfirm = {
                confirmHide = false
                onHide()
            },
            onDismiss = { confirmHide = false },
        )
    }
}

/**
 * «Pokémon › 2ª generación · archivo.gb». En la raíz, solo el archivo, o «Carpeta principal · archivo» si [inRoot] lo pide
 * (en «También en», donde hace falta decir dónde está).
 */
@Composable
internal fun locationText(location: RomLocation, inRoot: String?): String = when {
    location.folderPath.isNotEmpty() ->
        stringResource(R.string.n1_location, location.folderPath.joinToString(" › "), location.fileName)
    inRoot != null -> stringResource(R.string.n1_location, inRoot, location.fileName)
    else -> location.fileName
}

/** N1a: las otras copias del juego (misma huella) y qué comparten. */
@Composable
private fun AlsoIn(copies: List<RomLocation>) {
    val root = stringResource(R.string.n1_root_folder)
    Column(
        Modifier.fillMaxWidth().padding(top = 4.dp).testTag("game-details-also-in").semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DuplicateBadge()
            Text(
                stringResource(R.string.n1_also_in),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        copies.forEach { copy ->
            Text(
                locationText(copy, inRoot = root),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (LocalLargeFont.current) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            stringResource(R.string.n1_also_in_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        val settingsDescription = stringResource(R.string.details_settings_button)
        OutlinedButton(
            onClick = onOpenSettings,
            modifier = modifier.heightIn(min = 48.dp).testTag("game-details-settings")
                .semantics { contentDescription = settingsDescription },
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
                contentDescription = stringResource(R.string.details_problem),
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
                    Text(stringResource(R.string.details_reading_metadata), style = MaterialTheme.typography.bodyMedium)
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
    Fact(stringResource(R.string.details_fact_cartridge), details.cartridge)
    Fact("ROM", ByteFormat.format(details.romBytes.toLong()))
    Fact(
        stringResource(R.string.details_fact_save),
        buildString {
            append(if (details.sramBytes == 0) stringResource(R.string.details_no_ram) else ByteFormat.format(details.sramBytes.toLong()))
            if (details.hasBattery) append(stringResource(R.string.details_battery_suffix))
            if (details.hasRtc) append(stringResource(R.string.details_rtc_suffix))
        },
    )
    ChecksumFact("Checksum de cabecera", details.headerChecksumOk, "Correcto", "Incorrecto")
    ChecksumFact("Checksum global", details.globalChecksumOk, "Correcto", "No coincide (la consola real lo ignora)")
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(stringResource(R.string.details_sha_label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

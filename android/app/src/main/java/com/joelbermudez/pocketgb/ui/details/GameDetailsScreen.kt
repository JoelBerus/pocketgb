package com.joelbermudez.pocketgb.ui.details

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.ui.a11y.LocalLargeFont
import com.joelbermudez.pocketgb.library.ByteFormat
import com.joelbermudez.pocketgb.library.CategoryPaths
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
import com.joelbermudez.pocketgb.ui.components.MovedBadge
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
    /** N4: «Partida» del centro de ajustes del juego (Ajustes › Partidas de esa huella). */
    onOpenSaves: ((String) -> Unit)? = null,
    /** N6: «Momentos» del juego (con su id). `null` = el botón deshabilitado de antes. */
    onOpenMoments: ((String) -> Unit)? = null,
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
        onOpenMoments = onOpenMoments?.let { open -> { open(entry.id) } },
        progress = if (fingerprint != null && prefs.hasConfirmedFingerprint(entry)) {
            { com.joelbermudez.pocketgb.ui.progress.ProgressHost(fingerprint, editable = false) }
        } else {
            null
        },
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
        onOpenSaves = onOpenSaves,
        onHidden = onBack,
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
    /** Solo el catálogo de capturas: la información arranca desplazada estos px (se limita al final). */
    initialInfoScroll: Int = 0,
    /** N6: abre «Momentos»; `null` = botón deshabilitado («Próximamente», catálogos anteriores). */
    onOpenMoments: (() -> Unit)? = null,
    /** N6: panel de progreso (tiempo, hitos, lector Pokémon) bajo las estadísticas; `null` = sin panel. */
    progress: (@Composable () -> Unit)? = null,
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
        // N3a: dos columnas si el espacio es más ancho que alto o ≥ 600 dp (imagen a la izquierda limitada en alto, la
        // información con su propio scroll a la derecha y «Jugar» visible sin desplazar); si no, una columna con la imagen
        // como mucho al 45 % del alto. La proporción es la de la consola.
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val ratio = entry.screenAspectRatio
            val layout = detailLayoutFor(maxWidth.value, maxHeight.value, ratio)
            val artwork: @Composable (Modifier) -> Unit = { artworkModifier ->
                GameArtwork(
                    entry,
                    artworkKey,
                    artworkModifier.width(layout.artworkWidthDp.dp).testTag("game-details-artwork"),
                    shape = MaterialTheme.shapes.large,
                    aspectRatio = ratio,
                    fit = true,
                )
            }
            val info: @Composable () -> Unit = {
                DetailsInfo(
                    entry = entry,
                    load = load,
                    favorite = favorite,
                    lastPlayedAt = lastPlayedAt,
                    onPlay = onPlay,
                    onToggleFavorite = onToggleFavorite,
                    onOpenSettings = onOpenSettings,
                    canResume = canResume,
                    onPlayFromStart = onPlayFromStart,
                    onHide = { confirmHide = true },
                    onOpenMoments = onOpenMoments,
                    progress = progress,
                    // «Jugar» siempre justo bajo el título (H3): con un nombre o una ruta largos, la ruta y «También en» lo
                    // empujaban fuera de la pantalla (en dos columnas y también en vertical). En dos columnas el título
                    // ocupa como mucho 2 líneas (completo en la barra superior y en «Renombrar»).
                    playFirst = true,
                    titleMaxLines = if (layout.twoColumns) 2 else Int.MAX_VALUE,
                )
            }
            val scroll = rememberScrollState(initialInfoScroll)
            if (layout.twoColumns) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = DETAIL_MARGIN_DP.dp).testTag("game-details-two-columns"),
                    horizontalArrangement = Arrangement.spacedBy(DETAIL_COLUMN_SPACING_DP.dp),
                ) {
                    Box(Modifier.padding(vertical = (DETAIL_VERTICAL_PADDING_DP / 2).dp)) { artwork(Modifier) }
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(scroll)
                            .padding(vertical = (DETAIL_VERTICAL_PADDING_DP / 2).dp)
                            .testTag("game-details-info"),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) { info() }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        .padding(horizontal = DETAIL_MARGIN_DP.dp, vertical = 8.dp)
                        .testTag("game-details-info"),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    artwork(Modifier.align(Alignment.CenterHorizontally))
                    info()
                }
            }
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

/** Cabecera, Jugar/Continuar, estadísticas, acciones, información técnica y ocultar (columna derecha o bajo la imagen). */
@Composable
private fun DetailsInfo(
    entry: RomEntry,
    load: DetailsLoad,
    favorite: Boolean,
    lastPlayedAt: Long?,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenSettings: () -> Unit,
    canResume: Boolean,
    onPlayFromStart: () -> Unit,
    onHide: () -> Unit,
    playFirst: Boolean = false,
    titleMaxLines: Int = Int.MAX_VALUE,
    onOpenMoments: (() -> Unit)? = null,
    progress: (@Composable () -> Unit)? = null,
) {
    val title: @Composable () -> Unit = {
        Text(
            entry.displayTitle,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = titleMaxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("game-details-title"),
        )
    }
    if (playFirst) {
        title()
        PlayActions(entry, load, canResume, onPlay, onPlayFromStart)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!playFirst) title()
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
                ConsoleChip(entry.console)
                locationText()
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConsoleChip(entry.console)
                locationText()
            }
        }
        if (entry.isMovedInApp) ShownIn(entry.categoryPath)
        if (entry.tags.isNotEmpty()) DetailTags(entry.tags)
        if (entry.isDuplicate) AlsoIn(entry.alsoAt)
    }

    val problemMessage = entry.problem?.message ?: (load as? DetailsLoad.Failed)?.error?.message
    if (problemMessage != null) ProblemCard(problemMessage)

    if (!playFirst) PlayActions(entry, load, canResume, onPlay, onPlayFromStart)

    Stats(entry, lastPlayedAt)
    SecondaryActions(favorite, onToggleFavorite, onOpenSettings, onOpenMoments)
    progress?.invoke()
    TechnicalInfo(entry, load)

    OutlinedButton(
        onClick = onHide,
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

/** «Jugar» o «Continuar», y con «Continuar» también «Jugar desde el inicio» y su explicación. */
@Composable
private fun PlayActions(
    entry: RomEntry,
    load: DetailsLoad,
    canResume: Boolean,
    onPlay: () -> Unit,
    onPlayFromStart: () -> Unit,
) {
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

/** N4 (ND3): el juego se ve en otra categoría que la de su carpeta (la ruta de arriba es la del archivo). */
@Composable
private fun ShownIn(path: List<String>) {
    val where = if (path.isEmpty()) stringResource(R.string.n3_category_root) else CategoryPaths.display(path)
    Row(
        Modifier.semantics(mergeDescendants = true) {}.testTag("game-details-shown-in"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MovedBadge()
        Text(
            stringResource(R.string.n4_details_shown_in, where),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/** N4: las etiquetas del juego (se cambian en sus ajustes). */
@Composable
private fun DetailTags(tags: List<String>) {
    val description = stringResource(R.string.n4_details_tags, tags.joinToString(", "))
    // Como texto (no son botones, H2): se cambian en los ajustes del juego.
    Text(
        tagsText(tags),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clearAndSetSemantics { contentDescription = description }.testTag("game-details-tags"),
    )
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

/**
 * Favorito, Estados (aún no) y Ajustes del juego. N3a: en una fila con el icono al lado si caben los tres textos enteros
 * (se miden con la fuente real); si no, en una fila con el icono encima (antes se cortaban en un teléfono de 360 dp:
 * «Favori», «Estad», «Ajust»); con fuente grande (R9), apilados a todo el ancho.
 */
@Composable
private fun SecondaryActions(
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMoments: (() -> Unit)? = null,
) {
    val favoriteLabel = stringResource(if (favorite) R.string.menu_favorite_remove else R.string.menu_favorite_add)
    val soon = stringResource(R.string.details_states_soon)
    val settingsDescription = stringResource(R.string.details_settings_button)
    val labels = listOf(
        stringResource(R.string.game_favorite),
        stringResource(if (onOpenMoments != null) R.string.n6_details_moments else R.string.details_states),
        stringResource(R.string.details_settings),
    )
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelLarge
    val density = LocalDensity.current
    val textsWidth = remember(labels, labelStyle, density) {
        with(density) { labels.sumOf { measurer.measure(it, labelStyle, maxLines = 1).size.width }.toDp() }
    }
    val gaps = SecondaryActionsSpacing * (labels.size - 1)
    val largeFont = LocalLargeFont.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val style = when {
            largeFont -> SecondaryStyle.STACKED
            maxWidth >= textsWidth + SecondaryInlineChrome * labels.size + gaps -> SecondaryStyle.INLINE
            maxWidth >= textsWidth + SecondaryCompactChrome * labels.size + gaps -> SecondaryStyle.COMPACT
            else -> SecondaryStyle.STACKED
        }
        val button: @Composable (
            Modifier, String, ImageVector, Boolean, String, androidx.compose.ui.semantics.SemanticsPropertyReceiver.() -> Unit, () -> Unit,
        ) -> Unit = { modifier, label, icon, enabled, tag, semantics, onClick ->
            OutlinedButton(
                onClick = onClick,
                enabled = enabled,
                modifier = modifier.heightIn(min = 48.dp).testTag(tag).semantics(properties = semantics),
                // Con el icono encima, esquinas redondeadas en vez de pastilla (el botón es más alto).
                shape = if (style == SecondaryStyle.COMPACT) MaterialTheme.shapes.large else ButtonDefaults.outlinedShape,
                contentPadding = if (style == SecondaryStyle.COMPACT) {
                    PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                } else {
                    ButtonDefaults.ButtonWithIconContentPadding
                },
            ) {
                if (style == SecondaryStyle.COMPACT) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                        Text(label, maxLines = 1)
                    }
                } else {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(label, modifier = Modifier.padding(start = 6.dp), maxLines = 1)
                }
            }
        }
        val buttons: @Composable (Modifier) -> Unit = { modifier ->
            button(
                modifier, labels[0], if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder, true, "game-details-favorite",
                { contentDescription = favoriteLabel }, onToggleFavorite,
            )
            if (onOpenMoments != null) {
                button(modifier, labels[1], Icons.Outlined.ViewAgenda, true, "game-details-states", {}, onOpenMoments)
            } else {
                button(modifier, labels[1], Icons.Outlined.ViewAgenda, false, "game-details-states", { stateDescription = soon }, {})
            }
            button(
                modifier, labels[2], Icons.Outlined.Tune, true, "game-details-settings",
                { contentDescription = settingsDescription }, onOpenSettings,
            )
        }
        if (style == SecondaryStyle.STACKED) {
            Column(
                Modifier.fillMaxWidth().testTag("game-details-actions-stacked"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { buttons(Modifier.fillMaxWidth()) }
        } else {
            Row(
                Modifier.fillMaxWidth().testTag("game-details-actions-${style.name.lowercase()}"),
                horizontalArrangement = Arrangement.spacedBy(SecondaryActionsSpacing),
            ) { buttons(Modifier.weight(1f)) }
        }
    }
}

private enum class SecondaryStyle { INLINE, COMPACT, STACKED }

private val SecondaryActionsSpacing = 8.dp

/** Lo que ocupa un botón con el icono al lado además de su texto: márgenes 16 + 24, icono 18, separación 6 y aire. */
private val SecondaryInlineChrome = 68.dp

/** Con el icono encima: márgenes 8 + 8 y aire. */
private val SecondaryCompactChrome = 24.dp

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

/**
 * «Información técnica» (datos del núcleo): cartucho, ROM, partida (RAM · batería · reloj), checksums y SHA-256
 * seleccionable. Plegable (N3a, como el `DisclosureGroup` que iOS adopta); desplegada de entrada para no esconder lo que
 * Android ya mostraba. El estado se conserva al girar.
 */
@Composable
private fun TechnicalInfo(entry: RomEntry, load: DetailsLoad) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    val stateText = stringResource(if (expanded) R.string.n3_details_technical_expanded else R.string.n3_details_technical_collapsed)
    val actionLabel = stringResource(if (expanded) R.string.n3_details_technical_collapse else R.string.n3_details_technical_expand)
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClickLabel = actionLabel, role = Role.Button) { expanded = !expanded }
                .semantics(mergeDescendants = true) { stateDescription = stateText }
                .testTag("game-details-technical-toggle"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.n3_details_technical),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
        }
        if (expanded) Facts(entry, load)
    }
}

@Composable
private fun Facts(entry: RomEntry, load: DetailsLoad) {
    Column(Modifier.testTag("game-details-technical")) {
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
    val gba = details.gba
    if (gba != null) {
        // N8 (= iOS `GameTechnicalInfo`): tipo de partida detectado, código de juego y reloj; sin checksum global.
        Fact(stringResource(R.string.n8_details_fact_save_type), stringResource(R.string.n8_details_detected, details.cartridge))
        gba.codeLine?.let { Fact(stringResource(R.string.n8_details_fact_game_code), it) }
        Fact("ROM", ByteFormat.format(details.romBytes.toLong()))
        Fact(stringResource(R.string.details_fact_save), details.saveDescription)
        Fact(
            stringResource(R.string.n8_details_fact_rtc),
            stringResource(if (details.hasRtc) R.string.n8_details_rtc_yes else R.string.n8_details_rtc_no),
        )
        ChecksumFact("Checksum de cabecera", details.headerChecksumOk, "Correcto", "Incorrecto")
    } else {
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
    }
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

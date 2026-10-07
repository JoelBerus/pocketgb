package com.joelbermudez.pocketgb.ui.details

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.emulator.GbaBiosStatus
import com.joelbermudez.pocketgb.emulator.GbaRtc
import com.joelbermudez.pocketgb.emulator.GbaSaveType
import com.joelbermudez.pocketgb.library.DetailsLoad
import com.joelbermudez.pocketgb.library.RomConsole
import com.joelbermudez.pocketgb.library.LibraryQuery
import com.joelbermudez.pocketgb.library.LibraryState
import com.joelbermudez.pocketgb.library.LibraryTree
import com.joelbermudez.pocketgb.library.LibraryViewModel
import com.joelbermudez.pocketgb.library.tagOptions
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.settings.GameOverrides
import com.joelbermudez.pocketgb.settings.GameplaySettingsData
import com.joelbermudez.pocketgb.settings.GameplaySettingsRepository
import com.joelbermudez.pocketgb.ui.components.HideGameDialog
import com.joelbermudez.pocketgb.ui.components.RenameGameHost
import com.joelbermudez.pocketgb.ui.settings.SettingRowLabel
import com.joelbermudez.pocketgb.ui.settings.compatPaletteOptions
import com.joelbermudez.pocketgb.ui.settings.compatPaletteTitle

/**
 * Aloja el centro de ajustes de [entry] (o nada si es `null`). Los ajustes por juego, las etiquetas y la categoría
 * virtual se guardan por huella: si aún no se conoce, o es una heredada sin confirmar (N1-H1), se calcula leyendo el ROM
 * antes de dejar cambiarlos (K6). [onOpenSaves] lleva a Ajustes › Partidas de esa huella; [onHidden] se llama tras
 * ocultar el juego (el detalle vuelve atrás).
 */
@Composable
fun GameSettingsHost(
    entry: RomEntry?,
    library: LibraryViewModel,
    repository: GameplaySettingsRepository,
    onDismiss: () -> Unit,
    onOpenSaves: ((String) -> Unit)? = null,
    onHidden: () -> Unit = {},
) {
    if (entry == null) return
    val prefs by library.prefs.collectAsStateWithLifecycle()
    val state by library.state.collectAsStateWithLifecycle()
    val settings by repository.state.collectAsStateWithLifecycle()
    var unavailable by remember(entry.id) { mutableStateOf(false) }
    var renaming by remember(entry.id) { mutableStateOf(false) }
    var picking by remember(entry.id) { mutableStateOf(false) }
    var editingTags by remember(entry.id) { mutableStateOf(false) }
    var confirmHide by remember(entry.id) { mutableStateOf(false) }
    var saveFailed by remember(entry.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // H15: si la huella dejó de estar confirmada entre abrir y aplicar, se vuelve a confirmar leyendo el ROM y se
    // reintenta; si aun así no se puede, se avisa en el centro (nunca se cierra en silencio sin guardar).
    val persist: (() -> Boolean) -> Unit = { change ->
        scope.launch {
            saveFailed = !(change() || library.confirmFingerprint(entry) && change())
        }
    }
    val fingerprint = prefs.fingerprints[entry.id]
    // N1-H1: una huella heredada de un movimiento (sin leer el ROM) se confirma antes de leer o escribir ajustes.
    val confirmed = fingerprint != null && prefs.hasConfirmedFingerprint(entry)
    LaunchedEffect(entry.id, confirmed) {
        if (!confirmed && library.loadDetails(entry.id) is DetailsLoad.Failed) unavailable = true
    }
    val entries = when (val current = state) {
        is LibraryState.Ready -> current.entries
        is LibraryState.Scanning -> current.previous
        else -> emptyList()
    }
    val shown = remember(entries, prefs, entry.id) { LibraryQuery.presented(entries, prefs, entry.id) } ?: prefs.withAlias(entry)
    val center = GameCenterState(
        categoryPath = shown.categoryPath,
        folderPath = entry.folderPath,
        moved = shown.isMovedInApp,
        tags = shown.tags,
        enabled = confirmed,
        onChangeCategory = { picking = true },
        onReturnToFolder = { persist { library.returnToFolder(entry) } },
        onEditTags = { editingTags = true },
        onOpenSaves = if (onOpenSaves != null && confirmed && fingerprint != null) {
            {
                onDismiss()
                onOpenSaves(fingerprint)
            }
        } else {
            null
        },
        onHide = { confirmHide = true },
        saveFailed = saveFailed,
        cover = rememberCoverCenter(entry, fingerprint?.takeIf { confirmed }),
        title = shown.displayTitle,
    )
    val gbaInfo = if (entry.isGba) rememberGbaSettingsInfo(fingerprint?.takeIf { confirmed }) else GbaSettingsInfo()
    GameSettingsSheet(
        title = shown.displayTitle,
        headerTitle = entry.title,
        onRename = { renaming = true },
        console = entry.console,
        gbaInfo = gbaInfo,
        global = settings,
        overrides = fingerprint?.takeIf { confirmed }?.let { settings.perGame[it] } ?: GameOverrides(),
        onOverridesChange = { next ->
            if (confirmed && fingerprint != null) repository.update { it.setOverrides(fingerprint, next) }
        },
        onDismiss = onDismiss,
        loading = !confirmed && !unavailable,
        unavailable = !confirmed && unavailable,
        center = center,
    )
    RenameGameHost(
        entry = shown.takeIf { renaming },
        prefs = prefs,
        onSetAlias = library::setAlias,
        onDismiss = { renaming = false },
    )
    if (picking) {
        val known = remember(entries, prefs) { LibraryTree.knownPaths(entries, prefs) }
        CategoryPickerDialog(
            current = shown.categoryPath,
            folder = entry.folderPath,
            moved = shown.isMovedInApp,
            known = known,
            onPick = { path ->
                persist { library.moveToCategory(entry, path) }
                picking = false
            },
            onReturnToFolder = {
                persist { library.returnToFolder(entry) }
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
    if (editingTags) {
        val suggestions = remember(entries, prefs) { LibraryQuery.tagOptions(entries, prefs).map { it.tag } }
        TagEditorDialog(
            title = shown.displayTitle,
            tags = shown.tags,
            suggestions = suggestions,
            onAdd = { tag -> persist { library.addTag(entry, tag) } },
            onRemove = { tag -> persist { library.removeTag(entry, tag) } },
            onDismiss = { editingTags = false },
        )
    }
    if (confirmHide) {
        HideGameDialog(
            title = shown.displayTitle,
            onConfirm = {
                confirmHide = false
                library.hide(entry)
                onDismiss()
                onHidden()
            },
            onDismiss = { confirmHide = false },
        )
    }
}

/**
 * N4 · lo que el centro de ajustes muestra además del nombre y del color: categoría (virtual, ND3), etiquetas, portada y
 * progreso (próximamente, N5/N6), partida (Ajustes › Partidas de la huella) y ocultar. [enabled] = la huella está
 * confirmada (categoría y etiquetas se guardan por huella).
 */
class GameCenterState(
    val categoryPath: List<String>,
    val folderPath: List<String>,
    val moved: Boolean,
    val tags: List<String>,
    val enabled: Boolean,
    val onChangeCategory: () -> Unit,
    val onReturnToFolder: () -> Unit,
    val onEditTags: () -> Unit,
    val onOpenSaves: (() -> Unit)?,
    val onHide: () -> Unit,
    /** H15: el último cambio no se pudo guardar (no se pudo confirmar la huella). */
    val saveFailed: Boolean = false,
    /** N5: «Portada»; `null` = la fila «próximamente» de antes (catálogos de N4/N8). */
    val cover: CoverCenterState? = null,
    /** N5: nombre del juego (título del diálogo de portada). */
    val title: String = "",
)

/**
 * Centro de ajustes de un juego (N4; antes, K8, solo color y paleta): nombre, categoría, etiquetas, portada, progreso,
 * partida, color y paleta y ocultar. En los ajustes del juego cada valor dice con texto si es «Global» o
 * «Personalizado» y se aplica al abrir el juego. Un juego de Game Boy Color va siempre en color: su sección está
 * deshabilitada. Sin [center] es la hoja de antes (catálogo y pruebas de A6).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameSettingsSheet(
    title: String,
    /** N8: GB/GBC (color y paleta) o GBA (tipo de partida, reloj y BIOS). */
    console: RomConsole,
    global: GameplaySettingsData,
    overrides: GameOverrides,
    onOverridesChange: (GameOverrides) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    unavailable: Boolean = false,
    /** A9: título de la cabecera (se muestra bajo el nombre si el juego está renombrado). */
    headerTitle: String? = null,
    /** A9: fila «Nombre» con «Renombrar»; `null` la oculta. */
    onRename: (() -> Unit)? = null,
    /** N4: el resto del centro de ajustes; `null` = solo nombre y color. */
    center: GameCenterState? = null,
    /** Solo el catálogo de capturas: el contenido arranca desplazado estos px (se limita al final). */
    initialScroll: Int = 0,
    /** N8: lo detectado del juego de GBA y el estado de la BIOS (para «Detectado (…)» y la fila de BIOS). */
    gbaInfo: GbaSettingsInfo = GbaSettingsInfo(),
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier.testTag("game-settings"),
    ) {
        val scroll = rememberScrollState(initialScroll)
        if (initialScroll > 0) {
            // Solo el catálogo: la hoja crece mientras se abre y el primer recorte del desplazamiento se queda corto; se
            // vuelve a pedir cuando ya tiene su alto final.
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(600)
                scroll.scrollTo(minOf(initialScroll, scroll.maxValue))
            }
        }
        Column(Modifier.verticalScroll(scroll).padding(bottom = 24.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.game_settings_title, title),
                    modifier = Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                    // N4: con fuente grande, el nombre entero (A7 R9).
                    maxLines = if (com.joelbermudez.pocketgb.ui.a11y.LocalLargeFont.current) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("game-settings-done")) {
                    Text(stringResource(R.string.game_settings_done))
                }
            }
            if (onRename != null) NameRow(title, headerTitle, onRename)
            if (center != null) {
                CenterStatus(loading, unavailable, center.saveFailed)
                GameCenterRows(center)
                if (!loading && !unavailable) {
                    CenterSectionHeader(
                        stringResource(if (console == RomConsole.GBA) R.string.n8_center_gba_header else R.string.n4_center_color_header),
                    )
                    SettingsBody(console, global, overrides, onOverridesChange, gbaInfo)
                }
                HideRow(center.onHide)
                Text(
                    stringResource(R.string.n4_center_footer),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                when {
                    loading -> Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.game_settings_loading))
                    }
                    unavailable -> Text(
                        stringResource(R.string.game_settings_unavailable),
                        modifier = Modifier.padding(16.dp).testTag("game-settings-unavailable"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> SettingsBody(console, global, overrides, onOverridesChange, gbaInfo)
                }
            }
        }
    }
}

/** «Nombre»: el nombre visible del juego (alias o cabecera) y «Renombrar» (A9). Solo presentación. */
@Composable
private fun NameRow(title: String, headerTitle: String?, onRename: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.a9_game_settings_name)) },
        supportingContent = {
            Column {
                Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (headerTitle != null && headerTitle != title) {
                    Text(
                        stringResource(R.string.a9_game_settings_name_header, headerTitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        trailingContent = {
            TextButton(onClick = onRename, modifier = Modifier.heightIn(min = 48.dp).testTag("game-settings-rename")) {
                Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.a9_rename), modifier = Modifier.padding(start = 6.dp))
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.testTag("game-settings-name"),
    )
}

@Composable
private fun SettingsBody(
    console: RomConsole,
    global: GameplaySettingsData,
    overrides: GameOverrides,
    onOverridesChange: (GameOverrides) -> Unit,
    gbaInfo: GbaSettingsInfo,
) {
    if (console == RomConsole.GBA) {
        GbaSettingsBody(overrides, onOverridesChange, gbaInfo)
        return
    }
    val isColor = console == RomConsole.GBC
    val effectiveColor = overrides.colorForGameBoy ?: global.colorForGameBoy
    Text(
        stringResource(if (isColor) R.string.game_settings_header_gbc else R.string.game_settings_header_dmg),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("game-settings-header"),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(Modifier.alpha(if (isColor) 0.5f else 1f)) {
        GameSettingRow(
            title = stringResource(R.string.game_settings_color),
            customized = overrides.colorForGameBoy != null,
            options = listOf(
                null to stringResource(
                    if (global.colorForGameBoy) R.string.game_settings_global_color_on else R.string.game_settings_global_color_off,
                ),
                true to stringResource(R.string.game_settings_color_on),
                false to stringResource(R.string.game_settings_color_off),
            ),
            selected = overrides.colorForGameBoy,
            onSelect = { onOverridesChange(overrides.copy(colorForGameBoy = it)) },
            enabled = !isColor,
            tag = "game-setting-color",
        )
        GameSettingRow(
            title = stringResource(R.string.game_settings_palette),
            customized = overrides.compatPalette != null,
            options = listOf<Pair<Int?, String>>(
                null to stringResource(R.string.game_settings_global_palette, compatPaletteTitle(global.compatPalette)),
            ) + compatPaletteOptions().map { (id, name) -> id to name },
            selected = overrides.compatPalette,
            onSelect = { onOverridesChange(overrides.copy(compatPalette = it)) },
            enabled = !isColor && effectiveColor,
            tag = "game-setting-palette",
        )
    }
    Text(
        stringResource(R.string.game_settings_footer),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (!overrides.isEmpty) {
        OutlinedButton(
            onClick = { onOverridesChange(GameOverrides()) },
            modifier = Modifier.padding(horizontal = 16.dp).heightIn(min = 48.dp).testTag("game-settings-reset"),
        ) {
            Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.game_settings_reset), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** Fila de elección con el origen del valor («Global»/«Personalizado») en texto y un menú desplegable. */
@Composable
private fun <T> GameSettingRow(
    title: String,
    customized: Boolean,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    enabled: Boolean,
    tag: String,
) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == selected }?.second.orEmpty()
    Box {
        ListItem(
            headlineContent = { SettingRowLabel(title, customized) },
            supportingContent = { Text(current) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .heightIn(min = 56.dp)
                .clickable(enabled = enabled, role = Role.DropdownList) { open = true }
                .testTag(tag),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { index, (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        open = false
                        onSelect(value)
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("$tag-$index"),
                )
            }
        }
    }
}

/**
 * N8: lo que los ajustes de un juego de GBA muestran además de los valores: el medio y el reloj que detectó el núcleo
 * la última vez que se abrió sin ajustes forzados (`SavesIndex`, = iOS `gbaMedia`/`gbaHasRTC`) y el estado de la BIOS
 * de la carpeta (`gba_bios.bin`). `null` = aún no se sabe.
 */
data class GbaSettingsInfo(
    val detectedMedia: String? = null,
    val detectedRtc: Boolean? = null,
    val biosStatus: GbaBiosStatus? = null,
)

/** Tipos de partida que se pueden forzar (= iOS `gbaSaveTypes`); sin forzar, el núcleo detecta el tipo. */
internal val GBA_SAVE_TYPE_CHOICES: List<GbaSaveType> = listOf(
    GbaSaveType.NONE, GbaSaveType.SRAM, GbaSaveType.FLASH64, GbaSaveType.FLASH128, GbaSaveType.EEPROM512, GbaSaveType.EEPROM8K,
)

@Composable
internal fun gbaSaveTypeTitle(type: GbaSaveType): String = stringResource(
    when (type) {
        GbaSaveType.NONE -> R.string.n8_save_type_none
        GbaSaveType.SRAM -> R.string.n8_save_type_sram
        GbaSaveType.FLASH64 -> R.string.n8_save_type_flash64
        GbaSaveType.FLASH128 -> R.string.n8_save_type_flash128
        GbaSaveType.EEPROM512 -> R.string.n8_save_type_eeprom512
        GbaSaveType.EEPROM8K -> R.string.n8_save_type_eeprom8k
        GbaSaveType.AUTO -> R.string.n8_save_type_detected
    },
)

/** Lee en segundo plano lo detectado de [fingerprint] y el estado de la BIOS para [GbaSettingsInfo]. */
@Composable
private fun rememberGbaSettingsInfo(fingerprint: String?): GbaSettingsInfo {
    val context = androidx.compose.ui.platform.LocalContext.current
    var info by remember(fingerprint) { mutableStateOf(GbaSettingsInfo()) }
    LaunchedEffect(fingerprint) {
        info = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val record = fingerprint?.let {
                com.joelbermudez.pocketgb.saves.SavesIndex(java.io.File(context.filesDir, "saves")).load()[it]
            }
            GbaSettingsInfo(record?.gbaMedia, record?.gbaHasRtc, com.joelbermudez.pocketgb.library.GbaBiosSource.status(context))
        }
    }
    return info
}

@Composable
private fun GbaSettingsBody(overrides: GameOverrides, onOverridesChange: (GameOverrides) -> Unit, info: GbaSettingsInfo) {
    Text(
        stringResource(R.string.n8_game_settings_header_gba),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("game-settings-header"),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val detectedMedia = info.detectedMedia?.let { stringResource(R.string.n8_detected_with, it) }
        ?: stringResource(R.string.n8_save_type_detected)
    GameSettingRow(
        title = stringResource(R.string.n8_game_setting_save_type),
        customized = overrides.gbaSaveType != null,
        options = listOf<Pair<Int?, String>>(null to detectedMedia) +
            GBA_SAVE_TYPE_CHOICES.map { it.native to gbaSaveTypeTitle(it) },
        selected = overrides.gbaSaveType,
        onSelect = { onOverridesChange(overrides.copy(gbaSaveType = it)) },
        enabled = true,
        tag = "game-setting-gba-save",
    )
    val detectedRtc = info.detectedRtc?.let {
        stringResource(R.string.n8_detected_with, stringResource(if (it) R.string.n8_rtc_with_lower else R.string.n8_rtc_without_lower))
    } ?: stringResource(R.string.n8_save_type_detected)
    GameSettingRow(
        title = stringResource(R.string.n8_game_setting_rtc),
        customized = overrides.gbaRtc != null,
        options = listOf<Pair<Int?, String>>(
            null to detectedRtc,
            GbaRtc.ON.native to stringResource(R.string.n8_rtc_on),
            GbaRtc.OFF.native to stringResource(R.string.n8_rtc_off),
        ),
        selected = overrides.gbaRtc,
        onSelect = { onOverridesChange(overrides.copy(gbaRtc = it)) },
        enabled = true,
        tag = "game-setting-gba-rtc",
    )
    GameSettingRow(
        title = stringResource(R.string.n8_game_setting_bios),
        customized = overrides.gbaUseBios != null,
        options = listOf<Pair<Boolean?, String>>(
            null to stringResource(R.string.n8_bios_global),
            false to stringResource(R.string.n8_bios_emulated),
        ),
        selected = overrides.gbaUseBios,
        onSelect = { onOverridesChange(overrides.copy(gbaUseBios = it)) },
        enabled = true,
        tag = "game-setting-gba-bios",
    )
    info.biosStatus?.let { status ->
        Text(
            gbaBiosStatusText(status),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("game-settings-bios-status"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text(
        stringResource(R.string.n8_game_settings_gba_footer),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (overrides.gbaSaveType != null || overrides.gbaRtc != null || overrides.gbaUseBios != null) {
        OutlinedButton(
            onClick = { onOverridesChange(overrides.copy(gbaSaveType = null, gbaRtc = null, gbaUseBios = null)) },
            modifier = Modifier.padding(horizontal = 16.dp).heightIn(min = 48.dp).testTag("game-settings-reset"),
        ) {
            Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.n8_game_settings_gba_reset), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** Estado de la BIOS de la carpeta en texto (= iOS `BIOSFile.Status.settingsText`). */
@Composable
fun gbaBiosStatusText(status: GbaBiosStatus): String = stringResource(
    when (status) {
        GbaBiosStatus.ABSENT -> R.string.n8_bios_status_absent
        GbaBiosStatus.VALID -> R.string.n8_bios_status_valid
        GbaBiosStatus.INVALID -> R.string.n8_bios_status_invalid
    },
)

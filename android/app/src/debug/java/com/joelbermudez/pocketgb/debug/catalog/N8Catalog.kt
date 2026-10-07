package com.joelbermudez.pocketgb.debug.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.joelbermudez.pocketgb.app.AppNavigationState
import com.joelbermudez.pocketgb.app.AppScaffold
import com.joelbermudez.pocketgb.debug.DebugIntent
import com.joelbermudez.pocketgb.emulator.GbaBiosStatus
import com.joelbermudez.pocketgb.emulator.GbaRtc
import com.joelbermudez.pocketgb.emulator.GbaSaveType
import com.joelbermudez.pocketgb.game.StatesUi
import com.joelbermudez.pocketgb.input.ControlId
import com.joelbermudez.pocketgb.library.DetailsLoad
import com.joelbermudez.pocketgb.library.GameDetails
import com.joelbermudez.pocketgb.library.GbaDetails
import com.joelbermudez.pocketgb.library.LibraryFilter
import com.joelbermudez.pocketgb.library.LibraryPreferencesData
import com.joelbermudez.pocketgb.library.LibraryState
import com.joelbermudez.pocketgb.library.RomConsole
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.library.artwork.ArtworkStore
import com.joelbermudez.pocketgb.saves.FramePng
import com.joelbermudez.pocketgb.saves.SaveLoadWarning
import com.joelbermudez.pocketgb.saves.StateSlot
import com.joelbermudez.pocketgb.saves.StateStore
import com.joelbermudez.pocketgb.settings.GameOverrides
import com.joelbermudez.pocketgb.settings.GameplaySettingsData
import com.joelbermudez.pocketgb.ui.components.LocalArtworkStore
import com.joelbermudez.pocketgb.ui.details.GameCenterState
import com.joelbermudez.pocketgb.ui.details.GameDetailsContent
import com.joelbermudez.pocketgb.ui.details.GameSettingsSheet
import com.joelbermudez.pocketgb.ui.details.GbaSettingsInfo
import com.joelbermudez.pocketgb.ui.gameplay.SaveLoadWarningDialog
import com.joelbermudez.pocketgb.ui.gameplay.StatesSheet
import com.joelbermudez.pocketgb.ui.library.GameActions
import com.joelbermudez.pocketgb.ui.library.LibraryContent
import com.joelbermudez.pocketgb.ui.library.LibraryPanel
import com.joelbermudez.pocketgb.ui.library.LibraryToolsPreset
import com.joelbermudez.pocketgb.ui.library.LocalLibraryToolsPreset
import com.joelbermudez.pocketgb.ui.settings.ControllerMappingContent
import com.joelbermudez.pocketgb.ui.settings.EmulationSettingsContent
import java.io.File

/**
 * N8 · biblioteca con juegos de Game Boy Advance (datos sintéticos; los títulos son de demostración, no hay ROMs):
 * ```
 * Roms/
 *   Kirby/Kirby - Nightmare in Dream Land.gba
 *   Pokémon/1ª generación/Pokemon Red.gb
 *   Pokémon/2ª generación/Pokemon Gold.gbc
 *   Pokémon/3ª generación/Pokemon Emerald.gba
 *   Homebrew/PGBA Demo.gba
 *   Tetra Blocks.gb
 * ```
 */
internal object N8Data {
    private fun entry(id: String, title: String, console: RomConsole, size: Long) = RomEntry(
        id = id,
        uri = "content://demo/n8/$id",
        fileName = id.substringAfterLast('/'),
        title = title,
        console = console,
        sizeBytes = size,
        headerChecksumOk = true,
        problem = null,
    )

    val kirby = entry("Kirby/Kirby - Nightmare in Dream Land.gba", "KIRBY DREAM", RomConsole.GBA, 8L shl 20)
    val red = entry("Pokémon/1ª generación/Pokemon Red.gb", "POKÉMON RED", RomConsole.GB, 1L shl 20)
    val gold = entry("Pokémon/2ª generación/Pokemon Gold.gbc", "POKÉMON GOLD", RomConsole.GBC, 2L shl 20)
    val emerald = entry("Pokémon/3ª generación/Pokemon Emerald.gba", "POKEMON EMER", RomConsole.GBA, 16L shl 20)
    val demo = entry("Homebrew/PGBA Demo.gba", "PGBA DEMO", RomConsole.GBA, 512L * 1024)
    val tetra = entry("Tetra Blocks.gb", "TETRA BLOCKS", RomConsole.GB, 32L * 1024)
    val entries = listOf(kirby, red, gold, emerald, demo, tetra)

    fun fingerprint(entry: RomEntry): String = CatalogData.fingerprint("n8/" + entry.id)

    fun prefs(now: Long = System.currentTimeMillis()): LibraryPreferencesData {
        val hour = 3_600_000L
        return LibraryPreferencesData(
            fingerprints = entries.associate { it.id to fingerprint(it) },
            knownIds = entries.map { it.id }.toSet(),
            lastPlayedByFingerprint = mapOf(fingerprint(kirby) to now - hour, fingerprint(red) to now - 4 * hour, fingerprint(emerald) to now - 30 * hour),
            favoriteFingerprints = setOf(fingerprint(kirby), fingerprint(gold)),
        )
    }

    /** Detalle de un juego de GBA: Flash 128 KiB con reloj (como un Pokémon de 3.ª generación). */
    fun details(entry: RomEntry) = GameDetails(
        entry = entry,
        cartridge = "Flash 128 KiB",
        romBytes = entry.sizeBytes.toInt(),
        sramBytes = 128 * 1024,
        hasBattery = true,
        hasRtc = true,
        headerChecksumOk = true,
        globalChecksumOk = true,
        fingerprint = fingerprint(entry),
        gba = GbaDetails(gameCode = "BPEE", makerCode = "01", version = 0, saveType = GbaSaveType.FLASH128, eeprom = false),
    )

    /** Captura de 240×160 (3:2) con bandas de color: deja ver que las portadas y estados de GBA no se deforman. */
    fun gbaPixels(seed: Int): IntArray = IntArray(240 * 160) { i ->
        val x = i % 240
        val y = i / 240
        val band = ((x / 30) + seed) % 8
        val r = (band * 32 + y) and 0xFF
        val g = (255 - band * 28) and 0xFF
        val b = ((x + seed * 40) / 2) and 0xFF
        // FramePng espera el orden del núcleo (R en el byte bajo).
        0xFF000000.toInt() or (b shl 16) or (g shl 8) or r
    }
}

@Composable
private fun N8Artwork(content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) {
        val dir = File(context.cacheDir, "debug-artwork-n8")
        dir.listFiles()?.forEach { it.delete() }
        ArtworkStore(dir, executor = null).also { store ->
            N8Data.entries.forEachIndexed { index, entry ->
                val pixels = if (entry.console == RomConsole.GBA) N8Data.gbaPixels(index) else CatalogData.coverPixels(index)
                store.save(N8Data.fingerprint(entry), pixels)
            }
        }
    }
    CompositionLocalProvider(LocalArtworkStore provides store, content = content)
}

@Composable
private fun N8Frame(content: @Composable () -> Unit) {
    val navigation = remember { AppNavigationState() }
    N8Artwork { AppScaffold(navigation) { content() } }
}

private val n8Actions = GameActions(
    onOpenDetails = {},
    onToggleFavorite = {},
    onHide = {},
    onPlay = {},
    onGameSettings = {},
    onPlayFromStart = {},
    onRename = {},
    canResume = { entry -> entry == N8Data.kirby || entry == N8Data.red },
)

@Composable
private fun N8Library(initialFilter: LibraryFilter = LibraryFilter.ALL, preset: LibraryToolsPreset = LibraryToolsPreset()) {
    var prefs by remember { mutableStateOf(N8Data.prefs()) }
    var filter by remember { mutableStateOf(initialFilter) }
    N8Frame {
        CompositionLocalProvider(LocalLibraryToolsPreset provides preset) {
            LibraryContent(
                state = LibraryState.Ready(N8Data.entries, "Roms"),
                prefs = prefs,
                query = "",
                filter = filter,
                onQueryChange = {},
                onFilterChange = { filter = it },
                onLayoutChange = { prefs = prefs.copy(layout = it) },
                onSortChange = { prefs = prefs.copy(sort = it) },
                onChooseFolder = {},
                onRescan = {},
                actions = n8Actions,
                artworkFingerprints = N8Data.entries.map(N8Data::fingerprint).toSet(),
                onOpenCategory = {},
            )
        }
    }
}

@Composable
private fun N8Details(entry: RomEntry = N8Data.emerald, infoScroll: Int = 0) {
    N8Frame {
        GameDetailsContent(
            entry = entry,
            load = DetailsLoad.Loaded(N8Data.details(entry)),
            favorite = false,
            lastPlayedAt = System.currentTimeMillis() - 30 * 3_600_000L,
            onPlay = {},
            onToggleFavorite = {},
            onHide = {},
            onBack = {},
            fingerprint = N8Data.fingerprint(entry),
            onOpenSettings = {},
            onRename = {},
            canResume = true,
            initialInfoScroll = infoScroll,
        )
    }
}

/** Desplazamiento (px a densidad 2,0 del AVD de capturas) que deja arriba la sección «Partida, reloj y BIOS». */
private const val GBA_SECTION_SCROLL = 1250

@Composable
private fun N8Center(overrides: GameOverrides = GameOverrides(), scrolled: Boolean = false) {
    val entry = N8Data.emerald
    N8Details(entry)
    GameSettingsSheet(
        title = entry.displayTitle,
        headerTitle = entry.title,
        onRename = {},
        console = entry.console,
        global = GameplaySettingsData(),
        overrides = overrides,
        onOverridesChange = {},
        onDismiss = {},
        center = GameCenterState(
            categoryPath = entry.categoryPath,
            folderPath = entry.folderPath,
            moved = false,
            tags = emptyList(),
            enabled = true,
            onChangeCategory = {},
            onReturnToFolder = {},
            onEditTags = {},
            onOpenSaves = {},
            onHide = {},
        ),
        initialScroll = if (scrolled) GBA_SECTION_SCROLL else 0,
        gbaInfo = GbaSettingsInfo(detectedMedia = "Flash 128 KiB", detectedRtc = true, biosStatus = GbaBiosStatus.ABSENT),
    )
}

@Composable
private fun N8States() {
    val states = remember {
        fun thumb(seed: Int) = FramePng.encode(N8Data.gbaPixels(seed))
        StatesUi(
            entries = mapOf(
                StateSlot.AUTO to StateStore.Entry(StateSlot.AUTO, 1_759_700_000_000, thumb(1), false),
                StateSlot.MANUAL1 to StateStore.Entry(StateSlot.MANUAL1, 1_759_650_000_000, thumb(4), false),
            ),
        )
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        StatesSheet(false, states, remember { SnackbarHostState() }, {}, {}, { _, _ -> }, {})
    }
}

/** Pantallas nuevas de N8 (Android, GBA). La orientación, la fuente y el tema los fija el manifiesto. */
internal val n8CatalogScreens: Map<String, @Composable (DebugIntent) -> Unit> = buildMap {
    fun game(id: String, content: @Composable (DebugIntent) -> Unit) = put(id) { i -> GameplayFrame { content(i) } }

    // Biblioteca con juegos de GBA (chip «GBA», portadas 3:2 recortadas en la tarjeta) y el filtro GBA.
    put("n8-library") { N8Library() }
    put("n8-library-filter-gba") { N8Library(LibraryFilter.GBA) }
    // Horizontal: el panel de filtros de N3/N4 con «GBA».
    put("n8-library-landscape-filters") { N8Library(LibraryFilter.GBA, LibraryToolsPreset(panel = LibraryPanel.FILTERS)) }
    // Detalle de un juego de GBA: imagen 3:2, tipo de partida detectado, código del juego y reloj.
    put("n8-details") { N8Details() }
    put("n8-details-landscape") { N8Details() }
    // Desplazado: «Información técnica» con el tipo de partida detectado, el código del juego y el reloj.
    put("n8-details-technical") { N8Details(infoScroll = Int.MAX_VALUE / 2) }
    // Centro de ajustes del juego de GBA: tipo de partida, reloj y BIOS (todo detectado / personalizado).
    put("n8-game-center") { N8Center(scrolled = true) }
    put("n8-game-center-custom") {
        N8Center(GameOverrides(gbaSaveType = GbaSaveType.EEPROM8K.native, gbaRtc = GbaRtc.OFF.native, gbaUseBios = false), scrolled = true)
    }
    // Ajustes › Emulación (BIOS) y Ajustes › Mando (L1/R1 = L/R en GBA).
    put("n8-settings-emulation") { EmulationSettingsContent(GameplaySettingsData(), {}, {}, biosStatus = GbaBiosStatus.ABSENT) }
    put("n8-controller-mapping") { ControllerMappingContent(GameplaySettingsData(), {}, {}) }
    // Juego de GBA real (núcleo GBA + ROM sintética): imagen 3:2 y L/R, en vertical y horizontal.
    game("n8-gameplay") { i -> GameplayCatalogScreen(i.gameplaySettings(), rom = "gba") }
    game("n8-gameplay-landscape") { i -> GameplayCatalogScreen(i.gameplaySettings(), rom = "gba") }
    game("n8-gameplay-landscape-fill") { i -> GameplayCatalogScreen(i.gameplaySettings(), rom = "gba") }
    // Editor de la disposición GBA (con L elegido), por orientación.
    game("n8-editor") { i -> GameplayCatalogScreen(i.gameplaySettings(), rom = "gba", editing = true, initialSelected = ControlId.L) }
    game("n8-editor-landscape") { i -> GameplayCatalogScreen(i.gameplaySettings(), rom = "gba", editing = true, initialSelected = ControlId.R) }
    // Estados con capturas de 240×160 y el aviso de ajustes que no casan con la partida.
    game("n8-states") { N8States() }
    game("n8-settings-warning") {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            SaveLoadWarningDialog(SaveLoadWarning.GameSettingsMismatch(noSave = false)) {}
        }
    }
}

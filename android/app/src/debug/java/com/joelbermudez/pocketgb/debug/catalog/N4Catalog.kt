package com.joelbermudez.pocketgb.debug.catalog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.joelbermudez.pocketgb.app.AppNavigationState
import com.joelbermudez.pocketgb.app.AppScaffold
import com.joelbermudez.pocketgb.debug.DebugIntent
import com.joelbermudez.pocketgb.library.DetailsLoad
import com.joelbermudez.pocketgb.library.GameDetails
import com.joelbermudez.pocketgb.library.HomeSettings
import com.joelbermudez.pocketgb.library.LibraryCategory
import com.joelbermudez.pocketgb.library.LibraryFilter
import com.joelbermudez.pocketgb.library.LibraryHome
import com.joelbermudez.pocketgb.library.LibraryLayout
import com.joelbermudez.pocketgb.library.LibraryPreferencesData
import com.joelbermudez.pocketgb.library.LibraryQuery
import com.joelbermudez.pocketgb.library.LibraryState
import com.joelbermudez.pocketgb.library.LibraryTree
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.library.artwork.ArtworkStore
import com.joelbermudez.pocketgb.library.tagOptions
import com.joelbermudez.pocketgb.settings.GameOverrides
import com.joelbermudez.pocketgb.settings.GameplaySettingsData
import com.joelbermudez.pocketgb.ui.components.LocalArtworkStore
import com.joelbermudez.pocketgb.ui.details.CategoryPickerDialog
import com.joelbermudez.pocketgb.ui.details.GameCenterState
import com.joelbermudez.pocketgb.ui.details.GameDetailsContent
import com.joelbermudez.pocketgb.ui.details.GameSettingsSheet
import com.joelbermudez.pocketgb.ui.details.TagEditorDialog
import com.joelbermudez.pocketgb.ui.library.CategoryContent
import com.joelbermudez.pocketgb.ui.library.GameActions
import com.joelbermudez.pocketgb.ui.library.LibraryContent
import com.joelbermudez.pocketgb.ui.library.LibraryPanel
import com.joelbermudez.pocketgb.ui.library.LibraryToolsPreset
import com.joelbermudez.pocketgb.ui.library.LocalLibraryToolsPreset
import com.joelbermudez.pocketgb.ui.settings.HomeSettingsContent
import com.joelbermudez.pocketgb.ui.settings.LibrarySettingsContent
import java.io.File

/**
 * N4 · árbol demo de varias carpetas y niveles (datos sintéticos, nada comercial):
 * ```
 * Roms/
 *   Pokémon/1ª generación/{Pokemon Red.gb, Pokemon Yellow.gbc}
 *   Pokémon/2ª generación/Pokemon Gold.gbc
 *   Pokémon/2ª generación/Johto/Pokemon Crystal.gbc
 *   Aventuras/{Demo Adventure.gb, Texto Quest.gbc}
 *   Puzles/{Tetra Blocks.gb, Puzzle Lab.gb}      ← Tetra Blocks se muestra en «Favoritas» (categoría virtual)
 *   Pruebas/Space Test.gb
 *   Color Demo.gbc                               ← sin categoría
 * ```
 * Con etiquetas (rpg, pendiente, dos jugadores), tres favoritos y tres juegos que se pueden continuar.
 */
internal object N4Data {
    private fun entry(id: String, title: String, color: Boolean, size: Long) = RomEntry(
        id = id,
        uri = "content://demo/n4/$id",
        fileName = id.substringAfterLast('/'),
        title = title,
        isColor = color,
        sizeBytes = size,
        headerChecksumOk = true,
        problem = null,
    )

    val red = entry("Pokémon/1ª generación/Pokemon Red.gb", "POKÉMON RED", false, 1024L * 1024)
    val yellow = entry("Pokémon/1ª generación/Pokemon Yellow.gbc", "POKÉMON YELLOW", true, 1024L * 1024)
    val gold = entry("Pokémon/2ª generación/Pokemon Gold.gbc", "POKÉMON GOLD", true, 2048L * 1024)
    val crystal = entry("Pokémon/2ª generación/Johto/Pokemon Crystal.gbc", "POKÉMON CRYSTAL", true, 2048L * 1024)
    val adventure = entry("Aventuras/Demo Adventure.gb", "DEMO ADVENTURE", false, 32L * 1024)
    val quest = entry("Aventuras/Texto Quest.gbc", "TEXTO QUEST", true, 256L * 1024)
    val tetra = entry("Puzles/Tetra Blocks.gb", "TETRA BLOCKS", false, 32L * 1024)
    val lab = entry("Puzles/Puzzle Lab.gb", "PUZZLE LAB", false, 128L * 1024)
    val space = entry("Pruebas/Space Test.gb", "SPACE TEST", false, 64L * 1024)
    val colorDemo = entry("Color Demo.gbc", "COLOR DEMO", true, 512L * 1024)

    val entries = listOf(red, yellow, gold, crystal, adventure, quest, tetra, lab, space, colorDemo)

    fun fingerprint(entry: RomEntry): String = CatalogData.fingerprint("n4/" + entry.id)

    val resumable: Set<String> = listOf(red, gold, adventure).map(::fingerprint).toSet()

    fun prefs(now: Long = System.currentTimeMillis(), home: HomeSettings = HomeSettings()): LibraryPreferencesData {
        val hour = 3_600_000L
        val base = LibraryPreferencesData(
            fingerprints = entries.associate { it.id to fingerprint(it) },
            knownIds = entries.map { it.id }.toSet(),
            lastPlayedByFingerprint = mapOf(
                fingerprint(red) to now - hour,
                fingerprint(gold) to now - 5 * hour,
                fingerprint(adventure) to now - 72 * hour,
            ),
            favoriteFingerprints = listOf(red, tetra, colorDemo).map(::fingerprint).toSet(),
            home = home,
        )
        return base
            .addTag(red, "rpg").addTag(gold, "rpg").addTag(crystal, "rpg").addTag(crystal, "pendiente")
            .addTag(tetra, "dos jugadores").addTag(lab, "pendiente")
            .moveToCategory(tetra, listOf("Favoritas"))
    }

    fun details(entry: RomEntry) = GameDetails(
        entry = entry,
        cartridge = "MBC3 + RAM + batería",
        romBytes = entry.sizeBytes.toInt(),
        sramBytes = 32 * 1024,
        hasBattery = true,
        hasRtc = false,
        headerChecksumOk = true,
        globalChecksumOk = true,
        fingerprint = fingerprint(entry),
    )

    fun presented(entry: RomEntry, prefs: LibraryPreferencesData = prefs()): RomEntry =
        LibraryQuery.presented(entries, prefs, entry.id)!!
}

/** Portadas sintéticas de los diez juegos (deterministas, sin hilos). */
@Composable
private fun N4Artwork(content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) {
        val dir = File(context.cacheDir, "debug-artwork-n4")
        dir.listFiles()?.forEach { it.delete() }
        ArtworkStore(dir, executor = null).also { store ->
            N4Data.entries.forEachIndexed { index, entry -> store.save(N4Data.fingerprint(entry), CatalogData.coverPixels(index)) }
        }
    }
    CompositionLocalProvider(LocalArtworkStore provides store, content = content)
}

/** El marco real de la app: barra inferior en vertical y NavigationRail en horizontal. */
@Composable
private fun N4Frame(content: @Composable () -> Unit) {
    val navigation = remember { AppNavigationState() }
    N4Artwork { AppScaffold(navigation) { content() } }
}

private val n4Actions = GameActions(
    onOpenDetails = {},
    onToggleFavorite = {},
    onHide = {},
    onPlay = {},
    onGameSettings = {},
    onPlayFromStart = {},
    onRename = {},
    canResume = { entry -> N4Data.fingerprint(entry) in N4Data.resumable },
)

/** El inicio con estado propio: las categorías abren su pantalla también aquí (se puede tocar en el catálogo). */
@Composable
private fun N4Home(
    preset: LibraryToolsPreset = LibraryToolsPreset(),
    initialTag: String? = null,
    home: HomeSettings = HomeSettings(),
) {
    var prefs by remember { mutableStateOf(N4Data.prefs(home = home)) }
    var filter by remember { mutableStateOf(LibraryFilter.ALL) }
    var tag by remember { mutableStateOf(initialTag) }
    var category by remember { mutableStateOf<LibraryCategory?>(null) }
    N4Frame {
        val open = category
        if (open != null) {
            CategoryContent(
                state = LibraryState.Ready(N4Data.entries, "Roms"),
                prefs = prefs,
                category = open,
                actions = n4Actions,
                onBack = { category = null },
                onOpenCategory = { category = it },
                onOpenCrumb = { category = it },
                onLayoutChange = { prefs = prefs.withCategoryLayout(open, it) },
            )
        } else {
            CompositionLocalProvider(LocalLibraryToolsPreset provides preset) {
                LibraryContent(
                    state = LibraryState.Ready(N4Data.entries, "Roms"),
                    prefs = prefs,
                    query = "",
                    filter = filter,
                    onQueryChange = {},
                    onFilterChange = { filter = it },
                    onLayoutChange = { prefs = prefs.copy(layout = it) },
                    onSortChange = { prefs = prefs.copy(sort = it) },
                    onChooseFolder = {},
                    onRescan = {},
                    actions = n4Actions,
                    artworkFingerprints = N4Data.entries.map(N4Data::fingerprint).toSet(),
                    onOpenCategory = { category = it },
                    tag = tag,
                    onTagChange = { tag = it },
                )
            }
        }
    }
}

@Composable
private fun N4Category(path: List<String>, layout: LibraryLayout = LibraryLayout.GRID) {
    val initial = LibraryCategory.fromPath(path)
    var category by remember { mutableStateOf(initial) }
    var prefs by remember { mutableStateOf(N4Data.prefs().withCategoryLayout(initial, layout)) }
    N4Frame {
        CategoryContent(
            state = LibraryState.Ready(N4Data.entries, "Roms"),
            prefs = prefs,
            category = category,
            actions = n4Actions,
            onBack = {},
            onOpenCategory = { category = it },
            onOpenCrumb = { category = it ?: initial },
            onLayoutChange = { prefs = prefs.withCategoryLayout(category, it) },
        )
    }
}

@Composable
private fun N4Details(entry: RomEntry) {
    val shown = N4Data.presented(entry)
    N4Frame {
        GameDetailsContent(
            entry = shown,
            load = DetailsLoad.Loaded(N4Data.details(entry)),
            favorite = true,
            lastPlayedAt = null,
            onPlay = {},
            onToggleFavorite = {},
            onHide = {},
            onBack = {},
            fingerprint = N4Data.fingerprint(entry),
            onOpenSettings = {},
            onRename = {},
        )
    }
}

/** El centro de ajustes del juego sobre su detalle (como en la app). */
@Composable
private fun N4Center(entry: RomEntry, loading: Boolean = false, scrolled: Boolean = false) {
    val shown = N4Data.presented(entry)
    N4Details(entry)
    run {
        GameSettingsSheet(
            title = shown.displayTitle,
            headerTitle = entry.title,
            onRename = {},
            isColor = entry.isColor,
            global = GameplaySettingsData(),
            overrides = GameOverrides(),
            onOverridesChange = {},
            onDismiss = {},
            loading = loading,
            center = GameCenterState(
                categoryPath = shown.categoryPath,
                folderPath = entry.folderPath,
                moved = shown.isMovedInApp,
                tags = shown.tags,
                enabled = !loading,
                onChangeCategory = {},
                onReturnToFolder = {},
                onEditTags = {},
                onOpenSaves = {},
                onHide = {},
            ),
            initialScroll = if (scrolled) Int.MAX_VALUE / 2 else 0,
        )
    }
}

@Composable
private fun N4TagEditor(entry: RomEntry) {
    val prefs = N4Data.prefs()
    var tags by remember { mutableStateOf(prefs.tagsOf(entry)) }
    N4Details(entry)
    TagEditorDialog(
        title = N4Data.presented(entry).displayTitle,
        tags = tags,
        suggestions = LibraryQuery.tagOptions(N4Data.entries, prefs).map { it.tag },
        onAdd = { tags = com.joelbermudez.pocketgb.library.Tags.added(tags, it) },
        onRemove = { tags = com.joelbermudez.pocketgb.library.Tags.removed(tags, it) },
        onDismiss = {},
    )
}

@Composable
private fun N4Picker(entry: RomEntry) {
    val prefs = N4Data.prefs()
    val shown = N4Data.presented(entry, prefs)
    N4Details(entry)
    CategoryPickerDialog(
        current = shown.categoryPath,
        folder = entry.folderPath,
        moved = shown.isMovedInApp,
        known = LibraryTree.knownPaths(N4Data.entries, prefs),
        onPick = {},
        onReturnToFolder = {},
        onDismiss = {},
    )
}

@Composable
private fun N4HomeSettings() {
    var prefs by remember {
        mutableStateOf(N4Data.prefs(home = HomeSettings().withPinned("Pokémon", true).withHidden("Pruebas", true)))
    }
    val keys = LibraryHome.keys(N4Data.entries, prefs)
    HomeSettingsContent(
        rows = LibraryHome.arrangement(N4Data.entries, prefs),
        showFavorites = prefs.home.showFavorites,
        onShowFavorites = { prefs = prefs.copy(home = prefs.home.copy(showFavorites = it)) },
        onMove = { key, offset -> prefs = prefs.copy(home = prefs.home.move(key, offset, keys)) },
        onPinned = { key, value -> prefs = prefs.copy(home = prefs.home.withPinned(key, value)) },
        onHidden = { key, value -> prefs = prefs.copy(home = prefs.home.withHidden(key, value)) },
        onReset = { prefs = prefs.copy(home = HomeSettings()) },
        onBack = {},
        changed = prefs.home != HomeSettings(),
    )
}

/** Pantallas nuevas de N4 (Android). La orientación, la fuente y el tema los fija el manifiesto. */
internal val n4CatalogScreens: Map<String, @Composable (DebugIntent) -> Unit> = buildMap {
    // Inicio: carril, Favoritos y una estantería por categoría (Favoritas es virtual) antes de «Todos los juegos».
    put("n4-home") { N4Home() }
    put("n4-home-scrolled") { N4Home() }
    put("n4-home-ax5") { N4Home() }
    put("n4-home-landscape") { N4Home() }
    put("n4-home-landscape-scrolled") { N4Home(LibraryToolsPreset(scrolled = true)) }
    put("n4-home-landscape-categories") { N4Home(LibraryToolsPreset(panel = LibraryPanel.CATEGORIES)) }
    put("n4-home-landscape-filters") { N4Home(LibraryToolsPreset(panel = LibraryPanel.FILTERS)) }
    // Filtro por etiqueta: sin estanterías, «Etiqueta «rpg»» como título.
    put("n4-home-tag") { N4Home(initialTag = "rpg") }
    // Inicio con Puzles fijada arriba y Pruebas oculta (Ajustes › Biblioteca › Inicio).
    put("n4-home-arranged") { N4Home(home = HomeSettings().withPinned("Puzles", true).withHidden("Pruebas", true)) }
    // Pantalla de categoría: migas, subcategorías y juegos (cuadrícula o lista por categoría).
    put("n4-category") { N4Category(listOf("Pokémon")) }
    put("n4-category-nested-list") { N4Category(listOf("Pokémon", "2ª generación"), LibraryLayout.LIST) }
    put("n4-category-landscape") { N4Category(listOf("Pokémon")) }
    put("n4-category-ax5") { N4Category(listOf("Pokémon", "2ª generación")) }
    put("n4-category-virtual") { N4Category(listOf("Favoritas")) }
    put("n4-category-root") { N4Category(emptyList()) }
    // Detalle de un juego movido en la app (insignia, «Se ve en…» y etiquetas).
    put("n4-details-moved") { N4Details(N4Data.tetra) }
    // Centro de ajustes del juego.
    put("n4-game-center") { N4Center(N4Data.crystal) }
    put("n4-game-center-moved") { N4Center(N4Data.tetra) }
    // Desplazado hasta el final: partida, color y paleta, ocultar y el pie.
    put("n4-game-center-scrolled") { N4Center(N4Data.tetra, scrolled = true) }
    put("n4-game-center-reading") { N4Center(N4Data.space, loading = true) }
    put("n4-game-center-ax5") { N4Center(N4Data.crystal) }
    put("n4-game-center-landscape") { N4Center(N4Data.crystal) }
    put("n4-tag-editor") { N4TagEditor(N4Data.crystal) }
    put("n4-move-category") { N4Picker(N4Data.tetra) }
    put("n4-move-category-scrolled") { N4Picker(N4Data.tetra) }
    put("n4-move-category-ax5") { N4Picker(N4Data.gold) }
    // Ajustes › Biblioteca (fila «Inicio») y Ajustes › Biblioteca › Inicio.
    put("n4-settings-library") {
        LibrarySettingsContent(
            state = LibraryState.Ready(N4Data.entries, "Roms"),
            folderName = "Roms",
            hidden = emptyList(),
            onChooseFolder = {},
            onRescan = {},
            onForget = {},
            onUnhide = {},
            onBack = {},
            onOpenHome = {},
        )
    }
    put("n4-settings-home") { N4HomeSettings() }
    put("n4-settings-home-ax5") { N4HomeSettings() }
}

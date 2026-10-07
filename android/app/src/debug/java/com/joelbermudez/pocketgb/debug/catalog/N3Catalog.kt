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
import com.joelbermudez.pocketgb.library.LibraryCategory
import com.joelbermudez.pocketgb.library.LibraryFilter
import com.joelbermudez.pocketgb.library.LibraryLayout
import com.joelbermudez.pocketgb.library.LibraryPreferencesData
import com.joelbermudez.pocketgb.library.LibraryState
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.library.RomLocation
import com.joelbermudez.pocketgb.library.artwork.ArtworkStore
import com.joelbermudez.pocketgb.ui.components.LocalArtworkStore
import com.joelbermudez.pocketgb.ui.details.GameDetailsContent
import com.joelbermudez.pocketgb.ui.library.CategoryContent
import com.joelbermudez.pocketgb.ui.library.GameActions
import com.joelbermudez.pocketgb.ui.library.LibraryContent
import com.joelbermudez.pocketgb.ui.library.LibraryPanel
import com.joelbermudez.pocketgb.ui.library.LibraryToolsPreset
import com.joelbermudez.pocketgb.ui.library.LocalLibraryToolsPreset
import java.io.File

/**
 * N3 · biblioteca y detalle adaptables. Datos sintéticos: los juegos del catálogo repartidos en carpetas (categorías
 * «Aventuras», «Pokémon» con una subcarpeta, «Pruebas», «Puzles» y uno en la raíz). Cinco tienen portada y fecha de
 * juego; cuatro se pueden continuar y «COLOR DEMO» no (tiene portada y se jugó, pero sin estado automático): ND15
 * lo deja fuera del carril.
 */
private object N3Data {
    private val folders = listOf("Pokémon", "Pokémon/Amarillo", "Aventuras", "", "Pruebas", "Puzles", "Aventuras", "Puzles")

    val entries: List<RomEntry> = CatalogData.games.mapIndexed { i, game ->
        val folder = folders[i]
        game.copy(
            id = if (folder.isEmpty()) game.fileName else "$folder/${game.fileName}",
            uri = "content://demo/n3/$i",
            folderPath = folder.split('/').filter { it.isNotEmpty() },
        )
    }

    /** Huella de cada juego: la del catálogo original (sus portadas sintéticas). */
    fun fingerprint(index: Int): String = CatalogData.fingerprint(CatalogData.games[index].id)

    private val byId: Map<String, String> = entries.indices.associate { entries[it].id to fingerprint(it) }

    fun fingerprintOf(entry: RomEntry): String? = byId[entry.id]


    /** «COLOR DEMO» (3) se jugó, pero no se puede continuar. */
    val resumableFingerprints: Set<String> = listOf(0, 1, 2, 4).map(::fingerprint).toSet()

    fun prefs(layout: LibraryLayout = LibraryLayout.GRID, now: Long = System.currentTimeMillis()): LibraryPreferencesData {
        val hour = 3_600_000L
        return LibraryPreferencesData(
            favorites = setOf(entries[0].id, entries[3].id),
            lastPlayed = mapOf(
                entries[0].id to now - hour,
                entries[3].id to now - 2 * hour,
                entries[1].id to now - 5 * hour,
                entries[2].id to now - 72 * hour,
                entries[4].id to now - 96 * hour,
            ),
            fingerprints = entries.indices.associate { entries[it].id to fingerprint(it) },
            knownIds = entries.map { it.id }.toSet(),
            layout = layout,
        )
    }

    fun details(entry: RomEntry, index: Int) = GameDetails(
        entry = entry,
        cartridge = "MBC3 + RAM + batería",
        romBytes = 1024 * 1024,
        sramBytes = 32 * 1024,
        hasBattery = true,
        hasRtc = false,
        headerChecksumOk = true,
        globalChecksumOk = true,
        fingerprint = fingerprint(index),
    )
}

/**
 * Portada casi blanca (blanco con rayas gris muy claro): la captura de un juego de pantalla blanca, para comprobar que
 * el título de sección y la barra flotante se leen encima (N3, como `library-landscape-white` de iOS).
 */
private fun whiteCoverPixels(seed: Int): IntArray = IntArray(160 * 144) { i ->
    val x = i % 160
    val y = i / 160
    if ((x + y + seed * 7) % 24 < 2) 0xFFE4E4E4.toInt() else 0xFFFFFFFF.toInt()
}

/** Portadas sintéticas de los cinco juegos con portada (sin hilos: determinista); [white] = casi blancas. */
@Composable
private fun N3Artwork(white: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    val store = remember(context, white) {
        val dir = File(context.cacheDir, if (white) "debug-artwork-n3-white" else "debug-artwork-n3")
        dir.listFiles()?.forEach { it.delete() }
        ArtworkStore(dir, executor = null).also { store ->
            (0 until 5).forEach { index ->
                store.save(N3Data.fingerprint(index), if (white) whiteCoverPixels(index) else CatalogData.coverPixels(index))
            }
        }
    }
    CompositionLocalProvider(LocalArtworkStore provides store, content = content)
}

/** El marco real de la app: barra inferior en vertical y NavigationRail desde 600 dp (horizontal y ventana ancha). */
@Composable
private fun N3Frame(white: Boolean = false, content: @Composable () -> Unit) {
    val navigation = remember { AppNavigationState() }
    N3Artwork(white) { AppScaffold(navigation) { content() } }
}

private val n3Actions = GameActions(
    onOpenDetails = {},
    onToggleFavorite = {},
    onHide = {},
    onPlay = {},
    onGameSettings = {},
    onPlayFromStart = {},
    onRename = {},
    canResume = { entry -> N3Data.fingerprintOf(entry) in N3Data.resumableFingerprints },
)

/** Biblioteca con estado propio (búsqueda, filtro, categoría, vista y orden): en el catálogo también se puede tocar. */
@Composable
private fun N3Library(
    preset: LibraryToolsPreset = LibraryToolsPreset(),
    initialQuery: String = "",
    initialCategory: LibraryCategory = LibraryCategory.All,
    initialLayout: LibraryLayout = LibraryLayout.GRID,
    white: Boolean = false,
) {
    var prefs by remember {
        mutableStateOf(N3Data.prefs(initialLayout).let { if (initialCategory != LibraryCategory.All) it.withCategoryLayout(initialCategory, initialLayout) else it })
    }
    var query by remember { mutableStateOf(initialQuery) }
    var filter by remember { mutableStateOf(LibraryFilter.ALL) }
    // N4: una categoría ya no filtra la biblioteca: se abre su pantalla (migas, subcategorías y juegos).
    var category by remember { mutableStateOf(initialCategory) }
    N3Frame(white) {
        CompositionLocalProvider(LocalLibraryToolsPreset provides preset) {
            if (category != LibraryCategory.All) {
                CategoryContent(
                    state = LibraryState.Ready(N3Data.entries, "Roms"),
                    prefs = prefs,
                    category = category,
                    actions = n3Actions,
                    onBack = { category = LibraryCategory.All },
                    onOpenCategory = { category = it },
                    onOpenCrumb = { category = it ?: LibraryCategory.All },
                    onLayoutChange = { prefs = prefs.withCategoryLayout(category, it) },
                )
                return@CompositionLocalProvider
            }
            LibraryContent(
                state = LibraryState.Ready(N3Data.entries, "Roms"),
                prefs = prefs,
                query = query,
                filter = filter,
                onQueryChange = { query = it },
                onFilterChange = { filter = it },
                onLayoutChange = { prefs = prefs.copy(layout = it) },
                onSortChange = { prefs = prefs.copy(sort = it) },
                onChooseFolder = {},
                onRescan = {},
                actions = n3Actions,
                onOpenCategory = { category = it },
            )
        }
    }
}

/** Nombre de 80 caracteres, ruta de cinco carpetas y dos copias: el caso largo del detalle (H3). */
private val longEntry: RomEntry
    get() = N3Data.entries[0].copy(
        alias = "Pokémon Rojo — la partida principal con el equipo completo y todas las medallas.",
        folderPath = listOf("Clásicos de la consola", "Nintendo y compañía", "Pokémon", "Primera generación", "Kanto"),
        alsoAt = listOf(RomLocation(listOf("Copias"), "Pokemon Red (1).gb"), RomLocation(listOf("Copias", "Viejas"), "Pokemon Red (2).gb")),
    )

@Composable
private fun N3Details(canResume: Boolean = false, scrolled: Boolean = false, long: Boolean = false) {
    val entry = if (long) longEntry else N3Data.entries[0]
    N3Frame {
        GameDetailsContent(
            entry = entry,
            load = DetailsLoad.Loaded(N3Data.details(entry, 0)),
            favorite = true,
            lastPlayedAt = System.currentTimeMillis() - 3_600_000L,
            onPlay = {},
            onToggleFavorite = {},
            onHide = {},
            onBack = {},
            fingerprint = N3Data.fingerprint(0),
            onOpenSettings = {},
            canResume = canResume,
            onPlayFromStart = {},
            onRename = {},
            initialInfoScroll = if (scrolled) Int.MAX_VALUE / 2 else 0,
        )
    }
}

/** Pantallas nuevas de N3 (Android). La orientación, la fuente y la ventana ancha las fija el manifiesto. */
internal val n3CatalogScreens: Map<String, @Composable (DebugIntent) -> Unit> = buildMap {
    // Carril «Continuar jugando» con el ancho de las columnas de la cuadrícula (solo reanudables: COLOR DEMO no sale).
    put("n3-rail-portrait") { N3Library() }
    put("n3-rail-portrait-ax5") { N3Library() }
    // Biblioteca en horizontal en reposo: sin buscador ni chips, herramientas como iconos de la barra superior, carril
    // con 3 columnas y sin barra flotante.
    put("n3-library-landscape") { N3Library() }
    put("n3-library-landscape-ax5") { N3Library() }
    // Desplazada: barra superior plegada, título de sección fijado y barra flotante a la derecha.
    put("n3-library-landscape-scrolled") { N3Library(LibraryToolsPreset(scrolled = true)) }
    // Cada panel en reposo (cuelga de los iconos de la barra superior) y desplazada (sube desde la barra flotante).
    put("n3-library-landscape-filters") { N3Library(LibraryToolsPreset(panel = LibraryPanel.FILTERS)) }
    put("n3-library-landscape-categories") { N3Library(LibraryToolsPreset(panel = LibraryPanel.CATEGORIES)) }
    put("n3-library-landscape-view") { N3Library(LibraryToolsPreset(panel = LibraryPanel.VIEW)) }
    put("n3-library-landscape-scrolled-filters") { N3Library(LibraryToolsPreset(panel = LibraryPanel.FILTERS, scrolled = true)) }
    put("n3-library-landscape-scrolled-categories") { N3Library(LibraryToolsPreset(panel = LibraryPanel.CATEGORIES, scrolled = true)) }
    put("n3-library-landscape-scrolled-view") { N3Library(LibraryToolsPreset(panel = LibraryPanel.VIEW, scrolled = true)) }
    // Portadas casi blancas bajo la barra superior (reposo) y bajo el título fijado y la barra flotante (desplazada).
    put("n3-library-landscape-white") { N3Library(white = true) }
    put("n3-library-landscape-white-scrolled") { N3Library(LibraryToolsPreset(scrolled = true), white = true) }
    // Buscando: el campo ocupa la barra superior y desaparece la barra flotante.
    put("n3-library-landscape-search") { N3Library(LibraryToolsPreset(search = true), initialQuery = "po") }
    // La misma búsqueda sin teclado: los resultados en lista bajo el campo.
    put("n3-library-landscape-search-results") {
        N3Library(LibraryToolsPreset(search = true, focusSearch = false), initialQuery = "o")
    }
    // Categoría elegida: título de sección «Pokémon» (con su subcarpeta) y sin carril.
    put("n3-library-landscape-category") { N3Library(initialCategory = LibraryCategory.Folder("Pokémon")) }
    put("n3-library-landscape-list") {
        N3Library(initialLayout = LibraryLayout.LIST, initialCategory = LibraryCategory.Folder("Aventuras"))
    }
    // Vertical con una categoría elegida (desde el menú «⋮»): el título de sección la nombra.
    put("n3-library-portrait-category") { N3Library(initialCategory = LibraryCategory.Folder("Puzles")) }
    // Ventana ancha (853 dp, rail): misma disposición horizontal con más columnas.
    put("n3-library-wide") { N3Library() }
    // Desplazada con el panel de filtros: no tapa el encabezado entero (título y «Roms», H6).
    put("n3-library-wide-filters") { N3Library(LibraryToolsPreset(panel = LibraryPanel.FILTERS, scrolled = true)) }
    // Detalle: una columna (imagen ≤ 45 % del alto) o dos columnas con «Jugar» visible sin desplazar.
    put("n3-details-portrait") { N3Details() }
    put("n3-details-portrait-ax5") { N3Details() }
    put("n3-details-landscape") { N3Details() }
    put("n3-details-landscape-resume") { N3Details(canResume = true) }
    put("n3-details-landscape-ax5") { N3Details() }
    put("n3-details-landscape-scrolled") { N3Details(scrolled = true) }
    put("n3-details-wide") { N3Details(canResume = true) }
    // Nombre de 80 caracteres, ruta larga y dos copias: «Continuar» sigue a la vista bajo el título (H3).
    put("n3-details-landscape-long") { N3Details(canResume = true, long = true) }
    put("n3-details-portrait-long") { N3Details(canResume = true, long = true) }
}

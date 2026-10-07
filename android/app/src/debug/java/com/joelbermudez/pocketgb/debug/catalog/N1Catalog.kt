package com.joelbermudez.pocketgb.debug.catalog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.joelbermudez.pocketgb.debug.DebugIntent
import com.joelbermudez.pocketgb.library.DetailsLoad
import com.joelbermudez.pocketgb.library.GameDetails
import com.joelbermudez.pocketgb.library.LibraryFilter
import com.joelbermudez.pocketgb.library.LibraryLayout
import com.joelbermudez.pocketgb.library.LibraryPreferencesData
import com.joelbermudez.pocketgb.library.LibraryQuery
import com.joelbermudez.pocketgb.library.LibraryState
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.ui.details.GameDetailsContent
import com.joelbermudez.pocketgb.ui.library.GameActions
import com.joelbermudez.pocketgb.ui.library.LibraryContent

/**
 * N1 · identidad y carpetas: detalle con ruta profunda (5 niveles) e insignia «Duplicado» con «También en». Datos
 * sintéticos: la copia comparte la huella (y por tanto la portada) del primer juego del catálogo.
 */
private object N1Data {
    private fun entry(id: String, title: String, color: Boolean, size: Long) = RomEntry(
        id = id,
        uri = "content://demo/$id",
        fileName = id.substringAfterLast('/'),
        title = title,
        isColor = color,
        sizeBytes = size,
        headerChecksumOk = true,
        problem = null,
    )

    /** Juego en el quinto nivel de carpetas (el máximo que se escanea). */
    val deep = entry("Clásicos/Nintendo/Pokémon/2ª generación/Johto/Pokemon Gold.gbc", "POKÉMON GOLD", true, 2L * 1024 * 1024)

    /** Dos copias más de «POKÉMON RED» (la de la raíz es `CatalogData.games[0]`). */
    val redCopy = entry("Pokémon/1ª generación/Pokemon Red.gb", "POKÉMON RED", false, 1024L * 1024)
    val redBackup = entry("Copias/Pokemon Red (1).gb", "POKÉMON RED", false, 1024L * 1024)

    val entries: List<RomEntry> = CatalogData.games + redCopy + redBackup + deep

    fun prefs(layout: LibraryLayout = LibraryLayout.GRID): LibraryPreferencesData {
        val base = CatalogData.prefs()
        val red = CatalogData.fingerprint(CatalogData.games[0].id)
        return base.copy(
            layout = layout,
            fingerprints = base.fingerprints + mapOf(
                redCopy.id to red,
                redBackup.id to red,
                deep.id to CatalogData.fingerprint(deep.id),
            ),
        )
    }

    fun details(entry: RomEntry) = GameDetails(
        entry = entry,
        cartridge = "MBC3 + reloj + RAM + batería",
        romBytes = entry.sizeBytes.toInt(),
        sramBytes = 32 * 1024,
        hasBattery = true,
        hasRtc = true,
        headerChecksumOk = true,
        globalChecksumOk = true,
        fingerprint = CatalogData.fingerprint(entry.id),
    )
}

private val n1Actions = GameActions(
    onOpenDetails = {},
    onToggleFavorite = {},
    onHide = {},
    onPlay = {},
    onGameSettings = {},
    onPlayFromStart = {},
    onRename = {},
)

@Composable
private fun N1Library(layout: LibraryLayout) {
    LibraryContent(
        state = LibraryState.Ready(N1Data.entries, "Roms"),
        prefs = remember(layout) { N1Data.prefs(layout) },
        query = "",
        filter = LibraryFilter.ALL,
        onQueryChange = {},
        onFilterChange = {},
        onLayoutChange = {},
        onSortChange = {},
        onChooseFolder = {},
        onRescan = {},
        actions = n1Actions,
        artworkFingerprints = CatalogData.coveredFingerprints,
    )
}

@Composable
private fun N1Details(id: String, favorite: Boolean) {
    val prefs = remember { N1Data.prefs() }
    val entry = remember(id) { requireNotNull(LibraryQuery.presented(N1Data.entries, prefs, id)) }
    GameDetailsContent(
        entry = entry,
        load = DetailsLoad.Loaded(N1Data.details(entry)),
        favorite = favorite,
        lastPlayedAt = prefs.lastPlayedAt(entry),
        onPlay = {},
        onToggleFavorite = {},
        onHide = {},
        onBack = {},
        fingerprint = prefs.fingerprints[entry.id],
        onOpenSettings = {},
        onRename = {},
    )
}

/** Pantallas nuevas de N1 (Android). */
internal val n1CatalogScreens: Map<String, @Composable (DebugIntent) -> Unit> = buildMap {
    // Detalle de un juego en el quinto nivel: «Clásicos › Nintendo › Pokémon › 2ª generación › Johto · archivo».
    put("details-deep-path") { N1Details(N1Data.deep.id, favorite = false) }
    // La misma con fuente al 200 % (la ruta se ve entera, en varias líneas).
    put("details-deep-path-ax5") { N1Details(N1Data.deep.id, favorite = false) }
    // Detalle de una copia: insignia «Duplicado» y «También en» con la carpeta principal y la otra subcarpeta.
    put("details-duplicate") { N1Details(N1Data.redCopy.id, favorite = true) }
    // Biblioteca con tres copias de «POKÉMON RED»: insignia «Duplicado» discreta en cada tarjeta; el carril lo muestra una vez.
    put("library-duplicates") { N1Library(LibraryLayout.GRID) }
    put("library-duplicates-list") { N1Library(LibraryLayout.LIST) }
}

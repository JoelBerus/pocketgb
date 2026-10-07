package com.joelbermudez.pocketgb.debug.catalog

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.joelbermudez.pocketgb.app.AppNavigationState
import com.joelbermudez.pocketgb.app.AppScaffold
import com.joelbermudez.pocketgb.debug.DebugIntent
import com.joelbermudez.pocketgb.library.DetailsLoad
import com.joelbermudez.pocketgb.library.GameDetails
import com.joelbermudez.pocketgb.library.LibraryFilter
import com.joelbermudez.pocketgb.library.LibraryLayout
import com.joelbermudez.pocketgb.library.LibraryPreferencesData
import com.joelbermudez.pocketgb.library.LibraryState
import com.joelbermudez.pocketgb.library.RomConsole
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.library.artwork.ArtworkStore
import com.joelbermudez.pocketgb.library.artwork.CoverAvailability
import com.joelbermudez.pocketgb.library.artwork.CoverChoice
import com.joelbermudez.pocketgb.library.artwork.CoverDecoder
import com.joelbermudez.pocketgb.library.artwork.CoverImageRules
import com.joelbermudez.pocketgb.library.artwork.CoverKind
import com.joelbermudez.pocketgb.library.artwork.CoverPreference
import com.joelbermudez.pocketgb.library.artwork.CoverRepository
import com.joelbermudez.pocketgb.library.artwork.CoverSettingsStore
import com.joelbermudez.pocketgb.settings.GameOverrides
import com.joelbermudez.pocketgb.settings.GameplaySettingsData
import com.joelbermudez.pocketgb.ui.components.LocalCoverRepository
import com.joelbermudez.pocketgb.ui.details.CoverCenterState
import com.joelbermudez.pocketgb.ui.details.GameCenterState
import com.joelbermudez.pocketgb.ui.details.GameDetailsContent
import com.joelbermudez.pocketgb.ui.details.GameSettingsSheet
import com.joelbermudez.pocketgb.ui.gameplay.PauseSheet
import com.joelbermudez.pocketgb.ui.library.GameActions
import com.joelbermudez.pocketgb.ui.library.LibraryContent
import com.joelbermudez.pocketgb.ui.settings.LibrarySettingsContent
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * N5 · portadas con datos sintéticos (nada de arte comercial: las «imágenes» se pintan aquí con degradados y formas):
 * ```
 * Roms/
 *   Aventura/Isla Pixel.gb        + imagen importada (vertical, 3:4: en la tarjeta se recorta centrada)
 *   Aventura/Torre Lunar.gbc      + Torre Lunar.png junto al ROM (apaisada, 2:1)
 *   Puzle/Bloques.gb              captura del juego
 *   Puzle/Sin Portada.gb          generada
 *   Carreras/Turbo.gba            captura de GBA (3:2)
 *   Carreras/Rally.gba            cover.webp de la carpeta, pero el juego eligió «Generada»
 * ```
 */
internal object N5Data {
    private fun entry(id: String, title: String, console: RomConsole, cover: Boolean = false) = RomEntry(
        id = id,
        uri = "content://demo/n5/$id",
        fileName = id.substringAfterLast('/'),
        title = title,
        console = console,
        sizeBytes = if (console == RomConsole.GBA) 8L shl 20 else 1L shl 20,
        headerChecksumOk = true,
        problem = null,
        coverUri = if (cover) "content://demo/n5/cover/$id" else null,
        coverStamp = if (cover) "demo|1" else null,
    )

    val island = entry("Aventura/Isla Pixel.gb", "ISLA PIXEL", RomConsole.GB)
    val tower = entry("Aventura/Torre Lunar.gbc", "TORRE LUNAR", RomConsole.GBC, cover = true)
    val blocks = entry("Puzle/Bloques.gb", "BLOQUES", RomConsole.GB)
    val plain = entry("Puzle/Sin Portada.gb", "SIN PORTADA", RomConsole.GB)
    val turbo = entry("Carreras/Turbo.gba", "TURBO", RomConsole.GBA)
    val rally = entry("Carreras/Rally.gba", "RALLY", RomConsole.GBA, cover = true)
    val entries = listOf(island, tower, blocks, plain, turbo, rally)

    fun fingerprint(entry: RomEntry): String = CatalogData.fingerprint("n5/" + entry.id)

    fun prefs(now: Long = System.currentTimeMillis()): LibraryPreferencesData {
        val hour = 3_600_000L
        return LibraryPreferencesData(
            fingerprints = entries.associate { it.id to fingerprint(it) },
            knownIds = entries.map { it.id }.toSet(),
            lastPlayedByFingerprint = mapOf(
                fingerprint(island) to now - hour,
                fingerprint(tower) to now - 3 * hour,
                fingerprint(blocks) to now - 8 * hour,
                fingerprint(turbo) to now - 30 * hour,
            ),
        )
    }

    fun details(entry: RomEntry) = GameDetails(
        entry = entry,
        cartridge = "MBC5 + RAM + batería",
        romBytes = entry.sizeBytes.toInt(),
        sramBytes = 32 * 1024,
        hasBattery = true,
        hasRtc = false,
        headerChecksumOk = true,
        globalChecksumOk = true,
        fingerprint = fingerprint(entry),
    )

    /** «Foto» sintética: cielo en degradado, sol y montañas. [hue] cambia la paleta. */
    fun painting(width: Int, height: Int, hue: Int): ByteArray {
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        val skies = listOf(
            intArrayOf(0xFF1D3B6F.toInt(), 0xFFF2A65A.toInt()),
            intArrayOf(0xFF2B1B4A.toInt(), 0xFF6FC2D0.toInt()),
            intArrayOf(0xFF0E4D45.toInt(), 0xFFE9D985.toInt()),
        )
        val sky = skies[hue % skies.size]
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), sky[0], sky[1], Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
        paint.color = 0xFFFFF4D6.toInt()
        canvas.drawCircle(width * 0.68f, height * 0.36f, minOf(width, height) * 0.13f, paint)
        val layers = listOf(0xFF3E5C76.toInt() to 0.62f, 0xFF22384F.toInt() to 0.74f, 0xFF111D2B.toInt() to 0.86f)
        layers.forEachIndexed { index, (color, base) ->
            paint.color = color
            val path = Path().apply {
                moveTo(0f, height.toFloat())
                val peaks = 4 + index
                for (p in 0..peaks) {
                    val x = width * p / peaks.toFloat()
                    val y = height * (base - if (p % 2 == 0) 0.18f else 0.04f)
                    lineTo(x, y)
                }
                lineTo(width.toFloat(), height.toFloat())
                close()
            }
            canvas.drawPath(path, paint)
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }
}

/** Repositorio de portadas del catálogo: escribe las fuentes sintéticas (pasando por la reducción real) y lo inyecta. */
@Composable
private fun N5Covers(preference: CoverPreference = CoverPreference.IMAGES, content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    val repository = remember(context, preference) {
        val root = File(context.cacheDir, "debug-covers-n5").apply { deleteRecursively() }
        val untrusted: (ByteArray) -> androidx.compose.ui.graphics.ImageBitmap? = { bytes ->
            CoverDecoder.decode(bytes)?.asImageBitmap()
        }
        val folderImages = mapOf(
            N5Data.tower.coverUri to N5Data.painting(1200, 600, 1),
            N5Data.rally.coverUri to N5Data.painting(800, 800, 2),
        )
        CoverRepository(
            captures = ArtworkStore(File(root, "artwork"), executor = null),
            pinned = ArtworkStore(File(root, "artwork-pinned"), executor = null),
            imported = ArtworkStore(File(root, "imported"), executor = null, maxReadBytes = CoverImageRules.MAX_BYTES, decoder = untrusted),
            folderCache = ArtworkStore(File(root, "folder"), executor = null, maxReadBytes = CoverImageRules.MAX_BYTES, decoder = untrusted),
            settings = CoverSettingsStore(File(root, "settings.json")),
            readFolderImage = { uri -> folderImages[uri] },
        ).also { repo ->
            repo.captures.save(N5Data.fingerprint(N5Data.blocks), CatalogData.coverPixels(1))
            repo.captures.save(N5Data.fingerprint(N5Data.turbo), N8Data.gbaPixels(3))
            repo.captures.save(N5Data.fingerprint(N5Data.island), CatalogData.coverPixels(2))
            repo.importImage(N5Data.fingerprint(N5Data.island), N5Data.painting(600, 800, 0))
            // Importar elige «Imagen»; aquí se deja en Automática para que se vea el efecto de la preferencia global.
            repo.setChoice(N5Data.fingerprint(N5Data.island), CoverChoice.AUTO)
            repo.setChoice(N5Data.fingerprint(N5Data.rally), CoverChoice.GENERATED)
            repo.setPreference(preference)
        }
    }
    CompositionLocalProvider(LocalCoverRepository provides repository, content = content)
}

private val n5OwnCovers: Set<String> = listOf(N5Data.island, N5Data.blocks, N5Data.turbo).map(N5Data::fingerprint).toSet()

private val n5Actions = GameActions(
    onOpenDetails = {},
    onToggleFavorite = {},
    onHide = {},
    onPlay = {},
    onGameSettings = {},
    onPlayFromStart = {},
    onRename = {},
    canResume = { entry -> entry == N5Data.island || entry == N5Data.tower || entry == N5Data.blocks },
)

@Composable
private fun N5Library(layout: LibraryLayout = LibraryLayout.GRID, filter: LibraryFilter = LibraryFilter.ALL) {
    var prefs by remember {
        // Con el filtro Favoritos todos lo son: la lista enseña las seis portadas en miniatura.
        val base = N5Data.prefs().copy(layout = layout)
        mutableStateOf(if (filter == LibraryFilter.FAVORITES) base.copy(favoriteFingerprints = N5Data.entries.map(N5Data::fingerprint).toSet()) else base)
    }
    val navigation = remember { AppNavigationState() }
    N5Covers {
        AppScaffold(navigation) {
            LibraryContent(
                state = LibraryState.Ready(N5Data.entries, "Roms"),
                prefs = prefs,
                query = "",
                filter = filter,
                onQueryChange = {},
                onFilterChange = {},
                onLayoutChange = { prefs = prefs.copy(layout = it) },
                onSortChange = { prefs = prefs.copy(sort = it) },
                onChooseFolder = {},
                onRescan = {},
                actions = n5Actions,
                // Como en N8: las huellas con portada propia se dan ya hechas (sin la carga asíncrona del carril).
                artworkFingerprints = n5OwnCovers,
                onOpenCategory = {},
            )
        }
    }
}

@Composable
private fun N5DetailsBody(entry: RomEntry) {
    GameDetailsContent(
        entry = entry,
        load = DetailsLoad.Loaded(N5Data.details(entry)),
        favorite = false,
        lastPlayedAt = System.currentTimeMillis() - 3_600_000L,
        onPlay = {},
        onToggleFavorite = {},
        onHide = {},
        onBack = {},
        fingerprint = N5Data.fingerprint(entry),
        onOpenSettings = {},
        onRename = {},
        canResume = true,
    )
}

@Composable
private fun N5Details(entry: RomEntry) {
    val navigation = remember { AppNavigationState() }
    N5Covers { AppScaffold(navigation) { N5DetailsBody(entry) } }
}

@Composable
private fun N5Center(dialog: Boolean, failed: Boolean = false, imported: Boolean = false) {
    val entry = N5Data.tower
    val navigation = remember { AppNavigationState() }
    N5Covers {
        AppScaffold(navigation) { N5DetailsBody(entry) }
        GameSettingsSheet(
            title = entry.displayTitle,
            headerTitle = entry.title,
            onRename = {},
            console = entry.console,
            global = GameplaySettingsData(),
            overrides = GameOverrides(),
            onOverridesChange = {},
            onDismiss = {},
            center = GameCenterState(
                categoryPath = entry.categoryPath,
                folderPath = entry.folderPath,
                moved = false,
                tags = listOf("pendiente"),
                enabled = true,
                onChangeCategory = {},
                onReturnToFolder = {},
                onEditTags = {},
                onOpenSaves = {},
                onHide = {},
                cover = CoverCenterState(
                    choice = CoverChoice.AUTO,
                    shown = CoverKind.SIDECAR,
                    available = CoverAvailability(imported = imported, sidecar = true, capture = true),
                    hasPinned = true,
                    showDialog = dialog,
                    onShowDialog = {},
                    onChoose = {},
                    onImportPhotos = {},
                    onImportFile = {},
                    onRemoveImported = {},
                    onUnpin = {},
                    importFailed = failed,
                ),
                title = entry.displayTitle,
            ),
        )
    }
}

/** Pantallas nuevas de N5 (Android, portadas). La orientación, la fuente y el tema los fija el manifiesto. */
internal val n5CatalogScreens: Map<String, @Composable (DebugIntent) -> Unit> = buildMap {
    // Inicio con las cuatro fuentes: imagen importada (recortada), imagen de la carpeta, captura, GBA y generada; el
    // carril «Continuar jugando» con imagen y captura.
    put("n5-library") { N5Library() }
    put("n5-library-list") { N5Library(LibraryLayout.LIST, LibraryFilter.FAVORITES) }
    // Detalle: la imagen se ve entera dentro del marco de la consola (bandas a los lados o arriba y abajo).
    put("n5-details-imported") { N5Details(N5Data.island) }
    put("n5-details-folder") { N5Details(N5Data.tower) }
    put("n5-details-capture") { N5Details(N5Data.blocks) }
    put("n5-details-generated") { N5Details(N5Data.plain) }
    put("n5-details-landscape") { N5Details(N5Data.island) }
    // Centro de ajustes: fila «Portada» y el diálogo para elegir la fuente o importar.
    put("n5-game-center") { N5Center(dialog = false) }
    put("n5-cover-dialog") { N5Center(dialog = true) }
    put("n5-cover-dialog-failed") { N5Center(dialog = true, failed = true) }
    // Desplazado: «Quitar imagen importada», «Soltar captura fijada» y el consejo.
    put("n5-cover-dialog-actions") { N5Center(dialog = true, imported = true) }
    // Pausa con «Usar como portada».
    put("n5-pause") { _ ->
        GameplayFrame {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                PauseSheet(landscape = false, busy = false, title = "BLOQUES", onContinue = {}, onStates = {}, onCustomize = {}, onExit = {}, onUseAsCover = {})
            }
        }
    }
    // Ajustes › Biblioteca con «Portadas: Preferir imágenes / capturas».
    put("n5-settings-library") { _ ->
        LibrarySettingsContent(
            state = LibraryState.Ready(N5Data.entries, "Roms"),
            folderName = "Roms",
            hidden = emptyList(),
            onChooseFolder = {},
            onRescan = {},
            onForget = {},
            onUnhide = {},
            onBack = {},
            coverPreference = CoverPreference.IMAGES,
        )
    }
    // «Preferir capturas»: Isla Pixel (imagen y captura) pasa a mostrar su captura.
    put("n5-library-prefer-captures") { _ ->
        var prefs by remember { mutableStateOf(N5Data.prefs()) }
        val navigation = remember { AppNavigationState() }
        N5Covers(CoverPreference.CAPTURES) {
            AppScaffold(navigation) {
                LibraryContent(
                    state = LibraryState.Ready(N5Data.entries, "Roms"),
                    prefs = prefs,
                    query = "",
                    filter = LibraryFilter.ALL,
                    onQueryChange = {},
                    onFilterChange = {},
                    onLayoutChange = { prefs = prefs.copy(layout = it) },
                    onSortChange = {},
                    onChooseFolder = {},
                    onRescan = {},
                    actions = n5Actions,
                    artworkFingerprints = n5OwnCovers,
                    onOpenCategory = {},
                )
            }
        }
    }
}

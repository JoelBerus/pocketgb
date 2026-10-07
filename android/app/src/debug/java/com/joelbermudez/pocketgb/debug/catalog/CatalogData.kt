package com.joelbermudez.pocketgb.debug.catalog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.joelbermudez.pocketgb.library.LibraryPreferencesData
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.library.RomProblem
import com.joelbermudez.pocketgb.library.artwork.ArtworkStore
import com.joelbermudez.pocketgb.ui.components.LocalArtworkStore
import java.io.File
import java.security.MessageDigest

/**
 * Datos sintéticos y deterministas del catálogo de capturas (nada de juegos ni capturas comerciales): las mismas
 * entradas, huellas, fechas relativas y portadas generadas con patrones en cada ejecución.
 */
internal object CatalogData {
    private fun entry(id: String, title: String, color: Boolean, size: Long, problem: RomProblem? = null, isNew: Boolean = false) =
        RomEntry(
            id = id,
            uri = "content://demo/$id",
            fileName = id.substringAfterLast('/'),
            title = title,
            isColor = color,
            sizeBytes = size,
            headerChecksumOk = problem == null,
            problem = problem,
            isNew = isNew,
        )

    /** 8 juegos: los tres primeros tienen portada y fecha de juego reciente. */
    val games: List<RomEntry> = listOf(
        entry("Pokemon Red.gb", "POKÉMON RED", false, 1024L * 1024),
        entry("Amarillo/Pokemon Yellow.gbc", "POKÉMON YELLOW", true, 1024L * 1024),
        entry("Demo Adventure.gb", "DEMO ADVENTURE", false, 32L * 1024),
        entry("Color Demo.gbc", "COLOR DEMO", true, 512L * 1024),
        entry("Space Test.gb", "SPACE TEST", false, 64L * 1024),
        entry("Tetra Blocks.gb", "TETRA BLOCKS", false, 32L * 1024),
        entry("Texto Quest.gbc", "TEXTO QUEST", true, 256L * 1024),
        entry("Puzzle Lab.gb", "PUZZLE LAB", false, 128L * 1024),
    )

    val broken: RomEntry = entry("Roto.gb", "ROTO", false, 16, RomProblem.INVALID_HEADER)

    /** Documento remoto aún no descargado (`REMOTE_UNAVAILABLE`). */
    val cloudPending: RomEntry = entry("Nube/Cloud Demo.gb", "CLOUD DEMO", false, 48L * 1024, RomProblem.REMOTE_UNAVAILABLE)

    fun fingerprint(id: String): String =
        MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }

    private val recentIds = games.take(3).map { it.id }

    /** Huellas con portada generada. */
    val coveredFingerprints: Set<String> = recentIds.map(::fingerprint).toSet()

    /**
     * Preferencias de la biblioteca de muestra: huellas de todos, tres recientes (hace 1 h, 5 h y 3 días) y dos
     * favoritos. Las fechas se calculan respecto a [now], así el texto relativo es el mismo en cada captura.
     */
    fun prefs(now: Long = System.currentTimeMillis(), favorites: Set<String> = setOf(games[0].id, games[3].id)): LibraryPreferencesData {
        val hour = 3_600_000L
        return LibraryPreferencesData(
            favorites = favorites,
            lastPlayed = mapOf(
                games[0].id to now - hour,
                games[1].id to now - 5 * hour,
                games[2].id to now - 72 * hour,
            ),
            fingerprints = (games + broken + cloudPending).associate { it.id to fingerprint(it.id) },
            knownIds = games.map { it.id }.toSet(),
        )
    }

    /** `FramePng` espera los fotogramas del núcleo (rojo y azul intercambiados): se invierte para ver los colores buscados. */
    private fun swapRedBlue(argb: Int): Int = (argb and 0xFF00FF00.toInt()) or ((argb and 0xFF) shl 16) or ((argb shr 16) and 0xFF)

    /** Portada de 160x144 con un patrón distinto por semilla (rayas, tablero, anillos, degradado). */
    fun coverPixels(seed: Int): IntArray {
        val palettes = listOf(
            intArrayOf(0xFF0F380F.toInt(), 0xFF306230.toInt(), 0xFF8BAC0F.toInt(), 0xFF9BBC0F.toInt()),
            intArrayOf(0xFF1B2A49.toInt(), 0xFF3D5A98.toInt(), 0xFFE8B04A.toInt(), 0xFFF4E8C8.toInt()),
            intArrayOf(0xFF3A1A3E.toInt(), 0xFF8E3B6E.toInt(), 0xFFE07A5F.toInt(), 0xFFF2E3D0.toInt()),
        )
        val colors = palettes[seed % palettes.size]
        return IntArray(160 * 144) { i ->
            val x = i % 160
            val y = i / 160
            val level = when (seed % 3) {
                0 -> ((x + y) / 12 + seed) % 4
                1 -> ((x / 16 + y / 16) % 2) * 2 + if ((x % 16 < 2) || (y % 16 < 2)) 1 else 0
                else -> {
                    val dx = x - 80
                    val dy = y - 72
                    (((dx * dx + dy * dy) / 600) + seed) % 4
                }
            }
            swapRedBlue(colors[level and 3])
        }
    }
}

/** Da a la UI un almacén de portadas propio con las portadas sintéticas ya escritas (sin hilos: determinista). */
@Composable
internal fun CatalogArtwork(content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) {
        val dir = File(context.cacheDir, "debug-artwork-catalog")
        dir.listFiles()?.forEach { it.delete() }
        ArtworkStore(dir, executor = null).also { store ->
            CatalogData.games.take(3).forEachIndexed { index, game ->
                store.save(CatalogData.fingerprint(game.id), CatalogData.coverPixels(index))
            }
        }
    }
    CompositionLocalProvider(LocalArtworkStore provides store, content = content)
}

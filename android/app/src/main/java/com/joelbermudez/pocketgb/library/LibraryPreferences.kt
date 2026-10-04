package com.joelbermudez.pocketgb.library

import java.io.File
import java.io.FileOutputStream
import java.text.Normalizer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class LibraryLayout(val title: String) {
    GRID("Cuadrícula"),
    LIST("Lista"),
}

@Serializable
enum class LibrarySort(val title: String) {
    TITLE("Nombre"),
    RECENT("Jugados recientemente"),
}

enum class LibraryFilter(val title: String) {
    ALL("Todos"),
    GB("GB"),
    GBC("GBC"),
    FAVORITES("Favoritos"),
}

/**
 * Lo que PocketGB recuerda de cada juego. Solo metadatos de la app: ocultar un juego
 * no toca el ROM, ni su partida, ni sus copias.
 */
@Serializable
data class LibraryPreferencesData(
    val favorites: Set<String> = emptySet(),
    /** Última vez que se abrió cada juego (epoch ms), por id. */
    val lastPlayed: Map<String, Long> = emptyMap(),
    /** Huella SHA-256 del ROM por id; se conoce al abrirlo o ver su detalle. */
    val fingerprints: Map<String, String> = emptyMap(),
    /** Ocultos por huella: sobreviven a mover o renombrar el ROM. */
    val hiddenFingerprints: Set<String> = emptySet(),
    /** Ocultos que aún no tienen huella, por id. */
    val hiddenPaths: Set<String> = emptySet(),
    val layout: LibraryLayout = LibraryLayout.GRID,
    val sort: LibrarySort = LibrarySort.TITLE,
) {
    fun isFavorite(entry: RomEntry) = entry.id in favorites

    fun lastPlayedAt(entry: RomEntry): Long? = lastPlayed[entry.id]

    fun isHidden(entry: RomEntry): Boolean {
        if (entry.id in hiddenPaths) return true
        val fingerprint = fingerprints[entry.id] ?: return false
        return fingerprint in hiddenFingerprints
    }

    fun toggleFavorite(entry: RomEntry) =
        copy(favorites = if (isFavorite(entry)) favorites - entry.id else favorites + entry.id)

    fun recordPlayed(id: String, fingerprint: String, at: Long) =
        copy(lastPlayed = lastPlayed + (id to at), fingerprints = fingerprints + (id to fingerprint))

    fun recordFingerprint(id: String, fingerprint: String) = copy(fingerprints = fingerprints + (id to fingerprint))

    fun hide(entry: RomEntry): LibraryPreferencesData {
        val fingerprint = fingerprints[entry.id]
        return if (fingerprint != null) {
            copy(hiddenFingerprints = hiddenFingerprints + fingerprint)
        } else {
            copy(hiddenPaths = hiddenPaths + entry.id)
        }
    }

    fun unhide(entry: RomEntry): LibraryPreferencesData {
        val fingerprint = fingerprints[entry.id]
        return copy(
            hiddenPaths = hiddenPaths - entry.id,
            hiddenFingerprints = if (fingerprint != null) hiddenFingerprints - fingerprint else hiddenFingerprints,
        )
    }
}

/** Qué juegos se ven y en qué orden. Cuadrícula, lista y Favoritos usan la misma función. */
object LibraryQuery {
    fun matches(entry: RomEntry, filter: LibraryFilter, isFavorite: Boolean): Boolean = when (filter) {
        LibraryFilter.ALL -> true
        LibraryFilter.GB -> !entry.isColor
        LibraryFilter.GBC -> entry.isColor
        LibraryFilter.FAVORITES -> isFavorite
    }

    fun matches(entry: RomEntry, query: String): Boolean {
        val q = fold(query.trim())
        if (q.isEmpty()) return true
        return fold(entry.title).contains(q) || fold(entry.fileName).contains(q)
    }

    fun visible(
        entries: List<RomEntry>,
        prefs: LibraryPreferencesData,
        filter: LibraryFilter,
        query: String,
    ): List<RomEntry> {
        val shown = entries.filter {
            !prefs.isHidden(it) && matches(it, filter, prefs.isFavorite(it)) && matches(it, query)
        }
        return when (prefs.sort) {
            LibrarySort.TITLE -> shown.sortedWith(LibraryScanner.titleOrder)
            LibrarySort.RECENT -> shown.sortedWith(
                compareByDescending<RomEntry> { prefs.lastPlayedAt(it) ?: Long.MIN_VALUE }
                    .then(LibraryScanner.titleOrder),
            )
        }
    }

    /** Juegos que el usuario ocultó, para poder mostrarlos de nuevo. */
    fun hidden(entries: List<RomEntry>, prefs: LibraryPreferencesData): List<RomEntry> =
        entries.filter { prefs.isHidden(it) }.sortedWith(LibraryScanner.titleOrder)

    /** Jugados recientemente (no ocultos), del más reciente al más antiguo. */
    fun recent(entries: List<RomEntry>, prefs: LibraryPreferencesData, limit: Int = 10): List<RomEntry> =
        entries.filter { !prefs.isHidden(it) && prefs.lastPlayedAt(it) != null }
            .sortedByDescending { prefs.lastPlayedAt(it) }
            .take(limit)

    private fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
            .lowercase()
}

/**
 * Persistencia de [LibraryPreferencesData] en un archivo privado, con escritura atómica:
 * temporal sincronizado y renombrado. Un archivo ilegible se aparta como `.corrupt`
 * en vez de sobrescribirse.
 */
class LibraryPreferencesFile(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): LibraryPreferencesData {
        if (!file.exists()) return LibraryPreferencesData()
        return try {
            json.decodeFromString(LibraryPreferencesData.serializer(), file.readText())
        } catch (_: Exception) {
            file.renameTo(File(file.parentFile, file.name + ".corrupt"))
            LibraryPreferencesData()
        }
    }

    fun save(data: LibraryPreferencesData) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use { out ->
            out.write(json.encodeToString(LibraryPreferencesData.serializer(), data).toByteArray())
            out.fd.sync()
        }
        if (!temp.renameTo(file)) {
            temp.delete()
            throw java.io.IOException("No se pudo reemplazar ${file.name}")
        }
    }
}

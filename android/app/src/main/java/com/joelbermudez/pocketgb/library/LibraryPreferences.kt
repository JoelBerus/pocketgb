package com.joelbermudez.pocketgb.library

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.Normalizer
import kotlinx.serialization.SerializationException
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

/** Resultado de intentar llevar las preferencias a disco. */
sealed interface PersistResult {
    data object Saved : PersistResult

    /** La última versión sigue solo en memoria; se reintenta con el siguiente cambio, al parar o al salir. */
    data class Failed(val error: IOException) : PersistResult
}

/** Dónde viven las preferencias. Interfaz pequeña para inyectar fallos de I/O en las pruebas. */
interface PreferencesStore {
    /** Lee las preferencias. Falla con [IOException] si el disco no responde (no es "corrupto"). */
    fun load(): LibraryPreferencesData

    fun save(data: LibraryPreferencesData)
}

/** Operaciones de archivo de [LibraryPreferencesFile], separadas para poder probar fallos a mitad de la escritura. */
interface PreferencesFileOps {
    /** Escribe todos los bytes y los sincroniza a disco. */
    fun writeSynced(file: File, bytes: ByteArray)

    fun rename(from: File, to: File): Boolean
}

object DefaultPreferencesFileOps : PreferencesFileOps {
    override fun writeSynced(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
    }

    override fun rename(from: File, to: File): Boolean = from.renameTo(to)
}

/**
 * Persistencia de [LibraryPreferencesData] en un archivo privado, con escritura atómica:
 * temporal sincronizado y renombrado. Si el rename no ocurre, el archivo previo queda intacto.
 *
 * - Un error de I/O al leer se propaga: no es motivo para apartar ni sobrescribir el archivo.
 * - Un contenido que no se puede interpretar se aparta como `.corrupt-<timestamp>` (sin pisar uno previo).
 * - Si falta el archivo pero hay un temporal completo (se cortó la primera escritura justo antes del rename),
 *   se recupera el temporal.
 */
class LibraryPreferencesFile(
    private val file: File,
    private val ops: PreferencesFileOps = DefaultPreferencesFileOps,
    private val clock: () -> Long = System::currentTimeMillis,
) : PreferencesStore {
    // coerceInputValues: un enum desconocido (versión futura) cae en su valor por defecto sin descartar el resto.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }
    private val temp get() = File(file.parentFile, file.name + ".tmp")

    override fun load(): LibraryPreferencesData {
        if (!file.exists()) return recoverTemp() ?: LibraryPreferencesData()
        val text = file.readText()
        return decode(text) ?: run {
            quarantine()
            LibraryPreferencesData()
        }
    }

    override fun save(data: LibraryPreferencesData) {
        file.parentFile?.mkdirs()
        val temp = temp
        try {
            ops.writeSynced(temp, json.encodeToString(LibraryPreferencesData.serializer(), data).toByteArray())
        } catch (error: IOException) {
            temp.delete()
            throw error
        }
        if (!ops.rename(temp, file)) {
            temp.delete()
            throw IOException("No se pudo reemplazar ${file.name}")
        }
    }

    private fun decode(text: String): LibraryPreferencesData? = try {
        json.decodeFromString(LibraryPreferencesData.serializer(), text)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun recoverTemp(): LibraryPreferencesData? {
        val temp = temp
        if (!temp.exists()) return null
        val data = decode(temp.readText()) ?: return null
        ops.rename(temp, file) // si falla, la próxima escritura lo regenera igualmente
        return data
    }

    private fun quarantine() {
        val base = "${file.name}.corrupt-${clock()}"
        var target = File(file.parentFile, base)
        var n = 1
        while (target.exists()) target = File(file.parentFile, "$base-${n++}")
        if (!file.renameTo(target)) throw IOException("No se pudo apartar ${file.name} ilegible")
    }
}

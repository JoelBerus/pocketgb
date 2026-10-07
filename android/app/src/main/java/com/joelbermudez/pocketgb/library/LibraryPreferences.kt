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
    /** K19: ids ya vistos y reconocidos (abiertos o presentes en el primer escaneo de la carpeta). */
    val knownIds: Set<String> = emptySet(),
    /** A9: nombre visible estable por huella, cuando ya se conoce el contenido del ROM (iOS D8.1). */
    val aliasesByFingerprint: Map<String, String> = emptyMap(),
    /** A9: nombre visible provisional por id (ruta) antes de conocer la huella; se migra al conocerla. */
    val aliasesByPath: Map<String, String> = emptyMap(),
) {
    fun acknowledge(ids: Set<String>) = if (knownIds.containsAll(ids)) this else copy(knownIds = knownIds + ids)

    fun isFavorite(entry: RomEntry) = entry.id in favorites

    fun lastPlayedAt(entry: RomEntry): Long? = lastPlayed[entry.id]

    fun isHidden(entry: RomEntry): Boolean {
        if (entry.id in hiddenPaths) return true
        val fingerprint = fingerprints[entry.id] ?: return false
        return fingerprint in hiddenFingerprints
    }

    fun toggleFavorite(entry: RomEntry) =
        copy(favorites = if (isFavorite(entry)) favorites - entry.id else favorites + entry.id)

    /** Al abrir un juego: fecha y huella. Un alias provisional por ruta pasa a la huella (iOS `recordPlayed`). */
    fun recordPlayed(id: String, fingerprint: String, at: Long) =
        copy(lastPlayed = lastPlayed + (id to at)).recordFingerprint(id, fingerprint)

    /** Huella conocida (al abrir o al ver el detalle). Migra el alias provisional por ruta a la huella. */
    fun recordFingerprint(id: String, fingerprint: String): LibraryPreferencesData {
        val pathAlias = aliasesByPath[id]
        return copy(
            fingerprints = fingerprints + (id to fingerprint),
            aliasesByFingerprint = if (pathAlias != null) aliasesByFingerprint + (fingerprint to pathAlias) else aliasesByFingerprint,
            aliasesByPath = if (pathAlias != null) aliasesByPath - id else aliasesByPath,
        )
    }

    /** Alias del juego, o `null` si se muestra el título de la cabecera. */
    fun aliasOf(entry: RomEntry): String? {
        val fingerprint = fingerprints[entry.id]
        if (fingerprint != null) aliasesByFingerprint[fingerprint]?.let { return it }
        return aliasesByPath[entry.id]
    }

    /** Nombre que ve el usuario: el alias o, si no hay, el título de la cabecera. */
    fun displayTitle(entry: RomEntry): String = aliasOf(entry) ?: entry.title

    /** [entry] con su alias aplicado ([RomEntry.displayTitle]); el título de la cabecera no cambia. */
    fun withAlias(entry: RomEntry): RomEntry {
        val alias = aliasOf(entry)
        return if (alias == entry.alias) entry else entry.copy(alias = alias)
    }

    /**
     * Renombrar (A9, semántica de iOS D8.1): solo cambia la presentación, nunca el ROM ni sus partidas. Se recorta,
     * los saltos de línea pasan a espacios y se limita a [Alias.MAX_LENGTH] caracteres visibles. Vacío (o igual al
     * título de la cabecera) vuelve al título de la cabecera. Va por huella si se conoce y por ruta si no.
     */
    fun setAlias(entry: RomEntry, value: String): LibraryPreferencesData {
        val alias = Alias.normalize(value)?.takeUnless { it == entry.title }
        val fingerprint = fingerprints[entry.id]
        return if (fingerprint != null) {
            copy(
                aliasesByFingerprint = if (alias == null) aliasesByFingerprint - fingerprint else aliasesByFingerprint + (fingerprint to alias),
                aliasesByPath = aliasesByPath - entry.id,
            )
        } else {
            copy(aliasesByPath = if (alias == null) aliasesByPath - entry.id else aliasesByPath + (entry.id to alias))
        }
    }

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

    /** Busca en el alias (si lo hay), en el título de la cabecera y en el nombre del archivo (A9). */
    fun matches(entry: RomEntry, query: String): Boolean {
        val q = fold(query.trim())
        if (q.isEmpty()) return true
        return fold(entry.displayTitle).contains(q) || fold(entry.title).contains(q) || fold(entry.fileName).contains(q)
    }

    fun visible(
        entries: List<RomEntry>,
        prefs: LibraryPreferencesData,
        filter: LibraryFilter,
        query: String,
    ): List<RomEntry> {
        // Con el alias aplicado (A9): la búsqueda, el orden y la UI usan el nombre visible.
        val shown = entries.filter { !prefs.isHidden(it) && matches(it, filter, prefs.isFavorite(it)) }
            .map(prefs::withAlias)
            .filter { matches(it, query) }
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
        entries.filter { prefs.isHidden(it) }.map(prefs::withAlias).sortedWith(LibraryScanner.titleOrder)

    /** Máximo de juegos del carril «Continuar jugando» (K10). */
    const val CONTINUE_LIMIT = 5

    /**
     * Jugados recientemente (no ocultos), del más reciente al más antiguo. Con [hasArtwork] solo quedan los
     * jugables cuya huella tiene portada capturada (carril «Continuar jugando», K10): nunca una portada inventada.
     */
    fun recent(
        entries: List<RomEntry>,
        prefs: LibraryPreferencesData,
        limit: Int = CONTINUE_LIMIT,
        hasArtwork: ((String) -> Boolean)? = null,
    ): List<RomEntry> =
        entries.filter {
            !prefs.isHidden(it) && prefs.lastPlayedAt(it) != null &&
                (hasArtwork == null || it.isPlayable && prefs.fingerprints[it.id]?.let(hasArtwork) == true)
        }
            .sortedByDescending { prefs.lastPlayedAt(it) }
            .take(limit)
            .map(prefs::withAlias)

    private fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
            .lowercase()
}

/** Normalización del alias (A9): misma regla que iOS D8.1 (`setAlias`), sin partir caracteres visibles. */
object Alias {
    /** Máximo de caracteres visibles (grafemas) de un alias, como iOS. */
    const val MAX_LENGTH = 80

    /** Recortado, con los saltos de línea y tabuladores como espacios y limitado a [MAX_LENGTH]; `null` si queda vacío. */
    fun normalize(value: String): String? {
        val flat = value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim()
        if (flat.isEmpty()) return null
        return truncate(flat, MAX_LENGTH).trimEnd()
    }

    /** Los primeros [max] grafemas de [text] (un emoji o una letra con acento combinante cuentan como uno). */
    fun truncate(text: String, max: Int): String {
        val iterator = java.text.BreakIterator.getCharacterInstance()
        iterator.setText(text)
        var end = 0
        var count = 0
        while (count < max) {
            val next = iterator.next()
            if (next == java.text.BreakIterator.DONE) return text
            end = next
            count++
        }
        return text.substring(0, end)
    }

    /** Cuántos grafemas tiene [text] (para el contador del campo). */
    fun length(text: String): Int {
        val iterator = java.text.BreakIterator.getCharacterInstance()
        iterator.setText(text)
        var count = 0
        while (iterator.next() != java.text.BreakIterator.DONE) count++
        return count
    }
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

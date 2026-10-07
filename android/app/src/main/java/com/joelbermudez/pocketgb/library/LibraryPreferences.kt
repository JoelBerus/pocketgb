package com.joelbermudez.pocketgb.library

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.Normalizer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

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
 * Versiones de `preferences.json` (N1a). Un archivo sin `formatVersion` es de A5–A9 ([LEGACY]): favoritos y «jugado»
 * por ruta. Al leerlo se migra ([LibraryPreferencesData.migrated]) y se escribe como [CURRENT].
 */
object LibraryPreferencesFormat {
    const val LEGACY = 1

    /** N1a: favoritos, «jugado», ocultos y alias por huella; la ruta solo es provisional; caché de sellos por ruta. */
    const val CURRENT = 2
}

/**
 * Lo que PocketGB recuerda de cada juego. Solo metadatos de la app: ocultar un juego
 * no toca el ROM, ni su partida, ni sus copias.
 *
 * Identidad (N1a): todo lo del juego va por **huella** (SHA-256 del ROM) en cuanto se conoce, así que sobrevive a mover o
 * renombrar el archivo. Antes de conocerla, la ruta relativa ([RomEntry.id]) es una clave provisional que se migra al
 * conocer la huella ([recordFingerprint]). La huella se conoce al abrir el juego o ver su detalle (en Android nunca se
 * calcula en segundo plano: en Drive leer el ROM lo descarga) y, sin leerlo, cuando un reescaneo reconoce que una ruta
 * nueva es un documento ya visto ([reconciled]).
 */
@Serializable
data class LibraryPreferencesData(
    /** Favoritos provisionales por id (ruta), sin huella conocida. */
    val favorites: Set<String> = emptySet(),
    /** Última vez que se abrió cada juego (epoch ms), provisional por id (ruta), sin huella conocida. */
    val lastPlayed: Map<String, Long> = emptyMap(),
    /** Huella SHA-256 del ROM por id; se conoce al abrirlo, al ver su detalle o al reconocer que se movió (N1a). */
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
    /** N1a: favoritos por huella. */
    val favoriteFingerprints: Set<String> = emptySet(),
    /** N1a: última vez que se abrió cada juego (epoch ms), por huella. */
    val lastPlayedByFingerprint: Map<String, Long> = emptyMap(),
    /**
     * N1a: caché por documento. Sello (nombre, tamaño, fecha) de cada ruta del último escaneo; junto con [fingerprints]
     * da la terna → huella con la que se reconoce un ROM movido sin leerlo.
     */
    val documents: Map<String, DocumentStamp> = emptyMap(),
    /** N1a: versión del formato ([LibraryPreferencesFormat]). */
    val formatVersion: Int = LibraryPreferencesFormat.CURRENT,
) {
    fun acknowledge(ids: Set<String>) = if (knownIds.containsAll(ids)) this else copy(knownIds = knownIds + ids)

    fun isFavorite(entry: RomEntry): Boolean =
        entry.id in favorites || fingerprints[entry.id]?.let { it in favoriteFingerprints } == true

    /** La fecha más reciente entre la de la huella y la provisional de la ruta. */
    fun lastPlayedAt(entry: RomEntry): Long? {
        val byFingerprint = fingerprints[entry.id]?.let(lastPlayedByFingerprint::get)
        val byPath = lastPlayed[entry.id]
        return if (byFingerprint == null) byPath else if (byPath == null) byFingerprint else maxOf(byFingerprint, byPath)
    }

    fun isHidden(entry: RomEntry): Boolean {
        if (entry.id in hiddenPaths) return true
        val fingerprint = fingerprints[entry.id] ?: return false
        return fingerprint in hiddenFingerprints
    }

    /** Por huella si se conoce; si no, provisional por ruta. Quitarlo lo quita de ambos sitios. */
    fun toggleFavorite(entry: RomEntry): LibraryPreferencesData {
        val fingerprint = fingerprints[entry.id]
        return when {
            isFavorite(entry) -> copy(
                favorites = favorites - entry.id,
                favoriteFingerprints = if (fingerprint != null) favoriteFingerprints - fingerprint else favoriteFingerprints,
            )
            fingerprint != null -> copy(favoriteFingerprints = favoriteFingerprints + fingerprint)
            else -> copy(favorites = favorites + entry.id)
        }
    }

    /** Al abrir un juego: fecha (por huella) y huella, con la migración de lo provisional ([recordFingerprint]). */
    fun recordPlayed(id: String, fingerprint: String, at: Long): LibraryPreferencesData {
        val known = recordFingerprint(id, fingerprint)
        return known.copy(lastPlayedByFingerprint = known.lastPlayedByFingerprint + (fingerprint to at))
    }

    /**
     * Huella conocida para la ruta [id] (al abrir, al ver el detalle o al reconocer un movimiento). Lo provisional de
     * esa ruta (favorito, fecha, oculto y alias) pasa a la huella (iOS `recordPlayed`). Si no hay nada que cambiar
     * devuelve el mismo valor (no provoca escrituras).
     */
    fun recordFingerprint(id: String, fingerprint: String): LibraryPreferencesData {
        val pathAlias = aliasesByPath[id]
        val pathPlayed = lastPlayed[id]
        val pathFavorite = id in favorites
        val pathHidden = id in hiddenPaths
        if (fingerprints[id] == fingerprint && pathAlias == null && pathPlayed == null && !pathFavorite && !pathHidden) return this
        val fingerprintPlayed = lastPlayedByFingerprint[fingerprint]
        return copy(
            fingerprints = fingerprints + (id to fingerprint),
            favorites = favorites - id,
            favoriteFingerprints = if (pathFavorite) favoriteFingerprints + fingerprint else favoriteFingerprints,
            lastPlayed = lastPlayed - id,
            lastPlayedByFingerprint = if (pathPlayed == null) {
                lastPlayedByFingerprint
            } else {
                lastPlayedByFingerprint + (fingerprint to maxOf(pathPlayed, fingerprintPlayed ?: Long.MIN_VALUE))
            },
            hiddenPaths = hiddenPaths - id,
            hiddenFingerprints = if (pathHidden) hiddenFingerprints + fingerprint else hiddenFingerprints,
            aliasesByFingerprint = if (pathAlias != null) aliasesByFingerprint + (fingerprint to pathAlias) else aliasesByFingerprint,
            aliasesByPath = if (pathAlias != null) aliasesByPath - id else aliasesByPath,
        )
    }

    /**
     * N1a: migración desde [LibraryPreferencesFormat.LEGACY] (A5–A9). Cada ruta con huella conocida lleva sus
     * registros provisionales a la huella; lo que no tiene huella sigue por ruta. Lo que ve el usuario no cambia.
     */
    fun migrated(): LibraryPreferencesData =
        fingerprints.entries.fold(this) { data, (id, fingerprint) -> data.recordFingerprint(id, fingerprint) }
            .copy(formatVersion = LibraryPreferencesFormat.CURRENT)

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
        val copies = copies(entries, prefs)
        // Con el alias aplicado (A9): la búsqueda, el orden y la UI usan el nombre visible. Con las copias (N1a).
        val shown = entries.filter { !prefs.isHidden(it) && matches(it, filter, prefs.isFavorite(it)) }
            .map { present(it, prefs, copies) }
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
    fun hidden(entries: List<RomEntry>, prefs: LibraryPreferencesData): List<RomEntry> {
        val copies = copies(entries, prefs)
        return entries.filter { prefs.isHidden(it) }.map { present(it, prefs, copies) }.sortedWith(LibraryScanner.titleOrder)
    }

    /** Máximo de juegos del carril «Continuar jugando» (K10). */
    const val CONTINUE_LIMIT = 5

    /**
     * Jugados recientemente (no ocultos), del más reciente al más antiguo. Con [hasArtwork] solo quedan los
     * jugables cuya huella tiene portada capturada (carril «Continuar jugando», K10): nunca una portada inventada.
     * Un juego con varias copias (N1a) sale una sola vez (la primera por ruta).
     */
    fun recent(
        entries: List<RomEntry>,
        prefs: LibraryPreferencesData,
        limit: Int = CONTINUE_LIMIT,
        hasArtwork: ((String) -> Boolean)? = null,
    ): List<RomEntry> {
        val copies = copies(entries, prefs)
        return entries.filter {
            !prefs.isHidden(it) && prefs.lastPlayedAt(it) != null &&
                (hasArtwork == null || it.isPlayable && prefs.fingerprints[it.id]?.let(hasArtwork) == true)
        }
            .sortedWith(compareByDescending<RomEntry> { prefs.lastPlayedAt(it) }.thenBy { it.id })
            .distinctBy { prefs.fingerprints[it.id] ?: "ruta:${it.id}" }
            .take(limit)
            .map { present(it, prefs, copies) }
    }

    /**
     * N1a: para cada id con copias, dónde están las demás copias presentes de su misma huella **conocida** (ordenadas por
     * ruta). Una huella desconocida nunca se adivina por título o tamaño.
     */
    fun copies(entries: List<RomEntry>, prefs: LibraryPreferencesData): Map<String, List<RomLocation>> {
        val groups = entries.groupBy { prefs.fingerprints[it.id] }.filter { (fingerprint, group) -> fingerprint != null && group.size > 1 }
        if (groups.isEmpty()) return emptyMap()
        val result = HashMap<String, List<RomLocation>>()
        for (group in groups.values) {
            val ordered = group.sortedWith { a, b -> NaturalOrder.compare(a.id, b.id) }
            for (entry in ordered) result[entry.id] = ordered.filter { it.id != entry.id }.map { it.location }
        }
        return result
    }

    /** N1a: el juego [id] tal como se muestra en el detalle (alias y copias), o `null` si ya no está en la carpeta. */
    fun presented(entries: List<RomEntry>, prefs: LibraryPreferencesData, id: String): RomEntry? {
        val entry = entries.firstOrNull { it.id == id } ?: return null
        return present(entry, prefs, copies(entries, prefs))
    }

    private fun present(entry: RomEntry, prefs: LibraryPreferencesData, copies: Map<String, List<RomLocation>>): RomEntry {
        val withAlias = prefs.withAlias(entry)
        val others = copies[entry.id].orEmpty()
        return if (others == withAlias.alsoAt) withAlias else withAlias.copy(alsoAt = others)
    }

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

    /**
     * Decodifica y, si el archivo es de una versión anterior (sin `formatVersion`: A5–A9), lo migra (N1a). Un archivo de
     * una versión futura conserva lo que esta versión conoce y se escribirá como la actual.
     */
    private fun decode(text: String): LibraryPreferencesData? = try {
        val data = json.decodeFromString(LibraryPreferencesData.serializer(), text)
        val version = (json.parseToJsonElement(text) as? JsonObject)?.get("formatVersion")
            ?.let { it as? JsonPrimitive }?.intOrNull ?: LibraryPreferencesFormat.LEGACY
        if (version < LibraryPreferencesFormat.CURRENT) data.migrated() else data.copy(formatVersion = LibraryPreferencesFormat.CURRENT)
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

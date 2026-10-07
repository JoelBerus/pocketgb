package com.joelbermudez.pocketgb.library

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.Normalizer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
    /** N8: solo Game Boy Advance (= iOS `.gba`). */
    GBA("GBA"),
    FAVORITES("Favoritos"),
}

/**
 * Versiones de `preferences.json` (N1a). Un archivo sin `formatVersion` es de A5–A9 ([LEGACY]): favoritos y «jugado»
 * por ruta. Al leerlo se migra ([LibraryPreferencesData.migrated]) y se escribe como [CURRENT].
 */
object LibraryPreferencesFormat {
    const val LEGACY = 1

    /** N1a: favoritos, «jugado», ocultos y alias por huella; la ruta solo es provisional; caché de sellos por ruta. */
    const val V2 = 2

    /**
     * N1-V2: además lápidas (`tombstones`) y huellas sin confirmar (`inferredFingerprints`). Un archivo v2 se lee igual
     * (esas claves toman su valor vacío) y se escribe como v3; una app v2 no sobrescribirá un v3 (lo verá como futuro).
     */
    const val V3 = 3

    /**
     * N4: además etiquetas y categoría virtual por huella (`tagsByFingerprint`, `virtualFoldersByFingerprint`), ajustes
     * del inicio (`home`) y vista por categoría (`categoryLayouts`), todo por dispositivo (ND12). Un archivo v2 o v3 se
     * lee igual (esas claves toman su valor vacío) y se escribe como v4; una app v3 no sobrescribirá un v4.
     */
    const val CURRENT = 4
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
    /** N1-H4: rutas desaparecidas que se pueden recuperar si el documento vuelve ([reconciled]). */
    val tombstones: List<Tombstone> = emptyList(),
    /**
     * N1-H1: rutas cuya huella se heredó sin leer el ROM (movimiento o lápida) y aún no se ha confirmado. Se confirma al
     * abrir el juego o ver su detalle ([recordFingerprint]); los ajustes del juego la confirman antes de escribir.
     */
    val inferredFingerprints: Set<String> = emptySet(),
    /** N4: etiquetas libres por huella, en orden natural ([Tags]). */
    val tagsByFingerprint: Map<String, List<String>> = emptyMap(),
    /**
     * N4 (ND3): categoría virtual por huella («Mostrar en categoría…»): la ruta de carpetas en la que se ve el juego en
     * vez de la de su carpeta (vacía = «Sin categoría»). La app lo recuerda sin tocar los archivos.
     */
    val virtualFoldersByFingerprint: Map<String, List<String>> = emptyMap(),
    /** N4: ajustes del inicio de este dispositivo (ND12): orden, fijadas, ocultas y fila de Favoritos. */
    val home: HomeSettings = HomeSettings(),
    /** N4: vista (cuadrícula o lista) de cada pantalla de categoría ([LibraryCategory.key]); sin valor, [layout]. */
    val categoryLayouts: Map<String, LibraryLayout> = emptyMap(),
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
        val wasInferred = id in inferredFingerprints
        if (fingerprints[id] == fingerprint && pathAlias == null && pathPlayed == null && !pathFavorite && !pathHidden && !wasInferred) {
            return this
        }
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
            // Leída del ROM: ya no es una huella heredada sin confirmar (N1-H1).
            inferredFingerprints = if (wasInferred) inferredFingerprints - id else inferredFingerprints,
        )
    }

    /** N1-H1: la huella de [entry] es conocida y salió de leer su ROM (no heredada de un movimiento sin confirmar). */
    fun hasConfirmedFingerprint(entry: RomEntry): Boolean = entry.id in fingerprints && entry.id !in inferredFingerprints

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

    // ---- N4: etiquetas, categoría virtual y vista por categoría ----

    /** Etiquetas del juego (por su huella conocida, también una heredada sin confirmar: solo para mostrar). */
    fun tagsOf(entry: RomEntry): List<String> = fingerprints[entry.id]?.let(tagsByFingerprint::get).orEmpty()

    /**
     * Añade la etiqueta [raw] ([Tags.normalize]) al juego. Solo con la huella confirmada (N1-H1): sin ella no se escribe
     * nada (quien llama la confirma antes leyendo el ROM). Una etiqueta que ya tiene (sin distinguir mayúsculas ni
     * acentos) o pasar de [Tags.MAX_PER_GAME] no cambia nada.
     */
    fun addTag(entry: RomEntry, raw: String): LibraryPreferencesData {
        val fingerprint = confirmedFingerprint(entry) ?: return this
        val current = tagsByFingerprint[fingerprint].orEmpty()
        val next = Tags.added(current, raw)
        return if (next == current) this else copy(tagsByFingerprint = tagsByFingerprint + (fingerprint to next))
    }

    /** Quita la etiqueta [tag] (sin distinguir mayúsculas ni acentos); sin etiquetas no queda entrada. */
    fun removeTag(entry: RomEntry, tag: String): LibraryPreferencesData {
        val fingerprint = confirmedFingerprint(entry) ?: return this
        val current = tagsByFingerprint[fingerprint] ?: return this
        val next = Tags.removed(current, tag)
        return when {
            next == current -> this
            next.isEmpty() -> copy(tagsByFingerprint = tagsByFingerprint - fingerprint)
            else -> copy(tagsByFingerprint = tagsByFingerprint + (fingerprint to next))
        }
    }

    /** N4 (ND3): categoría virtual del juego, o `null` si se ve en la de su carpeta. */
    fun virtualFolderOf(entry: RomEntry): List<String>? = fingerprints[entry.id]?.let(virtualFoldersByFingerprint::get)

    /** La categoría en la que se ve el juego: la virtual o la de su carpeta. */
    fun categoryPathOf(entry: RomEntry): List<String> = virtualFolderOf(entry) ?: entry.folderPath

    /**
     * «Mostrar en categoría…» (ND3): el juego se ve en [path] (ya validada, [CategoryPaths.parse]; vacía = «Sin
     * categoría») en vez de en la de su carpeta. Nunca toca el archivo. Elegir su propia carpeta es volver a ella. Solo
     * con la huella confirmada (N1-H1); si no, no se escribe nada.
     */
    fun moveToCategory(entry: RomEntry, path: List<String>): LibraryPreferencesData {
        val fingerprint = confirmedFingerprint(entry) ?: return this
        if (path == entry.folderPath) return returnToFolder(entry)
        if (virtualFoldersByFingerprint[fingerprint] == path) return this
        return copy(virtualFoldersByFingerprint = virtualFoldersByFingerprint + (fingerprint to path.toList()))
    }

    /** «Volver a su carpeta»: quita la categoría virtual. */
    fun returnToFolder(entry: RomEntry): LibraryPreferencesData {
        val fingerprint = confirmedFingerprint(entry) ?: return this
        if (fingerprint !in virtualFoldersByFingerprint) return this
        return copy(virtualFoldersByFingerprint = virtualFoldersByFingerprint - fingerprint)
    }

    /** Vista de la pantalla de [category]: la suya si se eligió, si no la de la biblioteca. */
    fun layoutFor(category: LibraryCategory): LibraryLayout = categoryLayouts[category.key] ?: layout

    fun withCategoryLayout(category: LibraryCategory, value: LibraryLayout): LibraryPreferencesData {
        if (categoryLayouts[category.key] == value) return this
        val next = (categoryLayouts - category.key).entries.take(HomeSettings.MAX_KEYS - 1).associate { it.key to it.value }
        return copy(categoryLayouts = next + (category.key to value))
    }

    private fun confirmedFingerprint(entry: RomEntry): String? =
        fingerprints[entry.id]?.takeIf { hasConfirmedFingerprint(entry) }

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
        LibraryFilter.GB -> entry.console == RomConsole.GB
        LibraryFilter.GBC -> entry.console == RomConsole.GBC
        LibraryFilter.GBA -> entry.console == RomConsole.GBA
        LibraryFilter.FAVORITES -> isFavorite
    }

    /**
     * Busca en el alias (si lo hay), en el título de la cabecera y en el nombre del archivo (A9) y, en [entry] presentado,
     * en la categoría en la que se ve (cada nivel, N4), en sus etiquetas (N4) y en su consola (N8).
     */
    fun matches(entry: RomEntry, query: String): Boolean {
        val q = fold(query.trim())
        if (q.isEmpty()) return true
        return fold(entry.displayTitle).contains(q) || fold(entry.title).contains(q) || fold(entry.fileName).contains(q) ||
            entry.categoryPath.any { fold(it).contains(q) } || entry.tags.any { fold(it).contains(q) } ||
            matchesConsole(entry.console, q)
    }

    /**
     * N8: la consola también se busca: su nombre corto exacto («gba», «gbc») o, desde 4 letras, su nombre completo
     * («advance», «game boy color»).
     */
    private fun matchesConsole(console: RomConsole, folded: String): Boolean =
        fold(console.shortName) == folded || (folded.length >= 4 && fold(console.displayName).contains(folded))

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

    /** Con alias, copias (N1a), etiquetas y categoría virtual (N4) aplicados. */
    private fun present(entry: RomEntry, prefs: LibraryPreferencesData, copies: Map<String, List<RomLocation>>): RomEntry {
        val withAlias = prefs.withAlias(entry)
        val others = copies[entry.id].orEmpty()
        val tags = prefs.tagsOf(entry)
        val virtual = prefs.virtualFolderOf(entry)
        if (others == withAlias.alsoAt && tags == withAlias.tags && virtual == withAlias.virtualFolderPath) return withAlias
        return withAlias.copy(alsoAt = others, tags = tags, virtualFolderPath = virtual)
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

    /**
     * N1-H2: el último [load] encontró un archivo de una versión futura o con una versión que no se entiende. Esta
     * versión de la app no lo reescribe ([save] falla con [PreferencesWriteProtectedException]): los cambios quedan en
     * memoria y la UI lo avisa.
     */
    val writeProtected: Boolean get() = false
}

/** N1-H2: no se escribe `preferences.json` porque es de otra versión de la app ([version] tal como venía). */
class PreferencesWriteProtectedException(val version: String) :
    IOException("preferences.json es de otra versión ($version): no se sobrescribe")

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

    /** Versión del archivo tal como venía, si no es una que esta app sepa escribir (futura o ilegible). */
    @Volatile private var foreignVersion: String? = null

    override val writeProtected: Boolean get() = foreignVersion != null

    override fun load(): LibraryPreferencesData {
        foreignVersion = null
        if (!file.exists()) return recoverTemp() ?: LibraryPreferencesData()
        val text = file.readText()
        return decode(text) ?: run {
            quarantine()
            LibraryPreferencesData()
        }
    }

    override fun save(data: LibraryPreferencesData) {
        foreignVersion?.let { throw PreferencesWriteProtectedException(it) }
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
     * Decodifica (N1-H2/H3, N1-V2-H3). La versión se decide **antes** de decodificar, leída con tolerancia:
     * - sin `formatVersion` (o `null`) o `1`: A5–A9, se decodifica y se migra ([LibraryPreferencesData.migrated]);
     * - `2`, `3` o la actual (también `2.0` o `"4"`): se decodifica tal cual (N4: lo nuevo de v4 toma su valor vacío);
     * - futura, no entera, no numérica o menor que 1: versión ajena. Se decodifica con tolerancia (las claves que esta
     *   versión no sabe leer se ignoran una a una), sin migrar, y el archivo queda protegido contra escritura
     *   ([writeProtected]). Un archivo de versión ajena nunca se aparta como corrupto.
     * Solo un JSON ilegible, o uno de versión conocida que no se puede decodificar, devuelve `null` (se aparta).
     */
    private fun decode(text: String): LibraryPreferencesData? {
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: return null
        val body = JsonObject(root - VERSION_KEY)
        return when (val version = versionOf(root[VERSION_KEY])) {
            LibraryPreferencesFormat.LEGACY -> decodeStrict(body)?.migrated()
            LibraryPreferencesFormat.V2, LibraryPreferencesFormat.V3, LibraryPreferencesFormat.CURRENT ->
                decodeStrict(body)?.copy(formatVersion = LibraryPreferencesFormat.CURRENT)
            else -> {
                foreignVersion = root[VERSION_KEY].toString()
                decodeTolerant(body).copy(formatVersion = version ?: LibraryPreferencesFormat.CURRENT)
            }
        }
    }

    private fun decodeStrict(body: JsonObject): LibraryPreferencesData? = try {
        json.decodeFromString(LibraryPreferencesData.serializer(), body.toString())
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Clave a clave: lo que esta versión no sabe leer (otro tipo en una versión futura) se ignora; nunca falla. */
    private fun decodeTolerant(body: JsonObject): LibraryPreferencesData {
        decodeStrict(body)?.let { return it }
        val readable = body.filter { (key, value) -> decodeStrict(JsonObject(mapOf(key to value))) != null }
        return decodeStrict(JsonObject(readable)) ?: LibraryPreferencesData()
    }

    /**
     * [LibraryPreferencesFormat.LEGACY] si falta; la versión si es un entero conocido; `CURRENT + 1` o más si es futura;
     * `null` si no se entiende (no numérica, con decimales, menor que 1 o fuera de rango).
     */
    private fun versionOf(element: JsonElement?): Int? {
        if (element == null || element is JsonNull) return LibraryPreferencesFormat.LEGACY
        val primitive = element as? JsonPrimitive ?: return null
        val number = primitive.content.trim().toBigDecimalOrNull() ?: return null
        val integral = try {
            number.toBigIntegerExact()
        } catch (_: ArithmeticException) {
            return null
        }
        if (integral < java.math.BigInteger.ONE) return null
        return if (integral > java.math.BigInteger.valueOf(Int.MAX_VALUE.toLong())) Int.MAX_VALUE else integral.toInt()
    }

    private companion object {
        const val VERSION_KEY = "formatVersion"
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

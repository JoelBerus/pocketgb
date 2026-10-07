package com.joelbermudez.pocketgb.library

import java.text.Normalizer
import kotlinx.serialization.Serializable

/**
 * N4 · etiquetas libres por juego. Se guardan por huella ([LibraryPreferencesData.tagsByFingerprint]) en orden natural;
 * dos escrituras de la misma etiqueta («RPG» y «rpg», «acción» y «accion») son la misma.
 */
object Tags {
    /** Máximo de caracteres visibles de una etiqueta. */
    const val MAX_LENGTH = 30

    /** Máximo de etiquetas por juego. */
    const val MAX_PER_GAME = 20

    /** Recortada, sin `#` delante, con los espacios seguidos como uno y limitada a [MAX_LENGTH]; `null` si queda vacía. */
    fun normalize(raw: String): String? {
        val flat = raw.replace(Regex("\\s+"), " ").trim().trimStart('#').trim()
        if (flat.isEmpty()) return null
        return Alias.truncate(flat, MAX_LENGTH).trimEnd()
    }

    /** Clave de comparación: sin mayúsculas ni acentos. */
    fun key(tag: String): String = fold(tag.trim())

    fun same(a: String, b: String): Boolean = key(a) == key(b)

    /** [current] con [tag] (normalizada) si no estaba y si cabe; en orden natural. */
    fun added(current: List<String>, raw: String): List<String> {
        val tag = normalize(raw) ?: return current
        if (current.any { same(it, tag) } || current.size >= MAX_PER_GAME) return current
        return (current + tag).sortedWith { a, b -> NaturalOrder.compare(a, b) }
    }

    fun removed(current: List<String>, tag: String): List<String> = current.filterNot { same(it, tag) }

    internal fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
            .lowercase()
}

/**
 * N4 · rutas de categoría (carpetas reales o virtuales, ND3). Las que escribe el usuario siguen las reglas de las
 * carpetas (ND11): sin niveles vacíos, ninguno empieza por `.` ni por `_` (esas carpetas nunca se leen) y como mucho
 * [LibraryScanner.MAX_FOLDER_DEPTH] niveles.
 */
object CategoryPaths {
    /** Máximo de caracteres visibles de cada nivel de una categoría escrita en la app. */
    const val MAX_SEGMENT_LENGTH = 60

    /** Separador visible de las migas y de las rutas (también se acepta al escribir). */
    const val SEPARATOR = " › "

    enum class Problem {
        /** No hay ningún nombre. */
        EMPTY,

        /** Un nivel empieza por `.` o por `_` (carpetas que PocketGB nunca lee, ND11). */
        RESERVED,

        /** Más de [LibraryScanner.MAX_FOLDER_DEPTH] niveles. */
        TOO_DEEP,
    }

    sealed interface Parsed {
        data class Valid(val path: List<String>) : Parsed

        data class Invalid(val problem: Problem) : Parsed
    }

    /** «Pokémon / Favoritas» o «Pokémon › Favoritas» → `[Pokémon, Favoritas]`, validada. */
    fun parse(text: String): Parsed {
        val segments = text.split('/', '›').mapNotNull(::normalizeSegment)
        return when {
            segments.isEmpty() -> Parsed.Invalid(Problem.EMPTY)
            segments.any { it.startsWith('.') || it.startsWith('_') } -> Parsed.Invalid(Problem.RESERVED)
            segments.size > LibraryScanner.MAX_FOLDER_DEPTH -> Parsed.Invalid(Problem.TOO_DEEP)
            else -> Parsed.Valid(segments)
        }
    }

    /** Un nivel recortado, con los espacios seguidos como uno y limitado a [MAX_SEGMENT_LENGTH]; `null` si queda vacío. */
    fun normalizeSegment(raw: String): String? {
        val flat = raw.replace(Regex("\\s+"), " ").trim()
        if (flat.isEmpty()) return null
        return Alias.truncate(flat, MAX_SEGMENT_LENGTH).trimEnd()
    }

    /**
     * H13: [typed] con la ortografía de una categoría que ya existe si coincide nivel a nivel sin mayúsculas ni acentos
     * («pokemon/para jugar» → «Pokémon/para jugar»), para no crear otra casi igual.
     */
    fun matchExisting(typed: List<String>, known: List<List<String>>): List<String> {
        val result = ArrayList<String>(typed.size)
        for (segment in typed) {
            val existing = known.firstOrNull { path ->
                path.size == result.size + 1 && path.subList(0, result.size) == result && Tags.same(path.last(), segment)
            }
            result += existing?.last() ?: segment
        }
        return result
    }

    /** «Pokémon › 2ª generación». */
    fun display(path: List<String>): String = path.joinToString(SEPARATOR)

    /** Orden natural nivel a nivel; una carpeta va antes que sus subcarpetas. */
    val order: Comparator<List<String>> = Comparator { a, b ->
        for (i in 0 until minOf(a.size, b.size)) {
            val c = NaturalOrder.compare(a[i], b[i])
            if (c != 0) return@Comparator c
        }
        a.size - b.size
    }
}

/** N4 · el árbol de categorías tal como se ve (carpetas reales y categorías virtuales). */
object LibraryTree {
    /**
     * Subcategorías directas de [parent] entre los juegos presentados [shown] ([LibraryQuery.visible]), con los juegos
     * de todo su subárbol, en orden natural. «Sin categoría» ([parent] vacío) no tiene subcategorías.
     */
    fun subcategories(shown: List<RomEntry>, parent: List<String>): List<CategoryOption> {
        if (parent.isEmpty()) return emptyList()
        val counts = LinkedHashMap<String, Int>()
        for (entry in shown) {
            val path = entry.categoryPath
            if (path.size <= parent.size || path.subList(0, parent.size) != parent) continue
            val child = path[parent.size]
            counts[child] = (counts[child] ?: 0) + 1
        }
        return counts.keys.sortedWith { a, b -> NaturalOrder.compare(a, b) }
            .map { CategoryOption(LibraryCategory.Folder(parent + it), counts.getValue(it)) }
    }

    /** Migas de [path]: cada nivel desde el primero hasta el actual («Pokémon», «Pokémon › 2ª generación»…). */
    fun breadcrumbs(path: List<String>): List<List<String>> = path.indices.map { path.subList(0, it + 1).toList() }

    /**
     * Todas las categorías que existen (carpetas con juegos, sus carpetas padre y las categorías virtuales), sin la raíz,
     * en orden natural en profundidad: lo que ofrece «Mostrar en categoría…». Sin juegos ocultos.
     */
    fun knownPaths(entries: List<RomEntry>, prefs: LibraryPreferencesData): List<List<String>> {
        val paths = HashSet<List<String>>()
        for (entry in entries) {
            if (prefs.isHidden(entry)) continue
            for (path in listOf(entry.folderPath, prefs.categoryPathOf(entry))) {
                for (depth in 1..path.size) paths += path.subList(0, depth).toList()
            }
        }
        return paths.sortedWith(CategoryPaths.order)
    }
}

/**
 * N4 · ajustes del inicio (por dispositivo, ND12): orden de las estanterías, las fijadas arriba, las ocultas y si se ve
 * la fila de Favoritos. Las claves son [LibraryCategory.key]. Una categoría que desaparece un rato conserva su sitio; una
 * nueva va detrás de las ordenadas.
 */
@Serializable
data class HomeSettings(
    /** Claves en el orden elegido (puede incluir categorías que ahora no están). */
    val order: List<String> = emptyList(),
    /** Fijadas arriba: van antes que las demás, en el orden de [order]. */
    val pinned: Set<String> = emptySet(),
    /** Fuera del inicio (sus juegos siguen en «Todos los juegos» y en su pantalla de categoría). */
    val hidden: Set<String> = emptySet(),
    val showFavorites: Boolean = true,
) {
    /**
     * [keys] (las categorías presentes, en su orden natural) en el orden del inicio: primero las fijadas, después las
     * demás; dentro de cada grupo, las ordenadas por el usuario y luego las nuevas en orden natural.
     */
    fun arrange(keys: List<String>): List<String> {
        val ordered = ordered(keys)
        return ordered.filter { it in pinned } + ordered.filterNot { it in pinned }
    }

    /** Sube ([offset] < 0) o baja la categoría [key] dentro de su grupo (fijadas o no) entre las presentes [keys]. */
    fun move(key: String, offset: Int, keys: List<String>): HomeSettings {
        // H11: se trabaja sobre el orden relativo (sin poner las fijadas delante): al soltar una vuelve a su sitio.
        val ordered = ordered(keys).toMutableList()
        if (key !in ordered || offset == 0) return this
        val group = arrange(keys).filter { (it in pinned) == (key in pinned) }
        val target = group.indexOf(key) + offset
        if (target !in group.indices) return this
        ordered.remove(key)
        ordered.add(ordered.indexOf(group[target]).let { if (offset > 0) it + 1 else it }, key)
        return copy(order = remember(ordered))
    }

    /** Las presentes en el orden elegido y, detrás, las nuevas en su orden natural (sin separar las fijadas). */
    private fun ordered(keys: List<String>): List<String> = order.filter { it in keys } + keys.filter { it !in order }

    /** Fija o suelta [key]. El orden no cambia: al soltarla vuelve a su sitio entre las demás. */
    fun withPinned(key: String, value: Boolean): HomeSettings =
        copy(pinned = if (value) pinned + key else pinned - key)

    fun withHidden(key: String, value: Boolean): HomeSettings = copy(hidden = if (value) hidden + key else hidden - key)

    /**
     * El orden relativo y, detrás, las claves que ahora no están (conservan su sitio relativo); acotado. Si «Sin
     * categoría» queda la última no se guarda: así sigue al final cuando aparece una carpeta nueva (H11).
     */
    private fun remember(ordered: List<String>): List<String> {
        val saved = if (ordered.lastOrNull() == LibraryCategory.UNCATEGORIZED_KEY) ordered.dropLast(1) else ordered
        return (saved + order.filter { it !in ordered }).take(MAX_KEYS)
    }

    companion object {
        /** Máximo de claves recordadas en el orden. */
        const val MAX_KEYS = 500
    }
}

/** Una estantería del inicio: una categoría de primer nivel con hasta [LibraryHome.SHELF_LIMIT] juegos. */
data class HomeShelf(
    val category: LibraryCategory,
    val games: List<RomEntry>,
    /** Juegos visibles de la categoría (todo su subárbol). */
    val total: Int,
    val pinned: Boolean,
)

/**
 * Lo que muestra el inicio entre «Continuar jugando» y «Todos los juegos». [favoritesTotal] cuenta todos los favoritos
 * (H1), aunque la fila muestre como mucho [LibraryHome.SHELF_LIMIT].
 */
data class HomeSections(val favorites: List<RomEntry>, val shelves: List<HomeShelf>, val favoritesTotal: Int = favorites.size)

/** Una fila de Ajustes › Biblioteca › Inicio. */
data class HomeCategoryRow(
    val category: LibraryCategory,
    val count: Int,
    val pinned: Boolean,
    val hidden: Boolean,
) {
    val key: String get() = category.key
}

/** N4 · el inicio de la biblioteca: fila de Favoritos y una estantería por categoría de primer nivel. */
object LibraryHome {
    /** Juegos como mucho en cada estantería y en la fila de Favoritos (el resto, en «Ver todo»). */
    const val SHELF_LIMIT = 10

    /**
     * Favoritos (si [HomeSettings.showFavorites]; cada juego una vez aunque tenga copias) y una estantería por categoría
     * de primer nivel (con «Sin categoría» para la raíz) en el orden del inicio, sin las ocultas. Sin carpetas no hay
     * estanterías: repetirían «Todos los juegos». Los juegos van en el orden de la biblioteca.
     */
    fun sections(entries: List<RomEntry>, prefs: LibraryPreferencesData, limit: Int = SHELF_LIMIT): HomeSections {
        val shown = LibraryQuery.visible(entries, prefs, LibraryFilter.ALL, "")
        val allFavorites = if (prefs.home.showFavorites) {
            shown.filter { prefs.isFavorite(it) }.distinctBy { prefs.fingerprints[it.id] ?: "ruta:${it.id}" }
        } else {
            emptyList()
        }
        val favorites = allFavorites.take(limit)
        val options = LibraryCategory.options(entries, prefs)
        if (!LibraryCategory.hasFolders(options)) return HomeSections(favorites, emptyList(), allFavorites.size)
        val byKey = options.associateBy { it.category.key }
        val shelves = prefs.home.arrange(options.map { it.category.key })
            .filterNot { it in prefs.home.hidden }
            .mapNotNull { key ->
                val option = byKey[key] ?: return@mapNotNull null
                val games = shown.filter(option.category::contains)
                HomeShelf(option.category, games.take(limit), games.size, key in prefs.home.pinned)
            }
        return HomeSections(favorites, shelves, allFavorites.size)
    }

    /** Todas las categorías de primer nivel en el orden del inicio, también las ocultas (Ajustes › Biblioteca › Inicio). */
    fun arrangement(entries: List<RomEntry>, prefs: LibraryPreferencesData): List<HomeCategoryRow> {
        val options = LibraryCategory.options(entries, prefs)
        val byKey = options.associateBy { it.category.key }
        return prefs.home.arrange(options.map { it.category.key }).map { key ->
            val option = byKey.getValue(key)
            HomeCategoryRow(option.category, option.count, key in prefs.home.pinned, key in prefs.home.hidden)
        }
    }

    /** Claves de las categorías presentes en su orden natural (lo que esperan [HomeSettings.move] y compañía). */
    fun keys(entries: List<RomEntry>, prefs: LibraryPreferencesData): List<String> =
        LibraryCategory.options(entries, prefs).map { it.category.key }
}

package com.joelbermudez.pocketgb.library

/**
 * Categoría de la biblioteca (N3b, N4): una carpeta (plan §3.2, «la carpeta es la verdad») con todo su subárbol, a
 * cualquier profundidad (el primer nivel es la categoría y los siguientes, subcategorías). Los juegos de la raíz son
 * «Sin categoría». La carpeta que cuenta es la de [RomEntry.categoryPath]: la categoría virtual si el juego se movió en
 * la app (ND3) o la de su carpeta. Mismo criterio que `LibraryCategory` de iOS.
 */
sealed interface LibraryCategory {
    /** Sin filtro de categoría. */
    data object All : LibraryCategory

    /** Juegos en la raíz de la carpeta (o movidos ahí en la app). */
    data object Uncategorized : LibraryCategory

    /** Una carpeta con sus subcarpetas: [path] desde la raíz (nunca vacía). */
    data class Folder(override val path: List<String>) : LibraryCategory {
        /** Una carpeta de primer nivel. */
        constructor(name: String) : this(listOf(name))

        init {
            require(path.isNotEmpty()) { "Una carpeta tiene al menos un nivel" }
        }

        /** Nombre de la carpeta (el último nivel). */
        val name: String get() = path.last()

        /** Es de primer nivel (una categoría, no una subcategoría). */
        val isTopLevel: Boolean get() = path.size == 1
    }

    /** Nombre de la carpeta, o `null` para «Todas» y «Sin categoría» (la UI pone sus textos). */
    val folderName: String?
        get() = (this as? Folder)?.name

    /** Ruta de la categoría: la carpeta; vacía para «Sin categoría» (y para «Todas»). */
    val path: List<String>
        get() = emptyList()

    /**
     * Clave estable para las preferencias (orden del inicio, vista por categoría): la ruta con `/` (ningún nombre de
     * carpeta ni de categoría virtual la contiene) o [UNCATEGORIZED_KEY] (ningún nombre empieza por «.», ND11).
     */
    val key: String
        get() = when (this) {
            All -> ALL_KEY
            Uncategorized -> UNCATEGORIZED_KEY
            is Folder -> path.joinToString("/")
        }

    /** [entry] presentado ([LibraryQuery]) está en esta categoría o en una de sus subcategorías. */
    fun contains(entry: RomEntry): Boolean = when (this) {
        All -> true
        Uncategorized -> entry.categoryPath.isEmpty()
        is Folder -> entry.categoryPath.size >= path.size && entry.categoryPath.subList(0, path.size) == path
    }

    companion object {
        const val UNCATEGORIZED_KEY = "."
        /** «Todas» no se guarda; «» no puede ser el nombre de ninguna carpeta (H12). */
        private const val ALL_KEY = ""

        /** La categoría de una ruta: «Sin categoría» si es la raíz. */
        fun fromPath(path: List<String>): LibraryCategory = if (path.isEmpty()) Uncategorized else Folder(path)

        /**
         * Categorías de primer nivel con juegos visibles (sin ocultos) y cuántos tiene cada una: las carpetas por nombre
         * (orden natural) y «Sin categoría» al final si hay juegos en la raíz. «Todas» no se incluye (la UI la pone
         * arriba). Cuenta la categoría en la que se ve cada juego ([LibraryPreferencesData.categoryPathOf]).
         */
        fun options(entries: List<RomEntry>, prefs: LibraryPreferencesData): List<CategoryOption> {
            val counts = LinkedHashMap<String, Int>()
            var root = 0
            for (entry in entries) {
                if (prefs.isHidden(entry)) continue
                val first = prefs.categoryPathOf(entry).firstOrNull()
                if (first == null) root++ else counts[first] = (counts[first] ?: 0) + 1
            }
            val folders = counts.keys.sortedWith { a, b -> NaturalOrder.compare(a, b) }
                .map { CategoryOption(Folder(it), counts.getValue(it)) }
            return if (root > 0) folders + CategoryOption(Uncategorized, root) else folders
        }

        /** `true` si hay al menos una carpeta: sin carpetas, elegir categoría no aporta nada (menú de vertical). */
        fun hasFolders(options: List<CategoryOption>): Boolean = options.any { it.category is Folder }
    }
}

/** Una categoría elegible y sus juegos visibles. */
data class CategoryOption(val category: LibraryCategory, val count: Int)

/** N4: una etiqueta de los juegos visibles y cuántos la llevan (filtro por etiqueta). */
data class TagOption(val tag: String, val count: Int)

/**
 * Como [LibraryQuery.visible], solo con los juegos de [category] y, si se pide, con la etiqueta [tag] (N4, sin
 * distinguir mayúsculas ni acentos). El filtro de categoría va después: así un juego conserva «También en» aunque su
 * copia esté en otra categoría (N1a).
 */
fun LibraryQuery.visible(
    entries: List<RomEntry>,
    prefs: LibraryPreferencesData,
    filter: LibraryFilter,
    query: String,
    category: LibraryCategory,
    tag: String? = null,
): List<RomEntry> {
    val shown = visible(entries, prefs, filter, query)
    val inCategory = if (category == LibraryCategory.All) shown else shown.filter(category::contains)
    return if (tag == null) inCategory else inCategory.filter { entry -> entry.tags.any { Tags.same(it, tag) } }
}

/**
 * N4: etiquetas de los juegos visibles (sin ocultos) con cuántos juegos la llevan, en orden natural. Dos escrituras de
 * la misma etiqueta («RPG» y «rpg») son una sola opción, con la del primer juego que la lleva.
 */
fun LibraryQuery.tagOptions(entries: List<RomEntry>, prefs: LibraryPreferencesData): List<TagOption> {
    val counts = LinkedHashMap<String, Pair<String, Int>>()
    for (entry in entries) {
        if (prefs.isHidden(entry)) continue
        for (tag in prefs.tagsOf(entry)) {
            val key = Tags.key(tag)
            val current = counts[key]
            counts[key] = (current?.first ?: tag) to ((current?.second ?: 0) + 1)
        }
    }
    return counts.values.map { (tag, count) -> TagOption(tag, count) }.sortedWith { a, b -> NaturalOrder.compare(a.tag, b.tag) }
}

/**
 * Carril «Continuar jugando» (N3a, ND15 como iOS A9-5): los jugados recientemente que se pueden **continuar** (estado
 * automático vigente, [isResumable]) y con portada capturada ([hasArtwork], K10: nunca una portada inventada), del más
 * reciente al más antiguo, hasta [LibraryQuery.CONTINUE_LIMIT]. Un juego que solo tiene partida no sale: se abre con
 * «Jugar» desde su tarjeta.
 */
fun LibraryQuery.continueRail(
    entries: List<RomEntry>,
    prefs: LibraryPreferencesData,
    isResumable: (RomEntry) -> Boolean,
    hasArtwork: (String) -> Boolean,
): List<RomEntry> =
    recent(entries, prefs, limit = Int.MAX_VALUE, hasArtwork = hasArtwork)
        .filter(isResumable)
        .take(LibraryQuery.CONTINUE_LIMIT)

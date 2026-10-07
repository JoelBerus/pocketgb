package com.joelbermudez.pocketgb.library

/**
 * Categoría de la biblioteca (N3b): la carpeta de primer nivel de `folderPath` (plan §3.2, «la carpeta es la verdad»).
 * Una categoría contiene todo su subárbol; los juegos de la raíz son «Sin categoría». N4 añadirá subcategorías y
 * categorías virtuales. Mismo criterio que `LibraryCategory` de iOS.
 */
sealed interface LibraryCategory {
    /** Sin filtro de categoría. */
    data object All : LibraryCategory

    /** Juegos en la raíz de la carpeta. */
    data object Uncategorized : LibraryCategory

    /** Una carpeta de primer nivel (con sus subcarpetas). */
    data class Folder(val name: String) : LibraryCategory

    /** Nombre de la carpeta, o `null` para «Todas» y «Sin categoría» (la UI pone sus textos). */
    val folderName: String?
        get() = (this as? Folder)?.name

    fun contains(entry: RomEntry): Boolean = when (this) {
        All -> true
        Uncategorized -> entry.folderPath.isEmpty()
        is Folder -> entry.folderPath.firstOrNull() == name
    }

    companion object {
        /**
         * Categorías con juegos visibles (sin ocultos) y cuántos tiene cada una: las carpetas por nombre (orden
         * natural) y «Sin categoría» al final si hay juegos en la raíz. «Todas» no se incluye (la UI la pone arriba).
         */
        fun options(entries: List<RomEntry>, prefs: LibraryPreferencesData): List<CategoryOption> {
            val counts = LinkedHashMap<String, Int>()
            var root = 0
            for (entry in entries) {
                if (prefs.isHidden(entry)) continue
                val first = entry.folderPath.firstOrNull()
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

/**
 * Como [LibraryQuery.visible], solo con los juegos de [category]. El filtro de categoría va después: así un juego
 * conserva «También en» aunque su copia esté en otra categoría (N1a).
 */
fun LibraryQuery.visible(
    entries: List<RomEntry>,
    prefs: LibraryPreferencesData,
    filter: LibraryFilter,
    query: String,
    category: LibraryCategory,
): List<RomEntry> {
    val shown = visible(entries, prefs, filter, query)
    return if (category == LibraryCategory.All) shown else shown.filter(category::contains)
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

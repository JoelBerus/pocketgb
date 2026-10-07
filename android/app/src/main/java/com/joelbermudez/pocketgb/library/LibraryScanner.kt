package com.joelbermudez.pocketgb.library

import java.io.IOException
import java.security.MessageDigest
import java.text.Normalizer

/** Entrada de una carpeta, ya independiente de SAF para poder probar el escáner en JVM. */
data class TreeNode(
    val id: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    /** El proveedor marca el documento como virtual: no tiene bytes locales. */
    val isVirtual: Boolean = false,
    /**
     * Fecha de modificación (epoch ms) si el proveedor la da. Sirve para la fecha del `.sav` junto al ROM (K20) y,
     * en el ROM, para su sello de documento (N1a: detectar que se movió sin leerlo).
     */
    val lastModified: Long? = null,
)

/** Fallo de lectura de un documento; distingue "no disponible aún" de "ilegible". */
class DocumentReadException(val remote: Boolean, cause: Throwable? = null) : IOException(cause)

/**
 * N1-H4: el proveedor devolvió la carpeta a medias (`EXTRA_LOADING`: aún cargando; `EXTRA_ERROR`: con error). [nodes]
 * es lo que sí listó; el escaneo lo usa pero queda incompleto (no poda ni traslada nada).
 */
class PartialListingException(val nodes: List<TreeNode>, reason: String) : IOException(reason)

/**
 * N1-H6: cabeceras ya leídas, por documento sin cambios (id de documento, tamaño y fecha del proveedor). Un ROM que
 * sigue igual no se vuelve a abrir en cada escaneo (en Drive, abrirlo puede descargarlo).
 */
class HeaderCache private constructor(private val byKey: Map<Key, String>) {
    private data class Key(val documentId: String, val size: Long, val lastModified: Long)

    /** Cabecera (0x150 bytes, solo 0x134–0x14F con datos) si [node] no cambió desde que se leyó; si no, `null`. */
    fun lookup(node: TreeNode): ByteArray? {
        val modified = node.lastModified ?: return null
        if (node.sizeBytes <= 0) return null
        return byKey[Key(node.id, node.sizeBytes, modified)]?.let(RomHeader::fromIdentity)
    }

    companion object {
        val EMPTY = HeaderCache(emptyMap())

        /** Desde los sellos guardados: solo los que tienen id de documento, tamaño, fecha y cabecera. */
        fun from(stamps: Collection<DocumentStamp>): HeaderCache {
            val map = HashMap<Key, String>()
            for (stamp in stamps) {
                val id = stamp.documentId ?: continue
                val size = stamp.size?.takeIf { it > 0 } ?: continue
                val modified = stamp.lastModified?.takeIf { it > 0 } ?: continue
                val header = stamp.header ?: continue
                if (RomHeader.fromIdentity(header) != null) map[Key(id, size, modified)] = header
            }
            return HeaderCache(map)
        }
    }
}

interface DocumentTree {
    /**
     * Id del documento de la carpeta raíz, o `null` si el árbol no lo sabe. Los ROMs de la raíz lo
     * heredan como `folderDocumentId`, que el espejo SAF usa para buscar el `.sav` hermano.
     */
    val rootId: String? get() = null

    /** Hijos directos de [directoryId], o `null` si es la raíz. Falla con [IOException]. */
    fun children(directoryId: String?): List<TreeNode>

    /** Lee como mucho [limit] bytes del inicio del documento. */
    fun readHead(node: TreeNode, limit: Int): ByteArray

    fun uriOf(node: TreeNode): String
}

/**
 * Cuánto costó un escaneo (N1b). En SAF cada carpeta listada es una consulta al proveedor (en Google Drive, una
 * llamada de red) y cada cabecera leída abre el documento.
 */
data class ScanStats(
    /** Carpetas listadas (consultas `children`), incluida la raíz. */
    val folderQueries: Int = 0,
    /** Cabeceras leídas (aperturas de documento). */
    val headReads: Int = 0,
    /** Documentos vistos (archivos y carpetas), hasta [LibraryScanner.MAX_SCAN_ENTRIES]. */
    val documentsSeen: Int = 0,
    /** Archivos y carpetas que empiezan por `.` (ND11: se ignoran). */
    val hiddenSkipped: Int = 0,
    /** `PocketGB/` en la raíz (ND11: es de la app). */
    val reservedSkipped: Int = 0,
    /** Carpetas que empiezan por `_` (ND11: apartadas). */
    val setAsideSkipped: Int = 0,
    /** Carpetas más allá de [LibraryScanner.MAX_FOLDER_DEPTH] niveles (no se listan). */
    val tooDeepSkipped: Int = 0,
    /**
     * Carpetas que no se pudieron listar o que el proveedor dio a medias (`EXTRA_LOADING`/`EXTRA_ERROR`), y subcarpetas
     * sin acceso con el permiso del árbol aún vigente (el resto sigue).
     */
    val folderErrors: Int = 0,
    /** Se alcanzó [LibraryScanner.MAX_SCAN_ENTRIES]: hay documentos sin ver. */
    val truncated: Boolean = false,
    /** N1-H6: cabeceras tomadas de la caché ([HeaderCache]) sin abrir el ROM. */
    val headerCacheHits: Int = 0,
) {
    /** Todas las carpetas a su alcance se listaron: lo que no aparece es que no está (N1a poda y traslada solo así). */
    val complete: Boolean get() = folderErrors == 0 && !truncated

    /** Consultas al proveedor en total. */
    val providerCalls: Int get() = folderQueries + headReads
}

class ScanResult(val entries: List<RomEntry>, val stats: ScanStats)

/**
 * Enumera los ROMs de la carpeta: solo `.gb`/`.gbc`, en la raíz y en sus subcarpetas hasta [MAX_FOLDER_DEPTH]
 * niveles (N1b), sin abrir ni modificar los ROMs (solo se lee su cabecera). Nombres reservados (ND11): lo que empieza
 * por `.` se ignora, `PocketGB/` en la raíz es de la app y las carpetas que empiezan por `_` quedan apartadas.
 */
object LibraryScanner {
    const val MAX_ROM_BYTES = 8L * 1024 * 1024

    /** Niveles de carpeta bajo la raíz que se recorren (la raíz es el nivel 0; `a/b/c/d/e/x.gb` es el último). */
    const val MAX_FOLDER_DEPTH = 5

    /** Documentos (archivos y carpetas) que se miran como mucho en un escaneo: una carpeta enorme no lo eterniza. */
    const val MAX_SCAN_ENTRIES = 5_000

    /** Carpeta de la app en la raíz (ND11): intercambio y exportados; no se escanea. */
    const val APP_FOLDER = "PocketGB"

    private val extensions = setOf("gb", "gbc")

    fun scan(
        tree: DocumentTree,
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
        checkCancelled: () -> Unit = {},
    ): List<RomEntry> = scanDetailed(tree, progress, checkCancelled).entries

    /**
     * Como [scan], con lo que costó ([ScanStats]). [checkCancelled] se llama antes de listar cada carpeta y de leer
     * cada cabecera: si lanza (cancelación), el escaneo se corta ahí sin más consultas. [headerCache] evita reabrir los
     * ROMs que no cambiaron (N1-H6).
     */
    fun scanDetailed(
        tree: DocumentTree,
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
        checkCancelled: () -> Unit = {},
        headerCache: HeaderCache = HeaderCache.EMPTY,
    ): ScanResult {
        val stats = StatsBuilder()
        val candidates = uniqueIds(candidates(tree, stats, checkCancelled))
        val entries = candidates.mapIndexed { index, candidate ->
            checkCancelled()
            entry(tree, candidate, stats, headerCache).also { progress(index + 1, candidates.size) }
        }
        return ScanResult(entries.sortedWith(titleOrder), stats.build())
    }

    private class StatsBuilder {
        var folderQueries = 0
        var headReads = 0
        var documentsSeen = 0
        var hiddenSkipped = 0
        var reservedSkipped = 0
        var setAsideSkipped = 0
        var tooDeepSkipped = 0
        var folderErrors = 0
        var truncated = false
        var headerCacheHits = 0

        fun build() = ScanStats(
            folderQueries, headReads, documentsSeen, hiddenSkipped, reservedSkipped, setAsideSkipped,
            tooDeepSkipped, folderErrors, truncated, headerCacheHits,
        )
    }

    /** Ruta relativa, documento, carpeta que lo contiene (id de documento; `null` si se desconoce la raíz) y su ruta. */
    private data class Candidate(
        val relative: String,
        val node: TreeNode,
        val folderId: String?,
        val saveDate: Long?,
        val folderPath: List<String>,
    )

    private class Folder(val id: String?, val path: List<String>)

    /** Recorrido por niveles (los juegos menos profundos primero si se llega al tope). */
    private fun candidates(tree: DocumentTree, stats: StatsBuilder, checkCancelled: () -> Unit): List<Candidate> {
        val result = mutableListOf<Candidate>()
        val queue = ArrayDeque<Folder>()
        val enqueued = HashSet<String>()
        tree.rootId?.let(enqueued::add)
        queue += Folder(null, emptyList())
        while (queue.isNotEmpty() && !stats.truncated) {
            val folder = queue.removeFirst()
            checkCancelled()
            stats.folderQueries++
            val children = try {
                tree.children(folder.id)
            } catch (partial: PartialListingException) {
                // N1-H4: lo listado vale, pero el escaneo queda incompleto (Drive aún cargando o con error).
                stats.folderErrors++
                partial.nodes
            } catch (error: TreePermissionException) {
                // Perder el permiso tumba el escaneo: la biblioteca parcial sería engañosa.
                throw error
            } catch (error: IOException) {
                // La raíz que falla tumba el escaneo (no hay biblioteca que mostrar); una subcarpeta, no.
                if (folder.id == null) throw error
                stats.folderErrors++
                continue
            }
            val depth = folder.path.size
            val folderDocumentId = folder.id ?: tree.rootId
            // Orden estable por nombre: con el tope, qué se queda fuera no depende del orden del proveedor.
            for (item in children.sortedWith(compareBy<TreeNode>({ it.name }, { it.id }))) {
                if (stats.documentsSeen >= MAX_SCAN_ENTRIES) {
                    stats.truncated = true
                    break
                }
                stats.documentsSeen++
                when {
                    item.name.startsWith(".") -> stats.hiddenSkipped++
                    item.isDirectory -> when {
                        depth == 0 && item.name.equals(APP_FOLDER, ignoreCase = true) -> stats.reservedSkipped++
                        item.name.startsWith("_") -> stats.setAsideSkipped++
                        depth + 1 > MAX_FOLDER_DEPTH -> stats.tooDeepSkipped++
                        // Drive deja que una carpeta tenga dos padres: se lista una sola vez.
                        enqueued.add(item.id) -> queue += Folder(item.id, folder.path + item.name)
                    }
                    isRom(item.name) -> result += Candidate(
                        relative = (folder.path + item.name).joinToString("/"),
                        node = item,
                        folderId = folderDocumentId,
                        saveDate = saveDateOf(children, item.name),
                        folderPath = folder.path,
                    )
                }
            }
        }
        return result
    }

    /** K20: fecha del `<base>.sav` hermano del ROM (sin distinguir mayúsculas), sin abrir el archivo. */
    private fun saveDateOf(siblings: List<TreeNode>, romName: String): Long? {
        val wanted = romName.substringBeforeLast('.') + ".sav"
        return siblings.firstOrNull { !it.isDirectory && it.name.equals(wanted, ignoreCase = true) }
            ?.lastModified?.takeIf { it > 0 }
    }

    /**
     * Garantiza ids únicos (se usan como clave en favoritos, ocultos y listas Lazy*). Si varias entradas
     * comparten ruta relativa (proveedores remotos permiten nombres repetidos), TODAS reciben un sufijo
     * `#<hash del id de documento>`: así el id no depende del orden en que el proveedor las liste.
     * El sufijo va tras el nombre de archivo, por lo que `subfolder` y `folderPath` no cambian. Vale igual con rutas
     * profundas (N1b): la ruta relativa completa es la que se compara.
     */
    private fun uniqueIds(items: List<Candidate>): List<Candidate> {
        val counts = items.groupingBy { it.relative }.eachCount()
        val base = items.map { (relative, node) ->
            if (counts.getValue(relative) == 1) relative else "$relative#${digest(node.id)}"
        }
        // Colisión residual del resumen (o documento repetido): se desempata por id de documento,
        // no por el orden del proveedor, para que el resultado siga siendo estable.
        val rank = HashMap<Int, Int>()
        items.indices.groupBy { base[it] }.values.filter { it.size > 1 }.forEach { group ->
            group.sortedBy { items[it].node.id }.forEachIndexed { n, index -> rank[index] = n }
        }
        return items.mapIndexed { index, item ->
            val n = rank[index]
            item.copy(relative = if (n == null || n == 0) base[index] else "${base[index]}~$n")
        }
    }

    private fun digest(documentId: String): String =
        MessageDigest.getInstance("SHA-256").digest(documentId.toByteArray())
            .take(6).joinToString("") { "%02x".format(it) }

    private fun isRom(name: String): Boolean =
        !name.startsWith(".") && name.substringAfterLast('.', "").lowercase() in extensions

    private fun entry(tree: DocumentTree, candidate: Candidate, stats: StatsBuilder, cache: HeaderCache): RomEntry {
        val node = candidate.node
        val fallbackTitle = node.name.substringBeforeLast('.')
        val colorByName = node.name.substringAfterLast('.').equals("gbc", ignoreCase = true)

        fun make(
            problem: RomProblem?,
            title: String = fallbackTitle,
            isColor: Boolean = colorByName,
            checksumOk: Boolean = true,
            headerKey: String? = null,
        ) = RomEntry(
            id = candidate.relative,
            uri = tree.uriOf(node),
            fileName = node.name,
            title = title.ifEmpty { fallbackTitle },
            isColor = isColor,
            sizeBytes = node.sizeBytes,
            headerChecksumOk = checksumOk,
            problem = problem,
            folderDocumentId = candidate.folderId,
            mirrorSaveDate = candidate.saveDate,
            folderPath = candidate.folderPath,
            lastModified = node.lastModified,
            documentId = node.id,
            headerKey = headerKey,
        )

        if (node.isVirtual) return make(RomProblem.REMOTE_UNAVAILABLE)
        if (node.sizeBytes > MAX_ROM_BYTES) return make(RomProblem.TOO_LARGE)
        val cached = cache.lookup(node)
        if (cached != null) stats.headerCacheHits++
        val head = cached ?: try {
            stats.headReads++
            tree.readHead(node, RomHeader.MINIMUM_BYTES)
        } catch (error: DocumentReadException) {
            return make(if (error.remote) RomProblem.REMOTE_UNAVAILABLE else RomProblem.UNREADABLE)
        } catch (_: IOException) {
            return make(RomProblem.UNREADABLE)
        }
        val key = RomHeader.identity(head)
        val info = RomHeader.parse(head) ?: return make(RomProblem.INVALID_HEADER, headerKey = key)
        return make(null, info.title, info.isColor, info.checksumOk, key)
    }

    /** Orden por el nombre visible (el alias si lo hay, A9; el título de la cabecera si no). */
    val titleOrder: Comparator<RomEntry> = Comparator { a, b -> NaturalOrder.compare(a.displayTitle, b.displayTitle) }
}

/** Orden "natural": ignora mayúsculas y acentos y compara los números por valor ("Juego 2" < "Juego 10"). */
object NaturalOrder {
    fun compare(a: String, b: String): Int {
        val c = compareFolded(fold(a), fold(b))
        return if (c != 0) c else a.compareTo(b)
    }

    private fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }

    private fun compareFolded(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i
                while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j
                while (ej < b.length && b[ej].isDigit()) ej++
                val na = a.substring(i, ei).trimStart('0')
                val nb = b.substring(j, ej).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = ei
                j = ej
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}

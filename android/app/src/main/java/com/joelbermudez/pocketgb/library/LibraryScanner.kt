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
)

/** Fallo de lectura de un documento; distingue "no disponible aún" de "ilegible". */
class DocumentReadException(val remote: Boolean, cause: Throwable? = null) : IOException(cause)

interface DocumentTree {
    /** Hijos directos de [directoryId], o `null` si es la raíz. Falla con [IOException]. */
    fun children(directoryId: String?): List<TreeNode>

    /** Lee como mucho [limit] bytes del inicio del documento. */
    fun readHead(node: TreeNode, limit: Int): ByteArray

    fun uriOf(node: TreeNode): String
}

/**
 * Enumera los ROMs de la carpeta: solo `.gb`/`.gbc`, en la raíz y en sus subcarpetas
 * directas (profundidad 1), sin abrir ni modificar los ROMs.
 */
object LibraryScanner {
    const val MAX_ROM_BYTES = 8L * 1024 * 1024
    private val extensions = setOf("gb", "gbc")

    fun scan(tree: DocumentTree, progress: (done: Int, total: Int) -> Unit = { _, _ -> }): List<RomEntry> {
        val candidates = uniqueIds(candidates(tree))
        val entries = candidates.mapIndexed { index, (relative, node) ->
            entry(tree, relative, node).also { progress(index + 1, candidates.size) }
        }
        return entries.sortedWith(titleOrder)
    }

    private fun candidates(tree: DocumentTree): List<Pair<String, TreeNode>> {
        val result = mutableListOf<Pair<String, TreeNode>>()
        for (item in tree.children(null)) {
            if (item.name.startsWith(".")) continue
            if (item.isDirectory) {
                // Una subcarpeta que falla de forma recuperable no tira el escaneo entero, pero perder el
                // permiso sí lo es: la biblioteca parcial sería engañosa.
                val inner = try {
                    tree.children(item.id)
                } catch (error: TreePermissionException) {
                    throw error
                } catch (_: IOException) {
                    continue
                }
                inner.filter { !it.isDirectory && isRom(it.name) }
                    .forEach { result += "${item.name}/${it.name}" to it }
            } else if (isRom(item.name)) {
                result += item.name to item
            }
        }
        return result
    }

    /**
     * Garantiza ids únicos (se usan como clave en favoritos, ocultos y listas Lazy*). Si varias entradas
     * comparten ruta relativa (proveedores remotos permiten nombres repetidos), TODAS reciben un sufijo
     * `#<hash del id de documento>`: así el id no depende del orden en que el proveedor las liste.
     * El sufijo va tras el nombre de archivo, por lo que `subfolder` no cambia.
     */
    private fun uniqueIds(items: List<Pair<String, TreeNode>>): List<Pair<String, TreeNode>> {
        val counts = items.groupingBy { it.first }.eachCount()
        val base = items.map { (relative, node) ->
            if (counts.getValue(relative) == 1) relative else "$relative#${digest(node.id)}"
        }
        // Colisión residual del resumen (o documento repetido): se desempata por id de documento,
        // no por el orden del proveedor, para que el resultado siga siendo estable.
        val rank = HashMap<Int, Int>()
        items.indices.groupBy { base[it] }.values.filter { it.size > 1 }.forEach { group ->
            group.sortedBy { items[it].second.id }.forEachIndexed { n, index -> rank[index] = n }
        }
        return items.mapIndexed { index, (_, node) ->
            val n = rank[index]
            (if (n == null || n == 0) base[index] else "${base[index]}~$n") to node
        }
    }

    private fun digest(documentId: String): String =
        MessageDigest.getInstance("SHA-256").digest(documentId.toByteArray())
            .take(6).joinToString("") { "%02x".format(it) }

    private fun isRom(name: String): Boolean =
        !name.startsWith(".") && name.substringAfterLast('.', "").lowercase() in extensions

    private fun entry(tree: DocumentTree, relative: String, node: TreeNode): RomEntry {
        val fallbackTitle = node.name.substringBeforeLast('.')
        val colorByName = node.name.substringAfterLast('.').equals("gbc", ignoreCase = true)

        fun make(
            problem: RomProblem?,
            title: String = fallbackTitle,
            isColor: Boolean = colorByName,
            checksumOk: Boolean = true,
        ) = RomEntry(
            id = relative,
            uri = tree.uriOf(node),
            fileName = node.name,
            title = title.ifEmpty { fallbackTitle },
            isColor = isColor,
            sizeBytes = node.sizeBytes,
            headerChecksumOk = checksumOk,
            problem = problem,
        )

        if (node.isVirtual) return make(RomProblem.REMOTE_UNAVAILABLE)
        if (node.sizeBytes > MAX_ROM_BYTES) return make(RomProblem.TOO_LARGE)
        val head = try {
            tree.readHead(node, RomHeader.MINIMUM_BYTES)
        } catch (error: DocumentReadException) {
            return make(if (error.remote) RomProblem.REMOTE_UNAVAILABLE else RomProblem.UNREADABLE)
        } catch (_: IOException) {
            return make(RomProblem.UNREADABLE)
        }
        val info = RomHeader.parse(head) ?: return make(RomProblem.INVALID_HEADER)
        return make(null, info.title, info.isColor, info.checksumOk)
    }

    val titleOrder: Comparator<RomEntry> = Comparator { a, b -> NaturalOrder.compare(a.title, b.title) }
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

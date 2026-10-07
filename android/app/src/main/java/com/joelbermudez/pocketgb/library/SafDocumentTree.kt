package com.joelbermudez.pocketgb.library

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** El permiso sobre la carpeta ya no es válido (revocado o retirado por el proveedor). */
class TreePermissionException(cause: Throwable? = null) : IOException("Permiso de carpeta revocado", cause)

/** La carpeta elegida ya no existe en el proveedor. */
class TreeMissingException(cause: Throwable? = null) : IOException("La carpeta ya no existe", cause)

/**
 * [DocumentTree] sobre el Storage Access Framework. Solo consulta y lee: nunca crea,
 * renombra, escribe ni borra documentos.
 *
 * @param treeStillGranted el permiso persistido del árbol sigue vigente (N1-H7): una `SecurityException` en una
 *   subcarpeta con el árbol aún concedido es un fallo de esa carpeta, no una revocación.
 */
class SafDocumentTree(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
    private val treeStillGranted: () -> Boolean = {
        resolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission }
    },
) : DocumentTree {
    override val rootId: String = DocumentsContract.getTreeDocumentId(treeUri)

    override fun children(directoryId: String?): List<TreeNode> {
        try {
            return queryChildren(directoryId)
        } catch (error: IOException) {
            throw error // incluye TreePermissionException, TreeMissingException y PartialListingException
        } catch (error: CancellationException) {
            throw error
        } catch (error: SecurityException) {
            throw denied(directoryId, error)
        } catch (error: RuntimeException) {
            // Proveedor mal portado (IllegalStateException, UnsupportedOperationException, ...).
            throw IOException("El proveedor falló al listar la carpeta", error)
        }
    }

    /** Sin acceso a [directoryId]: revocación si es la raíz o si el árbol ya no está concedido; si no, fallo de carpeta. */
    private fun denied(directoryId: String?, error: SecurityException): IOException {
        val granted = directoryId != null && try {
            treeStillGranted()
        } catch (_: RuntimeException) {
            false
        }
        return if (granted) IOException("Sin acceso a una subcarpeta con el permiso del árbol vigente", error) else TreePermissionException(error)
    }

    private fun queryChildren(directoryId: String?): List<TreeNode> {
        val parentId = directoryId ?: rootId
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val cursor = try {
            resolver.query(uri, CHILD_COLUMNS, null, null, null)
        } catch (error: SecurityException) {
            throw denied(directoryId, error)
        } catch (error: IllegalArgumentException) {
            throw TreeMissingException(error)
        }
        if (cursor == null) {
            // El proveedor falló; si ni siquiera existe el documento, la carpeta desapareció.
            if (directoryId == null && !documentExists(parentId)) throw TreeMissingException()
            throw IOException("El proveedor no devolvió contenido")
        }
        cursor.use {
            // id y nombre son imprescindibles; el resto se tolera ausente.
            val idColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (idColumn < 0 || nameColumn < 0) throw IOException("El proveedor no devolvió id o nombre de documento")
            val mimeColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val flagsColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
            val modifiedColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val result = ArrayList<TreeNode>(it.count)
            while (it.moveToNext()) {
                val name = it.getString(nameColumn) ?: continue
                result += TreeNode(
                    id = it.getString(idColumn) ?: continue,
                    name = name,
                    isDirectory = mimeColumn >= 0 && it.getString(mimeColumn) == DocumentsContract.Document.MIME_TYPE_DIR,
                    sizeBytes = knownSize(it, sizeColumn),
                    isVirtual = flagsColumn >= 0 && safeInt(it, flagsColumn) and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT != 0,
                    lastModified = knownTime(it, modifiedColumn),
                )
            }
            // N1-H4: un listado aún cargando (Drive) o con error no es el contenido completo de la carpeta.
            val extras = it.extras
            val loading = extras?.getBoolean(DocumentsContract.EXTRA_LOADING, false) == true
            val providerError = extras?.getString(DocumentsContract.EXTRA_ERROR)
            if (loading || providerError != null) {
                throw PartialListingException(
                    result,
                    if (loading) "El proveedor aún está cargando la carpeta" else "El proveedor informó un error",
                    loading = loading,
                )
            }
            return result
        }
    }

    /** Tamaño declarado, o 0 (desconocido) si falta la columna, es nulo o no es numérico. */
    private fun knownSize(cursor: Cursor, column: Int): Long {
        if (column < 0 || cursor.isNull(column)) return 0L
        return try {
            cursor.getLong(column).coerceAtLeast(0L)
        } catch (_: RuntimeException) {
            0L
        }
    }

    /** Fecha de modificación, o `null` si falta la columna, es nula, no es numérica o no es positiva. */
    private fun knownTime(cursor: Cursor, column: Int): Long? {
        if (column < 0 || cursor.isNull(column)) return null
        return try {
            cursor.getLong(column).takeIf { it > 0 }
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun safeInt(cursor: Cursor, column: Int): Int = try {
        if (cursor.isNull(column)) 0 else cursor.getInt(column)
    } catch (_: RuntimeException) {
        0
    }

    override fun readHead(node: TreeNode, limit: Int): ByteArray {
        val uri = documentUri(node)
        val stream = try {
            resolver.openInputStream(uri)
        } catch (error: SecurityException) {
            throw DocumentReadException(remote = false, cause = error)
        } catch (error: IOException) {
            throw DocumentReadException(remote = isRemote(uri), cause = error)
        } catch (error: CancellationException) {
            throw error
        } catch (error: RuntimeException) {
            throw DocumentReadException(remote = isRemote(uri), cause = error)
        } ?: throw DocumentReadException(remote = isRemote(uri))
        try {
            stream.use { input ->
                val buffer = ByteArray(limit)
                var filled = 0
                while (filled < limit) {
                    val read = input.read(buffer, filled, limit - filled)
                    if (read < 0) break
                    filled += read
                }
                return buffer.copyOf(filled)
            }
        } catch (error: IOException) {
            throw DocumentReadException(remote = isRemote(uri), cause = error)
        } catch (error: CancellationException) {
            throw error
        } catch (error: RuntimeException) {
            throw DocumentReadException(remote = isRemote(uri), cause = error)
        }
    }

    override fun uriOf(node: TreeNode): String = documentUri(node).toString()

    private fun documentUri(node: TreeNode): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, node.id)

    private fun documentExists(documentId: String): Boolean = try {
        resolver.query(
            DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null,
            null,
            null,
        )?.use { it.moveToFirst() } ?: false
    } catch (_: RuntimeException) {
        false
    }

    private fun isRemote(uri: Uri): Boolean = ProviderLocality.isRemote(uri)

    companion object {
        private val CHILD_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}

/** Criterio único para decidir si un documento puede estar en un proveedor remoto (nube, red). */
object ProviderLocality {
    /** Proveedores del sistema que sirven archivos del propio dispositivo. */
    private val LOCAL_AUTHORITIES = setOf(
        "com.android.externalstorage.documents",
        "com.android.providers.downloads.documents",
        "com.android.providers.media.documents",
    )

    fun isRemote(uri: Uri): Boolean = uri.authority !in LOCAL_AUTHORITIES
}

package com.joelbermudez.pocketgb.library

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.FileNotFoundException
import java.io.IOException

/** El permiso sobre la carpeta ya no es válido (revocado o retirado por el proveedor). */
class TreePermissionException(cause: Throwable? = null) : IOException("Permiso de carpeta revocado", cause)

/** La carpeta elegida ya no existe en el proveedor. */
class TreeMissingException(cause: Throwable? = null) : IOException("La carpeta ya no existe", cause)

/**
 * [DocumentTree] sobre el Storage Access Framework. Solo consulta y lee: nunca crea,
 * renombra, escribe ni borra documentos.
 */
class SafDocumentTree(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
) : DocumentTree {
    private val rootId: String = DocumentsContract.getTreeDocumentId(treeUri)

    override fun children(directoryId: String?): List<TreeNode> {
        val parentId = directoryId ?: rootId
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val cursor = try {
            resolver.query(uri, CHILD_COLUMNS, null, null, null)
        } catch (error: SecurityException) {
            throw TreePermissionException(error)
        } catch (error: IllegalArgumentException) {
            throw TreeMissingException(error)
        }
        if (cursor == null) {
            // El proveedor falló; si ni siquiera existe el documento, la carpeta desapareció.
            if (directoryId == null && !documentExists(parentId)) throw TreeMissingException()
            throw IOException("El proveedor no devolvió contenido")
        }
        cursor.use {
            val idColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val flagsColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_FLAGS)
            val result = ArrayList<TreeNode>(it.count)
            while (it.moveToNext()) {
                val name = it.getString(nameColumn) ?: continue
                result += TreeNode(
                    id = it.getString(idColumn) ?: continue,
                    name = name,
                    isDirectory = it.getString(mimeColumn) == DocumentsContract.Document.MIME_TYPE_DIR,
                    sizeBytes = if (it.isNull(sizeColumn)) 0L else it.getLong(sizeColumn),
                    isVirtual = it.getInt(flagsColumn) and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT != 0,
                )
            }
            return result
        }
    }

    override fun readHead(node: TreeNode, limit: Int): ByteArray {
        val uri = documentUri(node)
        val stream = try {
            resolver.openInputStream(uri)
        } catch (error: SecurityException) {
            throw DocumentReadException(remote = false, cause = error)
        } catch (error: IOException) {
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
        } catch (error: FileNotFoundException) {
            throw DocumentReadException(remote = isRemote(uri), cause = error)
        } catch (error: IOException) {
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

    private fun isRemote(uri: Uri): Boolean = uri.authority !in LOCAL_AUTHORITIES

    companion object {
        private val CHILD_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_FLAGS,
        )

        /** Proveedores del sistema que sirven archivos del propio dispositivo. */
        private val LOCAL_AUTHORITIES = setOf(
            "com.android.externalstorage.documents",
            "com.android.providers.downloads.documents",
            "com.android.providers.media.documents",
        )
    }
}

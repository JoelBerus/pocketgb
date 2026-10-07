package com.joelbermudez.pocketgb.library

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log

/** Resultado de elegir una carpeta: [writeGranted] es false si solo se obtuvo lectura. */
data class FolderGrant(val writeGranted: Boolean)

/** Recuerda la carpeta de la biblioteca y su permiso persistente. */
interface FolderStore {
    fun currentUri(): String?

    /** `false` si el permiso persistente ya no figura (revocado desde Ajustes del sistema o desinstalación). */
    fun hasPersistedPermission(): Boolean

    /** Toma el permiso persistente y recuerda la carpeta. Lanza [SecurityException] si el sistema lo rechaza. */
    fun select(uri: String): FolderGrant

    /** Olvida la carpeta y libera su permiso. No toca ningún archivo. */
    fun forget()

    fun displayName(): String?
}

class LibraryFolderStore(context: Context) : FolderStore {
    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun currentUri(): String? = prefs.getString(KEY_TREE_URI, null)

    override fun hasPersistedPermission(): Boolean {
        val current = currentUri() ?: return false
        return resolver.persistedUriPermissions.any { it.uri.toString() == current && it.isReadPermission }
    }

    override fun select(uri: String): FolderGrant {
        val parsed = Uri.parse(uri)
        val previous = currentUri()
        val writeGranted = try {
            resolver.takePersistableUriPermission(
                parsed,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            true
        } catch (_: SecurityException) {
            // A4 solo lee; sin escritura el espejo de partidas (A5) no podrá crearse.
            resolver.takePersistableUriPermission(parsed, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            Log.w(TAG, "Solo se obtuvo permiso de lectura sobre la carpeta")
            false
        }
        prefs.edit().putString(KEY_TREE_URI, uri).apply()
        if (previous != null && previous != uri) release(previous)
        return FolderGrant(writeGranted)
    }

    override fun forget() {
        currentUri()?.let(::release)
        prefs.edit().remove(KEY_TREE_URI).apply()
    }

    override fun displayName(): String? {
        val current = currentUri() ?: return null
        val tree = Uri.parse(current)
        val name = try {
            resolver.query(
                DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)),
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (_: RuntimeException) {
            null
        }
        return name ?: DocumentsContract.getTreeDocumentId(tree).substringAfterLast(':').substringAfterLast('/')
    }

    private fun release(uri: String) {
        try {
            resolver.releasePersistableUriPermission(
                Uri.parse(uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Ya no estaba concedido: nada que liberar.
        }
    }

    private companion object {
        const val TAG = "LibraryFolderStore"
        const val PREFS_NAME = "library_folder"
        const val KEY_TREE_URI = "tree_uri"
    }
}

package com.joelbermudez.pocketgb.saves.saf

import android.content.ContentResolver
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import com.joelbermudez.pocketgb.saves.SaveMirror
import com.joelbermudez.pocketgb.saves.SaveOpening
import com.joelbermudez.pocketgb.saves.SaveStore
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

/** Fallo del espejo SAF. Nunca afecta a la copia local: el llamador lo trata como "espejo pendiente". */
open class MirrorWriteException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Por qué el espejo dejó de usarse durante la sesión; la UI lo avisa (J2 y nombre alterado). */
enum class MirrorDisabledReason {
    /** El `.sav` junto a la ROM cambió por fuera durante la partida (J2): se conserva y se deja de espejar. */
    ExternalChange,

    /** El proveedor cambió el nombre del `.sav` al crearlo: se borra lo creado y no se espeja más. */
    NameAltered,
}

/** El `.sav` cambió por fuera durante la sesión (J2): ya se respaldó y el espejo queda desactivado. */
class MirrorExternalChangeException : MirrorWriteException("El .sav junto a la ROM cambió por fuera durante la partida")

/** El proveedor no respetó el nombre pedido al crear el `.sav`. */
class MirrorNameAlteredException(requested: String, actual: String?) :
    MirrorWriteException("El proveedor creó '$actual' en vez de '$requested'")

/**
 * Espejo SAF "in situ" (decisión J1): el `.sav` vive junto a la ROM y se reescribe en el mismo documento con
 * `openFileDescriptor(uri, "wt")` (nunca `"w"`, que en algunos proveedores no trunca). SAF no ofrece
 * semántica POSIX, así que **no se declara atómico**; la defensa es: respaldar lo que no sea nuestro antes de
 * tocarlo, sincronizar, y releer comparando longitud y SHA-256. Cualquier excepción (incluida
 * [SecurityException] por una revocación a mitad de escritura) sale como [MirrorWriteException], y la copia
 * local queda intacta (el reintento lo programa `MirrorChannel`).
 *
 * Todas las llamadas pueden bloquear (proveedores remotos): nunca desde el hilo principal.
 *
 * @param folderDocumentId carpeta que contiene la ROM (`RomEntry.folderDocumentId`).
 * @param romFileName nombre de archivo de la ROM; la base (sin la última extensión) da el nombre `<base>.sav`.
 * @param validSizes tamaños de `.sav` válidos: el tope de lectura es `max + 1` (más no puede ser una partida).
 * @param writePermissionGranted el permiso persistido incluye escritura (si no, solo se importa: J7).
 * @param onDisabled aviso a la UI cuando el espejo se desactiva por [MirrorDisabledReason].
 */
class SafSaveMirror(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
    private val folderDocumentId: String,
    private val romFileName: String,
    private val store: SaveStore,
    private val validSizes: Set<Int>,
    private val writePermissionGranted: Boolean = true,
    private val onDisabled: (MirrorDisabledReason) -> Unit = {},
) : SaveMirror {
    private class Child(
        val id: String,
        val name: String,
        val isDirectory: Boolean,
        val flags: Int,
        val lastModified: Long?,
    ) {
        val isVirtual: Boolean get() = flags and Document.FLAG_VIRTUAL_DOCUMENT != 0
    }

    private val base: String = romFileName.substringBeforeLast('.', missingDelimiterValue = romFileName)
    private val mirrorName: String = "$base.sav"
    private val readCap: Int = (validSizes.maxOrNull() ?: 0) + 1

    private val lock = Any()

    /** Qué había en el espejo la última vez que lo vimos o escribimos (para detectar cambios externos, J2). */
    private var baselineKnown = false
    private var baselineHash: String? = null
    private val ownHashes = LinkedHashSet<String>()
    private var lastAttempt: ByteArray? = null

    @Volatile
    var disabledReason: MirrorDisabledReason? = null
        private set

    // ---------------------------------------------------------------- apertura

    /**
     * Qué se permite hacer con el espejo en esta sesión, según el listado real del proveedor:
     * - [SaveOpening.MirrorMode.Shared] si otra ROM hermana (`Juego.gb` y `Juego.GBC`, sin distinguir
     *   mayúsculas) comparte la base del nombre: el espejo se desactiva por completo;
     * - [SaveOpening.MirrorMode.ReadOnly] sin permiso persistido de escritura o si el proveedor no ofrece
     *   escritura en el `.sav` (o crear en la carpeta si aún no existe): J7;
     * - [SaveOpening.MirrorMode.ReadWrite] en otro caso.
     * Si el listado falla no se puede juzgar: se devuelve ReadWrite y [snapshot] dirá `Unavailable`.
     */
    fun mirrorMode(): SaveOpening.MirrorMode {
        requireBackgroundThread()
        return try {
            decideMode(listChildren(), folderFlags())
        } catch (_: IOException) {
            SaveOpening.MirrorMode.ReadWrite
        } catch (_: RuntimeException) {
            SaveOpening.MirrorMode.ReadWrite
        }
    }

    private fun decideMode(children: List<Child>, folderFlags: Int): SaveOpening.MirrorMode {
        if (siblingRomsSharingBase(children) >= 2) return SaveOpening.MirrorMode.Shared
        if (!writePermissionGranted) return SaveOpening.MirrorMode.ReadOnly
        val matches = savFiles(children)
        val canWrite = if (matches.size == 1) {
            matches[0].flags and Document.FLAG_SUPPORTS_WRITE != 0
        } else {
            folderFlags and Document.FLAG_DIR_SUPPORTS_CREATE != 0
        }
        return if (canWrite) SaveOpening.MirrorMode.ReadWrite else SaveOpening.MirrorMode.ReadOnly
    }

    override fun snapshot(): SaveMirror.Snapshot {
        requireBackgroundThread()
        return synchronized(lock) {
            try {
                val matches = savFiles(listChildren())
                when {
                    matches.isEmpty() -> {
                        baselineKnown = true
                        baselineHash = null
                        SaveMirror.Snapshot.Absent
                    }
                    matches.size > 1 -> SaveMirror.Snapshot.Unavailable
                    matches[0].isVirtual -> SaveMirror.Snapshot.Unavailable
                    else -> {
                        val child = matches[0]
                        val data = readDocument(documentUri(child.id), readCap)
                        baselineKnown = true
                        baselineHash = sha256(data)
                        SaveMirror.Snapshot.Read(data, child.lastModified)
                    }
                }
            } catch (_: IOException) {
                SaveMirror.Snapshot.Unavailable
            } catch (_: RuntimeException) {
                // SecurityException, proveedor remoto que falla al abrir, cursor roto...
                SaveMirror.Snapshot.Unavailable
            }
        }
    }

    // ---------------------------------------------------------------- escritura

    override fun write(data: ByteArray): Long? {
        requireBackgroundThread()
        return synchronized(lock) {
            try {
                writeInPlace(data)
            } catch (error: MirrorWriteException) {
                throw error
            } catch (error: IOException) {
                throw MirrorWriteException("Fallo al escribir el espejo: ${error.message}", error)
            } catch (error: RuntimeException) {
                throw MirrorWriteException("El proveedor falló al escribir el espejo: ${error.message}", error)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw MirrorWriteException("Escritura del espejo interrumpida", error)
            }
        }
    }

    private fun writeInPlace(data: ByteArray): Long? {
        disabledReason?.let { throw MirrorWriteException("El espejo está desactivado ($it)") }
        if (!writePermissionGranted) throw MirrorWriteException("La carpeta es de solo lectura")
        val dataHash = sha256(data)

        val children = listChildren()
        if (siblingRomsSharingBase(children) >= 2) throw MirrorWriteException("Otra ROM comparte el nombre del .sav")
        val matches = savFiles(children)
        if (matches.size > 1) throw MirrorWriteException("Hay varios documentos llamados $mirrorName")
        val existing = matches.firstOrNull()
        if (existing != null && existing.isVirtual) throw MirrorWriteException("$mirrorName es un documento virtual")

        val target: Uri
        if (existing != null) {
            if (existing.flags and Document.FLAG_SUPPORTS_WRITE == 0) throw MirrorWriteException("$mirrorName no admite escritura")
            target = documentUri(existing.id)
            // Hay que poder leer lo que está ahí antes de tocarlo: si no, no se puede probar que sea nuestro.
            val actual = readDocument(target, readCap)
            val actualHash = sha256(actual)
            if (actualHash == dataHash) {
                // Ya está al día: nada que escribir.
                ownHashes += dataHash
                baselineKnown = true
                baselineHash = dataHash
                val date = observedDate(target)
                store.recordSuccessfulMirror(data, date)
                return date
            }
            val owned = isOwn(actual, actualHash, existing.lastModified)
            if (!owned) {
                // Antes de tocar algo que no es nuestro, se conserva (sin duplicar si ya estaba respaldado).
                store.addBackup(actual)
                if (baselineKnown && actualHash != baselineHash) {
                    // J2: cambió por fuera durante la partida. La local manda; el externo ya está a salvo.
                    disable(MirrorDisabledReason.ExternalChange)
                    throw MirrorExternalChangeException()
                }
            }
        } else {
            val folderFlags = folderFlags()
            if (folderFlags and Document.FLAG_DIR_SUPPORTS_CREATE == 0) throw MirrorWriteException("La carpeta no permite crear archivos")
            target = createMirrorDocument()
        }

        store.recordMirrorAttempt(data)
        ownHashes += dataHash
        lastAttempt = data
        writeAndSync(target, data)
        verify(target, data, dataHash)
        baselineKnown = true
        baselineHash = dataHash
        val date = observedDate(target)
        store.recordSuccessfulMirror(data, date)
        return date
    }

    /** ¿Es [actual] el resultado de una escritura nuestra (o el rastro parcial de una que se cortó)? */
    private fun isOwn(actual: ByteArray, actualHash: String, dateMs: Long?): Boolean {
        if (actualHash in ownHashes) return true
        if (store.recognizesOwnedMirror(actual, dateMs)) return true
        val attempt = lastAttempt ?: return false
        // Un `wt` interrumpido deja un prefijo (o nada) del contenido que intentábamos escribir.
        return actual.size < attempt.size && actual.indices.all { actual[it] == attempt[it] }
    }

    private fun createMirrorDocument(): Uri {
        val parent = documentUri(folderDocumentId)
        val created = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", mirrorName)
            ?: throw MirrorWriteException("El proveedor no pudo crear $mirrorName")
        val actualName = queryDocument(created, Document.COLUMN_DISPLAY_NAME)?.let { it as? String }
        if (actualName != mirrorName) {
            // El documento es vacío y lo acabamos de crear nosotros: se puede borrar sin perder nada.
            try {
                DocumentsContract.deleteDocument(resolver, created)
            } catch (error: Exception) {
                Log.w(TAG, "No se pudo borrar el documento creado con nombre alterado", error)
            }
            disable(MirrorDisabledReason.NameAltered)
            throw MirrorNameAlteredException(mirrorName, actualName)
        }
        return created
    }

    private fun writeAndSync(target: Uri, data: ByteArray) {
        val descriptor: ParcelFileDescriptor = resolver.openFileDescriptor(target, "wt")
            ?: throw MirrorWriteException("El proveedor no abrió $mirrorName para escribir")
        var failed = true
        try {
            FileOutputStream(descriptor.fileDescriptor).let { out ->
                out.write(data)
                out.flush()
                try {
                    descriptor.fileDescriptor.sync()
                } catch (error: java.io.SyncFailedException) {
                    // Un pipe o socket no se puede sincronizar; la verificación posterior es la garantía.
                    Log.i(TAG, "fsync no disponible para este descriptor: ${error.message}")
                }
            }
            // En una tubería fiable, el lector puede haber informado de un error.
            descriptor.checkError()
            failed = false
        } finally {
            try {
                descriptor.close()
            } catch (error: IOException) {
                if (!failed) throw MirrorWriteException("Fallo al cerrar $mirrorName: ${error.message}", error)
            }
        }
    }

    /** Relee el documento y compara longitud y SHA-256; una tubería asíncrona puede tardar unos instantes. */
    private fun verify(target: Uri, data: ByteArray, dataHash: String) {
        var lastProblem = "no se pudo releer"
        repeat(VERIFY_ATTEMPTS) { attempt ->
            val reread = readDocument(target, data.size + 1)
            when {
                reread.size != data.size -> lastProblem = "longitud ${reread.size}, esperada ${data.size}"
                sha256(reread) != dataHash -> lastProblem = "el contenido no coincide"
                else -> return
            }
            if (attempt < VERIFY_ATTEMPTS - 1) Thread.sleep(VERIFY_DELAY_MS)
        }
        throw MirrorWriteException("Verificación fallida de $mirrorName: $lastProblem")
    }

    private fun disable(reason: MirrorDisabledReason) {
        disabledReason = reason
        try {
            onDisabled(reason)
        } catch (_: RuntimeException) {
        }
    }

    // ---------------------------------------------------------------- SAF

    private fun documentUri(documentId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    private fun listChildren(): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderDocumentId)
        val cursor = resolver.query(uri, CHILD_COLUMNS, null, null, null)
            ?: throw IOException("El proveedor no devolvió el contenido de la carpeta")
        cursor.use {
            val id = it.getColumnIndex(Document.COLUMN_DOCUMENT_ID)
            val name = it.getColumnIndex(Document.COLUMN_DISPLAY_NAME)
            val mime = it.getColumnIndex(Document.COLUMN_MIME_TYPE)
            val flags = it.getColumnIndex(Document.COLUMN_FLAGS)
            val modified = it.getColumnIndex(Document.COLUMN_LAST_MODIFIED)
            if (id < 0 || name < 0) throw IOException("El proveedor no devolvió id o nombre")
            val result = ArrayList<Child>(it.count)
            while (it.moveToNext()) {
                result += Child(
                    id = it.getString(id) ?: continue,
                    name = it.getString(name) ?: continue,
                    isDirectory = mime >= 0 && it.getString(mime) == Document.MIME_TYPE_DIR,
                    flags = if (flags >= 0 && !it.isNull(flags)) it.getInt(flags) else 0,
                    lastModified = if (modified >= 0 && !it.isNull(modified)) it.getLong(modified).takeIf { ms -> ms > 0 } else null,
                )
            }
            return result
        }
    }

    /** Banderas de la propia carpeta; si no se pueden leer se asume que no admite crear (conservador). */
    private fun folderFlags(): Int =
        (queryDocument(documentUri(folderDocumentId), Document.COLUMN_FLAGS) as? Number)?.toInt() ?: 0

    private fun queryDocument(uri: Uri, column: String): Any? {
        resolver.query(uri, arrayOf(column), null, null, null)?.use {
            if (!it.moveToFirst() || it.isNull(0)) return null
            return when (it.getType(0)) {
                android.database.Cursor.FIELD_TYPE_INTEGER -> it.getLong(0)
                else -> it.getString(0)
            }
        }
        return null
    }

    /** Fecha observada tras escribir; 0, ausente o ilegible = desconocida. */
    private fun observedDate(uri: Uri): Long? = try {
        (queryDocument(uri, Document.COLUMN_LAST_MODIFIED) as? Number)?.toLong()?.takeIf { it > 0 }
    } catch (_: RuntimeException) {
        null
    }

    /** Lee como mucho [limit] bytes (el contenido del proveedor es entrada no confiable). */
    private fun readDocument(uri: Uri, limit: Int): ByteArray {
        val stream: InputStream = resolver.openInputStream(uri) ?: throw IOException("El proveedor no abrió el documento")
        stream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (total < limit) {
                val n = input.read(buffer, 0, minOf(buffer.size, limit - total))
                if (n < 0) break
                out.write(buffer, 0, n)
                total += n
            }
            return out.toByteArray()
        }
    }

    private fun savFiles(children: List<Child>): List<Child> =
        children.filter { !it.isDirectory && it.name.equals(mirrorName, ignoreCase = true) }

    /** Cuántas ROMs de la carpeta (contando esta) resolverían al mismo `<base>.sav`, sin distinguir mayúsculas. */
    private fun siblingRomsSharingBase(children: List<Child>): Int {
        val mine = base.lowercase(Locale.ROOT)
        return children.count { child ->
            !child.isDirectory && !child.name.startsWith(".") &&
                child.name.substringAfterLast('.', "").lowercase(Locale.ROOT) in ROM_EXTENSIONS &&
                child.name.substringBeforeLast('.').lowercase(Locale.ROOT) == mine
        }
    }

    private fun requireBackgroundThread() {
        check(Looper.getMainLooper()?.thread !== Thread.currentThread()) {
            "El espejo SAF no puede usarse en el hilo principal"
        }
    }

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "SafSaveMirror"
        const val VERIFY_ATTEMPTS = 8
        const val VERIFY_DELAY_MS = 150L
        val ROM_EXTENSIONS = setOf("gb", "gbc")
        val CHILD_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}

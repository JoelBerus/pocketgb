package com.joelbermudez.pocketgb.saf

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle

/**
 * Cliente de provisión de [TestDocumentsProvider]. El test corre con el UID de la app y el
 * proveedor con el del APK de test, así que los archivos se crean mediante `call`.
 */
class TestFixtures(private val resolver: ContentResolver) {
    private val authorityUri = Uri.parse("content://${TestDocumentsProvider.AUTHORITY}")

    private fun call(method: String, arg: String? = null, extras: Bundle? = null): Bundle =
        checkNotNull(resolver.call(authorityUri, method, arg, extras))

    fun reset() = call("reset")
    fun put(path: String, bytes: ByteArray, mtimeMs: Long? = null) = call(
        "put",
        path,
        Bundle().apply {
            putByteArray("bytes", bytes)
            if (mtimeMs != null) putLong("mtime", mtimeMs)
        },
    )

    /** Contenido de un archivo del proveedor, o `null` si no existe (lectura directa, sin pasar por SAF). */
    fun read(path: String): ByteArray? {
        val result = call("get", path)
        return if (result.getBoolean("exists")) result.getByteArray("bytes") else null
    }

    fun setMtime(path: String, ms: Long) = call("setMtime", path, Bundle().apply { putLong("ms", ms) })

    private fun flag(method: String, on: Boolean) = call(method, null, Bundle().apply { putBoolean("on", on) })

    /** Abrir para escribir falla. */
    fun failWrite(on: Boolean) = flag("failWrite", on)

    /** El descriptor es una tubería cuyo lector descarta los datos y reporta error. */
    fun failClose(on: Boolean) = flag("failClose", on)

    /** El modo `"w"` no trunca (como algunos proveedores); `"wt"` sí. */
    fun noTruncateOnW(on: Boolean) = flag("noTruncateOnW", on)

    /** Ningún modo trunca: la verificación posterior debe detectarlo. */
    fun noTruncateAtAll(on: Boolean) = flag("noTruncateAtAll", on)

    /** El descriptor es una tubería (fsync imposible) y la copia al archivo es asíncrona. */
    fun pipeFd(on: Boolean) = flag("pipeFd", on)

    /** `createDocument` devuelve un documento con otro nombre (extensión añadida). */
    fun createRenames(on: Boolean) = flag("createRenames", on)

    /** Las banderas no anuncian escritura ni creación, y el proveedor las rechaza. */
    fun readOnlyFlags(on: Boolean) = flag("readOnlyFlags", on)

    /** El permiso desaparece justo después de entregar el descriptor de escritura. */
    fun denyMidWrite(on: Boolean) = flag("denyMidWrite", on)

    /** COLUMN_LAST_MODIFIED llega nulo. */
    fun omitMtime(on: Boolean) = flag("omitMtime", on)

    /** Las aperturas para escribir esperan hasta [releaseWrite]. */
    fun blockWrite(on: Boolean) = flag("blockWrite", on)
    fun releaseWrite() = call("releaseWrite")
    fun waitingWriters(): Int = call("waitingWriters").getInt("count")

    /** Modos con que se abrieron documentos para escribir (`"wt"`...), en orden. */
    fun writeModes(): List<String> = call("writeModes").getStringArrayList("modes").orEmpty()
    fun putSparse(path: String, size: Long) = call("sparse", path, Bundle().apply { putLong("size", size) })
    fun mkdir(path: String) = call("mkdir", path)
    fun deleteAll() = call("deleteAll")
    fun deny(denied: Boolean) = call("deny", null, Bundle().apply { putBoolean("denied", denied) })
    fun denyDir(documentId: String?) = call("denyDir", documentId)
    fun throwDir(documentId: String?) = call("throwDir", documentId)
    fun omitSizeColumn(on: Boolean) = call("omitSize", null, Bundle().apply { putBoolean("on", on) })
    fun omitMimeColumn(on: Boolean) = call("omitMime", null, Bundle().apply { putBoolean("on", on) })
    fun textSize(on: Boolean) = call("textSize", null, Bundle().apply { putBoolean("on", on) })

    /** El proveedor anuncia [size] como SIZE del documento [documentId] aunque su contenido real sea otro. */
    fun declareSize(documentId: String, size: Long) =
        call("declareSize", documentId, Bundle().apply { putLong("size", size) })

    fun snapshot(): List<String> = call("snapshot").getStringArrayList("files").orEmpty()
}

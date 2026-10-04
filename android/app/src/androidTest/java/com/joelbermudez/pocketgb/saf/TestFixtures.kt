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
    fun put(path: String, bytes: ByteArray) = call("put", path, Bundle().apply { putByteArray("bytes", bytes) })
    fun putSparse(path: String, size: Long) = call("sparse", path, Bundle().apply { putLong("size", size) })
    fun mkdir(path: String) = call("mkdir", path)
    fun deleteAll() = call("deleteAll")
    fun deny(denied: Boolean) = call("deny", null, Bundle().apply { putBoolean("denied", denied) })
    fun snapshot(): List<String> = call("snapshot").getStringArrayList("files").orEmpty()
}

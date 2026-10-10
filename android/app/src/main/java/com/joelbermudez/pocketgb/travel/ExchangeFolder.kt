package com.joelbermudez.pocketgb.travel

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.joelbermudez.pocketgb.library.LibraryScanner
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * N7c · bandeja de intercambio `PocketGB/Intercambio/` en la carpeta de la biblioteca (ND2, ND11: `PocketGB/` es de la
 * app y el escáner no la lee como juegos). «Enviar a otro dispositivo» deja aquí el `.pgbm`; al abrir la biblioteca se
 * buscan los paquetes nuevos. Todo pasa por SAF; nada se borra nunca de la carpeta (el usuario la limpia si quiere).
 */
class ExchangeFolder(private val resolver: ContentResolver, private val treeUri: Uri) {
    class Item(val documentId: String, val name: String, val lastModified: Long?, val size: Long?) {
        /** Clave de «ya visto»: el mismo documento reescrito vuelve a ser nuevo. */
        val key: String get() = "$documentId|${lastModified ?: 0}|${size ?: -1}"
    }

    private val rootId: String = DocumentsContract.getTreeDocumentId(treeUri)

    private class Child(val id: String, val name: String, val isDir: Boolean, val modified: Long?, val size: Long?)

    private fun children(parentId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE)
        val cursor = resolver.query(uri, cols, null, null, null) ?: throw IOException("El proveedor no listó la carpeta")
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    add(Child(id, name, c.getString(2) == Document.MIME_TYPE_DIR, c.getLong(3).takeIf { !c.isNull(3) && it > 0 }, c.getLong(4).takeIf { !c.isNull(4) }))
                }
            }
        }
    }

    private fun dir(parentId: String, name: String, create: Boolean): String? {
        children(parentId).firstOrNull { it.isDir && it.name.equals(name, ignoreCase = true) }?.let { return it.id }
        if (!create) return null
        val created = DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId), Document.MIME_TYPE_DIR, name)
            ?: throw IOException("No se pudo crear la carpeta $name")
        return DocumentsContract.getDocumentId(created)
    }

    private fun inboxId(create: Boolean): String? = dir(rootId, LibraryScanner.APP_FOLDER, create)?.let { dir(it, INBOX, create) }

    /** Escribe [data] como un documento nuevo en la bandeja y lo verifica leyéndolo. Devuelve su id. */
    fun send(fileName: String, data: ByteArray): String {
        val parent = inboxId(create = true)!!
        val uri = DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(treeUri, parent), TravelService.MIME_BINARY, fileName)
            ?: throw IOException("No se pudo crear el archivo en Intercambio")
        resolver.openOutputStream(uri, "wt")?.use { it.write(data); it.flush() } ?: throw IOException("No se pudo escribir en Intercambio")
        val back = read(DocumentsContract.getDocumentId(uri))
        if (!back.contentEquals(data)) throw IOException("La copia en Intercambio no coincide")
        return DocumentsContract.getDocumentId(uri)
    }

    /** Paquetes de la bandeja (por extensión; el contenido se valida por cabecera al importarlo). */
    fun list(): List<Item> {
        val parent = inboxId(create = false) ?: return emptyList()
        return children(parent).filter { !it.isDir && !it.name.startsWith(".") && it.name.lowercase(Locale.ROOT).endsWith(".pgbm") }
            .map { Item(it.id, it.name, it.modified, it.size) }
    }

    fun read(documentId: String): ByteArray {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        val input = resolver.openInputStream(uri) ?: throw IOException("No se pudo abrir el paquete")
        input.use { s ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (out.size() <= PgbmResult.MAX_TOTAL) {
                val n = s.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            if (out.size() > PgbmResult.MAX_TOTAL) throw IOException("Paquete demasiado grande")
            return out.toByteArray()
        }
    }

    companion object {
        const val INBOX = "Intercambio"

        /** `<juego> · <equipo> · <aaaammdd-hhmmss>.pgbm`: único por envío y legible en Drive o Archivos. */
        fun fileName(title: String, device: String, nowMs: Long): String {
            fun clean(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").trim().take(60)
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(java.util.Date(nowMs))
            return "${clean(title).ifEmpty { "Partida" }} · ${clean(device).ifEmpty { "Android" }} · $stamp.pgbm"
        }
    }
}

/**
 * Qué paquetes de la bandeja ya se trataron (importados, descartados o enviados desde aquí), en `files/exchange-seen.json`.
 * Solo metadatos: si se pierde, la bandeja vuelve a ofrecerlos y el linaje evita instalar dos veces lo mismo.
 */
class ExchangeInbox(private val file: File) {
    @Serializable private data class Seen(val keys: List<String> = emptyList(), val sentIds: List<String> = emptyList())

    private fun load(): Seen = try {
        if (file.isFile) Json.decodeFromString(Seen.serializer(), file.readText()) else Seen()
    } catch (_: Exception) {
        Seen()
    }

    private fun save(s: Seen) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(Json.encodeToString(Seen.serializer(), s))
        if (!tmp.renameTo(file)) throw IOException("No se pudo guardar la bandeja")
    }

    /** Los nuevos: ni vistos ni enviados desde este teléfono. */
    fun pending(items: List<ExchangeFolder.Item>): List<ExchangeFolder.Item> {
        val s = load()
        return items.filter { it.key !in s.keys && it.documentId !in s.sentIds }.sortedByDescending { it.lastModified ?: 0 }
    }

    @Synchronized fun markSeen(item: ExchangeFolder.Item) = load().let { save(it.copy(keys = (it.keys + item.key).distinct().takeLast(256))) }

    @Synchronized fun markSent(documentId: String) = load().let { save(it.copy(sentIds = (it.sentIds + documentId).distinct().takeLast(256))) }
}

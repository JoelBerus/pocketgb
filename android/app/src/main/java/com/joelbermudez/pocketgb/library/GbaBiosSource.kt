package com.joelbermudez.pocketgb.library

import android.content.Context
import android.net.Uri
import com.joelbermudez.pocketgb.emulator.GbaBios
import com.joelbermudez.pocketgb.emulator.GbaBiosStatus
import java.io.IOException

/**
 * N8: la BIOS opcional de Game Boy Advance del usuario, `gba_bios.bin` en la **raíz** de la carpeta de la biblioteca
 * (= iOS `BIOSFile`; nunca en el repo ni en la app, regla dura 1). Solo se lee: como mucho 16 KiB + 1 (más no puede ser
 * la BIOS). Si no está, no se puede leer o no es la oficial ([GbaBios.status]), el núcleo emula la BIOS (HLE).
 */
object GbaBiosSource {
    /** Bytes de `gba_bios.bin` en la raíz de [tree] (sin distinguir mayúsculas), o `null` si no está. Lanza [IOException]. */
    fun read(tree: DocumentTree): ByteArray? {
        val node = tree.children(null).firstOrNull { !it.isDirectory && it.name.equals(GbaBios.FILE_NAME, ignoreCase = true) }
            ?: return null
        if (node.isVirtual) return null
        return tree.readHead(node, GbaBios.SIZE_BYTES + 1)
    }

    /** Como [read] sin lanzar: cualquier fallo (permiso, proveedor, red) cuenta como «no hay BIOS». */
    fun readOrNull(tree: DocumentTree): ByteArray? = try {
        read(tree)
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    /** La BIOS de la carpeta elegida en la app (SAF), o `null`. Bloquea: fuera del hilo principal. */
    fun read(context: Context): ByteArray? {
        val uri = LibraryFolderStore(context).currentUri() ?: return null
        val tree = try {
            SafDocumentTree(context.contentResolver, Uri.parse(uri))
        } catch (_: RuntimeException) {
            return null
        }
        return readOrNull(tree)
    }

    /** Estado para la UI (Ajustes › Emulación y ajustes del juego). Bloquea: fuera del hilo principal. */
    fun status(context: Context): GbaBiosStatus = GbaBios.status(read(context))
}

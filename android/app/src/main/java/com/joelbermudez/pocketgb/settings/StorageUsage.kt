package com.joelbermudez.pocketgb.settings

import java.io.File
import java.nio.file.Files

/** Uso de disco de PocketGB por tipo. Los ROM viven en la carpeta del usuario y nunca se cuentan ni se borran. */
data class StorageUsage(val saves: Long = 0, val states: Long = 0, val artwork: Long = 0) {
    companion object {
        /** Tamaño de todos los archivos bajo [dir] (0 si no existe). No sigue enlaces simbólicos. */
        fun size(dir: File): Long {
            if (Files.isSymbolicLink(dir.toPath())) return 0
            if (dir.isFile) return dir.length()
            val children = dir.listFiles() ?: return 0
            return children.sumOf { size(it) }
        }

        fun measure(filesDir: File) = StorageUsage(
            saves = size(File(filesDir, "saves")),
            states = size(File(filesDir, "states")),
            // N5: capturas, capturas fijadas e imágenes (importadas y copias de las de la carpeta).
            artwork = size(File(filesDir, "artwork")) + size(File(filesDir, "artwork-pinned")) + size(File(filesDir, "covers")),
        )
    }
}

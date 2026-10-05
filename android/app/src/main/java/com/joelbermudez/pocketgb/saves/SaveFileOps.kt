package com.joelbermudez.pocketgb.saves

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Operaciones de archivo de las partidas, inyectables para poder simular un fallo en cada paso de la
 * escritura atómica (SPEC §5.2). Todas lanzan [IOException] ante un error: nunca devuelven `false`
 * en silencio (por eso no se usa `File.renameTo`, que oculta el errno).
 */
interface SaveFileOps {
    fun exists(file: File): Boolean

    /** Tamaño en bytes, o -1 si no existe. */
    fun length(file: File): Long

    /** Fecha de modificación en milisegundos, o `null` si no existe. */
    fun lastModified(file: File): Long?

    /** Lee el archivo entero. Si pesa más que [limit] lanza [IOException]: el contenido es entrada no confiable. */
    fun readBytes(file: File, limit: Int): ByteArray

    /** Lee como mucho los primeros [count] bytes (cabeceras): no falla por el tamaño total del archivo. */
    fun readPrefix(file: File, count: Int): ByteArray

    /** Escribe todo, trunca y sincroniza el descriptor (fsync) antes de cerrar. */
    fun writeSynced(file: File, data: ByteArray)

    /** Renombra de forma atómica; si [to] existe, lo reemplaza. */
    fun atomicReplace(from: File, to: File)

    /** Sincroniza el directorio para que el renombrado sobreviva a un corte. Puede ser un no-op si el sistema no lo permite. */
    fun syncDirectory(dir: File)

    /** Borra si existe; idempotente. */
    fun delete(file: File)

    fun mkdirs(dir: File)

    /** Nombres de los hijos directos (vacío si el directorio no existe). */
    fun list(dir: File): List<String>
}

/** Implementación real sobre `java.nio`. Pura JVM: sirve igual en los tests y en Android. */
object PosixSaveFileOps : SaveFileOps {
    override fun exists(file: File) = file.exists()

    override fun length(file: File): Long = if (file.exists()) file.length() else -1

    override fun lastModified(file: File): Long? = if (file.exists()) file.lastModified() else null

    override fun readBytes(file: File, limit: Int): ByteArray {
        val size = file.length()
        if (size > limit) throw IOException("${file.path}: $size bytes superan el tope de $limit")
        // Se lee con tope también en el bucle: el archivo pudo crecer entre `length` y la lectura.
        file.inputStream().use { input ->
            val out = java.io.ByteArrayOutputStream(size.toInt().coerceAtLeast(16))
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > limit) throw IOException("${file.path}: supera el tope de $limit bytes")
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }
    }

    override fun readPrefix(file: File, count: Int): ByteArray = file.inputStream().use { input ->
        val buffer = ByteArray(count)
        var total = 0
        while (total < count) {
            val n = input.read(buffer, total, count - total)
            if (n < 0) break
            total += n
        }
        buffer.copyOf(total)
    }

    override fun writeSynced(file: File, data: ByteArray) {
        FileOutputStream(file).use { out ->
            out.write(data)
            out.flush()
            out.fd.sync()
        }
    }

    override fun atomicReplace(from: File, to: File) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /**
     * Mejor esfuerzo: abrir un directorio de solo lectura y hacer `force` es lo que hace `fsync(dirfd)` en
     * Linux/Android, pero algunos sistemas de archivos (o Windows en los tests) lo rechazan con `EINVAL`/
     * `EACCES`. El renombrado ya es atómico frente a la muerte del proceso; el fsync del directorio solo
     * añade durabilidad ante un corte de corriente, así que su fallo se tolera.
     */
    override fun syncDirectory(dir: File) {
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: IOException) {
        }
    }

    override fun delete(file: File) {
        Files.deleteIfExists(file.toPath())
    }

    override fun mkdirs(dir: File) {
        Files.createDirectories(dir.toPath())
    }

    override fun list(dir: File): List<String> = dir.list()?.toList() ?: emptyList()
}

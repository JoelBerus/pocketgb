package com.joelbermudez.pocketgb.saves

import java.io.File
import java.io.IOException

/** Huella de prueba (SHA-256 truncado a 32 hex, como las reales). */
const val TEST_FP = "00112233445566778899aabbccddeeff"

/** `n` bytes todos iguales a `value`: versiones sintéticas de una SRAM (nunca hay partidas reales en el repo). */
fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

fun version(value: Int, size: Int = 4): ByteArray = ByteArray(size) { value.toByte() }

fun File.names(): List<String> = list()?.sorted() ?: emptyList()

/** Falso de [SaveFileOps] que falla en la operación mutante número [failAt] (1 = la primera). */
class FaultInjectingFileOps(
    private val delegate: SaveFileOps = PosixSaveFileOps,
    var failAt: Int? = null,
) : SaveFileOps by delegate {
    var count = 0
        private set
    val log = mutableListOf<String>()

    class Injected(op: String) : IOException("fallo inyectado en $op")

    /** Devuelve `true` si esta operación debe fallar. */
    private fun tick(op: String): Boolean {
        count++
        log += op
        return failAt == count
    }

    override fun writeSynced(file: File, data: ByteArray) {
        val op = "writeSynced:${file.name}"
        if (tick(op)) {
            // Escritura parcial: la peor forma de fallar (el proceso muere a mitad de write).
            file.writeBytes(data.copyOf(data.size / 2))
            throw Injected(op)
        }
        delegate.writeSynced(file, data)
    }

    override fun copySynced(from: File, to: File) {
        val op = "copySynced:${to.name}"
        if (tick(op)) {
            to.writeBytes(from.readBytes().let { it.copyOf(it.size / 2) })
            throw Injected(op)
        }
        delegate.copySynced(from, to)
    }

    override fun atomicReplace(from: File, to: File) {
        val op = "atomicReplace:${from.name}->${to.name}"
        if (tick(op)) throw Injected(op)
        delegate.atomicReplace(from, to)
    }

    override fun syncDirectory(dir: File) {
        val op = "syncDirectory:${dir.name}"
        // El renombrado ya se hizo: el fallo del fsync no puede dejar nada a medias.
        val fail = tick(op)
        delegate.syncDirectory(dir)
        if (fail) throw Injected(op)
    }

    override fun mkdirs(dir: File) {
        val op = "mkdirs:${dir.name}"
        if (tick(op)) throw Injected(op)
        delegate.mkdirs(dir)
    }

    override fun delete(file: File) {
        val op = "delete:${file.name}"
        if (tick(op)) throw Injected(op)
        delegate.delete(file)
    }
}

/** Espejo en memoria. `dateOnWrite` es la fecha que "observa" el proveedor tras cada escritura. */
class FakeSaveMirror(
    @Volatile var snapshotValue: SaveMirror.Snapshot = SaveMirror.Snapshot.Absent,
    @Volatile var dateOnWrite: Long? = FAR_FUTURE_MS,
    /** ND20 (m): ubicación simulada del espejo (`null` = desconocida, como los tests anteriores). */
    override val location: String? = null,
) : SaveMirror {
    @Volatile var failWrites = false
    val writes = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()

    override fun snapshot() = snapshotValue

    override fun write(data: ByteArray): Long? {
        if (failWrites) throw IOException("espejo caído")
        writes += data
        snapshotValue = SaveMirror.Snapshot.Read(data, dateOnWrite)
        return dateOnWrite
    }

    companion object {
        /** Año 2100: siempre más nueva que la mtime real de un archivo recién creado en el test. */
        const val FAR_FUTURE_MS = 4_102_444_800_000L
    }
}

fun waitUntil(timeoutMs: Long = 3_000, condition: () -> Boolean): Boolean {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (System.nanoTime() < deadline) {
        if (condition()) return true
        Thread.sleep(5)
    }
    return condition()
}

/** Escritor de espejo que se bloquea hasta que el test lo libera (proveedor remoto lento). */
class BlockingWriter(private val mirror: FakeSaveMirror) {
    val started = java.util.concurrent.Semaphore(0)
    val unblock = java.util.concurrent.Semaphore(0)
    val finished = java.util.concurrent.Semaphore(0)

    fun write(data: ByteArray): Long? {
        started.release()
        unblock.acquire()
        val date = mirror.write(data)
        finished.release()
        return date
    }
}

fun java.util.concurrent.Semaphore.tryAcquireWithin(ms: Long = 3_000) =
    tryAcquire(ms, java.util.concurrent.TimeUnit.MILLISECONDS)

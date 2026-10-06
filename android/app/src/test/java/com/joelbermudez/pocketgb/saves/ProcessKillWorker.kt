package com.joelbermudez.pocketgb.saves

import java.io.File
import java.nio.ByteBuffer

/**
 * Proceso hijo de [ProcessKillTest]: guarda versiones numeradas en bucle con el [AtomicSaveWriter] real
 * hasta que lo matan. Imprime `s<v>` antes de empezar a guardar la versión `v` y `c<v>` al terminar.
 * Argumentos: directorio de partidas y huella.
 */
object ProcessKillWorker {
    const val SIZE = 8192

    /** Una versión = el entero `v` repetido hasta llenar [SIZE]: cualquier mezcla o truncado se detecta. */
    fun encode(v: Int): ByteArray {
        val buf = ByteBuffer.allocate(SIZE)
        repeat(SIZE / 4) { buf.putInt(v) }
        return buf.array()
    }

    /** `v` si [data] es una versión completa y coherente; `null` si está truncada o mezclada. */
    fun decode(data: ByteArray): Int? {
        if (data.size != SIZE) return null
        val buf = ByteBuffer.wrap(data)
        val v = buf.getInt(0)
        repeat(SIZE / 4) { if (buf.getInt() != v) return null }
        return v
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val store = SaveStore(File(args[0]), args[1])
        var v = (store.load()?.let(::decode) ?: 0) + 1
        println("ready")
        while (true) {
            println("s$v")
            store.save(encode(v))
            println("c$v")
            v++
        }
    }
}

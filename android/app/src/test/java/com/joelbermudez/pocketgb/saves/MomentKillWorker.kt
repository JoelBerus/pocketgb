package com.joelbermudez.pocketgb.saves

import java.io.File

/**
 * Proceso hijo de [MomentKillTest]: en bucle, alternando, (a) simula cargar un momento en la sesión (anillo «Antes de
 * cargar» + escritura atómica de la partida `v`) y (b) crea un momento con la partida `v` y lo «recupera» con
 * [MomentLibrary.installSram], hasta que lo matan. Imprime `s<v>` al empezar y `c<v>` al terminar cada vuelta.
 * Argumentos: raíz de datos y huella.
 */
object MomentKillWorker {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args[0])
        val fp = args[1]
        val library = MomentLibrary(File(root, "moments"), File(root, "states"), File(root, "saves"), ownership = FingerprintOwnership())
        val save = SaveStore(File(root, "saves"), fp)
        var v = (save.load()?.let(ProcessKillWorker::decode) ?: 0) + 1
        println("ready")
        while (true) {
            println("s$v")
            if (v % 2 == 0) {
                // Como `GameSession.loadMoment`: la posición actual al anillo y después la partida del momento se escribe
                // con el escritor atómico (lo que hace el vaciado tras aplicar el estado).
                val current = save.load()
                library.store(fp).pushBeforeLoad(MomentStore.Capture("PGBS$v".toByteArray(), current, null), "M$v")
                save.save(ProcessKillWorker.encode(v))
            } else {
                val m = library.store(fp).create(MomentStore.Capture(null, ProcessKillWorker.encode(v), null), "M$v")
                library.installSram(fp, MomentStore.Kind.MOMENT, m.id, m.name, openFingerprint = null)
            }
            // Los momentos viejos se borran para que el directorio no crezca sin fin.
            library.store(fp).snapshot().moments.drop(4).forEach { library.delete(fp, MomentStore.Kind.MOMENT, it.id) }
            println("c$v")
            v++
        }
    }
}

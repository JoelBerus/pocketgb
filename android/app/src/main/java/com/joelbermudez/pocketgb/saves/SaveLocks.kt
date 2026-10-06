package com.joelbermudez.pocketgb.saves

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * Un lock por partida (directorio + huella) compartido entre TODAS las instancias de [SaveStore], vivan donde
 * vivan: el hilo de guardado, el del espejo (`addBackup`), la apertura y Ajustes › Partidas. Sin él, dos hilos
 * mutando `backups/` a la vez pisaban el mismo temporal y podían perder de forma definitiva el contenido
 * externo del `.sav` (A5 auditoría, Opus H1). Es reentrante (restore → save) y es una hoja del orden de
 * locks: ninguna operación espera a otro lock mientras lo tiene.
 */
object SaveLocks {
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    fun forSave(directory: File, fingerprint: String): ReentrantLock =
        locks.getOrPut(File(directory.absoluteFile, fingerprint).path) { ReentrantLock() }
}

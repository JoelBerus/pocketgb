package com.joelbermudez.pocketgb.saves

import java.io.File
import java.io.IOException

/**
 * Escritura atómica con backups rotativos (SPEC §5.2). Invariante: si ya existía el destino, en todo
 * instante existe uno completo (el anterior o el nuevo); jamás uno parcial. Cada operación pasa por
 * [SaveFileOps] para poder inyectar un fallo en cualquiera de ellas.
 *
 * Es seguro frente a la muerte del proceso; la durabilidad ante un corte de corriente depende del fsync
 * del archivo y del directorio (mejor esfuerzo en el segundo).
 */
class AtomicSaveWriter(private val ops: SaveFileOps, private val keep: Int = SaveStore.KEEP_BACKUPS) {
    /**
     * @param target destino (`<huella>.sav`).
     * @param backup archivo del backup `n` (1…[keep]), p. ej. `backups/<huella>.<n>.sav`.
     * @return `true` si escribió; `false` si el contenido era idéntico al actual (no rota backups).
     */
    fun write(data: ByteArray, target: File, backup: (Int) -> File): Boolean {
        val exists = ops.exists(target)

        // 1. Contenido igual al actual: nada que hacer. Si no se puede leer, se sigue (se reescribe).
        if (exists && ops.length(target) == data.size.toLong()) {
            val same = try {
                ops.readBytes(target, data.size).contentEquals(data)
            } catch (_: IOException) {
                false
            }
            if (same) return false
        }

        ensureDirectory(target.parentFile)

        // 2. Escribir el temporal completo y sincronizar su descriptor.
        val tmp = File(target.path + ".tmp")
        ops.writeSynced(tmp, data)

        if (exists) {
            val backupsDir = backup(1).parentFile
            ensureDirectory(backupsDir)
            val current = ops.readBytes(target, SaveStore.MAX_SAVE_BYTES)
            // Si una escritura anterior murió entre el paso 4 y el 5, el .1 ya es copia del actual:
            // no se rota otra vez (evita duplicados que empujarían fuera del historial una versión real).
            val alreadyBackedUp = ops.exists(backup(1)) && try {
                ops.readBytes(backup(1), SaveStore.MAX_SAVE_BYTES).contentEquals(current)
            } catch (_: IOException) {
                false
            }
            if (!alreadyBackedUp) {
                // 3. Rotar solo backups: n-1 → n, …, 1 → 2 (el actual no se toca).
                for (n in keep - 1 downTo 1) {
                    if (ops.exists(backup(n))) ops.atomicReplace(backup(n), backup(n + 1))
                }
                // 4. Copiar (no mover) el actual a .1 vía .1.tmp.
                val backupTmp = File(backupsDir, backup(1).name.removeSuffix(".sav") + ".tmp")
                ops.writeSynced(backupTmp, current)
                ops.atomicReplace(backupTmp, backup(1))
                ops.syncDirectory(backupsDir)
            }
        }

        // 5. Instalar: el renombrado atómico reemplaza o crea.
        ops.atomicReplace(tmp, target)
        ops.syncDirectory(target.parentFile)
        return true
    }

    private fun ensureDirectory(dir: File) {
        if (!ops.exists(dir)) ops.mkdirs(dir)
    }
}

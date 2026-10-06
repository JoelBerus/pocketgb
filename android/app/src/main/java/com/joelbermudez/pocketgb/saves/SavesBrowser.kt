package com.joelbermudez.pocketgb.saves

import java.io.File

/** Una partida local con sus copias de seguridad, para Ajustes › Partidas. */
class SavedGameUi(
    val fingerprint: String,
    /** Título del juego si se conoce (si no, se muestra la huella). */
    val title: String?,
    val fileName: String?,
    val backups: List<SaveStore.BackupInfo>,
)

/**
 * Lista las partidas de `saves/` y restaura copias (J4). Restaurar usa [SaveStore.restore], que antes guarda la
 * partida actual como copia `.1`: nunca se pierde nada. La UI deshabilita la restauración de la huella que
 * tiene una sesión abierta; aquí además se rechaza si [openFingerprint] coincide.
 */
class SavesBrowser(private val directory: File, private val ops: SaveFileOps = PosixSaveFileOps) {
    fun list(): List<SavedGameUi> {
        val index = SavesIndex(directory, ops)
        return index.savedGames().map { game ->
            SavedGameUi(
                fingerprint = game.fingerprint,
                title = game.record?.title,
                fileName = game.record?.fileName,
                backups = SaveStore(directory, game.fingerprint, ops).backups(),
            )
        }
    }

    /** @throws IllegalStateException si esa huella tiene la sesión abierta. */
    fun restore(fingerprint: String, backup: Int, openFingerprint: String?) {
        check(fingerprint != openFingerprint) { "No se puede restaurar mientras el juego está abierto" }
        // Con los tamaños válidos del cartucho (guardados al abrir el juego) no se restaura una partida que el
        // núcleo rechazaría; si el índice no los tiene (entradas antiguas) no se puede juzgar y se permite.
        val sizes = SavesIndex(directory, ops).load()[fingerprint]?.validSizes?.toSet()
        SaveStore(directory, fingerprint, ops).restore(backup, sizes)
    }
}

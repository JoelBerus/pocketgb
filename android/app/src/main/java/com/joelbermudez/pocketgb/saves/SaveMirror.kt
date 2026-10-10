package com.joelbermudez.pocketgb.saves

/**
 * Copia de la partida junto a la ROM (`<rom>.sav`, en la carpeta SAF). La local es la autoritativa:
 * un fallo del espejo nunca invalida un guardado (SPEC §5.3). Las llamadas pueden bloquear (proveedor
 * remoto): nunca se hacen en el hilo principal ni en el hilo de guardado local.
 */
interface SaveMirror {
    /** Estado del espejo al abrir un juego. */
    sealed interface Snapshot {
        /** No hay `.sav` junto a la ROM. */
        data object Absent : Snapshot

        /** Leído: contenido y fecha de modificación en ms (si el proveedor la da). */
        class Read(val data: ByteArray, val dateMs: Long?) : Snapshot {
            override fun equals(other: Any?) = other is Read && dateMs == other.dateMs && data.contentEquals(other.data)
            override fun hashCode() = 31 * data.contentHashCode() + (dateMs?.hashCode() ?: 0)
            override fun toString() = "Read(${data.size} bytes, dateMs=$dateMs)"
        }

        /** Existe (o puede existir) pero no se pudo leer de forma fiable: no se debe tocar. */
        data object Unavailable : Snapshot
    }

    fun snapshot(): Snapshot

    /**
     * ND20 (m): dónde vive este espejo (carpeta + nombre), para el historial de escrituras propias. Un espejo en otra
     * ubicación (un duplicado del mismo ROM) no hereda el linaje. `null` = desconocida.
     */
    val location: String? get() = null

    /**
     * Escribe el espejo (no se declara atómico). Devuelve la fecha de modificación observada justo
     * después de escribir, para el historial de escrituras propias (`SaveStore.recordSuccessfulMirror`),
     * o `null` si el proveedor no la da. Lanza ante cualquier fallo.
     */
    fun write(data: ByteArray): Long?
}

/**
 * Fuente de la SRAM de la sesión viva. `dirtySeq` crece cada vez que el juego guarda;
 * `copy` devuelve una copia consistente (en el mismo hilo que luego la escribe: invariante I4).
 */
interface SramSource {
    fun dirtySeq(): Long
    fun copy(): ByteArray
}

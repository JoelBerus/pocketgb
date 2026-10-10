package com.joelbermudez.pocketgb.saves

/**
 * Qué partida cargar al abrir una ROM (SPEC §5.3). Decisión pura, sin E/S, para poder probar cada caso.
 * Portado de `SaveResolution` de iOS.
 */
sealed interface SaveResolution {
    /** No hay partida: el juego empieza de cero. */
    data object None : SaveResolution

    /**
     * Cargar [data].
     * - [backupOther]: el espejo, cuando pierde frente a la local; se guarda como backup.
     * - [installLocal]: la partida viene del espejo y se instala en local con la escritura atómica, que
     *   deja la local anterior en `.1` (así también queda respaldada).
     * - [updateMirror]: el espejo falta o está desfasado y se reescribe con [data].
     * - [mirrorIgnored]: el espejo tiene un tamaño que el core no acepta; no se toca en toda la sesión.
     * - [quarantineLocal]: la local tiene un tamaño incorrecto; se aparta (fuera de la rotación) antes de
     *   instalar el espejo.
     */
    class Load(
        val data: ByteArray,
        val backupOther: ByteArray?,
        val installLocal: Boolean,
        val updateMirror: Boolean,
        val mirrorIgnored: Boolean,
        val quarantineLocal: Boolean,
    ) : SaveResolution {
        override fun equals(other: Any?) = other is Load && data.contentEquals(other.data) &&
            java.util.Arrays.equals(backupOther, other.backupOther) && installLocal == other.installLocal &&
            updateMirror == other.updateMirror && mirrorIgnored == other.mirrorIgnored &&
            quarantineLocal == other.quarantineLocal

        override fun hashCode() = data.contentHashCode() * 31 + java.util.Arrays.hashCode(backupOther) +
            listOf(installLocal, updateMirror, mirrorIgnored, quarantineLocal).hashCode()

        override fun toString() = "Load(${data.size} bytes, backupOther=${backupOther?.size}, installLocal=$installLocal, " +
            "updateMirror=$updateMirror, mirrorIgnored=$mirrorIgnored, quarantineLocal=$quarantineLocal)"
    }

    /** La única partida que hay tiene un tamaño incorrecto: no se carga ni se toca, y la sesión no guarda. */
    data class WrongSize(val fromMirror: Boolean) : SaveResolution

    class Candidate(val data: ByteArray, val dateMs: Long?) {
        override fun equals(other: Any?) = other is Candidate && dateMs == other.dateMs && data.contentEquals(other.data)
        override fun hashCode() = data.contentHashCode() * 31 + (dateMs?.hashCode() ?: 0)
    }

    companion object {
        /**
         * @param local `saves/<huella>.sav`.
         * @param mirror `<rom>.sav` junto a la ROM (null si no hay).
         * @param mirrorIsOwned el espejo es una escritura propia terminada tarde (`SaveStore.recognizesOwnedMirror`).
         * @param isValidSize tamaños que acepta `gb_sram_load`.
         */
        fun resolve(
            local: Candidate?,
            mirror: Candidate?,
            mirrorIsOwned: Boolean = false,
            isValidSize: (Int) -> Boolean,
        ): SaveResolution {
            val localOk = local?.let { isValidSize(it.data.size) } ?: false
            val mirrorOk = mirror?.let { isValidSize(it.data.size) } ?: false
            if (local == null && mirror == null) return None
            if (mirror == null) {
                if (!localOk) return WrongSize(fromMirror = false)
                return Load(local.data, null, installLocal = false, updateMirror = true, mirrorIgnored = false, quarantineLocal = false)
            }
            if (local == null) {
                if (!mirrorOk) return WrongSize(fromMirror = true)
                return Load(mirror.data, null, installLocal = true, updateMirror = false, mirrorIgnored = false, quarantineLocal = false)
            }
            if (!mirrorOk) {
                // El espejo no se entiende: se usa la local (si vale) y el espejo no se toca.
                if (!localOk) return WrongSize(fromMirror = false)
                return Load(local.data, null, installLocal = false, updateMirror = false, mirrorIgnored = true, quarantineLocal = false)
            }
            if (!localOk) {
                // Local con tamaño incorrecto: se aparta intacta y se usa el espejo.
                return Load(mirror.data, null, installLocal = true, updateMirror = false, mirrorIgnored = false, quarantineLocal = true)
            }
            if (local.data.contentEquals(mirror.data)) {
                return Load(local.data, null, installLocal = false, updateMirror = false, mirrorIgnored = false, quarantineLocal = false)
            }
            if (mirrorIsOwned) {
                // Escritura propia terminada tarde: la local gana sin respaldar el espejo obsoleto.
                return Load(local.data, null, installLocal = false, updateMirror = true, mirrorIgnored = false, quarantineLocal = false)
            }
            // Difieren: gana la más reciente; ante la duda (sin fecha o empate), la local.
            val mirrorNewer = (mirror.dateMs ?: Long.MIN_VALUE) > (local.dateMs ?: Long.MIN_VALUE)
            return if (mirrorNewer) {
                Load(mirror.data, null, installLocal = true, updateMirror = false, mirrorIgnored = false, quarantineLocal = false)
            } else {
                Load(local.data, mirror.data, installLocal = false, updateMirror = true, mirrorIgnored = false, quarantineLocal = false)
            }
        }
    }
}

/** Aviso al abrir un juego sobre su partida. Los textos viven en recursos de la UI; aquí solo el tipo. */
sealed interface SaveLoadWarning {
    /**
     * N8 (= iOS `GameSettingsSaveWarning`, G8-H5): el `.sav` (local o junto al ROM) no coincide con el tipo de partida o
     * el reloj fijados en los ajustes del juego de GBA. Ese `.sav` no se toca. [noSave]: los ajustes fuerzan «Sin
     * partida» (sin reloj), así que el juego no guarda.
     */
    data class GameSettingsMismatch(val noSave: Boolean) : SaveLoadWarning

    /** La partida local tiene un tamaño incorrecto y no hay otra: no se toca y no se guarda. */
    data object LocalWrongSize : SaveLoadWarning

    /** El `.sav` junto al juego tiene un tamaño incorrecto y no hay otra: no se toca y no se guarda. */
    data object MirrorWrongSizeOnly : SaveLoadWarning

    /** El `.sav` junto al juego tiene un tamaño incorrecto; se usa la local. */
    data object MirrorIgnored : SaveLoadWarning

    /** La local tenía un tamaño incorrecto: se apartó y se usa la de junto al juego. */
    data object LocalQuarantined : SaveLoadWarning

    /** El `.sav` junto al juego existe pero no se pudo leer (proveedor remoto): se usa la local y no se toca. */
    data object MirrorUnavailable : SaveLoadWarning

    /** Otra ROM de la carpeta comparte el nombre del `.sav`: no se copia junto al juego. */
    data object MirrorShared : SaveLoadWarning

    /** La carpeta solo permite lectura: se importó el `.sav` si había, pero nunca se escribirá. */
    data object MirrorReadOnly : SaveLoadWarning

    /**
     * N1-H5: el `.sav` junto al juego (más nuevo y no escrito por PocketGB) sustituyó a la partida local, que quedó
     * apartada en Ajustes › Partidas › Apartadas.
     */
    data object LocalSetAside : SaveLoadWarning

    /**
     * N7a: el `.sav` junto al juego cambió por fuera (otro equipo) y la partida de aquí no había cambiado desde la última
     * vez que PocketGB lo escribió: se instaló el de fuera; la anterior quedó en las copias de seguridad.
     */
    data class ExternalChange(val readOnly: Boolean = false) : SaveLoadWarning

    /**
     * ND20 (c): el `.sav` junto al juego era una versión anterior escrita por PocketGB; gana la de aquí y esa versión
     * quedó apartada en Ajustes › Partidas. [readOnly]: además la carpeta es de solo lectura (H12).
     */
    data class MirrorOlderSetAside(val readOnly: Boolean = false) : SaveLoadWarning

    /**
     * N7a: la partida cambió aquí y también en el otro equipo. Se sigue con la de aquí; la otra quedó en las copias,
     * apartada y como momento «Conflicto …» ([conflictMomentId], null si no se pudo crear) para recuperarla.
     */
    data class Divergence(val conflictMomentId: String?, val readOnly: Boolean = false) : SaveLoadWarning

    /** Error al leer la partida local; [detail] viene del sistema. */
    data class Unreadable(val detail: String) : SaveLoadWarning
}

/** N8 (= iOS `GameSettingsSaveWarning.check`): ¿hay que avisar de que los ajustes forzados no casan con un `.sav`? */
object GameSettingsSaveCheck {
    /**
     * [validSizes]: los que acepta el cartucho con los ajustes forzados (vacío con «Sin partida» y sin reloj).
     * [existingSizes]: los de los `.sav` que existen (local y espejo; -1 = existe pero no se sabe su tamaño).
     * `null` si no hay ajustes forzados o todo `.sav` existente coincide.
     */
    fun check(forced: Boolean, validSizes: Set<Int>, existingSizes: List<Int>): SaveLoadWarning.GameSettingsMismatch? {
        if (!forced || existingSizes.none { it !in validSizes }) return null
        return SaveLoadWarning.GameSettingsMismatch(noSave = validSizes.isEmpty())
    }
}

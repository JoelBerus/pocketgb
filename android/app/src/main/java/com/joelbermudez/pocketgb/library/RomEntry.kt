package com.joelbermudez.pocketgb.library

/** Qué impide jugar a un archivo de la carpeta. Cada caso tiene su propia recuperación. */
enum class RomProblem(val message: String) {
    TOO_LARGE("Supera los 8 MiB, así que no es un ROM de Game Boy."),
    INVALID_HEADER("No tiene una cabecera de Game Boy válida."),
    UNREADABLE("No se pudo leer el archivo."),
    REMOTE_UNAVAILABLE("El proveedor aún no tiene el archivo disponible. Reintenta con conexión."),
}

/** Dónde está un archivo dentro de la carpeta de la biblioteca: carpetas desde la raíz y nombre (N1). */
data class RomLocation(val folderPath: List<String>, val fileName: String)

/** Un `.gb`/`.gbc` de la carpeta de la biblioteca. Valor inmutable creado por el escáner. */
data class RomEntry(
    /**
     * Ruta relativa a la carpeta ("Pokemon Red.gb" o "Pokémon/Gen 1/Pokemon Red.gb"), con sufijo `#…` si dos documentos
     * comparten ruta. Clave provisional de las preferencias hasta conocer la huella (N1a).
     */
    val id: String,
    /** URI SAF del documento; solo se usa para leer. */
    val uri: String,
    val fileName: String,
    /** Título de la cabecera, o el nombre del archivo si no se pudo leer. */
    val title: String,
    val isColor: Boolean,
    val sizeBytes: Long,
    val headerChecksumOk: Boolean,
    val problem: RomProblem?,
    /** Id de documento de la carpeta que contiene el ROM: ahí vive el `.sav` del espejo SAF. */
    val folderDocumentId: String? = null,
    /** K19: no se había visto en el escaneo anterior y aún no se ha abierto. */
    val isNew: Boolean = false,
    /** K20: fecha de modificación (epoch ms) del `.sav` junto al ROM, solo informativa. `null` si no hay o no se sabe. */
    val mirrorSaveDate: Long? = null,
    /**
     * A9: nombre que eligió el usuario (Renombrar), aplicado por [LibraryPreferencesData.withAlias]; el escáner nunca
     * lo pone. Solo es presentación: el ROM, su `.sav` y sus estados no cambian.
     */
    val alias: String? = null,
    /**
     * N1b: carpetas desde la raíz hasta el ROM (vacía en la raíz). El escáner la da siempre; por defecto se deduce del
     * id (datos de prueba y catálogo).
     */
    val folderPath: List<String> = id.substringBeforeLast('/', missingDelimiterValue = "").split('/').filter { it.isNotEmpty() },
    /** N1a: fecha de modificación del ROM según el proveedor (epoch ms), parte de su sello de documento; `null` si no la da. */
    val lastModified: Long? = null,
    /**
     * N1a: otras copias del mismo ROM (misma huella conocida) en la carpeta, aplicadas por [LibraryQuery]; el escáner
     * nunca las pone. Solo informativo: las copias comparten partida, estados, ajustes y portada (van por huella).
     */
    val alsoAt: List<RomLocation> = emptyList(),
) {
    /** Lo que ve el usuario en biblioteca, carril, favoritos, detalle y pausa: el alias o el título de la cabecera. */
    val displayTitle: String
        get() = alias ?: title

    /** Carpetas separadas por `/` ("" en la raíz). */
    val subfolder: String
        get() = folderPath.joinToString("/")

    val location: RomLocation
        get() = RomLocation(folderPath, fileName)

    /** N1a: hay otra copia con la misma huella. */
    val isDuplicate: Boolean
        get() = alsoAt.isNotEmpty()

    val isPlayable: Boolean
        get() = problem == null

    val system: String
        get() = if (isColor) "Game Boy Color" else "Game Boy"

    /** Etiqueta corta para tarjetas estrechas. */
    val systemShort: String
        get() = if (isColor) "GBC" else "GB"
}

/** Lectura de la cabecera del cartucho (docs/03 §Cabecera), solo para mostrar. */
object RomHeader {
    const val MINIMUM_BYTES = 0x150

    data class Info(val title: String, val isColor: Boolean, val checksumOk: Boolean)

    fun parse(bytes: ByteArray): Info? {
        if (bytes.size < MINIMUM_BYTES) return null
        val isColor = bytes[0x143].toInt() and 0x80 != 0
        // Título: 16 bytes, o 15 si 0x143 es el flag CGB; se corta en el primer 0.
        val titleEnd = if (isColor) 0x143 else 0x144
        val raw = (0x134 until titleEnd).map { bytes[it].toInt() and 0xFF }.takeWhile { it != 0 }
        val title = raw.joinToString("") { b -> if (b in 0x20 until 0x7F) b.toChar().toString() else "?" }.trim()
        return Info(title = title, isColor = isColor, checksumOk = checksumOk(bytes))
    }

    private fun checksumOk(bytes: ByteArray): Boolean {
        var x = 0
        for (i in 0x134..0x14C) x = (x - (bytes[i].toInt() and 0xFF) - 1) and 0xFF
        return x == (bytes[0x14D].toInt() and 0xFF)
    }
}

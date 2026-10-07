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
    /** N1a: id de documento del proveedor (`COLUMN_DOCUMENT_ID`), parte del sello; `null` en datos de prueba. */
    val documentId: String? = null,
    /** N1-H1: identidad de la cabecera ([RomHeader.identity]), parte del sello; `null` si no se pudo leer. */
    val headerKey: String? = null,
    /**
     * N4: etiquetas libres del juego (por huella), aplicadas por [LibraryQuery]; el escáner nunca las pone. Solo son
     * metadatos de la app.
     */
    val tags: List<String> = emptyList(),
    /**
     * N4 (ND3): categoría virtual elegida en la app («Mostrar en categoría…», por huella), aplicada por [LibraryQuery];
     * `null` = la de su carpeta. Vacía = «Sin categoría». Nunca mueve el archivo.
     */
    val virtualFolderPath: List<String>? = null,
) {
    /** Lo que ve el usuario en biblioteca, carril, favoritos, detalle y pausa: el alias o el título de la cabecera. */
    val displayTitle: String
        get() = alias ?: title

    /** N4: la categoría en la que se muestra: la virtual si la hay ([virtualFolderPath]) o la de su carpeta. */
    val categoryPath: List<String>
        get() = virtualFolderPath ?: folderPath

    /** N4: se muestra en otra categoría que la de su carpeta (insignia «Movido en la app»). */
    val isMovedInApp: Boolean
        get() = virtualFolderPath != null

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

    /** Primer y último byte (exclusivo) de la identidad de la cabecera: título … checksum global (0x134–0x14F). */
    private const val IDENTITY_START = 0x134
    private const val IDENTITY_END = 0x150

    /**
     * N1-H1: identidad de la cabecera en hexadecimal: título, código de fabricante, CGB, licencia, SGB, tipo de cartucho
     * (0x147), tamaños de ROM y RAM (0x148–0x149), destino, versión, checksum de cabecera (0x14D) y global
     * (0x14E–0x14F). Dos ROMs distintos casi nunca la comparten. `null` si [bytes] no llega a 0x150.
     */
    fun identity(bytes: ByteArray): String? {
        if (bytes.size < MINIMUM_BYTES) return null
        return (IDENTITY_START until IDENTITY_END).joinToString("") { "%02x".format(bytes[it].toInt() and 0xFF) }
    }

    /** Lo contrario de [identity]: 0x150 bytes con la identidad en su sitio (lo que [parse] necesita), o `null`. */
    fun fromIdentity(hex: String): ByteArray? {
        val length = IDENTITY_END - IDENTITY_START
        if (hex.length != length * 2) return null
        val bytes = ByteArray(MINIMUM_BYTES)
        for (i in 0 until length) {
            val value = hex.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
            bytes[IDENTITY_START + i] = value.toByte()
        }
        return bytes
    }

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

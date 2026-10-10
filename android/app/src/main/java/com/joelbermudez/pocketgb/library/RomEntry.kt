package com.joelbermudez.pocketgb.library

import com.joelbermudez.pocketgb.emulator.Console

/** Qué impide jugar a un archivo de la carpeta. Cada caso tiene su propia recuperación. */
enum class RomProblem(val message: String) {
    TOO_LARGE("Supera los 8 MiB, así que no es un ROM de Game Boy."),
    INVALID_HEADER("No tiene una cabecera de Game Boy válida."),
    /** N8: un `.gba` de más de 32 MiB (= iOS `tooLargeGBA`). */
    TOO_LARGE_GBA("Supera los 32 MiB, así que no es un ROM de Game Boy Advance."),
    /** N8: un `.gba` demasiado corto o sin el byte fijo 0x96 de la cabecera (= iOS `invalidHeaderGBA`). */
    INVALID_HEADER_GBA("No tiene una cabecera de Game Boy Advance válida."),
    UNREADABLE("No se pudo leer el archivo."),
    REMOTE_UNAVAILABLE("El proveedor aún no tiene el archivo disponible. Reintenta con conexión."),
}

/** Dónde está un archivo dentro de la carpeta de la biblioteca: carpetas desde la raíz y nombre (N1). */
data class RomLocation(val folderPath: List<String>, val fileName: String)

/**
 * Consola de un juego de la biblioteca tal y como la ve el usuario (chip, filtro, detalle; = iOS `ConsoleBadge`): Game
 * Boy, Game Boy Color o Game Boy Advance. [core] es el núcleo que lo ejecuta (GB y GBC comparten `core/`).
 */
enum class RomConsole(val shortName: String, val displayName: String, val core: Console) {
    GB("GB", "Game Boy", Console.GB),
    GBC("GBC", "Game Boy Color", Console.GB),
    GBA("GBA", "Game Boy Advance", Console.GBA),
    ;

    companion object {
        /** GB o GBC según el bit 7 de `0x143` (lo que el escáner lee de la cabecera). */
        fun gameBoy(isColor: Boolean): RomConsole = if (isColor) GBC else GB

        /** Sin cabecera (no se pudo leer): por la extensión, como iOS (`.gba` GBA, `.gbc` GBC, el resto GB). */
        fun fromFileName(name: String): RomConsole = when (name.substringAfterLast('.', "").lowercase()) {
            "gba" -> GBA
            "gbc" -> GBC
            else -> GB
        }
    }
}

/** Un `.gb`/`.gbc`/`.gba` de la carpeta de la biblioteca. Valor inmutable creado por el escáner. */
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
    /** N8: GB, GBC o GBA (sustituye a `isColor`). GB/GBC por la cabecera; GBA por la extensión `.gba`. */
    val console: RomConsole,
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
    /**
     * N5: URI SAF de la imagen junto al ROM (`<nombre>.png|jpg|jpeg|webp`, o `portada.*`/`cover.*` si la carpeta tiene
     * un solo juego). El escáner solo la localiza: se lee (con tope y validación) al dibujar la portada.
     */
    val coverUri: String? = null,
    /** N5: sello de esa imagen (documento, tamaño, fecha): si cambia, se vuelve a leer. */
    val coverStamp: String? = null,
    /**
     * Copias en conflicto del proveedor junto al `.sav` del ROM (`X 2.sav`, `X (Joel's conflicted copy …).sav`…), con su
     * fecha, vistas al escanear (= iOS `ConflictCopies.scan`): Ajustes › Partidas las lista aunque el juego no se haya
     * abierto desde entonces. Solo nombres: nunca se leen ni se borran.
     */
    val conflictCopies: List<Pair<String, Long?>> = emptyList(),
) {
    /** Lo que ve el usuario en biblioteca, carril, favoritos, detalle y pausa: el alias o el título de la cabecera. */
    val displayTitle: String
        get() = alias ?: title

    /** N4: la categoría en la que se muestra: la virtual si la hay ([virtualFolderPath]) o la de su carpeta. */
    val categoryPath: List<String>
        get() = virtualFolderPath ?: folderPath

    /**
     * N4: se muestra en otra categoría que la de su carpeta (insignia «Movido en la app»). Una copia que ya está en la
     * carpeta elegida para su huella no lo está (H10).
     */
    val isMovedInApp: Boolean
        get() = virtualFolderPath != null && virtualFolderPath != folderPath

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
        get() = console.displayName

    /** Etiqueta corta para tarjetas estrechas. */
    val systemShort: String
        get() = console.shortName

    /** N8: núcleo que lo ejecuta ([Console.GB] para GB y GBC). */
    val core: Console
        get() = console.core

    val isGba: Boolean
        get() = console == RomConsole.GBA
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

    // ---- Game Boy Advance (N8; = iOS `RomHeader.parseGBA`) ----

    /** Bytes de la cabecera de GBA: título 0xA0, código 0xAC, fabricante 0xB0, byte fijo 0xB2 y checksum 0xBD. */
    const val GBA_MINIMUM_BYTES = 0xC0

    private const val GBA_IDENTITY_START = 0xA0
    private const val GBA_IDENTITY_END = 0xC0

    /**
     * Cabecera de GBA (solo para mostrar; la validación de verdad la hace el núcleo): `null` si no llega a 0xC0 bytes o
     * el byte fijo 0xB2 no es 0x96. El título son los 12 bytes de 0xA0 hasta el primer 0 (no imprimibles → `?`); el
     * checksum de cabecera es el complemento de 0xA0..0xBC menos 0x19.
     */
    fun parseGba(bytes: ByteArray): Info? {
        if (bytes.size < GBA_MINIMUM_BYTES) return null
        if (bytes[0xB2].toInt() and 0xFF != 0x96) return null
        var sum = 0
        for (i in 0xA0..0xBC) sum = (sum - (bytes[i].toInt() and 0xFF)) and 0xFF
        val checksumOk = ((sum - 0x19) and 0xFF) == (bytes[0xBD].toInt() and 0xFF)
        val raw = (0xA0 until 0xAC).map { bytes[it].toInt() and 0xFF }.takeWhile { it != 0 }
        val title = raw.joinToString("") { b -> if (b in 0x20 until 0x7F) b.toChar().toString() else "?" }.trim()
        return Info(title = title, isColor = false, checksumOk = checksumOk)
    }

    /**
     * Identidad de una cabecera de GBA (0xA0..0xBF: título, códigos, byte fijo, versión y checksum), con la misma
     * función que [identity] en el sello de N1 (64 caracteres hex; la de GB tiene 56, así que nunca se confunden).
     */
    fun gbaIdentity(bytes: ByteArray): String? {
        if (bytes.size < GBA_MINIMUM_BYTES) return null
        return (GBA_IDENTITY_START until GBA_IDENTITY_END).joinToString("") { "%02x".format(bytes[it].toInt() and 0xFF) }
    }

    /** Lo contrario de [gbaIdentity]: 0xC0 bytes con la identidad en su sitio, o `null`. */
    fun fromGbaIdentity(hex: String): ByteArray? {
        val length = GBA_IDENTITY_END - GBA_IDENTITY_START
        if (hex.length != length * 2) return null
        val bytes = ByteArray(GBA_MINIMUM_BYTES)
        for (i in 0 until length) {
            val value = hex.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
            bytes[GBA_IDENTITY_START + i] = value.toByte()
        }
        return bytes
    }

    /** La cabecera de una identidad de cualquiera de las dos consolas ([fromIdentity] o [fromGbaIdentity]). */
    fun fromAnyIdentity(hex: String): ByteArray? = fromIdentity(hex) ?: fromGbaIdentity(hex)
}

/**
 * Copias en conflicto del último escaneo agrupadas por huella (las copias del mismo ROM se juntan, sin repetir nombre).
 * Solo las huellas conocidas; una huella escaneada sin copias da lista vacía (ya no hay).
 */
fun scannedConflictsByFingerprint(
    entries: List<RomEntry>,
    fingerprints: Map<String, String>,
): Map<String, List<com.joelbermudez.pocketgb.saves.SaveStore.ProviderConflict>> =
    entries.mapNotNull { e -> fingerprints[e.id]?.let { it to e } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, list) ->
            list.flatMap { it.conflictCopies }.distinctBy { it.first }
                .map { (name, date) -> com.joelbermudez.pocketgb.saves.SaveStore.ProviderConflict(name, date) }
        }

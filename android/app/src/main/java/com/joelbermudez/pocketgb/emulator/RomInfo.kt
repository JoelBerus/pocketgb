package com.joelbermudez.pocketgb.emulator

/**
 * Cabecera y medio de guardado de un ROM. Los campos de Game Boy (`cgbFlag`, `cartType`, `cgbMode`…) valen 0/false en
 * un juego de GBA, y los de GBA (desde [console]) tienen valores por defecto en uno de GB. En GBA: [sramBytes] es el
 * medio sin el RTC (EEPROM sin ajuste: 512 hasta que el `.sav` o la primera DMA digan 8 KiB; el tamaño vigente lo da
 * `EmulatorSession.sramSaveSize`), [hasBattery] = hay medio o RTC, y [globalChecksumOk] es `true` porque el GBA no
 * tiene checksum global (solo el de cabecera, [headerChecksumOk]).
 */
data class RomInfo(
    val title: String,
    val cgbFlag: Int,
    val cartType: Int,
    val romBytes: Int,
    val sramBytes: Int,
    val hasBattery: Boolean,
    val hasRtc: Boolean,
    val headerChecksumOk: Boolean,
    val globalChecksumOk: Boolean,
    val cgbMode: Boolean,
    val cgbCompat: Boolean,
    val fingerprint: ByteArray,
    val console: Console = Console.GB,
    /** GBA: código de juego (0xAC, 4 caracteres) y de fabricante (0xB0, 2). */
    val gameCode: String = "",
    val makerCode: String = "",
    /** GBA: versión del software (0xBC). */
    val version: Int = 0,
    /** GBA: medio de guardado efectivo (nunca AUTO); null en GB. */
    val gbaSaveType: GbaSaveType? = null,
    /** GBA: medio EEPROM (el `.sav` puede ser de 512 B o de 8 KiB). */
    val eeprom: Boolean = false,
    /** GBA: el ajuste por juego fija el tamaño de la EEPROM (solo vale ese tamaño). */
    val eepromSizeFixed: Boolean = false,
    /** GBA: se cargó la BIOS del usuario (si no, HLE). */
    val biosLoaded: Boolean = false,
) {
    /**
     * GBA (= iOS `GBACoreBridge.validSaveSizes`): tamaños de `.sav` que acepta la sesión. El medio (EEPROM sin ajuste:
     * 512 B u 8 KiB) y, con RTC, también con sus 16 bytes al final; con un medio de 0 bytes y RTC, solo los 16 del reloj.
     */
    val gbaValidSaveSizes: Set<Int>
        get() {
            check(console == Console.GBA) { "Solo para juegos de GBA" }
            val media = when {
                eeprom && !eepromSizeFixed -> setOf(512, 8192)
                sramBytes > 0 -> setOf(sramBytes)
                else -> emptySet()
            }
            if (!hasRtc) return media
            return if (media.isEmpty()) setOf(GBA_RTC_BYTES) else media + media.map { it + GBA_RTC_BYTES }
        }

    val fingerprintHex: String
        get() = fingerprint.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }

    override fun equals(other: Any?): Boolean = other is RomInfo &&
        title == other.title &&
        cgbFlag == other.cgbFlag &&
        cartType == other.cartType &&
        romBytes == other.romBytes &&
        sramBytes == other.sramBytes &&
        hasBattery == other.hasBattery &&
        hasRtc == other.hasRtc &&
        headerChecksumOk == other.headerChecksumOk &&
        globalChecksumOk == other.globalChecksumOk &&
        cgbMode == other.cgbMode &&
        cgbCompat == other.cgbCompat &&
        fingerprint.contentEquals(other.fingerprint) &&
        console == other.console &&
        gameCode == other.gameCode &&
        makerCode == other.makerCode &&
        version == other.version &&
        gbaSaveType == other.gbaSaveType &&
        eeprom == other.eeprom &&
        eepromSizeFixed == other.eepromSizeFixed &&
        biosLoaded == other.biosLoaded

    override fun hashCode(): Int = 31 * title.hashCode() + fingerprint.contentHashCode()
}

enum class CoreModel(val nativeValue: Int) {
    AUTO(0),
    DMG(1),
    CGB(2),
}

data class CoreOptions(
    val model: CoreModel = CoreModel.AUTO,
    val sampleRate: Int = 0,
    val unixTime: Long = 0,
    val compatPalette: Int = 0,
)

/** Construye un [RomInfo] a partir de lo que devuelve el puente nativo (10 enteros, huella y título ASCII). */
internal fun romInfoFromNative(ints: IntArray, fingerprint: ByteArray, title: ByteArray): RomInfo {
    check(ints.size >= 10) { "Metadatos nativos incompletos" }
    val end = title.indexOf(0.toByte()).let { if (it < 0) title.size else it }
    return RomInfo(
        title = String(title, 0, end, Charsets.US_ASCII),
        cgbFlag = ints[0],
        cartType = ints[1],
        romBytes = ints[2],
        sramBytes = ints[3],
        hasBattery = ints[4] != 0,
        hasRtc = ints[5] != 0,
        headerChecksumOk = ints[6] != 0,
        globalChecksumOk = ints[7] != 0,
        cgbMode = ints[8] != 0,
        cgbCompat = ints[9] != 0,
        fingerprint = fingerprint.copyOf(32),
    )
}

/** Bytes del bloque RTC al final de un `.sav` de GBA (`GBA_RTC_BYTES`, formato de iOS). */
const val GBA_RTC_BYTES = 16

/** Enteros, título y códigos de cabecera que entrega el puente para un ROM de GBA (`write_gba_info` en JNI). */
internal const val GBA_INFO_INTS = 8
internal const val GBA_INFO_TITLE = 13
internal const val GBA_INFO_CODES = 8

/**
 * [RomInfo] de un ROM de GBA a partir del puente: `ints` = tamaño del ROM, tipo de medio, bytes del medio, RTC,
 * checksum de cabecera, BIOS real, versión y EEPROM de tamaño fijo; `title` 13 bytes; `codes` = juego (5) + fabricante (3).
 */
internal fun gbaRomInfoFromNative(ints: IntArray, fingerprint: ByteArray, title: ByteArray, codes: ByteArray): RomInfo {
    check(ints.size >= GBA_INFO_INTS && codes.size >= GBA_INFO_CODES) { "Metadatos nativos incompletos" }
    val saveType = GbaSaveType.fromNative(ints[1])
    val saveBytes = ints[2]
    val hasRtc = ints[3] != 0
    return RomInfo(
        title = asciiUntilNul(title, 0, title.size),
        cgbFlag = 0,
        cartType = 0,
        romBytes = ints[0],
        sramBytes = saveBytes,
        hasBattery = saveBytes > 0 || hasRtc,
        hasRtc = hasRtc,
        headerChecksumOk = ints[4] != 0,
        globalChecksumOk = true,
        cgbMode = false,
        cgbCompat = false,
        fingerprint = fingerprint.copyOf(32),
        console = Console.GBA,
        gameCode = asciiUntilNul(codes, 0, 5),
        makerCode = asciiUntilNul(codes, 5, 3),
        version = ints[6],
        gbaSaveType = saveType,
        eeprom = saveType.isEeprom,
        eepromSizeFixed = ints[7] != 0,
        biosLoaded = ints[5] != 0,
    )
}

private fun asciiUntilNul(bytes: ByteArray, offset: Int, length: Int): String {
    var end = offset
    while (end < offset + length && end < bytes.size && bytes[end] != 0.toByte()) end++
    return String(bytes, offset, end - offset, Charsets.US_ASCII)
}

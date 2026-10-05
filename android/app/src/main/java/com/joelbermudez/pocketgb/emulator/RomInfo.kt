package com.joelbermudez.pocketgb.emulator

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
) {
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
        fingerprint.contentEquals(other.fingerprint)

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

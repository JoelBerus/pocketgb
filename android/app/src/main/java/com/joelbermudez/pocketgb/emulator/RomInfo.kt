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

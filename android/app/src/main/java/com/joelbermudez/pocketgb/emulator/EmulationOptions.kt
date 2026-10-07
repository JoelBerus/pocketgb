package com.joelbermudez.pocketgb.emulator

/** Modelo de consola (= `gb_model` del núcleo). */
enum class GbModel(val native: Int) { AUTO(0), DMG(1), CGB(2) }

/** Escalado de la imagen en la superficie (= `native_scale_mode`). */
enum class ScaleMode(val native: Int) { INTEGER(0), FILL(1) }

/**
 * Opciones de emulación fijadas al abrir. [compatPalette] 0 = automática, 1..[CoreBridge.COMPAT_PALETTES].
 * El modelo nunca cambia con la sesión abierta (cambiaría el estado de la máquina y rompería los estados).
 */
data class EmulationOptions(val model: GbModel = GbModel.AUTO, val compatPalette: Int = 0) {
    init {
        require(compatPalette in 0..CoreBridge.COMPAT_PALETTES) { "Paleta fuera de rango: $compatPalette" }
    }
}

/**
 * Medio de guardado de un cartucho de GBA (= `gba_save_type`). AUTO lo detecta el núcleo por las cadenas de la
 * biblioteca de Nintendo; los demás lo fuerzan (ajuste por juego, como iOS `gbaSaveType`).
 */
enum class GbaSaveType(val native: Int) {
    AUTO(0), NONE(1), SRAM(2), FLASH64(3), FLASH128(4), EEPROM512(5), EEPROM8K(6);

    val isEeprom: Boolean get() = this == EEPROM512 || this == EEPROM8K

    companion object {
        fun fromNative(value: Int): GbaSaveType = entries.firstOrNull { it.native == value } ?: NONE
    }
}

/** RTC del cartucho de GBA (= `GBA_RTC_*`): AUTO por el código del juego, o forzado. */
enum class GbaRtc(val native: Int) { AUTO(0), ON(1), OFF(2) }

/**
 * Opciones de un juego de Game Boy Advance fijadas al abrir (= `gba_options`; iOS `gbaSaveType`, `gbaRTC`,
 * `gbaUseBIOS`). Con [useBios] la sesión usa la BIOS del usuario si es la oficial ([GbaBios]); si no, HLE.
 */
data class GbaOptions(
    val saveType: GbaSaveType = GbaSaveType.AUTO,
    val rtc: GbaRtc = GbaRtc.AUTO,
    val useBios: Boolean = true,
)

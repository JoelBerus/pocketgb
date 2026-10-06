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

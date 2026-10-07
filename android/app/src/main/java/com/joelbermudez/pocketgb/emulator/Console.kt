package com.joelbermudez.pocketgb.emulator

/** Tamaño del framebuffer de una consola (RGBA8888, R en el byte bajo). Como `ScreenSize` de iOS. */
data class ScreenSize(val width: Int, val height: Int) {
    val pixelCount: Int get() = width * height

    /** Ancho / alto: 10:9 en Game Boy, 3:2 en Game Boy Advance. */
    val aspectRatio: Float get() = width.toFloat() / height.toFloat()

    companion object {
        /** El tamaño de un frame por su número de píxeles (portadas y miniaturas de estados); null si no es de ninguna consola. */
        fun ofPixelCount(pixelCount: Int): ScreenSize? = Console.entries.firstOrNull { it.screen.pixelCount == pixelCount }?.screen
    }
}

/**
 * Consola de un ROM: decide qué núcleo ejecuta la sesión (= `native_console` de `native_session.h`; iOS `Console`).
 * [buttonMask] son los bits de la máscara común que entiende su núcleo; [minRomBytes]/[maxRomBytes] los límites que
 * se validan antes de cruzar a JNI (el puente y el núcleo los vuelven a validar).
 */
enum class Console(
    val native: Int,
    val screen: ScreenSize,
    val buttonMask: Int,
    val minRomBytes: Int,
    val maxRomBytes: Int,
    val shortName: String,
) {
    GB(0, ScreenSize(160, 144), 0xFF, 0x150, 8 * 1024 * 1024, "GB"),
    GBA(1, ScreenSize(240, 160), 0x3FF, 0xC0, 32 * 1024 * 1024, "GBA"),
    ;

    companion object {
        /** Por la extensión del archivo, como iOS: `.gba` es Game Boy Advance; el resto, Game Boy. */
        fun fromFileName(name: String): Console =
            if (name.substringAfterLast('.', missingDelimiterValue = "").equals("gba", ignoreCase = true)) GBA else GB

        fun fromNative(value: Int): Console = entries.firstOrNull { it.native == value } ?: throw CoreError.InvalidArgument()
    }
}

/**
 * Bits de la máscara de botones común a las dos consolas (= iOS `ConsoleCore.setButtons`, `gba/include/pocketgba.h`):
 * A, B, Select, Start, derecha, izquierda, arriba y abajo en los bits 0..7 (los de `GameBoyButton`) y, solo en GBA,
 * R en el bit 8 y L en el bit 9. La sesión recorta la máscara a [Console.buttonMask].
 */
object GbaButtonBits {
    const val R = 1 shl 8
    const val L = 1 shl 9
}

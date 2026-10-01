package com.joelbermudez.pocketgb.emulator

sealed class CoreError(message: String) : RuntimeException(message) {
    class Closed : CoreError("La instancia del núcleo ya está cerrada.")
    class NullArgument : CoreError("El núcleo recibió un argumento nulo.")
    class OutOfMemory : CoreError("No hay memoria suficiente para cargar el ROM.")
    class RomTooSmall : CoreError("El archivo es demasiado pequeño para ser un ROM de Game Boy.")
    class RomTooLarge : CoreError("El ROM supera el límite de 8 MiB.")
    class RomTruncated : CoreError("El archivo es menor que el tamaño declarado en su cabecera.")
    class BadRomSizeCode : CoreError("La cabecera declara un tamaño de ROM inválido.")
    class BadRamSizeCode : CoreError("La cabecera declara un tamaño de RAM inválido.")
    class UnsupportedMbc : CoreError("El controlador de memoria del cartucho no está soportado.")
    class CgbOnly : CoreError("El juego requiere Game Boy Color.")
    class NoRom : CoreError("No hay un ROM cargado.")
    class SramSize : CoreError("La partida tiene un tamaño incompatible.")
    class StateMagic : CoreError("El estado no pertenece a PocketGB.")
    class StateVersion : CoreError("La versión del estado no está soportada.")
    class StateRomMismatch : CoreError("El estado pertenece a otro ROM o modelo.")
    class StateCorrupt : CoreError("El estado está dañado.")
    class BufferTooSmall : CoreError("El búfer de destino es demasiado pequeño.")
    class Unknown(val code: Int) : CoreError("Error desconocido del núcleo: $code")

    companion object {
        fun fromResult(code: Int): CoreError? = when (code) {
            0 -> null
            1 -> NullArgument()
            2 -> OutOfMemory()
            3 -> RomTooSmall()
            4 -> RomTooLarge()
            5 -> RomTruncated()
            6 -> BadRomSizeCode()
            7 -> BadRamSizeCode()
            8 -> UnsupportedMbc()
            9 -> CgbOnly()
            10 -> NoRom()
            11 -> SramSize()
            12 -> StateMagic()
            13 -> StateVersion()
            14 -> StateRomMismatch()
            15 -> StateCorrupt()
            16 -> BufferTooSmall()
            else -> Unknown(code)
        }
    }
}

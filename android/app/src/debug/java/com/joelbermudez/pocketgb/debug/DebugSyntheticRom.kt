package com.joelbermudez.pocketgb.debug

internal object DebugSyntheticRom {
    fun create(): ByteArray {
        val rom = ByteArray(32 * 1024)
        rom[0x100] = 0xC3.toByte() // Salta la cabecera hacia el programa sintético en 0x0150.
        rom[0x101] = 0x50
        rom[0x102] = 0x01
        "A2 VIDEO".encodeToByteArray().copyInto(rom, destinationOffset = 0x134)
        rom[0x147] = 0x00 // ROM only
        rom[0x148] = 0x00 // 32 KiB
        rom[0x149] = 0x00 // sin RAM

        var headerChecksum = 0
        for (index in 0x134..0x14C) {
            headerChecksum = (headerChecksum - (rom[index].toInt() and 0xFF) - 1) and 0xFF
        }
        rom[0x14D] = headerChecksum.toByte()

        // Programa mínimo generado: apaga el LCD, llena VRAM con un patrón y vuelve a encenderlo.
        byteArrayOf(
            0xAF.toByte(),
            0xE0.toByte(), 0x40,
            0x21, 0x00, 0x80.toByte(),
            0x11, 0x00, 0x20,
            0x3E, 0xAA.toByte(),
            0x22,
            0x1B,
            0x7A,
            0xB3.toByte(),
            0xC2.toByte(), 0x59, 0x01,
            0x3E, 0xE4.toByte(),
            0xE0.toByte(), 0x47,
            0x3E, 0x91.toByte(),
            0xE0.toByte(), 0x40,
            0xC3.toByte(), 0x6A, 0x01,
        ).copyInto(rom, destinationOffset = 0x150)

        var globalChecksum = 0
        rom.indices.forEach { index ->
            if (index != 0x14E && index != 0x14F) {
                globalChecksum = (globalChecksum + (rom[index].toInt() and 0xFF)) and 0xFFFF
            }
        }
        rom[0x14E] = (globalChecksum ushr 8).toByte()
        rom[0x14F] = globalChecksum.toByte()
        return rom
    }
}

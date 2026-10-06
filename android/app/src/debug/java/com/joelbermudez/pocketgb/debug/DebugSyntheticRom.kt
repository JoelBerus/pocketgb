package com.joelbermudez.pocketgb.debug

internal object DebugSyntheticRom {
    /**
     * [light] rellena la VRAM con ceros (pantalla blanca: peor caso de contraste para los controles superpuestos);
     * [damagedHeader] deja mal el checksum de cabecera (`gameplay-header-damaged`).
     */
    fun create(light: Boolean = false, damagedHeader: Boolean = false): ByteArray {
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
        rom[0x14D] = (if (damagedHeader) headerChecksum xor 0xFF else headerChecksum).toByte()

        // Programa mínimo generado: apaga el LCD, llena VRAM con un patrón y vuelve a encenderlo.
        byteArrayOf(
            0xAF.toByte(),
            0xE0.toByte(), 0x40,
            0x21, 0x00, 0x80.toByte(),
            0x11, 0x00, 0x20,
            0x3E, if (light) 0x00 else 0xAA.toByte(),
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

    /**
     * MBC1 + 8 KiB de RAM + batería que incrementa `$A000` cada frame y cierra la RAM tras cada escritura
     * (el núcleo lo toma como "el juego guardó"). Sirve para ver el guardado de partidas sin ROMs comerciales.
     */
    fun sramCounter(): ByteArray = batteryProgram(
        "A5 CONTADOR",
        byteArrayOf(
            0x3E, 0x01, 0xE0.toByte(), 0xFF.toByte(), 0xFB.toByte(),
            0x76, 0x00,
            0x3E, 0x0A, 0xEA.toByte(), 0x00, 0x00,
            0xFA.toByte(), 0x00, 0xA0.toByte(),
            0x3C,
            0xEA.toByte(), 0x00, 0xA0.toByte(),
            0x3E, 0x00, 0xEA.toByte(), 0x00, 0x00,
            0x18, 0xEB.toByte(),
        ),
    )

    /**
     * Como [sramCounter] pero con un contador de 16 bits (little endian en `$A000`/`$A001`): da la vuelta cada 65 536
     * frames (~18 min), no cada 4 s, así que la prueba de cierre forzado puede exigir que el contador del disco no
     * retroceda respecto al último confirmado.
     */
    fun sramCounter16(): ByteArray = batteryProgram(
        "A5 CONTADOR16",
        byteArrayOf(
            0x3E, 0x01, 0xE0.toByte(), 0xFF.toByte(), 0xFB.toByte(), // IE = VBlank; EI
            0x76, 0x00,                                              // $0155: HALT; NOP
            0x3E, 0x0A, 0xEA.toByte(), 0x00, 0x00,                   // habilitar RAM
            0x21, 0x00, 0xA0.toByte(),                               // LD HL,$A000
            0x34,                                                    // INC (HL): byte bajo
            0x20, 0x02,                                              // JR NZ,+2 (sin acarreo)
            0x23, 0x34,                                              // INC HL; INC (HL): byte alto
            0x3E, 0x00, 0xEA.toByte(), 0x00, 0x00,                   // deshabilitar RAM: "el juego guardó"
            0x18, 0xEA.toByte(),                                     // JR $0155
        ),
    )

    /** MBC1 + RAM + batería que escribe [value] en `$A000`, cierra la RAM y se queda en bucle. */
    fun sramWriter(value: Int): ByteArray = batteryProgram(
        "A5 ESCRITOR",
        byteArrayOf(
            0x3E, 0x0A, 0xEA.toByte(), 0x00, 0x00,
            0x3E, value.toByte(), 0xEA.toByte(), 0x00, 0xA0.toByte(),
            0x3E, 0x00, 0xEA.toByte(), 0x00, 0x00,
            0x18, 0xFE.toByte(),
        ),
    )

    private fun batteryProgram(title: String, code: ByteArray): ByteArray {
        val rom = ByteArray(32 * 1024)
        rom[0x40] = 0xD9.toByte() // RETI en el vector de VBlank
        rom[0x100] = 0xC3.toByte()
        rom[0x101] = 0x50
        rom[0x102] = 0x01
        title.encodeToByteArray().copyInto(rom, destinationOffset = 0x134)
        rom[0x147] = 0x03 // MBC1 + RAM + batería
        rom[0x148] = 0x00 // 32 KiB
        rom[0x149] = 0x02 // 8 KiB de RAM
        code.copyInto(rom, destinationOffset = 0x150)
        var header = 0
        for (index in 0x134..0x14C) header = (header - (rom[index].toInt() and 0xFF) - 1) and 0xFF
        rom[0x14D] = header.toByte()
        var global = 0
        rom.indices.forEach { index -> if (index != 0x14E && index != 0x14F) global = (global + (rom[index].toInt() and 0xFF)) and 0xFFFF }
        rom[0x14E] = (global ushr 8).toByte()
        rom[0x14F] = global.toByte()
        return rom
    }
}

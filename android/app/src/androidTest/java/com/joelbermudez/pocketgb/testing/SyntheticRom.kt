package com.joelbermudez.pocketgb.testing

object SyntheticRom {
    fun romOnly(title: String = "A2 TEST", color: Boolean = false): ByteArray {
        require(title.length <= if (color) 15 else 16)
        val rom = ByteArray(32 * 1024)
        rom[0x100] = 0xC3.toByte() // JP 0x0100
        rom[0x101] = 0x00
        rom[0x102] = 0x01
        title.encodeToByteArray().copyInto(rom, destinationOffset = 0x134)
        if (color) rom[0x143] = 0x80.toByte() // compatible con CGB
        rom[0x147] = 0x00 // ROM only
        rom[0x148] = 0x00 // 32 KiB
        rom[0x149] = 0x00 // sin RAM

        var header = 0
        for (index in 0x134..0x14C) {
            header = (header - (rom[index].toInt() and 0xFF) - 1) and 0xFF
        }
        rom[0x14D] = header.toByte()

        var global = 0
        rom.indices.forEach { index ->
            if (index != 0x14E && index != 0x14F) {
                global = (global + (rom[index].toInt() and 0xFF)) and 0xFFFF
            }
        }
        rom[0x14E] = (global ushr 8).toByte()
        rom[0x14F] = global.toByte()
        return rom
    }

    /** Tipo de cartucho 0x03: MBC1 + RAM + batería. */
    const val MBC1_RAM_BATTERY = 0x03

    /** 0x10: MBC3 + RTC + RAM + batería. */
    const val MBC3_RTC_RAM_BATTERY = 0x10

    /** 0x0F: MBC3 + RTC + batería, sin RAM (el `.sav` es solo el bloque de 48 bytes del reloj). */
    const val MBC3_RTC_BATTERY = 0x0F

    /**
     * MBC1 + RAM de 8 KiB + batería: incrementa `$A000` una vez por frame (espera VBlank con HALT) y
     * deshabilita la RAM tras cada escritura, que es lo que marca "el juego guardó" (`gb_sram_dirty`).
     * La SRAM cambia sin parar mientras corre; dos instantáneas en momentos distintos nunca coinciden.
     * Portado de `StateSRAMTests.rom(counting:)` de iOS, ajustado al flag de guardado del núcleo.
     */
    fun sramCounter(title: String = "CONTADOR"): ByteArray = program(
        title,
        MBC1_RAM_BATTERY,
        ramCode = 0x02,
        code = byteArrayOf(
            0x3E, 0x01, 0xE0.toByte(), 0xFF.toByte(), // IE = VBlank
            0xFB.toByte(),                            // EI
            0x76, 0x00,                               // $0155: HALT; NOP
            0x3E, 0x0A, 0xEA.toByte(), 0x00, 0x00,    // habilitar RAM
            0xFA.toByte(), 0x00, 0xA0.toByte(),       // LD A,($A000)
            0x3C,                                     // INC A
            0xEA.toByte(), 0x00, 0xA0.toByte(),       // LD ($A000),A
            0x3E, 0x00, 0xEA.toByte(), 0x00, 0x00,    // deshabilitar RAM: la partida queda "guardada"
            0x18, 0xEB.toByte(),                      // JR $0155
        ),
    )

    /** MBC1 + RAM de 8 KiB + batería: escribe [value] en `$A000`, cierra la RAM (guardado) y se queda en bucle. */
    fun sramWriter(value: Int, title: String = "ESCRITOR"): ByteArray = program(
        title,
        MBC1_RAM_BATTERY,
        ramCode = 0x02,
        code = byteArrayOf(
            0x3E, 0x0A, 0xEA.toByte(), 0x00, 0x00,    // habilitar RAM
            0x3E, value.toByte(), 0xEA.toByte(), 0x00, 0xA0.toByte(), // ($A000) = value
            0x3E, 0x00, 0xEA.toByte(), 0x00, 0x00,    // deshabilitar RAM
            0x18, 0xFE.toByte(),                      // JR -2
        ),
    )

    /**
     * MBC3 con reloj. Con [ram] hay 8 KiB de SRAM (`.sav` = 8192 + 48); sin ella (cartucho 0x0F) la
     * partida es solo el bloque del RTC (48 bytes). El programa se queda en bucle: lo que se prueba
     * es el puente (tamaños, bloque RTC, avance de la hora al reanudar), no el juego.
     */
    fun mbc3Rtc(ram: Boolean = true, title: String = "RELOJ"): ByteArray = program(
        title,
        if (ram) MBC3_RTC_RAM_BATTERY else MBC3_RTC_BATTERY,
        ramCode = if (ram) 0x02 else 0x00,
        code = byteArrayOf(0x18, 0xFE.toByte()),
    )

    /** ROM de 32 KiB con [code] en `$0150`, un `RETI` en el vector de VBlank y la cabecera/checksums correctos. */
    private fun program(title: String, cartType: Int, ramCode: Int, code: ByteArray): ByteArray {
        require(title.length <= 16)
        val rom = ByteArray(32 * 1024)
        rom[0x40] = 0xD9.toByte() // RETI
        rom[0x100] = 0xC3.toByte() // JP $0150
        rom[0x101] = 0x50
        rom[0x102] = 0x01
        title.encodeToByteArray().copyInto(rom, destinationOffset = 0x134)
        rom[0x147] = cartType.toByte()
        rom[0x148] = 0x00 // 32 KiB
        rom[0x149] = ramCode.toByte()
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

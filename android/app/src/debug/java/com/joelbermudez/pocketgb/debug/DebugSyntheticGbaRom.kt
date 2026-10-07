package com.joelbermudez.pocketgb.debug

/**
 * Solo Debug (N8): ROMs sintéticas de Game Boy Advance para el catálogo y la prueba de cierre forzado. Código ARM mínimo
 * propio (no hay ROM comercial ni BIOS, regla dura 1): sin BIOS el núcleo arranca en 0x08000000 en modo ARM. La cadena
 * `SRAM_V113` es la que el núcleo busca para detectar la SRAM de 32 KiB (docs/10-gba-spec.md §Medio de guardado).
 */
internal object DebugSyntheticGbaRom {
    private const val SIZE = 0x200

    /**
     * SRAM de 32 KiB con un contador de 16 bits (little endian en `0x0E000000`/`0x0E000001`) que sube una vez por
     * fotograma (espera a VBlank leyendo VCOUNT): da la vuelta cada 65 536 fotogramas (~18 min), así que la prueba de
     * cierre forzado puede exigir que el contador del disco nunca retroceda respecto al último confirmado.
     */
    fun sramCounter16(): ByteArray = program(
        "PGBA CONT16", "PGBK", "SRAM_V113",
        intArrayOf(
            0xE3A0040E.toInt(), // C0: mov r0, #0x0E000000 (SRAM)
            0xE3A02301.toInt(), // C4: mov r2, #0x04000000 (E/S)
            0xE1D230B6.toInt(), // C8: ldrh r3, [r2, #6]   (VCOUNT)
            0xE35300A0.toInt(), // CC: cmp r3, #160
            0xAAFFFFFC.toInt(), // D0: bge C8               (espera a salir de VBlank)
            0xE1D230B6.toInt(), // D4: ldrh r3, [r2, #6]
            0xE35300A0.toInt(), // D8: cmp r3, #160
            0xBAFFFFFC.toInt(), // DC: blt D4               (espera a entrar en VBlank)
            0xE5D01000.toInt(), // E0: ldrb r1, [r0]
            0xE5D03001.toInt(), // E4: ldrb r3, [r0, #1]
            0xE1811403.toInt(), // E8: orr r1, r1, r3, lsl #8
            0xE2811001.toInt(), // EC: add r1, r1, #1
            0xE5C01000.toInt(), // F0: strb r1, [r0]
            0xE1A03421.toInt(), // F4: mov r3, r1, lsr #8
            0xE5C03001.toInt(), // F8: strb r3, [r0, #1]
            0xEAFFFFF1.toInt(), // FC: b C8
        ),
    )

    /**
     * Pantalla de prueba para las capturas: modo 3 (mapa de bits 240×160) con un degradado (rojo en horizontal, verde en
     * vertical, azul fijo), escrito por dos bucles; luego se queda en bucle. Sin medio de guardado.
     */
    fun gradient(): ByteArray = program(
        "PGBA COLOR", "PGBG", null,
        intArrayOf(
            0xE3A00301.toInt(), // C0: mov r0, #0x04000000 (DISPCNT)
            0xE3A01C04.toInt(), // C4: mov r1, #0x400
            0xE2811003.toInt(), // C8: add r1, r1, #3       (modo 3 + BG2)
            0xE1C010B0.toInt(), // CC: strh r1, [r0]
            0xE3A00406.toInt(), // D0: mov r0, #0x06000000  (VRAM)
            0xE3A04000.toInt(), // D4: mov r4, #0           (y)
            0xE3A05000.toInt(), // D8: mov r5, #0           (x)
            0xE1A011A5.toInt(), // DC: mov r1, r5, lsr #3   (rojo = x / 8)
            0xE1A021A4.toInt(), // E0: mov r2, r4, lsr #3
            0xE1811282.toInt(), // E4: orr r1, r1, r2, lsl #5 (verde = y / 8)
            0xE3811901.toInt(), // E8: orr r1, r1, #0x4000  (azul = 16)
            0xE0C010B2.toInt(), // EC: strh r1, [r0], #2
            0xE2855001.toInt(), // F0: add r5, r5, #1
            0xE35500F0.toInt(), // F4: cmp r5, #240
            0xBAFFFFF7.toInt(), // F8: blt DC
            0xE2844001.toInt(), // FC: add r4, r4, #1
            0xE35400A0.toInt(), // 100: cmp r4, #160
            0xBAFFFFF3.toInt(), // 104: blt D8
            0xEAFFFFFE.toInt(), // 108: b .
        ),
    )

    private fun program(title: String, gameCode: String, saveId: String?, code: IntArray): ByteArray {
        // Con cadena de medio (en 0x100) el código cabe en 0xC0..0xFF; sin ella, hasta el final de la ROM.
        require(title.length <= 12 && gameCode.length == 4 && code.size <= if (saveId != null) 16 else (SIZE - 0xC0) / 4)
        val rom = ByteArray(SIZE)
        putWord(rom, 0x00, 0xEA00002E.toInt()) // b 0x080000C0 (por encima de la cabecera)
        title.encodeToByteArray().copyInto(rom, destinationOffset = 0xA0)
        gameCode.encodeToByteArray().copyInto(rom, destinationOffset = 0xAC)
        "01".encodeToByteArray().copyInto(rom, destinationOffset = 0xB0)
        rom[0xB2] = 0x96.toByte()
        var checksum = 0
        for (i in 0xA0..0xBC) checksum -= rom[i].toInt() and 0xFF
        rom[0xBD] = ((checksum - 0x19) and 0xFF).toByte()
        code.forEachIndexed { i, word -> putWord(rom, 0xC0 + 4 * i, word) }
        saveId?.encodeToByteArray()?.copyInto(rom, destinationOffset = 0x100)
        return rom
    }

    private fun putWord(rom: ByteArray, offset: Int, word: Int) {
        for (i in 0 until 4) rom[offset + i] = (word ushr (8 * i)).toByte()
    }
}

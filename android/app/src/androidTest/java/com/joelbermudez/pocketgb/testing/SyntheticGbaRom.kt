package com.joelbermudez.pocketgb.testing

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import java.io.FileNotFoundException

/**
 * ROMs de GBA para los instrumentados (N8). Las sintéticas se generan aquí (código ARM mínimo, cabecera válida): sin
 * BIOS, el núcleo arranca en 0x08000000 en modo ARM. Las cadenas `SRAM_V`/`EEPROM_V` son las que el núcleo busca
 * para detectar el medio de guardado (docs/10-gba-spec.md §Medio de guardado).
 */
object SyntheticGbaRom {
    private const val SIZE = 0x200

    /** Bucle infinito (`b .`); [saveId] = cadena de detección del medio (null = sin medio). */
    fun idle(title: String = "PGBA IDLE", gameCode: String = "PGBA", saveId: String? = null): ByteArray =
        program(title, gameCode, saveId, intArrayOf(0xEAFFFFFE.toInt()))

    /**
     * SRAM de 32 KiB: incrementa `0x0E000000` sin parar (`ldrb`/`add`/`strb`), así la partida cambia en cada frame
     * y el núcleo la marca como guardada (`gba_save_dirty`).
     */
    fun sramCounter(title: String = "PGBA CONTADOR"): ByteArray = program(
        title.take(12), "PGBC", "SRAM_V113",
        intArrayOf(
            0xE3A0040E.toInt(), // mov r0, #0x0E000000
            0xE5D01000.toInt(), // ldrb r1, [r0]
            0xE2811001.toInt(), // add r1, r1, #1
            0xE5C01000.toInt(), // strb r1, [r0]
            0xEAFFFFFB.toInt(), // b <ldrb>
        ),
    )

    /** SRAM de 32 KiB: escribe [value] en `0x0E000000` una vez y se queda en bucle. */
    fun sramWriter(value: Int, title: String = "PGBA ESCRIBE"): ByteArray = program(
        title.take(12), "PGBW", "SRAM_V113",
        intArrayOf(
            0xE3A0040E.toInt(),               // mov r0, #0x0E000000
            0xE3A01000.toInt() or (value and 0xFF), // mov r1, #value
            0xE5C01000.toInt(),               // strb r1, [r0]
            0xEAFFFFFE.toInt(),               // b .
        ),
    )

    /**
     * N8: EEPROM sin ajuste que se confirma de 8 KiB **solo leyendo**: una petición de lectura por DMA3 de 17 medias
     * palabras (2 de comando + 14 de dirección + 1) y bucle. El núcleo pasa a 8 KiB sin que la partida cambie (nota de la
     * auditoría N8 nativo): la app debe escribir igualmente el `.sav` de 8 KiB.
     */
    fun eepromReadOnly8k(title: String = "PGBA EE8K R"): ByteArray = program(
        title.take(12), "PGBE", "EEPROM_V124",
        intArrayOf(
            0xE3A00403.toInt(), // mov r0, #0x03000000 (IWRAM: búfer de bits)
            0xE3A01001.toInt(), // mov r1, #1
            0xE1C010B0.toInt(), // strh r1, [r0]       (bit 1 del comando de lectura «11»)
            0xE1C010B2.toInt(), // strh r1, [r0, #2]   (bit 2; dirección 0 y bit final 0)
            0xE3A02301.toInt(), // mov r2, #0x04000000
            0xE28220D4.toInt(), // add r2, r2, #0xD4   (DMA3SAD)
            0xE5820000.toInt(), // str r0, [r2]
            0xE3A0340D.toInt(), // mov r3, #0x0D000000 (EEPROM)
            0xE5823004.toInt(), // str r3, [r2, #4]    (DMA3DAD)
            0xE3A03102.toInt(), // mov r3, #0x80000000 (activa, inmediata, 16 bits)
            0xE3833011.toInt(), // orr r3, r3, #17     (17 medias palabras)
            0xE5823008.toInt(), // str r3, [r2, #8]    (DMA3CNT)
            0xEAFFFFFE.toInt(), // b .
        ),
    )

    /** Pantalla en blanco: `DISPCNT = 0x0080` (forced blank, que el núcleo dibuja blanco) y bucle. */
    fun forcedBlank(title: String = "PGBA BLANCO"): ByteArray = program(
        title.take(12), "PGBB", null,
        intArrayOf(
            0xE3A00301.toInt(), // mov r0, #0x04000000 (DISPCNT)
            0xE3A01080.toInt(), // mov r1, #0x80
            0xE1C010B0.toInt(), // strh r1, [r0]
            0xEAFFFFFE.toInt(), // b .
        ),
    )

    /** Copia de [rom] con el byte fijo 0xB2 distinto de 0x96 (cabecera inválida para el núcleo). */
    fun withBadFixedByte(rom: ByteArray): ByteArray = rom.copyOf().also { it[0xB2] = 0x00 }

    private fun program(title: String, gameCode: String, saveId: String?, code: IntArray): ByteArray {
        require(title.length <= 12 && gameCode.length == 4)
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

/**
 * ROMs libres de GBA que Gradle copia a los assets de prueba si están en disco (`copyGbaTestRoms`): jsmolka/gba-tests
 * (MIT) y las homebrew propias (MIT). Si faltan, el test se salta con el comando que las trae.
 */
object GbaTestRoms {
    fun load(name: String): ByteArray {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val bytes = try {
            assets.open("gba/$name").use { it.readBytes() }
        } catch (_: FileNotFoundException) {
            null
        }
        assumeTrue(
            "Falta gba/$name en los assets de prueba: tools/fetch-gba-test-roms.sh --solo-jsmolka " +
                "(y make -C gba homebrew para las propias)",
            bytes != null,
        )
        return bytes!!
    }
}

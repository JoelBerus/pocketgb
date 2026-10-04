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
}

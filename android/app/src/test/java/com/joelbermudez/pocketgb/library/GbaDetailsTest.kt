package com.joelbermudez.pocketgb.library

import com.joelbermudez.pocketgb.emulator.Console
import com.joelbermudez.pocketgb.emulator.GbaBios
import com.joelbermudez.pocketgb.emulator.GbaBiosStatus
import com.joelbermudez.pocketgb.emulator.GbaSaveType
import com.joelbermudez.pocketgb.emulator.RomInfo
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** N8: datos de GBA en el detalle (= iOS `GameTechnicalInfo`) y lectura de `gba_bios.bin` de la raíz de la carpeta. */
class GbaDetailsTest {
    private val entry = RomEntry("Kirby.gba", "content://k", "Kirby.gba", "KIRBY", RomConsole.GBA, 16L shl 20, true, null)

    private fun info(type: GbaSaveType, bytes: Int, rtc: Boolean, code: String = "A7KE", maker: String = "01") = RomInfo(
        title = "KIRBY", cgbFlag = 0, cartType = 0, romBytes = 16 shl 20, sramBytes = bytes, hasBattery = bytes > 0 || rtc,
        hasRtc = rtc, headerChecksumOk = true, globalChecksumOk = true, cgbMode = false, cgbCompat = false,
        fingerprint = ByteArray(32) { 7 }, console = Console.GBA, gameCode = code, makerCode = maker, version = 1,
        gbaSaveType = type, eeprom = type.isEeprom,
    )

    @Test
    fun detailsShowTheDetectedMediumTheGameCodeAndTheClock() {
        val flash = GameDetails.from(entry, info(GbaSaveType.FLASH128, 131072, rtc = true))
        assertEquals("Flash 128 KiB", flash.cartridge)
        assertEquals("A7KE · 01 · v1", flash.gba!!.codeLine)
        assertEquals("128 KiB · reloj", flash.saveDescription)
        val eeprom = GameDetails.from(entry, info(GbaSaveType.EEPROM512, 512, rtc = false))
        assertEquals("EEPROM", eeprom.cartridge)
        assertEquals("EEPROM (512 B u 8 KiB)", eeprom.saveDescription)
        val none = GameDetails.from(entry, info(GbaSaveType.NONE, 0, rtc = false, code = "", maker = ""))
        assertEquals("Sin partida", none.saveDescription)
        assertNull(none.gba!!.codeLine)
        assertEquals(listOf("Sin partida", "SRAM 32 KiB", "Flash 64 KiB", "Flash 128 KiB", "EEPROM", "EEPROM", "Desconocido"),
            listOf(GbaSaveType.NONE, GbaSaveType.SRAM, GbaSaveType.FLASH64, GbaSaveType.FLASH128, GbaSaveType.EEPROM512, GbaSaveType.EEPROM8K, GbaSaveType.AUTO).map(GbaSaveNames::describe))
    }

    @Test
    fun gameBoyDetailsHaveNoGbaPart() {
        val gb = RomInfo("RED", 0, 0x13, 1 shl 20, 32768, true, false, true, true, false, false, ByteArray(32))
        val details = GameDetails.from(entry.copy(console = RomConsole.GB), gb)
        assertNull(details.gba)
        assertEquals("MBC3 + RAM + batería", details.cartridge)
        assertEquals("32 KiB · batería", details.saveDescription)
    }

    private class Tree(val nodes: List<TreeNode>, val bytes: Map<String, ByteArray>, val fail: Boolean = false) : DocumentTree {
        var reads = mutableListOf<Int>()
        override fun children(directoryId: String?): List<TreeNode> {
            if (fail) throw IOException("sin permiso")
            return if (directoryId == null) nodes else emptyList()
        }
        override fun readHead(node: TreeNode, limit: Int): ByteArray {
            reads += limit
            val data = bytes.getValue(node.id)
            return data.copyOf(minOf(limit, data.size))
        }
        override fun uriOf(node: TreeNode) = "content://t/${node.id}"
    }

    @Test
    fun theBiosIsOnlyLookedForInTheRootAndReadAtMost16KiBPlusOne() {
        val fake = ByteArray(GbaBios.SIZE_BYTES) { it.toByte() }
        val tree = Tree(
            listOf(TreeNode("d", "gba_bios.bin", isDirectory = true, sizeBytes = 0), TreeNode("f", "GBA_BIOS.BIN", false, 16384)),
            mapOf("f" to fake + ByteArray(100)),
        )
        val read = GbaBiosSource.read(tree)!!
        assertEquals(listOf(GbaBios.SIZE_BYTES + 1), tree.reads)
        assertEquals(GbaBios.SIZE_BYTES + 1, read.size)
        assertEquals("16 KiB + 1 nunca es la oficial", GbaBiosStatus.INVALID, GbaBios.status(read))
        assertEquals(GbaBiosStatus.INVALID, GbaBios.status(fake))
        assertNull(GbaBiosSource.read(Tree(listOf(TreeNode("x", "otra.bin", false, 16384)), emptyMap())))
        assertNull("un fallo del proveedor = sin BIOS (HLE)", GbaBiosSource.readOrNull(Tree(emptyList(), emptyMap(), fail = true)))
        assertNull("un documento virtual (sin descargar) no se lee", GbaBiosSource.read(Tree(listOf(TreeNode("v", "gba_bios.bin", false, 16384, isVirtual = true)), emptyMap())))
        assertEquals(GbaBiosStatus.ABSENT, GbaBios.status(null))
    }
}

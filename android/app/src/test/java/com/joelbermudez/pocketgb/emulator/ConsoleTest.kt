package com.joelbermudez.pocketgb.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * N8 nativo: tipos de consola, máscara de botones, opciones y BIOS de GBA. Las constantes se comparan con sus
 * fuentes en C (`gba/include/pocketgba.h`, `native_session.h`) y en iOS (`GBACoreBridge.swift`), para que las tres
 * copias no se separen.
 */
class ConsoleTest {
    private val gbaHeader = File("../../gba/include/pocketgba.h").readText()
    private val sessionHeader = File("src/main/cpp/native_session.h").readText()
    private val iosBridge = File("../../ios/PocketGB/Emulator/GBACoreBridge.swift").readText()

    private fun define(source: String, name: String): Long {
        val match = Regex("""#define\s+$name\s+\(?(0x[0-9A-Fa-f]+|\d+)u?(?:\s*\*\s*(\d+)u?\s*\*\s*(\d+)u?\))?""").find(source)
            ?: error("no está $name")
        val (first, second, third) = match.destructured
        val base = if (first.startsWith("0x")) first.drop(2).toLong(16) else first.toLong()
        return if (second.isEmpty()) base else base * second.toLong() * third.toLong()
    }

    @Test
    fun screensAndLimitsMatchTheCores() {
        assertEquals(ScreenSize(160, 144), Console.GB.screen)
        assertEquals(ScreenSize(240, 160), Console.GBA.screen)
        assertEquals(define(gbaHeader, "GBA_SCREEN_W").toInt(), Console.GBA.screen.width)
        assertEquals(define(gbaHeader, "GBA_SCREEN_H").toInt(), Console.GBA.screen.height)
        assertEquals(define(gbaHeader, "GBA_ROM_MAX_BYTES").toInt(), Console.GBA.maxRomBytes)
        assertEquals(define(gbaHeader, "GBA_ROM_MIN_BYTES").toInt(), Console.GBA.minRomBytes)
        assertEquals(define(gbaHeader, "GBA_RTC_BYTES").toInt(), GBA_RTC_BYTES)
        assertEquals(define(gbaHeader, "GBA_BIOS_BYTES").toInt(), GbaBios.SIZE_BYTES)
        assertEquals(CoreBridge.MAX_ROM_BYTES, Console.GB.maxRomBytes)
        assertEquals(CoreBridge.MIN_ROM_BYTES, Console.GB.minRomBytes)
        // Los llamadores que aún no distinguen consola usan las constantes de GB.
        assertEquals(Console.GB.screen.pixelCount, CoreBridge.FRAME_PIXELS)
        assertEquals(1.5f, Console.GBA.screen.aspectRatio, 0f)
        assertEquals(Console.GBA.screen, ScreenSize.ofPixelCount(240 * 160))
        assertEquals(Console.GB.screen, ScreenSize.ofPixelCount(160 * 144))
        assertNull(ScreenSize.ofPixelCount(100))
    }

    @Test
    fun nativeValuesMatchTheSessionEnum() {
        assertTrue(Regex("""NATIVE_CONSOLE_GB\s*=\s*0""").containsMatchIn(sessionHeader))
        assertTrue(Regex("""NATIVE_CONSOLE_GBA\s*=\s*1""").containsMatchIn(sessionHeader))
        assertEquals(listOf(0, 1), Console.entries.map { it.native })
        assertEquals(Console.GBA, Console.fromNative(1))
        assertThrows(CoreError.InvalidArgument::class.java) { Console.fromNative(2) }
        // gba_save_type y GBA_RTC_* en el orden de pocketgba.h.
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), GbaSaveType.entries.map { it.native })
        assertEquals(
            listOf("AUTO", "NONE", "SRAM", "FLASH64", "FLASH128", "EEPROM512", "EEPROM8K"),
            Regex("""GBA_SAVE_(\w+)""").findAll(gbaHeader.substringAfter("typedef enum gba_save_type")
                .substringBefore("} gba_save_type")).map { it.groupValues[1] }.toList(),
        )
        assertEquals(listOf(0, 1, 2), GbaRtc.entries.map { it.native })
    }

    @Test
    fun extensionDecidesTheConsoleLikeIos() {
        assertEquals(Console.GBA, Console.fromFileName("Kirby.gba"))
        assertEquals(Console.GBA, Console.fromFileName("JUEGO.GBA"))
        assertEquals(Console.GB, Console.fromFileName("Tetris.gb"))
        assertEquals(Console.GB, Console.fromFileName("Zelda.gbc"))
        assertEquals(Console.GB, Console.fromFileName("sin-extension"))
        assertEquals(Console.GB, Console.fromFileName("gba"))
    }

    @Test
    fun shouldersUseTheBitsOfIosAndTheCore() {
        assertEquals(1L shl 8, Regex("""GBA_BTN_R\s*=\s*1u\s*<<\s*(\d+)""").find(gbaHeader)!!.groupValues[1].let { 1L shl it.toInt() })
        assertEquals(1L shl 9, Regex("""GBA_BTN_L\s*=\s*1u\s*<<\s*(\d+)""").find(gbaHeader)!!.groupValues[1].let { 1L shl it.toInt() })
        assertEquals(0x100, GbaButtonBits.R)
        assertEquals(0x200, GbaButtonBits.L)
        assertEquals(0x3FF, Console.GBA.buttonMask)
        assertEquals(0, (GbaButtonBits.L or GbaButtonBits.R) and Console.GB.buttonMask)
    }

    @Test
    fun withoutOppositesOfN2KeepsTheGbaShoulders() {
        val up = 1 shl 6
        val down = 1 shl 7
        val left = 1 shl 5
        val right = 1 shl 4
        val shoulders = GbaButtonBits.L or GbaButtonBits.R
        assertEquals(shoulders, com.joelbermudez.pocketgb.input.withoutOpposites(shoulders or up or down))
        assertEquals(shoulders or up, com.joelbermudez.pocketgb.input.withoutOpposites(shoulders or up or left or right))
        assertEquals(0x30F, com.joelbermudez.pocketgb.input.withoutOpposites(Console.GBA.buttonMask))
    }

    @Test
    fun biosShaIsTheSameInKotlinCAndIos() {
        assertEquals(64, GbaBios.SHA256.length)
        assertTrue(sessionHeader.contains("#define NATIVE_GBA_BIOS_SHA256 \"${GbaBios.SHA256}\""))
        assertTrue(iosBridge.contains("knownBIOSSHA256 = \"${GbaBios.SHA256}\""))
        assertEquals("gba_bios.bin", GbaBios.FILE_NAME)
    }

    @Test
    fun biosStatusRejectsAnythingButTheOfficialDump() {
        assertEquals(GbaBiosStatus.ABSENT, GbaBios.status(null))
        assertEquals(GbaBiosStatus.INVALID, GbaBios.status(ByteArray(0)))
        assertEquals(GbaBiosStatus.INVALID, GbaBios.status(ByteArray(16 * 1024 - 1)))
        assertEquals(GbaBiosStatus.INVALID, GbaBios.status(ByteArray(16 * 1024)))
        assertEquals(GbaBiosStatus.INVALID, GbaBios.status(ByteArray(16 * 1024 + 1)))
        assertFalse(GbaBios.isOfficial(ByteArray(16 * 1024) { 0xFF.toByte() }))
        // El resumen se calcula bien (vector: 16 KiB a cero, calculado con hashlib).
        assertEquals("4fe7b59af6de3b665b67788cc2f99892ab827efae3a467342b3bb4e3bc8e5bfe", GbaBios.sha256Hex(ByteArray(16 * 1024)))
    }

    @Test
    fun gbaInfoFromTheBridge() {
        val title = "POCKETGBA".encodeToByteArray().copyOf(13)
        val codes = "ABCE".encodeToByteArray().copyOf(5) + "01".encodeToByteArray().copyOf(3)
        val info = gbaRomInfoFromNative(intArrayOf(4096, 5, 512, 1, 1, 0, 2, 0), ByteArray(32) { it.toByte() }, title, codes)
        assertEquals(Console.GBA, info.console)
        assertEquals("POCKETGBA", info.title)
        assertEquals("ABCE", info.gameCode)
        assertEquals("01", info.makerCode)
        assertEquals(4096, info.romBytes)
        assertEquals(GbaSaveType.EEPROM512, info.gbaSaveType)
        assertTrue(info.eeprom)
        assertFalse(info.eepromSizeFixed)
        assertTrue(info.hasRtc)
        assertTrue(info.hasBattery)
        assertTrue(info.globalChecksumOk)
        assertFalse(info.biosLoaded)
        assertEquals(2, info.version)
        assertEquals(setOf(512, 8192, 528, 8208), info.gbaValidSaveSizes)
    }

    @Test
    fun gbaValidSaveSizesFollowIos() {
        fun info(type: GbaSaveType, bytes: Int, rtc: Boolean, fixed: Boolean = false) = gbaRomInfoFromNative(
            intArrayOf(4096, type.native, bytes, if (rtc) 1 else 0, 1, 0, 0, if (fixed) 1 else 0),
            ByteArray(32), ByteArray(13), ByteArray(8),
        )
        assertEquals(setOf(32 * 1024), info(GbaSaveType.SRAM, 32 * 1024, rtc = false).gbaValidSaveSizes)
        assertEquals(setOf(128 * 1024, 128 * 1024 + 16), info(GbaSaveType.FLASH128, 128 * 1024, rtc = true).gbaValidSaveSizes)
        assertEquals(setOf(16), info(GbaSaveType.NONE, 0, rtc = true).gbaValidSaveSizes)
        assertEquals(emptySet<Int>(), info(GbaSaveType.NONE, 0, rtc = false).gbaValidSaveSizes)
        assertFalse(info(GbaSaveType.NONE, 0, rtc = false).hasBattery)
        assertEquals(setOf(8192), info(GbaSaveType.EEPROM8K, 8192, rtc = false, fixed = true).gbaValidSaveSizes)
        assertEquals(setOf(512, 8192), info(GbaSaveType.EEPROM512, 512, rtc = false).gbaValidSaveSizes)
        assertThrows(IllegalStateException::class.java) {
            RomInfo("GB", 0, 0, 0, 0, false, false, true, true, false, false, ByteArray(32)).gbaValidSaveSizes
        }
    }

    @Test
    fun resultCodesOfTheCommonSpace() {
        assertTrue(CoreError.fromResult(18) is CoreError.BadGbaHeader)
        assertTrue(CoreError.fromResult(19) is CoreError.BiosSize)
        assertTrue(CoreError.fromResult(20) is CoreError.StateConfig)
        assertTrue(CoreError.fromResult(11) is CoreError.SramSize)
        assertTrue(CoreError.fromResult(4, Console.GBA)!!.message!!.contains("32 MiB"))
        assertTrue(CoreError.fromResult(4)!!.message!!.contains("8 MiB"))
        assertTrue(Regex("""NS_ERR_GBA_BAD_HEADER\s+18""").containsMatchIn(sessionHeader))
        assertTrue(Regex("""NS_ERR_GBA_BIOS_SIZE\s+19""").containsMatchIn(sessionHeader))
        assertTrue(Regex("""NS_ERR_GBA_STATE_CONFIG\s+20""").containsMatchIn(sessionHeader))
        assertTrue(Regex("""NS_ERR_INVALID_ARGUMENT\s+17""").containsMatchIn(sessionHeader))
    }
}

package com.joelbermudez.pocketgb.emulator

import com.joelbermudez.pocketgb.testing.SyntheticRom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreBridgeTest {
    @Test
    fun rejectsTooSmallBeforeNative() {
        CoreBridge().use { core ->
            assertThrows(CoreError.RomTooSmall::class.java) {
                core.loadRom(ByteArray(0x14F))
            }
        }
    }

    @Test
    fun rejectsOverEightMiBBeforeNative() {
        CoreBridge().use { core ->
            assertThrows(CoreError.RomTooLarge::class.java) {
                core.loadRom(ByteArray(8 * 1024 * 1024 + 1))
            }
        }
    }

    @Test
    fun readsSyntheticRomMetadataAndFrame() {
        CoreBridge().use { core ->
            val info = core.loadRom(SyntheticRom.romOnly(title = "A2 TEST"))

            assertEquals("A2 TEST", info.title)
            assertEquals(32 * 1024, info.romBytes)
            assertEquals(64, info.fingerprintHex.length)
            val pixels = IntArray(160 * 144)
            core.runFrame()
            core.copyFrame(pixels)
            assertTrue(pixels.any { it != 0 })
        }
    }

    @Test
    fun useAfterCloseIsTypedFailure() {
        val core = CoreBridge()
        core.close()

        assertThrows(CoreError.Closed::class.java) { core.runFrame() }
    }
}

package com.joelbermudez.pocketgb.settings

import com.joelbermudez.pocketgb.emulator.Console
import com.joelbermudez.pocketgb.emulator.GbaOptions
import com.joelbermudez.pocketgb.emulator.GbaRtc
import com.joelbermudez.pocketgb.emulator.GbaSaveType
import com.joelbermudez.pocketgb.input.ControlId
import com.joelbermudez.pocketgb.input.ControlsOrientation
import com.joelbermudez.pocketgb.input.NormalizedPoint
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** N8: ajustes por juego de GBA (por huella) y disposición de controles por consola × orientación. */
class GameplaySettingsGbaTest {
    private val fp = "ab".repeat(32)
    private val other = "cd".repeat(32)

    @Test
    fun gbaOverridesAreStoredByFingerprintAndBecomeCoreOptions() {
        val data = GameplaySettingsData().setOverrides(fp, GameOverrides(gbaSaveType = GbaSaveType.FLASH128.native, gbaRtc = GbaRtc.ON.native, gbaUseBios = false))
        assertEquals(GbaOptions(GbaSaveType.FLASH128, GbaRtc.ON, useBios = false), data.gbaOptions(fp))
        assertEquals("otro juego: todo detectado", GbaOptions(), data.gbaOptions(other))
        assertEquals(GbaOptions(), data.gbaOptions(null))
        assertTrue(data.perGame.getValue(fp).gbaForced)
        // Volver a «Detectado» y «Global» borra la entrada.
        assertTrue(data.setOverrides(fp, GameOverrides()).perGame.isEmpty())
    }

    @Test
    fun invalidGbaValuesFromDiskAreDropped() {
        val dirty = GameplaySettingsData(
            perGame = mapOf(
                fp to GameOverrides(gbaSaveType = 0, gbaRtc = 7, gbaUseBios = true),
                other to GameOverrides(gbaSaveType = 99, gbaRtc = GbaRtc.OFF.native),
            ),
        ).sanitized()
        assertNull("AUTO, RTC desconocido y BIOS «true» (= global) no son ajustes", dirty.perGame[fp])
        assertEquals(GameOverrides(gbaRtc = GbaRtc.OFF.native), dirty.perGame[other])
    }

    @Test
    fun gbaSettingsSurviveAWriteAndReadOfTheFile() {
        val dir = Files.createTempDirectory("n8-settings").toFile()
        try {
            val file = GameplaySettingsFile(File(dir, "gameplay-settings.json"))
            val data = GameplaySettingsData()
                .setOverrides(fp, GameOverrides(gbaSaveType = GbaSaveType.EEPROM8K.native, gbaUseBios = false))
                .move(ControlsOrientation.LANDSCAPE, ControlId.L, NormalizedPoint(0.2f, 0.3f), Console.GBA)
            file.save(data)
            val read = file.load()
            assertEquals(data, read)
            assertEquals(GbaSaveType.EEPROM8K, read.gbaOptions(fp).saveType)
            assertEquals(NormalizedPoint(0.2f, 0.3f), read.layout(ControlsOrientation.LANDSCAPE, Console.GBA).positions[ControlId.L])
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun layoutsAreSeparatePerConsoleAndOrientation() {
        val p = ControlsOrientation.PORTRAIT
        val l = ControlsOrientation.LANDSCAPE
        val moved = GameplaySettingsData().move(p, ControlId.A, NormalizedPoint(0.7f, 0.2f), Console.GBA)
        assertTrue("Game Boy no cambia", moved.isFactoryLayout(p))
        assertFalse(moved.isFactoryLayout(p, Console.GBA))
        assertTrue(moved.isFactoryLayout(l, Console.GBA))
        assertEquals(NormalizedPoint(0.7f, 0.2f), moved.controlLayout(p, Console.GBA).centers[ControlId.A])
        assertTrue(ControlId.L in moved.controlLayout(p, Console.GBA).centers)
        assertFalse(ControlId.L in moved.controlLayout(p).centers)
        assertTrue(moved.resetLayout(p, Console.GBA).isFactoryLayout(p, Console.GBA))
    }

    @Test
    fun resizingStartsFromTheGbaFactoryScaleAndTheFactoryCheckKnowsIt() {
        val l = ControlsOrientation.LANDSCAPE
        val start = GameplaySettingsData()
        assertTrue(start.isFactoryLayout(l, Console.GBA))
        val smaller = start.resize(l, ControlId.DPAD, -0.1f, Console.GBA)
        assertEquals("de 0,6 a 0,6 (mínimo)", 0.6f, smaller.controlLayout(l, Console.GBA).scale(ControlId.DPAD), 1e-4f)
        assertTrue("0,6 es la de fábrica de GBA en horizontal", smaller.isFactoryLayout(l, Console.GBA))
        val larger = start.resize(l, ControlId.L, 0.1f, Console.GBA)
        assertEquals(1.0f, larger.controlLayout(l, Console.GBA).scale(ControlId.L), 1e-4f)
        assertFalse(larger.isFactoryLayout(l, Console.GBA))
        assertEquals("GB sigue en 1,0", 1f, larger.controlLayout(l).scale(ControlId.DPAD), 0f)
    }
}

package com.joelbermudez.pocketgb.settings

import com.joelbermudez.pocketgb.input.ControlId
import com.joelbermudez.pocketgb.input.ControlLayout
import com.joelbermudez.pocketgb.input.ControlsOrientation
import com.joelbermudez.pocketgb.input.NormalizedPoint
import com.joelbermudez.pocketgb.library.DefaultPreferencesFileOps
import com.joelbermudez.pocketgb.library.PreferencesFileOps
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GameplaySettingsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val fp = "a".repeat(64)
    private val fp2 = "b".repeat(64)

    @Test
    fun defaultsMatchThePlan() {
        val d = GameplaySettingsData()
        assertEquals(1, d.schema)
        assertEquals(70, d.opacity)
        assertEquals(ControlsVisibility.ALWAYS, d.visibility)
        assertTrue(d.haptics)
        assertEquals(1f, d.sizeScale, 0f)
        assertTrue(d.integerScaleLandscape)
        assertEquals(DpadStyle.CROSS, d.dpadStyle)
        assertEquals(1f, d.volume, 0f)
        assertFalse(d.colorForGameBoy)
        assertEquals(0, d.compatPalette)
        assertTrue(d.perGame.isEmpty())
        assertNull(d.controllerMapping)
        assertEquals(d, d.sanitized())
    }

    @Test
    fun sanitizedFixesEachInvalidField() {
        val dirty = GameplaySettingsData(
            opacity = 42,
            sizeScale = 3f,
            volume = -2f,
            compatPalette = 13,
            portraitLayout = StoredControlLayout(
                positions = mapOf(ControlId.A to NormalizedPoint(1.5f, 0.2f), ControlId.B to NormalizedPoint(0.3f, 0.4f)),
                scales = mapOf(ControlId.A to 9f, ControlId.B to 0.1f, ControlId.DPAD to Float.NaN),
            ),
            perGame = mapOf(
                "not-hex" to GameOverrides(colorForGameBoy = true),
                fp.uppercase() to GameOverrides(colorForGameBoy = true),
                fp to GameOverrides(compatPalette = 99),
                fp2 to GameOverrides(),
            ),
        ).sanitized()
        assertEquals(70, dirty.opacity)
        assertEquals(1.15f, dirty.sizeScale, 0f)
        assertEquals(0f, dirty.volume, 0f)
        assertEquals(0, dirty.compatPalette)
        assertEquals(setOf(ControlId.B), dirty.portraitLayout.positions.keys)
        assertEquals(1.6f, dirty.portraitLayout.scales.getValue(ControlId.A), 0f)
        assertEquals(0.6f, dirty.portraitLayout.scales.getValue(ControlId.B), 0f)
        assertFalse(ControlId.DPAD in dirty.portraitLayout.scales)
        // La huella en mayúsculas no es válida (se guarda en minúsculas); el override inválido pierde solo ese campo.
        assertEquals(emptyMap<String, GameOverrides>(), dirty.perGame)
    }

    @Test
    fun sanitizedKeepsValidValuesAndNanVolumeFallsBack() {
        val ok = GameplaySettingsData(opacity = 30, sizeScale = 0.85f, volume = 0.4f, compatPalette = 12, perGame = mapOf(fp to GameOverrides(true, 3)))
        assertEquals(ok, ok.sanitized())
        assertEquals(1f, GameplaySettingsData(volume = Float.NaN).sanitized().volume, 0f)
        assertEquals(1f, GameplaySettingsData(sizeScale = Float.NaN).sanitized().sizeScale, 0f)
        assertEquals(mapOf(fp to GameOverrides(true, 0)), GameplaySettingsData(perGame = mapOf(fp to GameOverrides(true, 0))).sanitized().perGame)
        assertEquals(mapOf(fp to GameOverrides(true, null)), GameplaySettingsData(perGame = mapOf(fp to GameOverrides(true, 13))).sanitized().perGame)
    }

    @Test
    fun setOverridesDeletesEntryWhenBackToGlobal() {
        val withEntry = GameplaySettingsData().setOverrides(fp, GameOverrides(colorForGameBoy = true))
        assertEquals(GameOverrides(colorForGameBoy = true), withEntry.perGame[fp])
        val partial = withEntry.setOverrides(fp, GameOverrides(colorForGameBoy = true, compatPalette = 4))
        assertEquals(4, partial.perGame.getValue(fp).compatPalette)
        val cleared = partial.setOverrides(fp, GameOverrides())
        assertTrue(cleared.perGame.isEmpty())
        assertEquals(GameplaySettingsData(), cleared)
    }

    @Test
    fun setOverridesRejectsInvalidFingerprint() {
        assertThrows(IllegalArgumentException::class.java) {
            GameplaySettingsData().setOverrides("../etc", GameOverrides(colorForGameBoy = true))
        }
    }

    @Test
    fun layoutOperationsDoNotCrossOrientations() {
        val p = ControlsOrientation.PORTRAIT
        val l = ControlsOrientation.LANDSCAPE
        val start = GameplaySettingsData()
        assertTrue(start.isFactoryLayout(p))
        assertTrue(start.isFactoryLayout(l))

        val moved = start.move(p, ControlId.A, NormalizedPoint(0.7f, 0.2f))
        assertFalse(moved.isFactoryLayout(p))
        assertTrue(moved.isFactoryLayout(l))
        assertEquals(NormalizedPoint(0.7f, 0.2f), ControlLayout.from(moved.portraitLayout, p).centers.getValue(ControlId.A))
        assertEquals(ControlLayout.defaults(l).centers.getValue(ControlId.A), ControlLayout.from(moved.landscapeLayout, l).centers.getValue(ControlId.A))

        val clamped = moved.move(l, ControlId.B, NormalizedPoint(-1f, 4f))
        assertEquals(NormalizedPoint(0f, 1f), clamped.landscapeLayout.positions.getValue(ControlId.B))

        val resized = clamped.resize(p, ControlId.DPAD, 0.1f)
        assertEquals(1.1f, ControlLayout.from(resized.portraitLayout, p).scale(ControlId.DPAD), 1e-4f)
        assertEquals(1f, ControlLayout.from(resized.landscapeLayout, l).scale(ControlId.DPAD), 1e-4f)

        val capped = (1..20).fold(resized) { acc, _ -> acc.resize(p, ControlId.DPAD, 0.1f) }
        assertEquals(1.6f, capped.portraitLayout.scales.getValue(ControlId.DPAD), 1e-4f)
        val floor = (1..20).fold(resized) { acc, _ -> acc.resize(p, ControlId.DPAD, -0.1f) }
        assertEquals(0.6f, floor.portraitLayout.scales.getValue(ControlId.DPAD), 1e-4f)

        val resetP = resized.resetLayout(p)
        assertTrue(resetP.isFactoryLayout(p))
        assertFalse(resetP.isFactoryLayout(l))
        assertTrue(resetP.resetLayout(l).isFactoryLayout(l))
    }

    @Test
    fun movingBackToFactoryPositionCountsAsFactory() {
        val p = ControlsOrientation.PORTRAIT
        val factory = ControlLayout.defaults(p).centers.getValue(ControlId.A)
        val data = GameplaySettingsData().move(p, ControlId.A, NormalizedPoint(0.1f, 0.1f)).move(p, ControlId.A, factory)
        assertTrue(data.isFactoryLayout(p))
    }

    @Test
    fun emulationResolvesModelAndPalette() {
        val global = GameplaySettingsData(colorForGameBoy = true, compatPalette = 5)
        // GBC: siempre AUTO.
        assertEquals(EmulationSelection(SelectedModel.AUTO, 5), global.emulation(fp, isCgbRom = true))
        // DMG con color global.
        assertEquals(EmulationSelection(SelectedModel.CGB, 5), global.emulation(fp, isCgbRom = false))
        // DMG sin color.
        assertEquals(EmulationSelection(SelectedModel.DMG, 0), GameplaySettingsData().emulation(fp, false))
        // Por juego manda sobre el global, campo a campo.
        val perGame = global.setOverrides(fp, GameOverrides(colorForGameBoy = false))
        assertEquals(EmulationSelection(SelectedModel.DMG, 5), perGame.emulation(fp, false))
        assertEquals(EmulationSelection(SelectedModel.CGB, 5), perGame.emulation(fp2, false))
        assertEquals(EmulationSelection(SelectedModel.CGB, 5), perGame.emulation(null, false))
        val palette = GameplaySettingsData().setOverrides(fp, GameOverrides(colorForGameBoy = true, compatPalette = 9))
        assertEquals(EmulationSelection(SelectedModel.CGB, 9), palette.emulation(fp, false))
    }

    @Test
    fun updateSanitizesTheResult() {
        val data = GameplaySettingsData().update { it.copy(opacity = 55, volume = 7f) }
        assertEquals(70, data.opacity)
        assertEquals(1f, data.volume, 0f)
        assertEquals(30, GameplaySettingsData().update { it.copy(opacity = 30) }.opacity)
    }

    // ---- Persistencia ----

    private fun file() = File(tmp.root, "gameplay-settings.json")

    @Test
    fun roundTripThroughAtomicFile() {
        val store = GameplaySettingsFile(file())
        val data = GameplaySettingsData(
            opacity = 100,
            visibility = ControlsVisibility.ON_TOUCH,
            haptics = false,
            sizeScale = 1.15f,
            dpadStyle = DpadStyle.ARROWS,
            volume = 0.35f,
            colorForGameBoy = true,
            compatPalette = 7,
        ).move(ControlsOrientation.PORTRAIT, ControlId.A, NormalizedPoint(0.5f, 0.5f))
            .resize(ControlsOrientation.LANDSCAPE, ControlId.B, 0.2f)
            .setOverrides(fp, GameOverrides(colorForGameBoy = false, compatPalette = 2))
        store.save(data)
        assertEquals(data, GameplaySettingsFile(file()).load())
        assertFalse(File(tmp.root, "gameplay-settings.json.tmp").exists())
    }

    @Test
    fun missingFileGivesDefaults() {
        assertEquals(GameplaySettingsData(), GameplaySettingsFile(file()).load())
    }

    @Test
    fun corruptFileGoesToQuarantineAndGivesDefaults() {
        file().writeText("{ esto no es json")
        val loaded = GameplaySettingsFile(file(), clock = { 123L }).load()
        assertEquals(GameplaySettingsData(), loaded)
        assertFalse(file().exists())
        assertTrue(File(tmp.root, "gameplay-settings.json.corrupt-123").exists())
        file().writeText("[1,2]")
        assertEquals(GameplaySettingsData(), GameplaySettingsFile(file(), clock = { 456L }).load())
        assertTrue(File(tmp.root, "gameplay-settings.json.corrupt-456").exists())
    }

    @Test
    fun invalidFieldFallsBackOnlyForThatField() {
        file().writeText(
            """{"opacity":"mucha","volume":0.5,"visibility":"NOPE","haptics":false,"compatPalette":99,"dpadStyle":"ARROWS","extra":1,
               "perGame":{"zz":{"colorForGameBoy":true}}}""",
        )
        val loaded = GameplaySettingsFile(file()).load()
        assertEquals(70, loaded.opacity)
        assertEquals(0.5f, loaded.volume, 0f)
        assertEquals(ControlsVisibility.ALWAYS, loaded.visibility)
        assertFalse(loaded.haptics)
        assertEquals(0, loaded.compatPalette)
        assertEquals(DpadStyle.ARROWS, loaded.dpadStyle)
        assertTrue(loaded.perGame.isEmpty())
        assertNull(loaded.controllerMapping)
        assertTrue(file().exists())
    }

    @Test
    fun orphanTempIsRecoveredWhenFileMissing() {
        val temp = File(tmp.root, "gameplay-settings.json.tmp")
        GameplaySettingsFile(File(tmp.root, "other.json")).save(GameplaySettingsData(opacity = 50))
        File(tmp.root, "other.json").copyTo(temp)
        assertEquals(50, GameplaySettingsFile(file()).load().opacity)
        assertTrue(file().exists())
        assertFalse(temp.exists())
    }

    @Test
    fun failedWriteLeavesPreviousFileIntact() {
        val store = GameplaySettingsFile(file())
        store.save(GameplaySettingsData(opacity = 30))
        val failing = GameplaySettingsFile(file(), ops = object : PreferencesFileOps {
            override fun writeSynced(file: File, bytes: ByteArray) = throw IOException("disco lleno")
            override fun rename(from: File, to: File) = false
        })
        assertThrows(IOException::class.java) { failing.save(GameplaySettingsData(opacity = 100)) }
        assertEquals(30, GameplaySettingsFile(file()).load().opacity)
        val noRename = GameplaySettingsFile(file(), ops = object : PreferencesFileOps {
            override fun writeSynced(file: File, bytes: ByteArray) = DefaultPreferencesFileOps.writeSynced(file, bytes)
            override fun rename(from: File, to: File) = false
        })
        assertThrows(IOException::class.java) { noRename.save(GameplaySettingsData(opacity = 100)) }
        assertEquals(30, GameplaySettingsFile(file()).load().opacity)
        assertNotNull(file())
    }
}

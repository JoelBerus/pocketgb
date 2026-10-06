package com.joelbermudez.pocketgb

import android.content.Intent
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.joelbermudez.pocketgb.debug.catalogScreens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cruza `tools/android-screens.txt` (el manifiesto que lee el script de capturas) con el registro del catálogo
 * debug: cada id del manifiesto existe, cada pantalla del catálogo está en el manifiesto, ningún id se repite con
 * orientación o argumentos contradictorios, y una pantalla desconocida es un fallo visible.
 */
@RunWith(AndroidJUnit4::class)
class CatalogCoverageTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private data class Line(val id: String, val orientation: String, val theme: String, val args: Map<String, String>)

    private fun manifest(): List<Line> {
        val text = InstrumentationRegistry.getInstrumentation().context.assets
            .open("android-screens.txt").bufferedReader().use { it.readText() }
        return text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { raw ->
            val parts = raw.split(Regex("\\s+"))
            assertTrue("Línea inválida en el manifiesto: «$raw»", parts.size >= 3)
            Line(
                id = parts[0],
                orientation = parts[1],
                theme = parts[2],
                args = parts.drop(3).associate { arg ->
                    assertTrue("Argumento sin «=» en «$raw»", '=' in arg)
                    arg.substringBefore('=') to arg.substringAfter('=')
                },
            )
        }
    }

    @Test
    fun manifestLinesAreWellFormedAndNeverRepeated() {
        val lines = manifest()
        assertTrue("el manifiesto no puede estar vacío", lines.isNotEmpty())
        lines.forEach {
            assertTrue("orientación desconocida en ${it.id}: ${it.orientation}", it.orientation in setOf("portrait", "landscape"))
            assertTrue("tema desconocido en ${it.id}: ${it.theme}", it.theme in setOf("light", "dark", "dyn-green", "dyn-violet"))
        }
        val repeated = lines.groupBy { Triple(it.id, it.orientation, it.theme) }.filterValues { it.size > 1 }.keys
        assertTrue("capturas duplicadas (id, orientación, tema): $repeated", repeated.isEmpty())
    }

    @Test
    fun anIdRepeatedForAnotherThemeNeverContradictsItself() {
        manifest().groupBy { it.id }.forEach { (id, lines) ->
            assertEquals("«$id» con orientaciones distintas", 1, lines.map { it.orientation }.toSet().size)
            assertEquals("«$id» con argumentos distintos", 1, lines.map { it.args }.toSet().size)
        }
    }

    @Test
    fun everyManifestIdExistsInTheCatalogAndViceVersa() {
        val manifestIds = manifest().map { it.id }.toSet()
        val catalogIds = catalogScreens.keys
        assertEquals("ids del manifiesto que el catálogo no conoce", emptySet<String>(), manifestIds - catalogIds)
        assertEquals("pantallas del catálogo que faltan en el manifiesto", emptySet<String>(), catalogIds - manifestIds)
    }

    @Test
    fun everyA6IdOfThePlanIsPresent() {
        val ids = manifest().map { it.id }.toSet()
        val missing = A6_IDS.filterNot { it in ids }
        assertTrue("faltan ids de A6: $missing", missing.isEmpty())
    }

    @Test
    fun everyA7IdOfThePlanIsPresentWithItsArguments() {
        val byId = manifest().groupBy { it.id }
        val missing = A7_IDS.keys.filterNot { it in byId }
        assertTrue("faltan ids de A7: $missing", missing.isEmpty())
        A7_IDS.forEach { (id, args) ->
            val actual = byId.getValue(id).first().args
            args.forEach { (key, value) -> assertEquals("«$id» argumento $key", value, actual[key]) }
        }
        assertEquals("los 14 ids del catálogo A7", 14, A7_IDS.size)
    }

    @Test
    fun scriptOnlyArgumentsNeverReachTheApp() {
        // `window` y `cutout` los consume tools/android-screenshots.sh; `DebugIntent` no los conoce.
        val ids = manifest().filter { "window" in it.args || "cutout" in it.args }.map { it.id }.toSet()
        assertEquals(setOf("library-wide-rail", "library-list-detail", "gameplay-cutout-landscape"), ids)
    }

    @Test
    fun gameScreensAreDarkOnlyAndLandscapeIdsRotate() {
        val byId = manifest().groupBy { it.id }
        GAME_IDS.forEach { id -> assertEquals("«$id» solo en oscuro", setOf("dark"), byId.getValue(id).map { it.theme }.toSet()) }
        LANDSCAPE_IDS.forEach { id -> assertEquals("«$id» en horizontal", "landscape", byId.getValue(id).first().orientation) }
    }

    @Test
    fun anUnknownIdIsNotInTheCatalog() {
        assertFalse("missing" in catalogScreens.keys)
    }

    @Test
    fun everyManifestScreenRendersItsOwnTagAndNeverTheUnknownScreen() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        manifest().distinctBy { it.id }.forEach { line ->
            val intent = Intent(context, MainActivity::class.java)
                .putExtra("screen", line.id)
                .putExtra("theme", if (line.theme == "dark") "dark" else "light")
                .putExtra("dynamicColor", false)
            line.args.filterKeys { it !in SCRIPT_ONLY }.forEach { (key, value) -> intent.putExtra(key, value) }
            ActivityScenario.launch<MainActivity>(intent).use {
                compose.onNodeWithTag("debug-screen-${line.id}").assertIsDisplayed()
                compose.onAllNodesWithTag("unknown-screen-id").fetchSemanticsNodes().let { nodes ->
                    assertTrue("«${line.id}» cae en la pantalla desconocida", nodes.isEmpty())
                }
            }
        }
    }

    private companion object {
        val SCRIPT_ONLY = setOf("wait", "swipe", "window", "cutout")

        /** Los 14 IDs de la tabla «Catálogo A7» de `docs/diseno-android/A7-plan.md` con los argumentos que fijan su variante. */
        val A7_IDS: Map<String, Map<String, String>> = mapOf(
            "gameplay-controller" to mapOf("controller" to "1"),
            "gameplay-controller-touch" to mapOf("controller" to "1", "showTouch" to "1"),
            "settings-controller-mapping" to mapOf("controller" to "1"),
            "controller-assign-dialog" to emptyMap(),
            "library-ax5" to mapOf("fontScale" to "2.0"),
            "library-detail-ax5" to mapOf("fontScale" to "2.0"),
            "settings-ax5" to mapOf("fontScale" to "2.0"),
            "library-high-contrast" to mapOf("contrast" to "high"),
            "gameplay-high-contrast" to mapOf("contrast" to "high", "opacity" to "30"),
            "gameplay-landscape-high-contrast" to mapOf("contrast" to "high", "opacity" to "30"),
            "pause-sheet-ax5" to mapOf("fontScale" to "2.0"),
            "library-wide-rail" to mapOf("window" to "wide"),
            "library-list-detail" to mapOf("window" to "wide"),
            "gameplay-cutout-landscape" to mapOf("cutout" to "tall"),
        )
        /** Ids que A6 añade o amplía (tabla «Catálogo (L5)» de `docs/diseno-android/A6-plan.md`). */
        val A6_IDS = listOf(
            "launch", "library-folder-empty", "library-cloud-pending", "library-cloud-downloading",
            "library-scan-progress", "library-scan-summary", "library-continue", "library-grid", "search-active",
            "search-results", "library-search", "game-context-menu", "remove-game-confirm", "game-settings",
            "library-detail", "favorites", "settings-controls", "settings-display", "settings-emulation",
            "settings-audio", "settings-storage", "settings-licenses", "gameplay-landscape",
            "gameplay-landscape-clear", "gameplay-landscape-hidden", "gameplay-portrait-arrows",
            "gameplay-landscape-arrows", "gameplay-fill", "customize-controls-portrait",
            "customize-controls-landscape", "customize-controls-size", "load-state-confirm", "replace-state-confirm",
            "gameplay-header-damaged", "pause-sheet",
        )
        val GAME_IDS = listOf(
            "gameplay-controls", "gameplay-fast-forward", "gameplay-landscape", "gameplay-landscape-clear",
            "gameplay-landscape-hidden", "gameplay-portrait-arrows", "gameplay-landscape-arrows", "gameplay-fill",
            "customize-controls-portrait", "customize-controls-landscape", "customize-controls-size", "pause-sheet",
            "pause-dialog", "states-sheet", "states-dialog", "load-state-confirm", "replace-state-confirm",
            "exit-save-failed", "exit-risk", "save-warning", "open-error", "save-problem", "states-rescue",
            "gameplay-header-damaged", "gameplay-controller", "gameplay-controller-touch", "gameplay-high-contrast",
            "gameplay-landscape-high-contrast", "pause-sheet-ax5", "gameplay-cutout-landscape",
        )
        val LANDSCAPE_IDS = listOf(
            "gameplay-landscape", "gameplay-landscape-clear", "gameplay-landscape-hidden",
            "gameplay-landscape-arrows", "gameplay-fill", "customize-controls-landscape", "pause-dialog", "states-dialog",
            "gameplay-controller", "gameplay-landscape-high-contrast", "gameplay-cutout-landscape", "library-wide-rail",
            "library-list-detail",
        )
    }
}

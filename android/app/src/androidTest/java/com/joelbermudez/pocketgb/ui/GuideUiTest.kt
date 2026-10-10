package com.joelbermudez.pocketgb.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.joelbermudez.pocketgb.guide.GuideLibrary
import com.joelbermudez.pocketgb.settings.DpadStyle
import com.joelbermudez.pocketgb.settings.GameplaySettingsData
import com.joelbermudez.pocketgb.tips.LocalTips
import com.joelbermudez.pocketgb.tips.SharedPreferencesTipsStorage
import com.joelbermudez.pocketgb.tips.Tip
import com.joelbermudez.pocketgb.tips.TipsState
import com.joelbermudez.pocketgb.ui.guide.GuideScreen
import com.joelbermudez.pocketgb.ui.guide.GuideSectionScreen
import com.joelbermudez.pocketgb.ui.settings.ControlsSettingsContent
import com.joelbermudez.pocketgb.ui.settings.SettingsScreen
import com.joelbermudez.pocketgb.ui.theme.PocketGBTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * N9 · Ajustes › Guía con la guía empaquetada de verdad (assets del APK, sin red): secciones, búsqueda, apartado al que
 * lleva un resultado, enlaces entre secciones, TalkBack (encabezados, enlaces) y fuente al 200 %; y las tarjetas de
 * consejo, cuyo descarte se guarda en el dispositivo.
 */
@RunWith(AndroidJUnit4::class)
class GuideUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun clearTips() {
        context.getSharedPreferences(SharedPreferencesTipsStorage.FILE, 0).edit().clear().commit()
    }

    /** Navegación mínima como la de la app: índice → sección (con apartado) → otra sección por un enlace. */
    private fun showGuide(fontScale: Float = 1f) {
        compose.setContent {
            PocketGBTheme {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                    var open by remember { mutableStateOf<Pair<String, String?>?>(null) }
                    Box(Modifier.fillMaxSize()) {
                        val current = open
                        if (current == null) {
                            GuideScreen(onOpenSection = { id, anchor -> open = id to anchor }, onBack = {})
                        } else {
                            GuideSectionScreen(current.first, current.second, onOpenSection = { id, anchor -> open = id to anchor }, onBack = { open = null })
                        }
                    }
                }
            }
        }
    }

    private fun waitForGuide() {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("guide-section-biblioteca-android").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun openSection(id: String) {
        compose.onNodeWithTag("guide-list").performScrollToNode(hasTestTag("guide-section-$id"))
        compose.onNodeWithTag("guide-section-$id").performClick()
    }

    private val isHeading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    @Test
    fun settingsHasTheGuideEntry() {
        var opened = false
        compose.setContent { PocketGBTheme { SettingsScreen(onAppearance = {}, onLibrary = {}, onSaves = {}, onAbout = {}, onGuide = { opened = true }) } }
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Guía"))
        compose.onNodeWithText("Guía").performClick()
        assertTrue(opened)
    }

    @Test
    fun everyBundledSectionIsListedAndOpens() {
        showGuide()
        waitForGuide()
        GuideLibrary.SECTION_IDS.forEach { id ->
            compose.onNodeWithTag("guide-list").performScrollToNode(hasTestTag("guide-section-$id"))
            compose.onNodeWithTag("guide-section-$id").assertIsDisplayed()
        }
        openSection("gba-android")
        compose.onNodeWithText("Game Boy Advance").assertIsDisplayed()
        compose.onNodeWithTag("guide-section-body").performScrollToNode(hasTestTag("guide-heading-la-bios-opcional"))
        compose.onNodeWithTag("guide-heading-la-bios-opcional").assertIsDisplayed()
        assertTrue("los apartados son encabezados para TalkBack", isHeading.matches(compose.onNodeWithTag("guide-heading-la-bios-opcional").fetchSemanticsNode()))
    }

    @Test
    fun searchFindsTheSubsectionAndOpensScrolledToIt() {
        showGuide()
        waitForGuide()
        compose.onNodeWithTag("guide-search").performTextInput("bíos OFICIAL")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("guide-hit-0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("guide-hit-0").assertIsDisplayed().performClick()
        compose.onNodeWithTag("guide-heading-la-bios-opcional").assertIsDisplayed()
    }

    @Test
    fun searchWithoutMatchesSaysSo() {
        showGuide()
        waitForGuide()
        compose.onNodeWithTag("guide-search").performTextInput("zzzqqq")
        compose.onNodeWithTag("guide-no-results").assertIsDisplayed()
        compose.onNodeWithTag("guide-search-clear").performClick()
        compose.onNodeWithTag("guide-section-biblioteca-android").assertExists()
    }

    @Test
    fun aLinkOpensTheOtherSectionWithItsTitle() {
        showGuide()
        waitForGuide()
        openSection("biblioteca-android")
        val linkText = "Categorías, etiquetas, inicio y ajustes del juego"
        val paragraph = hasText(linkText, substring = true) and hasText("Bajo «Continuar jugando»", substring = true)
        compose.onNodeWithTag("guide-section-body").performScrollToNode(paragraph)
        compose.onNode(paragraph).performFirstLinkClick()
        compose.onNodeWithText(linkText).assertIsDisplayed() // título de la barra superior de la otra sección
    }

    @Test
    fun atDoubleFontNothingOverflowsTheScreenWidth() {
        showGuide(fontScale = 2f)
        waitForGuide()
        openSection("gba-android")
        compose.onNodeWithTag("guide-section-body").performScrollToNode(hasTestTag("guide-table-row"))
        val width = compose.onRoot().fetchSemanticsNode().size.width
        val rows = compose.onAllNodesWithTag("guide-table-row").fetchSemanticsNodes()
        assertTrue(rows.isNotEmpty())
        rows.forEach { row ->
            assertTrue("ficha de tabla fuera de la pantalla con fuente al 200 %", row.boundsInRoot.right <= width + 1)
            // Cada fila es un bloque de texto que crece hacia abajo, sin truncar.
            assertTrue(row.size.height > 0)
        }
    }

    @Test
    fun aTipIsAccessibleAndStaysDismissedAfterRestart() {
        var state by mutableStateOf(TipsState(SharedPreferencesTipsStorage(context)))
        compose.setContent {
            PocketGBTheme {
                CompositionLocalProvider(LocalTips provides state) {
                    ControlsSettingsContent(GameplaySettingsData(dpadStyle = DpadStyle.ARROWS), {}, {})
                }
            }
        }
        val dismiss = compose.onNodeWithTag("tip-${Tip.ARROWS.id}-dismiss").performScrollTo().assertIsDisplayed().fetchSemanticsNode()
        val description = dismiss.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()
        assertTrue("el botón dice qué descarta: «$description»", description.contains("Ajusta las flechas"))
        with(compose.density) { assertTrue("objetivo táctil ≥ 48 dp", dismiss.size.height >= 48.dp.roundToPx() - 1) }
        compose.onNodeWithTag("tip-${Tip.ARROWS.id}-dismiss").performClick()
        compose.onNodeWithTag("tip-${Tip.ARROWS.id}").assertDoesNotExist()
        // «Reinicio»: un estado nuevo leído del disco no lo vuelve a mostrar; los demás consejos siguen visibles.
        compose.runOnIdle { state = TipsState(SharedPreferencesTipsStorage(context)) }
        compose.onNodeWithTag("tip-${Tip.ARROWS.id}").assertDoesNotExist()
        val fresh = TipsState(SharedPreferencesTipsStorage(context))
        assertFalse(fresh.isVisible(Tip.ARROWS))
        assertTrue(fresh.isVisible(Tip.MOMENTS))
        fresh.resetAll()
        assertTrue(TipsState(SharedPreferencesTipsStorage(context)).isVisible(Tip.ARROWS))
    }

    @Test
    fun withoutTheAppTipsNothingIsShown() {
        compose.setContent { PocketGBTheme { ControlsSettingsContent(GameplaySettingsData(dpadStyle = DpadStyle.ARROWS), {}, {}) } }
        compose.onNodeWithTag("tip-${Tip.ARROWS.id}").assertDoesNotExist()
    }

    @Test
    fun theArrowsTipOnlyShowsWithSplitArrows() {
        val tips = TipsState(SharedPreferencesTipsStorage(context))
        compose.setContent {
            PocketGBTheme {
                CompositionLocalProvider(LocalTips provides tips) {
                    ControlsSettingsContent(GameplaySettingsData(dpadStyle = DpadStyle.CROSS), {}, {})
                }
            }
        }
        compose.onNodeWithTag("tip-${Tip.ARROWS.id}").assertDoesNotExist()
        assertEquals(true, tips.isVisible(Tip.ARROWS))
    }
}

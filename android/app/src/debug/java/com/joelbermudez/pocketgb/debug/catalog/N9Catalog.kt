package com.joelbermudez.pocketgb.debug.catalog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.joelbermudez.pocketgb.debug.DebugIntent
import com.joelbermudez.pocketgb.settings.DpadStyle
import com.joelbermudez.pocketgb.settings.GameplaySettingsData
import com.joelbermudez.pocketgb.tips.InMemoryTipsStorage
import com.joelbermudez.pocketgb.tips.LocalTips
import com.joelbermudez.pocketgb.tips.TipsState
import com.joelbermudez.pocketgb.ui.guide.GuideContent
import com.joelbermudez.pocketgb.ui.guide.GuideSectionContent
import com.joelbermudez.pocketgb.ui.guide.rememberGuideSections
import com.joelbermudez.pocketgb.ui.settings.ControlsSettingsContent
import com.joelbermudez.pocketgb.ui.settings.SettingsScreen

/** N9 · Ajustes › Guía (con la guía empaquetada de verdad) y las tarjetas de consejo sobre pantallas de otros lotes. */
@Composable
private fun WithTips(content: @Composable () -> Unit) {
    val tips = remember { TipsState(InMemoryTipsStorage()) }
    CompositionLocalProvider(LocalTips provides tips) { content() }
}

@Composable
private fun N9Guide(query: String = "") {
    WithTips { GuideContent(rememberGuideSections(), onOpenSection = { _, _ -> }, onBack = {}, initialQuery = query) }
}

@Composable
private fun N9Section(id: String, anchor: String? = null) {
    val section = rememberGuideSections()?.firstOrNull { it.id == id }
    GuideSectionContent(section, anchor, onOpenSection = { _, _ -> }, onBack = {})
}

private fun reuse(screens: Map<String, @Composable (DebugIntent) -> Unit>, id: String): @Composable (DebugIntent) -> Unit {
    val screen = screens.getValue(id)
    return { i -> WithTips { screen(i) } }
}

internal val n9CatalogScreens: Map<String, @Composable (DebugIntent) -> Unit> = buildMap {
    put("n9-settings") { SettingsScreen(onAppearance = {}, onLibrary = {}, onSaves = {}, onAbout = {}) }
    put("n9-guide") { N9Guide() }
    put("n9-guide-ax5") { N9Guide() }
    put("n9-guide-search") { N9Guide("bios oficial") }
    put("n9-guide-search-empty") { N9Guide("zzz") }
    put("n9-guide-section") { N9Section("gba-android") }
    put("n9-guide-section-ax5") { N9Section("gba-android") }
    put("n9-guide-section-anchor") { N9Section("momentos-android", "cargar-un-momento-importante") }
    put("n9-guide-section-table") { N9Section("biblioteca-android", "en-horizontal") }
    put("n9-guide-section-landscape") { N9Section("biblioteca-android", "en-horizontal") }
    put("n9-tip-pause", reuse(n6CatalogScreens, "n6-pause"))
    put("n9-tip-category", reuse(n4CatalogScreens, "n4-game-center"))
    put("n9-tip-send", reuse(n7CatalogScreens, "n7-details-status"))
    put("n9-tip-arrows") { WithTips { ControlsSettingsContent(GameplaySettingsData(dpadStyle = DpadStyle.ARROWS), {}, {}) } }
    put("n9-tip-arrows-ax5") { WithTips { ControlsSettingsContent(GameplaySettingsData(dpadStyle = DpadStyle.ARROWS), {}, {}) } }
}

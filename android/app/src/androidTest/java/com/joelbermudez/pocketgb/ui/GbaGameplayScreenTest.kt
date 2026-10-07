package com.joelbermudez.pocketgb.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.joelbermudez.pocketgb.emulator.Console
import com.joelbermudez.pocketgb.emulator.EmulatorSession
import com.joelbermudez.pocketgb.testing.SyntheticGbaRom
import com.joelbermudez.pocketgb.testing.SyntheticRom
import com.joelbermudez.pocketgb.ui.gameplay.GameplayScreen
import com.joelbermudez.pocketgb.ui.theme.PocketGBTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** N8: en vertical la imagen ocupa el ancho con la proporción de la consola: 3:2 en GBA y 10:9 en GB. */
@RunWith(AndroidJUnit4::class)
class GbaGameplayScreenTest {
    @get:Rule val compose = createComposeRule()
    private val sessions = mutableListOf<EmulatorSession>()

    @After
    fun tearDown() = sessions.forEach { it.close() }

    private fun ratioOf(session: EmulatorSession): Float {
        sessions += session
        compose.setContent { PocketGBTheme(forceDark = true, dynamicColor = false) { GameplayScreen(session) } }
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag("gameplay-surface").fetchSemanticsNode().boundsInRoot
        return bounds.width / bounds.height
    }

    @Test
    fun aGbaGameIsThreeToTwoInPortrait() {
        val session = EmulatorSession(Console.GBA).apply { loadGba(SyntheticGbaRom.idle()) }
        assertEquals(1.5f, ratioOf(session), 0.01f)
    }

    @Test
    fun aGameBoyGameStaysTenToNine() {
        val session = EmulatorSession().apply { load(SyntheticRom.sramCounter()) }
        assertEquals(10f / 9f, ratioOf(session), 0.01f)
    }
}

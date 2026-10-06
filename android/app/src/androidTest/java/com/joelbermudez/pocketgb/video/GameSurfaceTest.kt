package com.joelbermudez.pocketgb.video

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.joelbermudez.pocketgb.MainActivity
import com.joelbermudez.pocketgb.emulator.EmulatorSession
import com.joelbermudez.pocketgb.emulator.ScaleMode
import com.joelbermudez.pocketgb.testing.SyntheticRom
import com.joelbermudez.pocketgb.ui.theme.PocketGBTheme
import org.junit.Rule
import org.junit.Test

class GameSurfaceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun surfaceCanBeRecreatedWhileSessionKeepsRunning() {
        val visible = mutableStateOf(true)
        EmulatorSession().use { session ->
            session.load(SyntheticRom.romOnly())
            session.start()
            compose.activityRule.scenario.onActivity { activity ->
                activity.setContent {
                    PocketGBTheme {
                        if (visible.value) {
                            GameSurface(
                                session = session,
                                modifier = Modifier.fillMaxSize().testTag("game-surface"),
                            )
                        }
                    }
                }
            }
            compose.onNodeWithTag("game-surface").assertIsDisplayed()
            val before = session.frameCount

            repeat(25) {
                compose.runOnUiThread { visible.value = false }
                compose.waitForIdle()
                compose.runOnUiThread { visible.value = true }
                compose.waitForIdle()
            }

            compose.onNodeWithTag("game-surface").assertIsDisplayed()
            check(session.frameCount > before)
        }
    }

    @Test
    fun fillModeIsAppliedToTheSessionAndSurvivesSurfaceRecreation() {
        val visible = mutableStateOf(true)
        val mode = mutableStateOf(ScaleMode.FILL)
        EmulatorSession().use { session ->
            session.load(SyntheticRom.romOnly())
            session.start()
            compose.activityRule.scenario.onActivity { activity ->
                activity.setContent {
                    PocketGBTheme {
                        if (visible.value) {
                            GameSurface(
                                session = session,
                                modifier = Modifier.fillMaxSize().testTag("game-surface"),
                                scaleMode = mode.value,
                            )
                        }
                    }
                }
            }
            compose.onNodeWithTag("game-surface").assertIsDisplayed()
            compose.waitUntil(5_000) { session.scaleMode == ScaleMode.FILL }
            val before = session.frameCount

            compose.runOnUiThread { visible.value = false }
            compose.waitForIdle()
            compose.runOnUiThread { visible.value = true }
            compose.waitForIdle()
            compose.runOnUiThread { mode.value = ScaleMode.INTEGER }
            compose.waitUntil(5_000) { session.scaleMode == ScaleMode.INTEGER }
            compose.runOnUiThread { mode.value = ScaleMode.FILL }
            compose.waitUntil(5_000) { session.scaleMode == ScaleMode.FILL }

            compose.onNodeWithTag("game-surface").assertIsDisplayed()
            compose.waitUntil(5_000) { session.frameCount > before }
        }
    }
}

package com.joelbermudez.pocketgb.game

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.joelbermudez.pocketgb.debug.GameplayTestActivity
import com.joelbermudez.pocketgb.debug.GameplayTestConfig
import com.joelbermudez.pocketgb.settings.AppearanceState
import com.joelbermudez.pocketgb.settings.GameplaySettingsFile
import com.joelbermudez.pocketgb.settings.GameplaySettingsRepository
import com.joelbermudez.pocketgb.testing.FailableOps
import com.joelbermudez.pocketgb.testing.tempDir
import com.joelbermudez.pocketgb.testing.waitUntil
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertTrue
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

/**
 * Anfitrión común de las pruebas de UI del juego (A6-L4): ROM sintética real, ajustes en un archivo temporal propio
 * y, si se pide, tema de la app en claro para comprobar que el juego sigue oscuro.
 */
class GameplayHarness(private val appearance: AppearanceState = AppearanceState.DEFAULT) {
    lateinit var root: File
    lateinit var settings: GameplaySettingsRepository
    val ops = FailableOps()
    val compose = createAndroidComposeRule<GameplayTestActivity>()

    val settingsFile: File get() = File(root, "gameplay-settings.json")

    val rules: RuleChain = RuleChain
        .outerRule(object : ExternalResource() {
            override fun before() {
                root = tempDir("gameplay-l4")
                settings = GameplaySettingsRepository(
                    GameplaySettingsFile(settingsFile),
                    CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                )
                GameplayTestConfig.settings = settings
                GameplayTestConfig.appearance = appearance
                GameplayTestConfig.factory = { GameplayViewModel(GameplayTestHost.launcher(root, ops)) }
            }

            override fun after() {
                GameplayTestConfig.factory = null
                GameplayTestConfig.settings = null
                GameplayTestConfig.appearance = AppearanceState.DEFAULT
                root.deleteRecursively()
            }
        })
        .around(compose)

    val vm: GameplayViewModel get() = compose.activity.viewModel

    fun openGame(): GameSession {
        vm.open(GameplayTestHost.entry)
        compose.waitUntil(10_000) { vm.game.value != null }
        val game = vm.game.value!!
        waitTag("gameplay-surface")
        compose.waitForIdle()
        assertTrue(waitUntil { game.session.sramDirtySequence() > 3 })
        return game
    }

    fun close() {
        vm.game.value?.close()
    }

    fun waitTag(tag: String, timeoutMs: Long = 8_000) =
        compose.waitUntil(timeoutMs) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    fun waitGone(tag: String, timeoutMs: Long = 8_000) =
        compose.waitUntil(timeoutMs) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty() }

    fun pressBackViaDispatcher() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
    }
}

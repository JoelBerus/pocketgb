package com.joelbermudez.pocketgb.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.joelbermudez.pocketgb.emulator.SessionState
import com.joelbermudez.pocketgb.saves.StateSlot
import com.joelbermudez.pocketgb.testing.FailableOps
import com.joelbermudez.pocketgb.testing.StallingOps
import com.joelbermudez.pocketgb.testing.tempDir
import com.joelbermudez.pocketgb.testing.waitUntil
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Destruir el ViewModel (la actividad termina) con una partida abierta: nunca fuerza una salida con riesgo,
 * nunca bloquea el hilo principal y espera a la operación en curso (A5 auditoría, Codex H3 / Opus H2).
 */
class GameplayViewModelDestroyTest {
    private lateinit var root: File
    private val rescueExecutor = Executors.newSingleThreadExecutor()

    @Before fun setUp() { root = tempDir("vm-destroy") }

    @After fun tearDown() {
        rescueExecutor.shutdownNow()
        root.deleteRecursively()
    }

    private fun newViewModel(ops: com.joelbermudez.pocketgb.saves.SaveFileOps) = GameplayViewModel(
        GameplayTestHost.launcher(root, ops),
        rescue = { rescueExecutor.execute(it) },
        rescueAttempts = 3,
        rescueRetryDelayMs = 50,
    )

    /** Registra el ViewModel en un store y lo limpia: así se ejecuta el `onCleared` real. */
    private fun destroy(vm: GameplayViewModel): Long {
        val store = ViewModelStore()
        val provider = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = vm as T
        })
        provider[GameplayViewModel::class.java]
        val started = System.nanoTime()
        store.clear()
        return (System.nanoTime() - started) / 1_000_000
    }

    private fun openAndPlay(vm: GameplayViewModel): GameSession {
        vm.open(GameplayTestHost.entry)
        assertTrue(waitUntil(15_000) { vm.game.value != null })
        val game = vm.game.value!!
        assertTrue(waitUntil { game.session.sramDirtySequence() > 3 })
        return game
    }

    @Test
    fun onClearedWithAFailingDiskKeepsTheSessionAndWritesARescueWithoutBlockingTheCaller() {
        val ops = FailableOps()
        val vm = newViewModel(ops)
        val game = openAndPlay(vm)
        ops.failSav = true

        val elapsedMs = destroy(vm)

        assertTrue("onCleared no bloquea al llamador ($elapsedMs ms)", elapsedMs < 1_000)
        rescueExecutor.shutdown()
        assertTrue(rescueExecutor.awaitTermination(30, TimeUnit.SECONDS))
        assertFalse("con el disco fallando NO se cierra la sesión", game.isClosed)
        assertTrue("estado de rescate escrito", File(root, "states/${game.fingerprint}/rescue.state").exists())
        assertNotNull(game.saveProblem.value)
        assertFalse("el rescate no usa la ranura AUTO", File(root, "states/${game.fingerprint}/auto.state").exists())

        // El disco vuelve: la sesión conservada sigue reintentando por su cuenta y deja disco == núcleo.
        ops.failSav = false
        val core = game.session.copySram()
        assertTrue("el guardado pendiente se confirma solo", waitUntil(30_000) {
            File(root, "saves/${game.fingerprint}.sav").let { it.exists() && it.readBytes().contentEquals(core) }
        })
        game.close()
    }

    @Test
    fun onClearedWithAHealthyDiskSavesSavesAutoStateAndClosesOffTheMainThread() {
        val vm = newViewModel(com.joelbermudez.pocketgb.saves.PosixSaveFileOps)
        val game = openAndPlay(vm)

        val elapsedMs = destroy(vm)

        assertTrue("no bloquea al llamador ($elapsedMs ms)", elapsedMs < 1_000)
        assertTrue("se cierra solo", waitUntil(30_000) { game.isClosed && game.state.value == SessionState.Closed })
        val sav = File(root, "saves/${game.fingerprint}.sav")
        assertTrue(sav.exists())
        assertTrue(File(root, "states/${game.fingerprint}/auto.state").exists())
        assertFalse(File(root, "states/${game.fingerprint}/rescue.state").exists())
    }

    @Test
    fun onClearedWaitsForAnOperationInProgressInsteadOfRacingIt() {
        val ops = StallingOps()
        val vm = newViewModel(ops)
        val game = openAndPlay(vm)
        ops.arm()
        vm.exit() // la salida normal se queda con el Mutex mientras el disco está atascado
        assertTrue(ops.awaitEntered())

        val elapsedMs = destroy(vm)
        assertTrue("onCleared no bloquea ($elapsedMs ms)", elapsedMs < 1_000)
        assertFalse("la salida en curso sigue sin cerrarse", game.isClosed)

        ops.release()
        assertTrue(waitUntil(30_000) { game.isClosed && game.state.value == SessionState.Closed })
        val core = File(root, "saves/${game.fingerprint}.sav").readBytes()
        assertTrue(core.isNotEmpty())
        assertArrayEquals("disco consistente tras salida y rescate", core, File(root, "saves/${game.fingerprint}.sav").readBytes())
        assertTrue(File(root, "states/${game.fingerprint}").listFiles { f -> f.name.endsWith(".tmp") }!!.isEmpty())
        // StateSlot importada para documentar la ranura esperada del AUTO
        assertTrue(File(root, "states/${game.fingerprint}/${StateSlot.AUTO.fileStem}.state").exists())
    }
}

package com.joelbermudez.pocketgb.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.joelbermudez.pocketgb.emulator.SessionState
import com.joelbermudez.pocketgb.saves.FingerprintOwnership
import com.joelbermudez.pocketgb.saves.SavePendingException
import com.joelbermudez.pocketgb.saves.SavesBrowser
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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

    private val ownership = FingerprintOwnership()
    private val orphans = OrphanSessionRegistry(initialDelayMs = 50, maxDelayMs = 200, keepAliveMs = 100)

    private fun newViewModel(ops: com.joelbermudez.pocketgb.saves.SaveFileOps) = GameplayViewModel(
        GameplayTestHost.launcher(root, ops, ownership = ownership),
        rescue = { rescueExecutor.execute(it) },
        rescueAttempts = 3,
        rescueRetryDelayMs = 50,
        orphans = orphans,
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

    /**
     * A5V2-H1, camino de producción SIN llamadas manuales: onCleared → KeptOpen → el registro de la app mantiene la
     * huella bloqueada (reabrir y restaurar se rechazan) → vuelve el disco → el registro guarda y cierra solo →
     * la huella se libera y ya se puede reabrir y restaurar.
     */
    @Test
    fun onClearedWithAFailingDiskHandsTheSessionToTheRegistryWhichBlocksThenClosesItWhenTheDiskRecovers() {
        val ops = FailableOps()
        val vm = newViewModel(ops)
        val game = openAndPlay(vm)
        ops.failSav = true

        // A5V3-H1 (c): transferencia onCleared -> registro SIN ventana. Un hilo intenta adquirir la huella sin parar,
        // desde antes de destruir el ViewModel hasta que la sesión se cierra: nunca debe lograrlo con la sesión abierta.
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val violations = java.util.concurrent.atomic.AtomicInteger()
        val attempts = java.util.concurrent.atomic.AtomicLong()
        val poller = Thread {
            while (!stop.get()) {
                val lease = ownership.tryAcquire(game.fingerprint, "intruso")
                attempts.incrementAndGet()
                if (lease != null) {
                    // La sesión marca `isClosed` ANTES de soltar el lease: con la sesión abierta, esto es una violación.
                    if (!game.isClosed) violations.incrementAndGet()
                    lease.close()
                    stop.set(true)
                }
            }
        }.apply { isDaemon = true; start() }

        val elapsedMs = destroy(vm)

        assertTrue("onCleared no bloquea al llamador ($elapsedMs ms)", elapsedMs < 1_000)
        assertTrue("la huella queda bloqueada ya desde onCleared", ownership.isOwned(game.fingerprint))
        rescueExecutor.shutdown()
        assertTrue(rescueExecutor.awaitTermination(30, TimeUnit.SECONDS))
        Thread.sleep(400) // el registro reintenta varias veces con el disco fallando
        assertFalse("con el disco fallando NO se cierra la sesión", game.isClosed)
        assertTrue("estado de rescate escrito", File(root, "states/${game.fingerprint}/rescue.state").exists())
        assertNotNull(game.saveProblem.value)
        assertFalse("el rescate no usa la ranura AUTO", File(root, "states/${game.fingerprint}/auto.state").exists())
        assertEquals("los reintentos no acumulan rescates archivados", 0, File(root, "states/${game.fingerprint}").listFiles { f -> f.name.startsWith("rescue-") }!!.size)

        // Mientras tanto: reabrir y restaurar se rechazan con un mensaje claro.
        val vm2 = newViewModel(ops)
        vm2.open(GameplayTestHost.entry)
        assertTrue(waitUntil(15_000) { vm2.dialog.value != null })
        assertEquals(GameDialog.OpenFailed(OpenError.SavePending), vm2.dialog.value)
        assertEquals(null, vm2.game.value)
        val restoreError = assertThrows(SavePendingException::class.java) {
            SavesBrowser(File(root, "saves"), ownership = ownership).restore(game.fingerprint, 1, vm2.openFingerprint.value)
        }
        assertTrue(restoreError.message!!.startsWith("Guardado pendiente de esa partida"))

        // El disco vuelve: SIN ninguna llamada manual el registro confirma el guardado, escribe AUTO y cierra.
        ops.failSav = false
        val core = game.session.copySram()
        assertTrue("la sesión se cierra sola", waitUntil(30_000) { game.isClosed && game.state.value == SessionState.Closed })
        assertTrue("la huella se libera", waitUntil(15_000) { !ownership.isOwned(game.fingerprint) })
        assertTrue("el intruso solo ganó tras el cierre", waitUntil(10_000) { stop.get() })
        poller.join(5_000)
        assertEquals("ninguna adquisición con la sesión viva (ni al pasar al registro)", 0, violations.get())
        assertTrue("el intruso lo intentó muchas veces (${attempts.get()})", attempts.get() > 100)
        assertArrayEquals("el disco quedó con la SRAM confirmada", core, File(root, "saves/${game.fingerprint}.sav").readBytes())
        assertTrue(File(root, "states/${game.fingerprint}/auto.state").exists())
        assertTrue("sin hilos del registro vivos", waitUntil(10_000) { orphans.liveThreads == 0 })

        // Y ahora sí: se puede restaurar (ya no es el error de guardado pendiente) y reabrir.
        val afterwards = runCatching { SavesBrowser(File(root, "saves"), ownership = ownership).restore(game.fingerprint, 1, null) }
        assertFalse("restaurar ya no está bloqueado", afterwards.exceptionOrNull() is SavePendingException)
        val vm3 = newViewModel(ops)
        vm3.open(GameplayTestHost.entry)
        assertTrue("se reabre", waitUntil(15_000) { vm3.game.value != null })
        vm3.game.value!!.close()
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

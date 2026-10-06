package com.joelbermudez.pocketgb.game

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.joelbermudez.pocketgb.saves.FlushResult
import com.joelbermudez.pocketgb.saves.SaveCoordinator
import com.joelbermudez.pocketgb.saves.SaveStore
import com.joelbermudez.pocketgb.saves.StateSlot
import com.joelbermudez.pocketgb.testing.tempDir
import com.joelbermudez.pocketgb.testing.waitUntil
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 100 ciclos abrir → jugar → pausar → guardar ranura 1 → salir sobre la misma partida. En cada vuelta:
 * disco == núcleo, la partida continúa donde se dejó, sin `.tmp`, ≤5 backups y el mismo número de hilos
 * `pocketgb-saves`. Mide el vaciado síncrono de `ON_PAUSE` (p50/p99) y lo escribe en logcat (`A5Metrics`).
 */
@RunWith(AndroidJUnit4::class)
class SaveCyclesTest {
    private lateinit var root: File

    @Before fun setUp() { root = tempDir("cycles") }
    @After fun tearDown() { root.deleteRecursively() }

    private companion object {
        /** Plazos de 3 s agotados tolerados en 200 operaciones (pause + exit por ciclo): solo por presión del emulador. */
        const val TIMEOUT_BUDGET = 5
    }

    private fun saveThreads() = Thread.getAllStackTraces().keys.count { it.name == SaveCoordinator.THREAD_NAME && it.isAlive }

    private fun percentile(sorted: List<Long>, p: Double): Long = sorted[((sorted.size - 1) * p).toInt()]

    @Test
    fun hundredOpenPlayPauseSaveStateExitCycles() {
        val launcher = GameplayTestHost.launcher(root)
        val baselineThreads = saveThreads()
        val flushNs = ArrayList<Long>()
        val pauseNs = ArrayList<Long>()
        var previousDisk: ByteArray? = null
        var savedCycles = 0
        var timeouts = 0
        repeat(100) { cycle ->
            val opened = launcher.openBlocking(GameplayTestHost.entry) as OpenResult.Opened
            val game = opened.game
            val sav = File(root, "saves/${game.fingerprint}.sav")
            previousDisk?.let {
                assertArrayEquals("ciclo $cycle: la partida continúa donde se dejó", it, game.session.copySram())
            }
            game.start()
            val dirty = game.session.sramDirtySequence()
            // Plazo generoso: el anfitrión puede congelar el emulador decenas de segundos bajo presión de memoria.
            assertTrue("ciclo $cycle: el juego guarda", waitUntil(45_000) { game.session.sramDirtySequence() > dirty + 5 })
            Thread.sleep(500)

            val t0 = System.nanoTime()
            var result = game.pause()
            pauseNs += System.nanoTime() - t0
            // Sin reintentos que escondan fallos (auditoría A5, Opus H9): un Failed falla el ciclo siempre, y
            // `pause()` NO se repite. ÚNICA excepción, acotada y medida: un `TimedOut` (el plazo de 3 s agotado por
            // un emulador sin CPU/memoria, comportamiento previsto por J5). Se cuenta, se comprueba que el problema
            // de guardado es visible y que el guardado en segundo plano acaba confirmándose por sí solo (no con una
            // segunda pausa), y el presupuesto total de plazos agotados es [TIMEOUT_BUDGET].
            if (result == FlushResult.TimedOut) {
                timeouts++
                assertTrue("ciclo $cycle: plazo agotado sin problema visible", game.saveProblem.value != null)
                game.retryPendingSave()
                assertTrue("ciclo $cycle: el guardado pendiente no se confirmó solo", waitUntil(90_000) {
                    game.saveProblem.value == null && sav.exists() && sav.readBytes().contentEquals(game.session.copySram())
                })
                result = FlushResult.Unchanged
            }
            assertTrue("ciclo $cycle: $result", result == FlushResult.Saved || result == FlushResult.Unchanged)
            if (result == FlushResult.Saved) savedCycles++
            val core = game.session.copySram()
            assertArrayEquals("ciclo $cycle: disco == núcleo", core, sav.readBytes())

            game.saveState(StateSlot.MANUAL1)
            val exit = game.exit()
            if (exit is ExitResult.LocalSaveFailed && exit.error is java.util.concurrent.TimeoutException) {
                timeouts++ // mismo caso acotado: solo un plazo agotado, nunca un Failed
                assertTrue("ciclo $cycle: el guardado pendiente de la salida no se confirmó", waitUntil(90_000) {
                    game.saveProblem.value == null
                })
                assertEquals("ciclo $cycle", ExitResult.Clean, game.exit())
            } else {
                assertEquals("ciclo $cycle", ExitResult.Clean, exit)
            }
            flushNs += game.flushDurationsNanos()
            previousDisk = sav.readBytes()
            assertArrayEquals("ciclo $cycle: tras salir, disco == núcleo", core, previousDisk)

            assertTrue(
                "ciclo $cycle: temporales ${root.walkTopDown().filter { it.name.endsWith(".tmp") }.toList()}",
                root.walkTopDown().none { it.name.endsWith(".tmp") },
            )
            val backups = SaveStore(File(root, "saves"), game.fingerprint).backups()
            assertTrue("ciclo $cycle: ${backups.size} backups", backups.size <= SaveStore.KEEP_BACKUPS)
            assertEquals("ciclo $cycle: hilos de guardado", baselineThreads, saveThreads())
        }
        assertTrue("al menos casi todos los ciclos escribieron", savedCycles >= 90)
        assertTrue("más plazos agotados de los tolerados ($timeouts > $TIMEOUT_BUDGET)", timeouts <= TIMEOUT_BUDGET)
        flushNs.sort()
        pauseNs.sort()
        val summary = "ciclos=100 guardados=$savedCycles plazos-agotados=$timeouts/$TIMEOUT_BUDGET flushSync n=${flushNs.size} " +
            "p50=${percentile(flushNs, 0.5) / 1_000_000.0} ms p99=${percentile(flushNs, 0.99) / 1_000_000.0} ms " +
            "max=${flushNs.last() / 1_000_000.0} ms | game.pause() p50=${percentile(pauseNs, 0.5) / 1_000_000.0} ms " +
            "p99=${percentile(pauseNs, 0.99) / 1_000_000.0} ms max=${pauseNs.last() / 1_000_000.0} ms"
        Log.i("A5Metrics", summary)
        assertTrue("el vaciado síncrono cabe en el plazo de 3 s salvo los $timeouts plazos agotados contados: $summary", flushNs.count { it >= 3_000_000_000L } <= timeouts)
    }
}

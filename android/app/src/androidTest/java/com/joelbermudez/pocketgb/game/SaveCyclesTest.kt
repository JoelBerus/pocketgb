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
        var exitRetries = 0
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
            // Un plazo de 3 s agotado (anfitrión congelado) es el comportamiento previsto (J5): queda pendiente y
            // el siguiente vaciado lo resuelve. Se cuenta, y solo falla si no se resuelve.
            var attempts = 0
            while (result == FlushResult.TimedOut && attempts++ < 20) {
                timeouts++
                result = game.pause()
            }
            assertTrue("ciclo $cycle: $result", result == FlushResult.Saved || result == FlushResult.Unchanged)
            if (result == FlushResult.Saved) savedCycles++
            val core = game.session.copySram()
            assertArrayEquals("ciclo $cycle: disco == núcleo", core, sav.readBytes())

            game.saveState(StateSlot.MANUAL1)
            var exit = game.exit()
            var exitAttempts = 0
            while (exit is ExitResult.LocalSaveFailed && exitAttempts++ < 20) {
                exitRetries++ // nunca se cierra limpio con un guardado pendiente: se reintenta, como el diálogo
                exit = game.exit()
            }
            assertEquals("ciclo $cycle", ExitResult.Clean, exit)
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
        flushNs.sort()
        pauseNs.sort()
        val summary = "ciclos=100 guardados=$savedCycles plazos-agotados=$timeouts reintentos-de-salida=$exitRetries flushSync n=${flushNs.size} " +
            "p50=${percentile(flushNs, 0.5) / 1_000_000.0} ms p99=${percentile(flushNs, 0.99) / 1_000_000.0} ms " +
            "max=${flushNs.last() / 1_000_000.0} ms | game.pause() p50=${percentile(pauseNs, 0.5) / 1_000_000.0} ms " +
            "p99=${percentile(pauseNs, 0.99) / 1_000_000.0} ms max=${pauseNs.last() / 1_000_000.0} ms"
        Log.i("A5Metrics", summary)
        assertTrue("el vaciado síncrono cabe en el plazo de 3 s: $summary", flushNs.last() < 3_000_000_000L)
    }
}

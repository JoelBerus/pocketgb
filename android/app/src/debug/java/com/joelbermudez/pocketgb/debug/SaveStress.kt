package com.joelbermudez.pocketgb.debug

import android.content.Context
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.joelbermudez.pocketgb.emulator.CoreBridge
import com.joelbermudez.pocketgb.emulator.EmulatorSession
import com.joelbermudez.pocketgb.game.GameLauncher
import com.joelbermudez.pocketgb.game.OpenResult
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.library.RomSource
import com.joelbermudez.pocketgb.saves.FlushResult
import com.joelbermudez.pocketgb.saves.LaunchMode
import com.joelbermudez.pocketgb.saves.SaveSizes
import com.joelbermudez.pocketgb.saves.SaveStore
import com.joelbermudez.pocketgb.saves.SramFlushPolicy
import com.joelbermudez.pocketgb.saves.StateSlot
import com.joelbermudez.pocketgb.saves.StateStore
import java.io.File
import java.util.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Solo Debug (excluido de Release): prueba de cierre forzado de A5 (`tools/android-save-kill-test.sh`).
 *
 * - `save-stress`: abre la ROM contador por el camino real (lanzador, `SaveCoordinator`, escritura atómica) con
 *   un debounce de 20 ms (muchísimas escrituras con rotación de backups) y, en paralelo, pausa/reanuda y
 *   guarda estados al azar, hasta que el script mata el proceso. A9: abre con «Continuar» exacto (si el estado AUTO
 *   no vale, con «Jugar desde el inicio», como la app) y guarda el AUTO de segundo plano al azar: si la continuación
 *   cargara una partida vieja, el contador retrocedería y `save-verify` lo detectaría.
 * - `save-verify`: en el proceso nuevo, tras `recoverOrphans`, comprueba el invariante y escribe una línea
 *   `SAVE-VERIFY OK|FAIL <detalle>` en logcat (etiqueta [TAG]).
 */
object SaveStress {
    const val TAG = "PocketGBStress"

    private val rom: ByteArray = DebugSyntheticRom.sramCounter16()

    private val entry = RomEntry(
        id = "Contador16.gb",
        uri = "content://stress/Contador16.gb",
        fileName = "Contador16.gb",
        title = "A5 CONTADOR16",
        console = com.joelbermudez.pocketgb.library.RomConsole.GB,
        sizeBytes = rom.size.toLong(),
        headerChecksumOk = true,
        problem = null,
    )

    private fun savesDir(context: Context) = File(context.filesDir, "saves")
    private fun statesRoot(context: Context) = File(context.filesDir, "states")

    /** Último contador confirmado en disco por un vaciado correcto (lo escribe `stress`, lo exige `verify`). */
    private fun confirmedFile(context: Context) = File(context.filesDir, "stress-confirmed.txt")

    /** Contador de 16 bits (little endian) de un `.sav` de la ROM [rom]. */
    private fun counterOf(data: ByteArray): Int = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)

    private fun fingerprint(): String = CoreBridge().use { it.loadRom(rom).fingerprintHex }

    /** Corre hasta que el proceso muere. */
    fun stress(context: Context) {
        val fp = fingerprint()
        val store = SaveStore(savesDir(context), fp)
        val valid = SaveSizes.validSizes(hasRtc = false, sramBytes = 8192)
        store.recoverOrphans(valid)
        // Una partida previa de verdad: el invariante ".sav presente" vale desde antes de la primera escritura.
        if (store.load() == null) {
            store.save(ByteArray(8192))
            confirmedFile(context).delete() // partida nueva: el contador vuelve a 0 y lo anotado ya no vale
        }

        val launcher = GameLauncher(
            roms = RomSource { _, _ -> rom },
            savesDirectory = savesDir(context),
            statesRoot = statesRoot(context),
            policy = { SramFlushPolicy({ System.nanoTime() / 1_000_000 }, debounceMs = 20, safetyNetMs = 60_000) },
        )
        val resume = launcher.openBlocking(entry, mode = LaunchMode.RESUME)
        val opened = resume as? OpenResult.Opened
            ?: launcher.openBlocking(entry) as? OpenResult.Opened
            ?: run {
                Log.e(TAG, "SAVE-STRESS ERROR no se pudo abrir")
                return
            }
        val mode = if (resume is OpenResult.Opened) "resumed" else "fresh(${(resume as? OpenResult.Failed)?.error})"
        val game = opened.game
        game.start()
        Log.i(TAG, "SAVE-STRESS READY fp=${fp.take(8)} mode=$mode")
        val random = Random()
        var rounds = 0
        while (true) {
            Thread.sleep(30L + random.nextInt(220))
            val flush = game.pause()
            if (flush == FlushResult.Saved || flush == FlushResult.Unchanged) {
                // Confirmado: el disco ya tiene al menos este contador. Se anota ANTES de seguir y se registra en logcat.
                store.load()?.let { disk ->
                    val counter = counterOf(disk)
                    confirmedFile(context).writeText(counter.toString())
                    Log.i(TAG, "SAVE-STRESS CONFIRMED $counter")
                }
            }
            if (rounds % 3 == 0) runCatching { game.saveState(StateSlot.MANUAL1) }
            // A9: el AUTO de segundo plano (`ON_STOP`): con la sesión aparcada y tras el vaciado.
            if (rounds % 4 == 1) runCatching { game.saveAutoStateIfParked() }
            Thread.sleep(random.nextInt(15).toLong())
            game.resume()
            rounds++
        }
    }

    /** @return "OK ..." o "FAIL ...". */
    fun verify(context: Context): String {
        val fp = fingerprint()
        val dir = savesDir(context)
        val store = SaveStore(dir, fp)
        val valid = SaveSizes.validSizes(hasRtc = false, sramBytes = 8192)
        store.recoverOrphans(valid)

        val problems = ArrayList<String>()
        val sav = store.saveFile
        val data = if (sav.exists()) sav.readBytes() else null
        if (data == null) problems += ".sav ausente"
        else if (data.size !in valid) problems += ".sav de ${data.size} bytes (válidos: $valid)"
        else if (data.drop(2).any { it != 0.toByte() }) problems += ".sav con contenido incoherente (la ROM contador solo escribe \$A000-\$A001)"
        else {
            // El contador del disco no puede estar por detrás del último confirmado (aritmética módulo 65 536 con
            // ventana de media vuelta: tolera la vuelta, rechaza el retroceso).
            val confirmed = confirmedFile(context).takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()
            if (confirmed != null) {
                val ahead = (counterOf(data) - confirmed) and 0xFFFF
                if (ahead >= 0x8000) problems += "el contador del disco (${counterOf(data)}) retrocedió respecto al último confirmado ($confirmed)"
            }
        }

        val tmps = dir.walkTopDown().filter { it.isFile && it.name.endsWith(".tmp") }.map { it.name }.toList()
        if (tmps.isNotEmpty()) problems += "temporales tras recoverOrphans: $tmps"

        val backups = store.backups()
        if (backups.size > SaveStore.KEEP_BACKUPS) problems += "${backups.size} backups (>5)"
        for (b in backups) {
            val file = store.backupFile(b.index)
            if (file.length().toInt() !in valid) problems += "backup ${b.index} de ${file.length()} bytes"
        }

        if (data != null && data.size in valid) {
            try {
                EmulatorSession().use { session ->
                    session.load(rom, 0L)
                    session.loadSram(data)
                    // Un estado a medias nunca debe ser visible: o está completo o no existe (también el AUTO y el AUTO
                    // obsoleto apartado de A9).
                    val states = StateStore(statesRoot(context), fp)
                    val files = (listOf(states.stateFile(StateSlot.MANUAL1), states.stateFile(StateSlot.AUTO)) + states.obsoleteAutoFiles())
                        .filter { it.exists() }
                    if (files.isNotEmpty()) {
                        session.start()
                        session.pause()
                        for (file in files) session.loadStateRaw(file.readBytes())
                    }
                }
            } catch (error: Exception) {
                problems += "el núcleo rechazó la partida o el estado: ${error.message}"
            }
        }
        val statesDir = File(statesRoot(context), fp)
        val stateTmpFound = statesDir.walkTopDown().count { it.name.endsWith(".tmp") }
        // Como al abrir un juego: la recuperación borra los temporales huérfanos de los estados; después no puede quedar ninguno.
        StateStore(statesRoot(context), fp).recoverOrphans()
        val stateTmps = statesDir.walkTopDown().count { it.name.endsWith(".tmp") }
        val confirmedNow = confirmedFile(context).takeIf { it.exists() }?.readText()?.trim()
        val summary = "bytes=${data?.size} counter=${data?.takeIf { it.size >= 2 }?.let(::counterOf)} confirmed=$confirmedNow " +
            "backups=${backups.size} stateTmpFound=$stateTmpFound stateTmpOrphans=$stateTmps"
        return if (problems.isEmpty()) "OK $summary" else "FAIL ${problems.joinToString("; ")} | $summary"
    }
}

@Composable
internal fun SaveStressScreen() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                SaveStress.stress(context)
            } catch (error: Throwable) {
                Log.e(SaveStress.TAG, "SAVE-STRESS ERROR ${error.message}", error)
            }
        }
    }
    Text("save-stress")
}

@Composable
internal fun SaveVerifyScreen() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) {
            try {
                SaveStress.verify(context)
            } catch (error: Throwable) {
                "FAIL excepción: ${error.message}"
            }
        }
        Log.i(SaveStress.TAG, "SAVE-VERIFY $result")
        (context as? ComponentActivity)?.finish()
    }
    Text("save-verify")
}

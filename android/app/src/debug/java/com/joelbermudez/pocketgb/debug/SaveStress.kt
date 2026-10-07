package com.joelbermudez.pocketgb.debug

import android.content.Context
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.joelbermudez.pocketgb.emulator.Console
import com.joelbermudez.pocketgb.emulator.CoreBridge
import com.joelbermudez.pocketgb.emulator.EmulatorSession
import com.joelbermudez.pocketgb.emulator.GbaOptions
import com.joelbermudez.pocketgb.emulator.GbaRtc
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
 * - N8: `save-stress-gba` / `save-verify-gba` hacen lo mismo con una ROM de GBA sintética (contador de 16 bits por
 *   fotograma en la SRAM de 32 KiB) con el RTC forzado: el `.sav` es el medio + 16 B del reloj (32 784 B), como iOS.
 */
object SaveStress {
    const val TAG = "PocketGBStress"

    /** Lo que cambia entre la prueba de Game Boy y la de Game Boy Advance. */
    class Profile(
        val console: Console,
        val rom: ByteArray,
        val fileName: String,
        val title: String,
        /** Bytes del medio (la RAM del cartucho): el contador vive en los dos primeros. */
        val mediaBytes: Int,
        val validSizes: Set<Int>,
        /** Tamaño de la partida inicial (con el pie del RTC en GBA). */
        val initialSize: Int,
        val gbaOptions: GbaOptions = GbaOptions(),
        val confirmedName: String,
    )

    val GB = Profile(
        console = Console.GB,
        rom = DebugSyntheticRom.sramCounter16(),
        fileName = "Contador16.gb",
        title = "A5 CONTADOR16",
        mediaBytes = 8192,
        validSizes = SaveSizes.validSizes(hasRtc = false, sramBytes = 8192),
        initialSize = 8192,
        confirmedName = "stress-confirmed.txt",
    )

    val GBA = Profile(
        console = Console.GBA,
        rom = DebugSyntheticGbaRom.sramCounter16(),
        fileName = "Contador16.gba",
        title = "PGBA CONT16",
        mediaBytes = 32768,
        validSizes = SaveSizes.gbaValidSizes(32768, hasRtc = true),
        initialSize = 32768 + com.joelbermudez.pocketgb.emulator.GBA_RTC_BYTES,
        gbaOptions = GbaOptions(rtc = GbaRtc.ON, useBios = false),
        confirmedName = "stress-confirmed-gba.txt",
    )

    private fun Profile.entry() = RomEntry(
        id = fileName,
        uri = "content://stress/$fileName",
        fileName = fileName,
        title = title,
        console = if (console == Console.GBA) com.joelbermudez.pocketgb.library.RomConsole.GBA else com.joelbermudez.pocketgb.library.RomConsole.GB,
        sizeBytes = rom.size.toLong(),
        headerChecksumOk = true,
        problem = null,
    )

    private fun savesDir(context: Context) = File(context.filesDir, "saves")
    private fun statesRoot(context: Context) = File(context.filesDir, "states")

    /** Último contador confirmado en disco por un vaciado correcto (lo escribe `stress`, lo exige `verify`). */
    private fun confirmedFile(context: Context, profile: Profile) = File(context.filesDir, profile.confirmedName)

    /** Contador de 16 bits (little endian) de un `.sav` de la ROM contador. */
    private fun counterOf(data: ByteArray): Int = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)

    private fun fingerprint(profile: Profile): String = CoreBridge(profile.console).use {
        when (profile.console) {
            Console.GB -> it.loadRom(profile.rom)
            Console.GBA -> it.loadGbaRom(profile.rom, profile.gbaOptions)
        }.fingerprintHex
    }

    /** Corre hasta que el proceso muere. */
    fun stress(context: Context, profile: Profile = GB) {
        val rom = profile.rom
        val entry = profile.entry()
        val fp = fingerprint(profile)
        val store = SaveStore(savesDir(context), fp)
        val valid = profile.validSizes
        store.recoverOrphans(valid)
        // Una partida previa de verdad: el invariante ".sav presente" vale desde antes de la primera escritura.
        if (store.load() == null) {
            store.save(ByteArray(profile.initialSize))
            confirmedFile(context, profile).delete() // partida nueva: el contador vuelve a 0 y lo anotado ya no vale
        }

        val launcher = GameLauncher(
            roms = RomSource { _, _ -> rom },
            savesDirectory = savesDir(context),
            statesRoot = statesRoot(context),
            policy = { SramFlushPolicy({ System.nanoTime() / 1_000_000 }, debounceMs = 20, safetyNetMs = 60_000) },
            gbaOptionsFor = { profile.gbaOptions },
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
                    confirmedFile(context, profile).writeText(counter.toString())
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
    fun verify(context: Context, profile: Profile = GB): String {
        val rom = profile.rom
        val fp = fingerprint(profile)
        val dir = savesDir(context)
        val store = SaveStore(dir, fp)
        val valid = profile.validSizes
        store.recoverOrphans(valid)

        val problems = ArrayList<String>()
        val sav = store.saveFile
        val data = if (sav.exists()) sav.readBytes() else null
        if (data == null) problems += ".sav ausente"
        else if (data.size !in valid) problems += ".sav de ${data.size} bytes (válidos: $valid)"
        else if (data.copyOfRange(2, profile.mediaBytes).any { it != 0.toByte() }) {
            problems += ".sav con contenido incoherente (la ROM contador solo escribe sus dos primeros bytes)"
        }
        else {
            // El contador del disco no puede estar por detrás del último confirmado (aritmética módulo 65 536 con
            // ventana de media vuelta: tolera la vuelta, rechaza el retroceso).
            val confirmed = confirmedFile(context, profile).takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()
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
                EmulatorSession(profile.console).use { session ->
                    when (profile.console) {
                        Console.GB -> session.load(rom, 0L)
                        Console.GBA -> session.loadGba(rom, 0L, profile.gbaOptions)
                    }
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
        val confirmedNow = confirmedFile(context, profile).takeIf { it.exists() }?.readText()?.trim()
        val summary = "bytes=${data?.size} counter=${data?.takeIf { it.size >= 2 }?.let(::counterOf)} confirmed=$confirmedNow " +
            "backups=${backups.size} stateTmpFound=$stateTmpFound stateTmpOrphans=$stateTmps"
        return if (problems.isEmpty()) "OK $summary" else "FAIL ${problems.joinToString("; ")} | $summary"
    }
}

@Composable
internal fun SaveStressScreen(profile: SaveStress.Profile = SaveStress.GB) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                SaveStress.stress(context, profile)
            } catch (error: Throwable) {
                Log.e(SaveStress.TAG, "SAVE-STRESS ERROR ${error.message}", error)
            }
        }
    }
    Text("save-stress")
}

@Composable
internal fun SaveVerifyScreen(profile: SaveStress.Profile = SaveStress.GB) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) {
            try {
                SaveStress.verify(context, profile)
            } catch (error: Throwable) {
                "FAIL excepción: ${error.message}"
            }
        }
        Log.i(SaveStress.TAG, "SAVE-VERIFY $result")
        (context as? ComponentActivity)?.finish()
    }
    Text("save-verify")
}

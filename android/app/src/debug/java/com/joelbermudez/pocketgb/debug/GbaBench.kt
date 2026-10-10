package com.joelbermudez.pocketgb.debug

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.joelbermudez.pocketgb.emulator.Console
import com.joelbermudez.pocketgb.emulator.CoreBridge
import com.joelbermudez.pocketgb.emulator.EmulatorSession
import com.joelbermudez.pocketgb.emulator.SessionState
import com.joelbermudez.pocketgb.library.ContentResolverRomSource
import com.joelbermudez.pocketgb.library.LibraryFolderStore
import com.joelbermudez.pocketgb.library.LibraryScanner
import com.joelbermudez.pocketgb.library.SafDocumentTree
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Solo Debug (N8): rendimiento de un juego de GBA **en la app** (mismo `libpocketgb.so`, Debug con -O2). Dos medidas:
 *
 * 1. **Núcleo**: ms por fotograma de `gba_run_frame` sin pausas (600 fotogramas tras 30 de calentamiento).
 * 2. **Sesión real** a ×4 (hilo nativo, audio, vídeo a una superficie inexistente) durante 5 s: fotogramas por segundo; a
 *    ×4 el tope es 4 × 59,73 ≈ 239 fps, así que llegar a él quiere decir que sobra margen para 60 fps.
 *
 * El ROM sale de `files/<rom>` (`--es rom bench.gba`, copiado con `run-as`) o de la carpeta de la biblioteca por nombre
 * de archivo (`--es library "Kirby - Nightmare in Dream Land.gba"`, para el teléfono de Joel: el ROM nunca sale de su
 * carpeta). Resultado en logcat: `adb logcat -s PocketGBBench`.
 */
internal object GbaBench {
    const val TAG = "PocketGBBench"

    fun romBytes(context: Context, rom: String?, library: String?): Pair<String, ByteArray> {
        if (rom != null) {
            require(!rom.contains('/') && rom.endsWith(".gba", ignoreCase = true)) { "rom: solo un nombre .gba de files/" }
            return rom to File(context.filesDir, rom).readBytes()
        }
        requireNotNull(library) { "falta --es rom o --es library" }
        val uri = LibraryFolderStore(context).currentUri() ?: error("no hay carpeta de biblioteca elegida")
        val tree = SafDocumentTree(context.contentResolver, Uri.parse(uri))
        val entry = LibraryScanner.scan(tree).firstOrNull { it.fileName == library } ?: error("no está en la biblioteca: $library")
        return library to ContentResolverRomSource(context.contentResolver).read(entry.uri, LibraryScanner.MAX_GBA_ROM_BYTES.toInt())
    }

    fun run(context: Context, rom: String?, library: String?): String {
        val (name, bytes) = romBytes(context, rom, library)
        val core = CoreBridge(Console.GBA).use { bridge ->
            bridge.loadGbaRom(bytes)
            repeat(30) { bridge.runFrame() }
            val begin = SystemClock.elapsedRealtimeNanos()
            repeat(FRAMES) { bridge.runFrame() }
            (SystemClock.elapsedRealtimeNanos() - begin) / 1_000_000.0 / FRAMES
        }
        val fps = EmulatorSession(Console.GBA).use { session ->
            session.loadGba(bytes, System.currentTimeMillis() / 1000)
            session.setVolume(0f)
            session.setSpeed(4)
            session.start()
            SystemClock.sleep(1_000)
            val f0 = session.frameCount
            val t0 = SystemClock.elapsedRealtimeNanos()
            SystemClock.sleep(5_000)
            val frames = session.frameCount - f0
            val seconds = (SystemClock.elapsedRealtimeNanos() - t0) / 1e9
            if (session.state.value == SessionState.Running) session.pause()
            session.stop()
            frames / seconds
        }
        return "$name ${android.os.Build.MODEL} ${android.os.Build.SUPPORTED_ABIS.first()} núcleo=${"%.3f".format(core)} ms/frame " +
            "sesión×4=${"%.1f".format(fps)} fps (tope 239)"
    }

    private const val FRAMES = 600
}

@Composable
internal fun GbaBenchScreen(rom: String?, library: String?) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("Midiendo…") }
    LaunchedEffect(Unit) {
        text = withContext(Dispatchers.Default) {
            try {
                "GBA-BENCH " + GbaBench.run(context, rom, library)
            } catch (error: Throwable) {
                "GBA-BENCH ERROR ${error.message}"
            }
        }
        Log.i(GbaBench.TAG, text)
    }
    Text(text)
}

package com.joelbermudez.pocketgb.travel

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.emulator.Console
import com.joelbermudez.pocketgb.library.LibraryPreferencesData
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.progress.ProgressService
import com.joelbermudez.pocketgb.saves.SavesIndex
import java.io.File
import java.io.IOException

/**
 * N7b/N7c · lo que la interfaz necesita para que la partida viaje: construir el [ImportTarget] y el [SaveExporter.Info]
 * de un juego, leer un documento recibido (con tope), compartir por `FileProvider` y escribir en un documento elegido.
 * Sin red (regla 5): solo archivos.
 */
class TravelService(private val context: Context) {
    val savesDirectory = File(context.filesDir, "saves")
    private val statesRoot = File(context.filesDir, "states")
    private val momentsRoot = File(context.filesDir, "moments")

    val importer = SaveImporter(savesDirectory, statesRoot, momentsRoot, conflictName = { device ->
        context.getString(R.string.n7_conflict_moment, device)
    })
    val exporter = SaveExporter(savesDirectory, statesRoot)

    fun target(entry: RomEntry, fingerprint: String): ImportTarget {
        val record = SavesIndex(savesDirectory).load()[fingerprint]
        return ImportTarget(
            fingerprint = fingerprint,
            console = consoleName(entry),
            // Sin registro el juego no se abrió nunca: no se sabe si tiene batería ni sus tamaños, el importador lo rechaza.
            hasBattery = record == null || record.validSizes != null,
            validSizes = record?.validSizes?.toSet(),
        )
    }

    fun exportInfo(entry: RomEntry, fingerprint: String, prefs: LibraryPreferencesData): SaveExporter.Info {
        val progress = try { ProgressService.shared(context).store.load(fingerprint) } catch (_: Exception) { null }
        return SaveExporter.Info(
            fingerprint = fingerprint,
            console = consoleName(entry),
            deviceName = deviceName(),
            coreVersion = appVersion(),
            title = entry.title,
            alias = prefs.aliasOf(entry),
            tags = prefs.tagsOf(entry).takeIf { it.isNotEmpty() },
            playTimeMs = progress?.playTimeMs?.takeIf { it > 0 },
            milestones = progress?.milestones?.takeIf { it.isNotEmpty() }?.map { PgbmMeta.Milestone(it.id, it.title, it.done) },
        )
    }

    /** N7c: la bandeja `PocketGB/Intercambio/` de la carpeta de la biblioteca, o `null` sin carpeta concedida. */
    fun exchange(): ExchangeFolder? {
        val store = com.joelbermudez.pocketgb.library.LibraryFolderStore(context)
        val uri = store.currentUri() ?: return null
        if (!store.hasPersistedPermission()) return null
        return ExchangeFolder(context.contentResolver, uri.toUri())
    }

    val inbox = ExchangeInbox(File(context.filesDir, "exchange-seen.json"))

    fun deviceName(): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL

    private fun appVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    /** Lee un documento recibido como mucho [PgbmResult.MAX_TOTAL] + 1 bytes (entrada no confiable). */
    fun read(uri: Uri): ByteArray {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("No se pudo abrir el archivo")
        input.use { stream ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (out.size() <= PgbmResult.MAX_TOTAL) {
                val n = stream.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            if (out.size() > PgbmResult.MAX_TOTAL) throw IOException("El archivo es demasiado grande")
            return out.toByteArray()
        }
    }

    /** Escribe [data] en un documento elegido con `ACTION_CREATE_DOCUMENT` (truncando). */
    fun writeTo(uri: Uri, data: ByteArray) {
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("No se pudo escribir el archivo")
        out.use { it.write(data); it.flush() }
    }

    /**
     * Comparte [data] con la hoja del sistema (`ACTION_SEND`) desde `cache/exports/` a través del `FileProvider` de
     * AndroidX (permiso de lectura temporal, nada público). La carpeta se vacía en cada exportación.
     */
    fun share(data: ByteArray, fileName: String, chooserTitle: String) {
        val dir = File(context.cacheDir, "exports")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, fileName)
        file.writeBytes(data)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME_BINARY
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(fileName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        const val MIME_BINARY = "application/octet-stream"
        fun consoleName(entry: RomEntry) = if (entry.console.core == Console.GBA) "gba" else "gb"
    }
}

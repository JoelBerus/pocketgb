package com.joelbermudez.pocketgb.library

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.joelbermudez.pocketgb.emulator.CoreBridge
import com.joelbermudez.pocketgb.emulator.RomInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** Lee ROMs por `ContentResolver`; nunca los modifica ni los copia. */
class ContentResolverRomSource(private val resolver: ContentResolver) : RomSource {
    override fun read(uri: String, limit: Int): ByteArray {
        val parsed = Uri.parse(uri)
        val remote = ProviderLocality.isRemote(parsed)
        try {
            val stream = resolver.openInputStream(parsed) ?: throw DocumentReadException(remote)
            stream.use { input ->
                val out = ByteArrayOutputStream(minOf(limit, 64 * 1024))
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (total < limit) {
                    val read = input.read(buffer, 0, minOf(buffer.size, limit - total))
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    total += read
                }
                return out.toByteArray()
            }
        } catch (error: DocumentReadException) {
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            throw DocumentReadException(remote, error)
        } catch (error: SecurityException) {
            throw DocumentReadException(remote = false, cause = error)
        } catch (error: RuntimeException) {
            // Un proveedor mal portado puede lanzar IllegalStateException, etc.
            throw DocumentReadException(remote, error)
        }
    }
}

/** Metadatos con el núcleo real: se crea una instancia efímera y se cierra al terminar. */
class CoreRomInspector : RomInspector {
    override fun inspect(rom: ByteArray): RomInfo = CoreBridge().use { it.loadRom(rom) }
}

class LibraryViewModelFactory(context: Context) : ViewModelProvider.Factory {
    private val appContext = context.applicationContext

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(LibraryViewModel::class.java)) { "ViewModel desconocido: $modelClass" }
        val resolver = appContext.contentResolver
        return LibraryViewModel(
            folders = LibraryFolderStore(appContext),
            openTree = { SafDocumentTree(resolver, Uri.parse(it)) },
            roms = ContentResolverRomSource(resolver),
            inspector = CoreRomInspector(),
            preferencesFile = LibraryPreferencesFile(File(appContext.filesDir, "library/preferences.json")),
        ) as T
    }
}

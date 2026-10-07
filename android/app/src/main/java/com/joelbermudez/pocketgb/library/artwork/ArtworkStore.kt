package com.joelbermudez.pocketgb.library.artwork

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.joelbermudez.pocketgb.library.DefaultPreferencesFileOps
import com.joelbermudez.pocketgb.library.PreferencesFileOps
import com.joelbermudez.pocketgb.saves.FramePng
import com.joelbermudez.pocketgb.settings.isValidFingerprint
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Portadas capturadas al cerrar un juego (K9): un PNG por huella en `filesDir/artwork/<huella>.png`. Es
 * información derivada y desechable: nunca toca `saves/` ni `states/`. Escritura atómica (temporal sincronizado
 * y rename), de modo que un fallo deja la portada anterior intacta. Mejor esfuerzo: un fallo se registra y no
 * afecta al cierre ni a la partida.
 *
 * [executor] `null` solo en pruebas: [saveAsync] guarda entonces en el mismo hilo.
 */
class ArtworkStore(
    private val directory: File,
    private val ops: PreferencesFileOps = DefaultPreferencesFileOps,
    private val encoder: (IntArray) -> ByteArray? = FramePng::encode,
    private val executor: ExecutorService? = defaultExecutor(),
) {
    private val _version = MutableStateFlow(0L)

    /** Crece cada vez que cambia el contenido (guardar o borrar): la UI recarga las portadas al verlo. */
    val version: StateFlow<Long> = _version.asStateFlow()

    /** Caché LRU pequeña de portadas decodificadas (160×144 en GB, 240×160 en GBA). */
    private val cache = object : LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > CACHE_ENTRIES
    }

    /** El registro es informativo: en pruebas JVM `Log` no existe y no debe romper el flujo. */
    private fun warn(message: String, error: Throwable) {
        try {
            Log.w(TAG, message, error)
        } catch (_: Throwable) {
        }
    }

    private fun fileFor(fingerprint: String) = File(directory, "$fingerprint.png")

    /** Encola el guardado en un hilo propio de baja prioridad. [pixels] debe ser una copia que nadie más toque. */
    fun saveAsync(fingerprint: String, pixels: IntArray) {
        val pool = executor
        if (pool == null) {
            save(fingerprint, pixels)
            return
        }
        try {
            pool.execute { save(fingerprint, pixels) }
        } catch (error: RuntimeException) {
            warn("No se pudo encolar la portada", error)
        }
    }

    /** Guarda la portada. `false` si se descartó (fotograma uniforme, huella inválida) o si falló la escritura. */
    fun save(fingerprint: String, pixels: IntArray): Boolean {
        if (!isValidFingerprint(fingerprint) || ArtworkCapture.isUniform(pixels)) return false
        val bytes = try {
            encoder(pixels)
        } catch (error: RuntimeException) {
            null
        } ?: return false
        val target = fileFor(fingerprint)
        val temp = File(directory, "$fingerprint.png.tmp")
        try {
            directory.mkdirs()
            ops.writeSynced(temp, bytes)
            if (!ops.rename(temp, target)) throw IOException("No se pudo reemplazar ${target.name}")
        } catch (error: IOException) {
            temp.delete()
            warn("No se pudo guardar la portada", error)
            return false
        } catch (error: RuntimeException) {
            temp.delete()
            warn("No se pudo guardar la portada", error)
            return false
        }
        synchronized(cache) { cache.remove(fingerprint) }
        _version.value += 1
        return true
    }

    /** Bytes del PNG, o `null` si no existe, supera el tope o la huella no es válida. */
    fun readBytes(fingerprint: String): ByteArray? {
        if (!isValidFingerprint(fingerprint)) return null
        val file = fileFor(fingerprint)
        return try {
            if (!file.isFile || file.length() > MAX_READ_BYTES) null else file.readBytes()
        } catch (_: IOException) {
            null
        }
    }

    /** Portada decodificada, o `null` si no hay (o no se pudo leer): quien llama dibuja el placeholder. */
    fun load(fingerprint: String): ImageBitmap? {
        synchronized(cache) { cache[fingerprint] }?.let { return it }
        val bytes = readBytes(fingerprint) ?: return null
        val image = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        } catch (_: RuntimeException) {
            null
        } ?: return null
        synchronized(cache) { cache[fingerprint] = image }
        return image
    }

    fun has(fingerprint: String): Boolean = isValidFingerprint(fingerprint) && fileFor(fingerprint).isFile

    /** Huellas con portada guardada. */
    fun fingerprints(): Set<String> =
        directory.list()?.filter { it.endsWith(".png") }?.map { it.removeSuffix(".png") }
            ?.filter(::isValidFingerprint)?.toSet().orEmpty()

    /** Bytes ocupados por las portadas. */
    fun sizeBytes(): Long = ownFiles().sumOf { it.length() }

    /** Borra las portadas (y temporales). Solo archivos de `artwork/`: nada fuera ni subcarpetas. Devuelve cuántas. */
    fun removeAll(): Int {
        var removed = 0
        for (file in ownFiles()) if (file.delete()) removed++
        synchronized(cache) { cache.clear() }
        _version.value += 1
        return removed
    }

    /** Fuerza a la UI a releer el disco (p. ej. tras borrar portadas desde Ajustes sin pasar por aquí). */
    fun refresh() {
        synchronized(cache) { cache.clear() }
        _version.value += 1
    }

    /** Memoria baja (A7 R13): vacía solo la caché en memoria; el disco y [version] no cambian (se vuelve a decodificar al pedirla). */
    fun trimMemory() {
        synchronized(cache) { cache.clear() }
    }

    private fun ownFiles(): List<File> {
        if (Files.isSymbolicLink(directory.toPath())) return emptyList()
        return directory.listFiles()?.filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }.orEmpty()
    }

    companion object {
        private const val TAG = "ArtworkStore"
        const val MAX_READ_BYTES = 512 * 1024
        private const val CACHE_ENTRIES = 48

        private fun defaultExecutor(): ExecutorService = Executors.newSingleThreadExecutor { task ->
            Thread(task, "pocketgb-artwork").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }

        @Volatile
        private var shared: ArtworkStore? = null

        /** Una sola instancia por proceso, compartida por la UI y por el cierre de partida. */
        fun shared(context: Context): ArtworkStore = shared ?: synchronized(this) {
            shared ?: ArtworkStore(File(context.applicationContext.filesDir, "artwork")).also { shared = it }
        }
    }
}

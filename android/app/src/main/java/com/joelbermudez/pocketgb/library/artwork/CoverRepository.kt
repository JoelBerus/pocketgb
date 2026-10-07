package com.joelbermudez.pocketgb.library.artwork

import android.content.Context
import androidx.core.net.toUri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.joelbermudez.pocketgb.library.RomEntry
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Lo que se dibuja: la fuente y su imagen (`null` en [CoverKind.GENERATED]). */
data class ShownCover(val kind: CoverKind, val image: ImageBitmap?) {
    companion object {
        val GENERATED = ShownCover(CoverKind.GENERATED, null)
    }
}

/**
 * N5 · todas las fuentes de portada de un juego y la elección. Las imágenes son entrada no confiable: se leen con tope,
 * se validan por firma y dimensiones, se decodifican submuestreadas ([CoverDecoder]) y, ante cualquier fallo, se pasa a
 * la siguiente fuente hasta la generada. Nada de esto toca partidas ni estados; la app no tiene red (las imágenes solo
 * llegan del usuario: importadas o en su carpeta).
 *
 * - [captures]: último fotograma al cerrar (K9), `filesDir/artwork/`.
 * - [pinned]: captura fijada con «Usar como portada», `filesDir/artwork-pinned/` (el cierre no la pisa).
 * - [imported]: copia reducida (≤ 1024 px, PNG) de la imagen importada, `filesDir/covers/imported/`.
 * - [folderCache]: copia reducida de la imagen de la carpeta, por sello, `filesDir/covers/folder/` (no se vuelve a
 *   leer del proveedor —en Drive, a descargar— mientras el sello no cambie).
 * - [settings]: elección por juego y preferencia global; `null` = Automática y «Preferir imágenes» (catálogo antiguo).
 */
class CoverRepository(
    val captures: ArtworkStore,
    val pinned: ArtworkStore? = null,
    val imported: ArtworkStore? = null,
    val folderCache: ArtworkStore? = null,
    val settings: CoverSettingsStore? = null,
    /** Lee la imagen de la carpeta (URI SAF) con tope; `null` si no se puede. */
    private val readFolderImage: (String) -> ByteArray? = { null },
    /** Copia reducida de una imagen no confiable ([CoverDecoder.reduce]); inyectable en pruebas JVM. */
    private val reduce: (ByteArray) -> ByteArray? = CoverDecoder::reduce,
) {
    /** Sellos de imágenes de la carpeta que ya fallaron en esta ejecución: no se reintentan en cada recomposición. */
    private val failedFolder = java.util.Collections.synchronizedSet(HashSet<String>())
    private val _version = MutableStateFlow(0L)

    /** Crece al importar o quitar imágenes o al fallar una imagen de la carpeta. */
    val version: StateFlow<Long> = _version.asStateFlow()

    private val defaultSettings = MutableStateFlow(CoverSettings())
    val settingsState: StateFlow<CoverSettings> get() = settings?.state ?: defaultSettings

    fun availability(entry: RomEntry, fingerprint: String?): CoverAvailability = CoverAvailability(
        imported = fingerprint != null && imported?.has(fingerprint) == true,
        sidecar = entry.coverUri != null && folderKey(entry)?.let { it !in failedFolder } == true,
        capture = fingerprint != null && (pinned?.has(fingerprint) == true || captures.has(fingerprint)),
    )

    fun choiceFor(fingerprint: String?): CoverChoice = settingsState.value.choiceFor(fingerprint)

    /** Fuente que se verá (sin decodificar). */
    fun resolve(entry: RomEntry, fingerprint: String?): CoverKind =
        CoverResolver.resolve(choiceFor(fingerprint), settingsState.value.preference, availability(entry, fingerprint))

    /** Decodifica la primera fuente que se lea bien, en el orden de [CoverResolver.candidates]. Bloqueante (E/S). */
    fun load(entry: RomEntry, fingerprint: String?): ShownCover {
        val current = settingsState.value
        val order = CoverResolver.candidates(current.choiceFor(fingerprint), current.preference, availability(entry, fingerprint))
        for (kind in order) {
            val image = when (kind) {
                CoverKind.IMPORTED -> fingerprint?.let { imported?.load(it) }
                CoverKind.SIDECAR -> loadFolderImage(entry)
                CoverKind.CAPTURE -> fingerprint?.let { pinned?.load(it) ?: captures.load(it) }
                CoverKind.GENERATED -> return ShownCover.GENERATED
            }
            if (image != null) return ShownCover(kind, image)
        }
        return ShownCover.GENERATED
    }

    /** Un candado por clave: dos tarjetas del mismo juego no leen la imagen de la carpeta a la vez. */
    private val folderLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /**
     * N5A-2: tras un escaneo completo, borra las copias de imágenes de la carpeta que ya no corresponden a ninguna
     * entrada (la imagen cambió de sello, se movió o se borró). Nunca toca la carpeta del usuario.
     */
    fun pruneFolderCache(entries: List<RomEntry>): Int {
        val keep = entries.mapNotNull(::folderKey).toSet()
        return folderCache?.retainOnly(keep) ?: 0
    }

    private fun loadFolderImage(entry: RomEntry): ImageBitmap? {
        val key = folderKey(entry) ?: return null
        return synchronized(folderLocks.computeIfAbsent(key) { Any() }) { loadFolderImageLocked(entry, key) }
    }

    private fun loadFolderImageLocked(entry: RomEntry, key: String): ImageBitmap? {
        val uri = entry.coverUri ?: return null
        if (key in failedFolder) return null
        val cache = folderCache
        cache?.load(key)?.let { return it }
        val reduced = readFolderImage(uri)?.let(reduce)
        if (reduced == null) {
            failedFolder += key
            _version.value += 1
            return null
        }
        cache?.saveEncoded(key, reduced)
        return cache?.load(key) ?: runCatching { ArtworkStore.decodePlain(reduced) }.getOrNull()
    }

    /** Clave del caché de la imagen de la carpeta: SHA-256 de URI + sello (tiene forma de huella). */
    private fun folderKey(entry: RomEntry): String? {
        val uri = entry.coverUri ?: return null
        return sha256("$uri|${entry.coverStamp.orEmpty()}")
    }

    /**
     * Importa una imagen ([bytes] tal como llegaron, no confiables): guarda la copia reducida y elige «Imagen» para el
     * juego. `false` si la imagen no vale o no se pudo guardar (no cambia nada).
     */
    fun importImage(fingerprint: String, bytes: ByteArray): Boolean {
        val store = imported ?: return false
        val reduced = reduce(bytes) ?: return false
        if (!store.saveEncoded(fingerprint, reduced)) return false
        settings?.setChoice(fingerprint, CoverChoice.IMAGE)
        _version.value += 1
        return true
    }

    /** Quita la imagen importada; si la elección era «Imagen» y no queda otra imagen, vuelve a Automática. */
    fun removeImported(entry: RomEntry, fingerprint: String) {
        imported?.remove(fingerprint)
        if (choiceFor(fingerprint) == CoverChoice.IMAGE && entry.coverUri == null) settings?.setChoice(fingerprint, CoverChoice.AUTO)
        _version.value += 1
    }

    /** «Usar como portada» (pausa): fija el fotograma y elige «Captura». `false` si el fotograma no vale. */
    fun pinCapture(fingerprint: String, pixels: IntArray): Boolean {
        val store = pinned ?: return false
        if (!store.save(fingerprint, pixels)) return false
        settings?.setChoice(fingerprint, CoverChoice.CAPTURE)
        _version.value += 1
        return true
    }

    /** Suelta la captura fijada: vuelve a usarse el último fotograma al cerrar. */
    fun unpinCapture(fingerprint: String) {
        pinned?.remove(fingerprint)
        _version.value += 1
    }

    fun hasPinned(fingerprint: String): Boolean = pinned?.has(fingerprint) == true

    fun setChoice(fingerprint: String, choice: CoverChoice) {
        settings?.setChoice(fingerprint, choice)
    }

    fun setPreference(preference: CoverPreference) {
        settings?.setPreference(preference) ?: run { defaultSettings.value = defaultSettings.value.copy(preference = preference) }
    }

    /** Huellas con una portada que no es la generada (carril «Continuar jugando», K10). */
    fun fingerprintsWithCover(): Set<String> =
        captures.fingerprints() + pinned?.fingerprints().orEmpty() + imported?.fingerprints().orEmpty()

    /** Bytes de todas las portadas (Ajustes › Almacenamiento). */
    fun sizeBytes(): Long = listOfNotNull(captures, pinned, imported, folderCache).sumOf { it.sizeBytes() }

    /** Borra todas las portadas guardadas por la app (nunca las imágenes de la carpeta del usuario). */
    fun removeAll(): Int {
        val removed = listOfNotNull(captures, pinned, imported, folderCache).sumOf { it.removeAll() }
        failedFolder.clear()
        _version.value += 1
        return removed
    }

    fun refresh() {
        listOfNotNull(captures, pinned, imported, folderCache).forEach { it.refresh() }
        failedFolder.clear()
        _version.value += 1
    }

    fun trimMemory() {
        listOfNotNull(captures, pinned, imported, folderCache).forEach { it.trimMemory() }
    }

    companion object {
        private fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

        @Volatile
        private var shared: CoverRepository? = null

        /** Una sola instancia por proceso (UI, pausa y Ajustes). */
        fun shared(context: Context): CoverRepository = shared ?: synchronized(this) {
            shared ?: create(context.applicationContext).also { shared = it }
        }

        private fun create(context: Context): CoverRepository {
            val files = context.filesDir
            val decodeUntrusted: (ByteArray) -> ImageBitmap? = { bytes -> CoverDecoder.decode(bytes)?.asImageBitmap() }
            return CoverRepository(
                captures = ArtworkStore.shared(context),
                pinned = ArtworkStore(File(files, "artwork-pinned")),
                imported = ArtworkStore(File(files, "covers/imported"), maxReadBytes = CoverImageRules.MAX_BYTES, decoder = decodeUntrusted),
                folderCache = ArtworkStore(File(files, "covers/folder"), maxReadBytes = CoverImageRules.MAX_BYTES, decoder = decodeUntrusted),
                settings = CoverSettingsStore(File(files, "covers/settings.json")),
                readFolderImage = { uri -> readUri(context, uri) },
            )
        }

        /** Lee un URI con el tope de [CoverImageRules.MAX_BYTES]; `null` si falla, no hay permiso o es mayor. */
        fun readUri(context: Context, uri: String): ByteArray? = try {
            context.contentResolver.openInputStream(uri.toUri())?.use(CoverDecoder::readLimited)
        } catch (_: java.io.IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }
}

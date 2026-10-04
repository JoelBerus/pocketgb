package com.joelbermudez.pocketgb.library

import androidx.lifecycle.ViewModel
import com.joelbermudez.pocketgb.emulator.CoreError
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** Lee un ROM completo desde su URI, con un tope de bytes. Solo lectura. */
fun interface RomSource {
    /** Devuelve como mucho [limit] bytes. Falla con [DocumentReadException] u otra [IOException]. */
    fun read(uri: String, limit: Int): ByteArray
}

/** Obtiene los metadatos del cartucho con el núcleo. Lanza [CoreError] si lo rechaza. */
fun interface RomInspector {
    fun inspect(rom: ByteArray): com.joelbermudez.pocketgb.emulator.RomInfo
}

/**
 * Estado y acciones de la biblioteca. Todo el I/O (escaneo, lectura de ROM, preferencias)
 * ocurre en [io]; el hilo principal solo publica estados.
 *
 * Recibe el [CoroutineScope] para poder probarse en JVM sin `Dispatchers.Main`.
 */
class LibraryViewModel(
    private val folders: FolderStore,
    private val openTree: (String) -> DocumentTree,
    private val roms: RomSource,
    private val inspector: RomInspector,
    private val preferencesFile: LibraryPreferencesFile,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ViewModel(scope) {
    private val _state = MutableStateFlow<LibraryState>(LibraryState.NoFolder)
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    private val _folderName = MutableStateFlow<String?>(null)
    val folderName: StateFlow<String?> = _folderName.asStateFlow()

    private val _prefs = MutableStateFlow(LibraryPreferencesData())
    val prefs: StateFlow<LibraryPreferencesData> = _prefs.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _filter = MutableStateFlow(LibraryFilter.ALL)
    val filter: StateFlow<LibraryFilter> = _filter.asStateFlow()

    private var scanJob: Job? = null

    // Las preferencias se cargan en `io`; los cambios que lleguen antes se aplican después, en orden.
    private val lock = Any()
    private var prefsLoaded = false
    private val pendingChanges = ArrayList<(LibraryPreferencesData) -> LibraryPreferencesData>()
    private val prefsLoad = CompletableDeferred<Unit>()

    private class WriteRequest(val data: LibraryPreferencesData?, val done: CompletableDeferred<Unit>?)

    private val writes = Channel<WriteRequest>(Channel.UNLIMITED)

    init {
        scope.launch(io) {
            for (request in writes) {
                request.data?.let {
                    try {
                        preferencesFile.save(it)
                    } catch (_: IOException) {
                        // Un fallo de preferencias no debe tumbar la biblioteca; el siguiente cambio reintenta.
                    }
                }
                request.done?.complete(Unit)
            }
        }
        scope.launch(io) {
            val loaded = preferencesFile.load()
            val persistNeeded: Boolean
            val merged: LibraryPreferencesData
            synchronized(lock) {
                persistNeeded = pendingChanges.isNotEmpty()
                merged = pendingChanges.fold(loaded) { data, change -> change(data) }
                pendingChanges.clear()
                prefsLoaded = true
                _prefs.value = merged
            }
            if (persistNeeded) writes.trySend(WriteRequest(merged, null))
            prefsLoad.complete(Unit)
        }
    }

    // ---- Carpeta y escaneo ----

    fun chooseFolder(uri: String) {
        scanJob?.cancel()
        scanJob = scope.launch(io) {
            try {
                folders.select(uri)
            } catch (_: SecurityException) {
                _state.value = LibraryState.Failed(LibraryError.PermissionRevoked)
                return@launch
            }
            scan()
        }
    }

    /** Vuelve a listar la carpeta; cancela el escaneo anterior. */
    fun rescan() {
        scanJob?.cancel()
        scanJob = scope.launch(io) { scan() }
    }

    fun forgetFolder() {
        scanJob?.cancel()
        scanJob = scope.launch(io) {
            folders.forget()
            _folderName.value = null
            _state.value = LibraryState.NoFolder
        }
    }

    private suspend fun scan() {
        val context = currentCoroutineContext()
        val uri = folders.currentUri()
        if (uri == null) {
            _folderName.value = null
            _state.value = LibraryState.NoFolder
            return
        }
        if (!folders.hasPersistedPermission()) {
            _state.value = LibraryState.Failed(LibraryError.PermissionRevoked)
            return
        }
        val name = folders.displayName()
        _folderName.value = name
        val previous = when (val current = _state.value) {
            is LibraryState.Ready -> current.entries
            is LibraryState.Scanning -> current.previous
            else -> emptyList()
        }
        _state.value = LibraryState.Scanning(previous, name)
        val outcome: LibraryState = try {
            val entries = LibraryScanner.scan(openTree(uri)) { _, _ -> context.ensureActive() }
            LibraryState.Ready(entries, name)
        } catch (error: CancellationException) {
            throw error
        } catch (_: TreePermissionException) {
            LibraryState.Failed(LibraryError.PermissionRevoked)
        } catch (_: SecurityException) {
            LibraryState.Failed(LibraryError.PermissionRevoked)
        } catch (_: TreeMissingException) {
            LibraryState.Failed(LibraryError.FolderMissing)
        } catch (_: IOException) {
            LibraryState.Failed(LibraryError.Unreadable)
        }
        context.ensureActive()
        _state.value = outcome
    }

    // ---- Búsqueda y filtro ----

    fun setQuery(value: String) {
        _query.value = value
    }

    fun setFilter(value: LibraryFilter) {
        _filter.value = value
    }

    // ---- Preferencias ----

    fun toggleFavorite(entry: RomEntry) = mutate { it.toggleFavorite(entry) }

    fun hide(entry: RomEntry) = mutate { it.hide(entry) }

    fun unhide(entry: RomEntry) = mutate { it.unhide(entry) }

    fun setLayout(layout: LibraryLayout) = mutate { it.copy(layout = layout) }

    fun setSort(sort: LibrarySort) = mutate { it.copy(sort = sort) }

    /** Espera a que las preferencias pendientes estén en disco. Útil en pruebas y antes de salir. */
    suspend fun flushPreferences() {
        prefsLoad.await()
        val done = CompletableDeferred<Unit>()
        writes.send(WriteRequest(null, done))
        done.await()
    }

    private fun mutate(change: (LibraryPreferencesData) -> LibraryPreferencesData) {
        synchronized(lock) {
            if (!prefsLoaded) {
                pendingChanges += change
                return
            }
            var next: LibraryPreferencesData? = null
            _prefs.update { current ->
                change(current).also { next = it }
            }
            val updated = next!!
            writes.trySend(WriteRequest(updated, null))
        }
    }

    // ---- Detalle ----

    /** Lee el ROM completo (≤ 8 MiB), obtiene sus metadatos con el núcleo y registra su huella. */
    suspend fun loadDetails(id: String): DetailsLoad {
        val entry = entryFor(id) ?: return DetailsLoad.Failed(DetailsError.NotFound)
        entry.problem?.let { return DetailsLoad.Failed(DetailsError.Problem(it)) }
        return withContext(io) {
            val bytes = try {
                roms.read(entry.uri, LibraryScanner.MAX_ROM_BYTES.toInt() + 1)
            } catch (error: DocumentReadException) {
                return@withContext DetailsLoad.Failed(if (error.remote) DetailsError.Remote else DetailsError.Unreadable)
            } catch (_: SecurityException) {
                return@withContext DetailsLoad.Failed(DetailsError.Unreadable)
            } catch (_: IOException) {
                return@withContext DetailsLoad.Failed(DetailsError.Unreadable)
            }
            if (bytes.size > LibraryScanner.MAX_ROM_BYTES) {
                return@withContext DetailsLoad.Failed(DetailsError.TooLarge)
            }
            val info = try {
                inspector.inspect(bytes)
            } catch (error: CoreError) {
                return@withContext DetailsLoad.Failed(DetailsError.CoreRejected(error))
            }
            val fingerprint = info.fingerprintHex
            if (_prefs.value.fingerprints[entry.id] != fingerprint) {
                mutate { it.recordFingerprint(entry.id, fingerprint) }
            }
            DetailsLoad.Loaded(GameDetails.from(entry, info))
        }
    }

    private fun entryFor(id: String): RomEntry? = when (val current = _state.value) {
        is LibraryState.Ready -> current.entries
        is LibraryState.Scanning -> current.previous
        else -> emptyList()
    }.firstOrNull { it.id == id }
}

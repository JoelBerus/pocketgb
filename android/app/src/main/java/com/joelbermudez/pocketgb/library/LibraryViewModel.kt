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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
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
    private val preferencesFile: PreferencesStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    /** N1b: cuánto costó cada escaneo (la app lo anota en el registro del sistema). */
    private val scanLog: (ScanStats) -> Unit = {},
) : ViewModel(scope) {
    private val _state = MutableStateFlow<LibraryState>(LibraryState.Loading)
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    private val _folderName = MutableStateFlow<String?>(null)
    val folderName: StateFlow<String?> = _folderName.asStateFlow()

    private val _prefs = MutableStateFlow(LibraryPreferencesData())
    val prefs: StateFlow<LibraryPreferencesData> = _prefs.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Juegos nuevos por anunciar (K19); 0 = nada que mostrar. La UI lo muestra una vez y llama a [dismissNewGamesSummary]. */
    private val _newGamesSummary = MutableStateFlow(0)
    val newGamesSummary: StateFlow<Int> = _newGamesSummary.asStateFlow()

    /** Nuevos ya anunciados en esta ejecución: un reescaneo al volver a primer plano no repite el aviso. */
    private val announcedNew = HashSet<String>()

    private val _filter = MutableStateFlow(LibraryFilter.ALL)
    val filter: StateFlow<LibraryFilter> = _filter.asStateFlow()

    /**
     * N1-H2: `preferences.json` es de otra versión de la app (futura o con una versión que no se entiende): no se
     * sobrescribe y los cambios de esta sesión se quedan en memoria. La biblioteca lo avisa.
     */
    private val _preferencesReadOnly = MutableStateFlow(false)
    val preferencesReadOnly: StateFlow<Boolean> = _preferencesReadOnly.asStateFlow()

    /** N1b: lo que costó el último escaneo terminado (consultas SAF, apartados, si fue completo). */
    private val _lastScan = MutableStateFlow<ScanStats?>(null)
    val lastScan: StateFlow<ScanStats?> = _lastScan.asStateFlow()

    // ---- Operaciones de carpeta: una a la vez, y solo la más reciente publica estado ----

    private val folderMutex = Mutex()
    private val generation = AtomicInteger()
    private val jobLock = Any()
    private var folderJob: Job? = null

    // ---- Preferencias ----
    //
    // `_prefs` es la fuente de verdad en memoria y se actualiza bajo `lock`. El escritor siempre persiste
    // el ÚLTIMO valor de `_prefs` (nunca una copia del momento de la petición), así que el orden de las
    // señales no importa: lo que acaba en disco es lo que hay en memoria. Si guardar falla, `lastSaved`
    // no avanza y la versión sigue pendiente hasta el siguiente intento.
    private val lock = Any()
    private val persistLock = Any()
    @Volatile private var loaded = false
    private var lastSaved: LibraryPreferencesData? = null
    private val pendingChanges = ArrayList<(LibraryPreferencesData) -> LibraryPreferencesData>()

    /** `null` = solo "persiste lo último"; con valor, además se completa con el resultado. */
    private val writes = Channel<CompletableDeferred<PersistResult>?>(Channel.UNLIMITED)

    init {
        scope.launch(io) {
            for (request in writes) {
                val result = persistLatest()
                request?.complete(result)
            }
        }
        writes.trySend(null) // carga inicial
    }

    private fun persistLatest(): PersistResult = synchronized(persistLock) {
        try {
            if (!loaded) loadNow()
            val snapshot = _prefs.value
            if (snapshot != lastSaved) {
                preferencesFile.save(snapshot)
                lastSaved = snapshot
            }
            PersistResult.Saved
        } catch (error: IOException) {
            PersistResult.Failed(error)
        } catch (error: RuntimeException) {
            PersistResult.Failed(IOException("Fallo inesperado al guardar preferencias", error))
        }
    }

    /** Carga el archivo y aplica encima los cambios hechos mientras tanto. Un fallo de I/O deja todo pendiente. */
    private fun loadNow() {
        val data = preferencesFile.load()
        _preferencesReadOnly.value = preferencesFile.writeProtected
        synchronized(lock) {
            _prefs.value = pendingChanges.fold(data) { current, change -> change(current) }
            pendingChanges.clear()
            lastSaved = data
            loaded = true
        }
    }

    // ---- Carpeta y escaneo ----

    private fun launchFolderOperation(block: suspend (isCurrent: () -> Boolean) -> Unit) {
        val mine = generation.incrementAndGet()
        synchronized(jobLock) {
            folderJob?.cancel()
            folderJob = scope.launch(io) {
                folderMutex.withLock { block { generation.get() == mine } }
            }
        }
    }

    fun chooseFolder(uri: String) = launchFolderOperation { isCurrent ->
        try {
            folders.select(uri)
            // Otra carpeta: el primer escaneo no marca nada como nuevo (K19).
            mutate { it.copy(knownIds = emptySet()) }
            announcedNew.clear()
        } catch (error: CancellationException) {
            throw error
        } catch (_: SecurityException) {
            if (isCurrent()) _state.value = LibraryState.Failed(LibraryError.AccessNotKept)
            return@launchFolderOperation
        } catch (_: IllegalArgumentException) {
            if (isCurrent()) _state.value = LibraryState.Failed(LibraryError.AccessNotKept)
            return@launchFolderOperation
        } catch (_: Exception) {
            if (isCurrent()) _state.value = LibraryState.Failed(LibraryError.Unreadable)
            return@launchFolderOperation
        }
        scan(isCurrent)
    }

    /** Vuelve a listar la carpeta; cancela el escaneo anterior. */
    fun rescan() = launchFolderOperation { isCurrent -> scan(isCurrent) }

    fun forgetFolder() = launchFolderOperation { isCurrent ->
        try {
            folders.forget()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Olvidar es local; si falla, el siguiente escaneo lo reflejará.
        }
        mutate { it.copy(knownIds = emptySet()) }
        announcedNew.clear()
        if (isCurrent()) {
            _folderName.value = null
            _state.value = LibraryState.NoFolder
        }
    }

    /** Siempre termina publicando un estado: ninguna excepción del proveedor escapa de aquí. */
    private suspend fun scan(isCurrent: () -> Boolean) {
        val context = currentCoroutineContext()
        fun publish(state: LibraryState) {
            context.ensureActive()
            if (isCurrent()) _state.value = state
        }
        val outcome: LibraryState = try {
            val uri = folders.currentUri()
            if (uri == null) {
                if (isCurrent()) _folderName.value = null
                publish(LibraryState.NoFolder)
                return
            }
            if (!folders.hasPersistedPermission()) {
                publish(LibraryState.Failed(LibraryError.PermissionRevoked))
                return
            }
            val name = folders.displayName()
            if (isCurrent()) _folderName.value = name
            val previous = when (val current = _state.value) {
                is LibraryState.Ready -> current.entries
                is LibraryState.Scanning -> current.previous
                else -> emptyList()
            }
            publish(LibraryState.Scanning(previous, name))
            // N1-H6: con las preferencias cargadas, los ROMs que no cambiaron no se vuelven a abrir.
            ensureLoaded()
            val headerCache = HeaderCache.from(_prefs.value.documents.values)
            val result = LibraryScanner.scanDetailed(
                openTree(uri),
                progress = { done, total ->
                    // Sin inundar a la UI: el primero, cada cinco y el último.
                    if (isCurrent() && (done == 1 || done == total || done % PROGRESS_STEP == 0)) {
                        _state.value = LibraryState.Scanning(previous, name, done, total)
                    }
                },
                checkCancelled = { context.ensureActive() },
                headerCache = headerCache,
            )
            context.ensureActive()
            _lastScan.value = result.stats
            scanLog(result.stats)
            ensureLoaded()
            // N1a: movimientos reconocidos por su sello (sin leer los ROMs) antes de decidir qué es «Nuevo».
            mutate { it.reconciled(result.entries, result.stats.complete) }
            LibraryState.Ready(markNew(result.entries, result.stats.complete), name)
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
        } catch (_: Exception) {
            // Red de seguridad: un proveedor mal portado (IllegalStateException, etc.) no debe tumbar la app.
            LibraryState.Failed(LibraryError.Unreadable)
        }
        publish(outcome)
    }

    /** Intenta cargar las preferencias si aún no lo están; si el disco falla, se sigue sin ellas. */
    private fun ensureLoaded() {
        synchronized(persistLock) {
            if (!loaded) {
                try {
                    loadNow()
                } catch (_: IOException) {
                } catch (_: RuntimeException) {
                }
            }
        }
    }

    /**
     * K19: «Nuevo» = no visto en el escaneo anterior. El primer escaneo de una carpeta (sin ids conocidos) reconoce
     * todo y no marca nada. Los nuevos siguen marcados hasta que se abren ([recordPlayed]). Cada escaneo completo poda
     * `knownIds` a los ids presentes (A6-H8): un ROM borrado o renombrado que reaparezca vuelve a ser «Nuevo», salvo que se
     * reconozca como movido o vuelva con su lápida ([reconciled], N1a/N1-H4). Un listado vacío o un escaneo incompleto
     * no podan (un proveedor en la nube con un fallo pasajero no debe olvidar toda la biblioteca).
     */
    private fun markNew(entries: List<RomEntry>, complete: Boolean): List<RomEntry> {
        ensureLoaded()
        if (!loaded) return entries // sin preferencias no se sabe qué era conocido: no se marca nada
        val ids = entries.map { it.id }.toSet()
        val known = _prefs.value.knownIds
        if (known.isEmpty()) {
            if (ids.isNotEmpty()) mutate { it.acknowledge(ids) }
            return entries
        }
        val gone = known - ids
        // N1a: con un escaneo incompleto (una carpeta falló) lo que no aparece puede seguir ahí: no se poda.
        if (gone.isNotEmpty() && ids.isNotEmpty() && complete) mutate { it.copy(knownIds = it.knownIds - gone) }
        val fresh = ids - known
        val toAnnounce = fresh - announcedNew
        if (toAnnounce.isNotEmpty()) {
            announcedNew += toAnnounce
            _newGamesSummary.value = toAnnounce.size
        }
        return entries.map { if (it.id in fresh) it.copy(isNew = true) else it }
    }

    fun dismissNewGamesSummary() {
        _newGamesSummary.value = 0
    }

    private fun clearNewMark(id: String) = _state.update { current ->
        fun List<RomEntry>.cleared() = map { if (it.id == id && it.isNew) it.copy(isNew = false) else it }
        when (current) {
            is LibraryState.Ready -> current.copy(entries = current.entries.cleared())
            is LibraryState.Scanning -> current.copy(previous = current.previous.cleared())
            else -> current
        }
    }

    // ---- Búsqueda y filtro ----

    fun setQuery(value: String) {
        _query.value = value
    }

    fun setFilter(value: LibraryFilter) {
        _filter.value = value
    }

    // ---- Preferencias (acciones) ----

    fun toggleFavorite(entry: RomEntry) = mutate { it.toggleFavorite(entry) }

    fun hide(entry: RomEntry) = mutate { it.hide(entry) }

    fun unhide(entry: RomEntry) = mutate { it.unhide(entry) }

    fun setLayout(layout: LibraryLayout) = mutate { it.copy(layout = layout) }

    fun setSort(sort: LibrarySort) = mutate { it.copy(sort = sort) }

    /** Renombrar (A9): solo cambia el nombre visible; el ROM, su `.sav` y sus estados no se tocan. Vacío = cabecera. */
    fun setAlias(entry: RomEntry, value: String) = mutate { it.setAlias(entry, value) }

    /** Registra que se abrió [entry] (para "Continuar jugando" y la huella). */
    fun recordPlayed(entry: RomEntry, fingerprint: String, at: Long) {
        mutate { it.recordPlayed(entry.id, fingerprint, at).acknowledge(setOf(entry.id)) }
        clearNewMark(entry.id)
    }

    /**
     * Espera a que lo último en memoria esté en disco. Devuelve [PersistResult.Failed] si no se pudo;
     * en ese caso la versión sigue pendiente y se reintenta sola.
     */
    suspend fun flushPreferences(): PersistResult {
        val done = CompletableDeferred<PersistResult>()
        writes.send(done)
        return done.await()
    }

    /** Pide (sin esperar) que se persista lo pendiente; la UI lo llama al parar la actividad. */
    fun retryPendingWrites() {
        writes.trySend(null)
    }

    /** Escritura síncrona de último recurso para [onCleared]; ya no habrá "siguiente cambio". */
    internal fun persistBlocking(): PersistResult = persistLatest()

    override fun onCleared() {
        persistBlocking()
    }

    private fun mutate(change: (LibraryPreferencesData) -> LibraryPreferencesData) {
        synchronized(lock) {
            _prefs.update(change)
            if (!loaded) pendingChanges += change
        }
        writes.trySend(null)
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
            } catch (error: CancellationException) {
                throw error
            } catch (_: SecurityException) {
                return@withContext DetailsLoad.Failed(DetailsError.Unreadable)
            } catch (_: IOException) {
                return@withContext DetailsLoad.Failed(DetailsError.Unreadable)
            } catch (_: Exception) {
                // Red de seguridad: un proveedor mal portado no debe tumbar la pantalla de detalle.
                return@withContext DetailsLoad.Failed(DetailsError.Unreadable)
            }
            if (bytes.size > LibraryScanner.MAX_ROM_BYTES) {
                return@withContext DetailsLoad.Failed(DetailsError.TooLarge)
            }
            val info = try {
                inspector.inspect(bytes)
            } catch (error: CoreError) {
                return@withContext DetailsLoad.Failed(DetailsError.CoreRejected(error))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return@withContext DetailsLoad.Failed(DetailsError.Unreadable)
            }
            val fingerprint = info.fingerprintHex
            // También confirma una huella heredada de un movimiento (N1-H1), aunque sea la misma.
            val current = _prefs.value
            if (current.fingerprints[entry.id] != fingerprint || entry.id in current.inferredFingerprints) {
                mutate { it.recordFingerprint(entry.id, fingerprint) }
            }
            DetailsLoad.Loaded(GameDetails.from(entry, info))
        }
    }

    private companion object {
        const val PROGRESS_STEP = 5
    }

    private fun entryFor(id: String): RomEntry? = when (val current = _state.value) {
        is LibraryState.Ready -> current.entries
        is LibraryState.Scanning -> current.previous
        else -> emptyList()
    }.firstOrNull { it.id == id }
}

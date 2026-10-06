package com.joelbermudez.pocketgb.game

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.joelbermudez.pocketgb.library.ContentResolverRomSource
import com.joelbermudez.pocketgb.library.LibraryFolderStore
import com.joelbermudez.pocketgb.library.LibraryViewModel
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.library.artwork.ArtworkStore
import com.joelbermudez.pocketgb.settings.GameplaySettingsRepository
import com.joelbermudez.pocketgb.saves.FlushResult
import com.joelbermudez.pocketgb.saves.SaveLoadWarning
import com.joelbermudez.pocketgb.saves.isSafe
import com.joelbermudez.pocketgb.saves.StateSlot
import com.joelbermudez.pocketgb.saves.StateStore
import com.joelbermudez.pocketgb.saves.saf.MirrorDisabledReason
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Qué hoja del menú de pausa está abierta. */
/** [Editor]: editor de disposición de los controles (A6-L4); el juego sigue en pausa. */
enum class GameMenu { None, Pause, States, Editor }

/** Diálogos de la partida y de la apertura. */
sealed interface GameDialog {
    data class OpenFailed(val error: OpenError) : GameDialog
    data class LoadWarning(val warning: SaveLoadWarning) : GameDialog

    /** No se pudo guardar la partida local al salir; [confirmingRisk] = pidiendo la segunda confirmación. */
    data class ExitSaveFailed(val error: Throwable, val confirmingRisk: Boolean = false) : GameDialog
}

/** Avisos breves (snackbar), con texto en la UI. */
sealed interface GameNotice {
    data object MirrorTrouble : GameNotice
    data class MirrorDisabled(val reason: MirrorDisabledReason) : GameNotice
    data class StateSaved(val slot: StateSlot) : GameNotice
    data class StateLoaded(val slot: StateSlot) : GameNotice
    data class StateDeleted(val slot: StateSlot) : GameNotice
    data class StateFailed(val error: StateError) : GameNotice
    data object SavePending : GameNotice

    /** La cabecera del ROM tiene el checksum incorrecto: se puede jugar, pero puede no ser un cartucho válido (K15). */
    data object HeaderDamaged : GameNotice

    /** Hay un estado de rescate de una salida anterior con fallo de guardado (J6). */
    data object RescueStateExists : GameNotice
}

/**
 * Hilo de rescate de la app: sobrevive al ViewModel. Cuando el ViewModel se destruye con una partida abierta, el
 * cierre (que puede bloquear con un disco que falla) corre aquí, nunca en el hilo principal.
 */
internal object GameRescue {
    private val executor: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "pocketgb-rescue").apply { isDaemon = true }
    }

    fun run(task: () -> Unit) {
        executor.execute(task)
    }
}

data class StatesUi(
    val entries: Map<StateSlot, StateStore.Entry> = emptyMap(),
    val busy: Boolean = false,
)

/**
 * Dueño de la [GameSession] (SPEC §2.2): sobrevive a la rotación y a la recreación de la actividad; la sesión
 * JAMÁS vive en un `remember`. Las operaciones con E/S (abrir, estados, salir) corren en [io] bajo una
 * exclusión mutua; mientras dura un estado la sesión está aparcada en pausa y el menú impide reanudarla.
 */
class GameplayViewModel(
    private val launcher: GameLauncher,
    private val recordPlayed: (RomEntry, String, Long) -> Unit = { _, _, _ -> },
    private val now: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    /** Dónde corre el rescate al destruirse el ViewModel (fuera del hilo principal). Se inyecta en los tests. */
    private val rescue: (() -> Unit) -> Unit = GameRescue::run,
    private val rescueAttempts: Int = 10,
    private val rescueRetryDelayMs: Long = 1_000,
    /** Dueño de las sesiones que sobreviven a este ViewModel con el guardado pendiente (A5V2-H1). */
    private val orphans: OrphanSessionRegistry = OrphanSessionRegistry.shared,
) : ViewModel(scope) {
    private val _game = MutableStateFlow<GameSession?>(null)
    val game: StateFlow<GameSession?> = _game.asStateFlow()

    private val _opening = MutableStateFlow(false)
    val opening: StateFlow<Boolean> = _opening.asStateFlow()

    private val _busy = MutableStateFlow(false)

    /** Hay una salida o un estado en curso (la UI bloquea los botones). */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _menu = MutableStateFlow(GameMenu.None)
    val menu: StateFlow<GameMenu> = _menu.asStateFlow()

    private val _dialog = MutableStateFlow<GameDialog?>(null)
    val dialog: StateFlow<GameDialog?> = _dialog.asStateFlow()

    private val _states = MutableStateFlow(StatesUi())
    val states: StateFlow<StatesUi> = _states.asStateFlow()

    private val _notices = MutableSharedFlow<GameNotice>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val notices: SharedFlow<GameNotice> = _notices.asSharedFlow()

    /** Huella de la partida abierta (Ajustes › Partidas no deja restaurar sobre ella). */
    val openFingerprint: StateFlow<String?> = _game.map { it?.fingerprint }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val operations = Mutex()

    // ------------------------------------------------------------------ abrir

    /** Abre [entry]. Ignora la petición si ya hay una apertura o una partida en curso (doble toque). */
    fun open(entry: RomEntry) {
        if (_opening.value || _game.value != null) return
        _opening.value = true
        scope.launch {
            try {
                when (val result = launcher.open(entry)) {
                    is OpenResult.Failed -> _dialog.value = GameDialog.OpenFailed(result.error)
                    is OpenResult.Opened -> {
                        val game = result.game
                        if (!isActive) {
                            game.close()
                            return@launch
                        }
                        try {
                            recordPlayed(entry, game.fingerprint, now())
                            game.start()
                        } catch (error: Exception) {
                            game.close()
                            _dialog.value = GameDialog.OpenFailed(OpenError.Core(error))
                            return@launch
                        }
                        _menu.value = GameMenu.None
                        _states.value = StatesUi()
                        _game.value = game
                        attachCover(game)
                        watch(game)
                        result.warning?.let { _dialog.value = GameDialog.LoadWarning(it) }
                        result.notices.forEach { _notices.tryEmit(it) }
                        if (game.hasRescueState) _notices.tryEmit(GameNotice.RescueStateExists)
                    }
                }
            } finally {
                _opening.value = false
            }
        }
    }

    /** Solo pruebas: toma una partida ya abierta (sin pasar por el lanzador). */
    internal fun adopt(game: GameSession) {
        _game.value = game
        attachCover(game)
    }

    private fun watch(game: GameSession) {
        scope.launch {
            game.events.flow.collect { event ->
                _notices.tryEmit(
                    when (event) {
                        GameEvent.MirrorTrouble -> GameNotice.MirrorTrouble
                        is GameEvent.MirrorDisabled -> GameNotice.MirrorDisabled(event.reason)
                    },
                )
            }
        }
    }

    fun dismissDialog() {
        _dialog.value = null
    }

    // ------------------------------------------------------------------ pausa

    /** Pausa (con vaciado acotado) y abre el menú. Seguro si ya está en pausa. */
    fun showPauseMenu() {
        val game = _game.value ?: return
        game.pause()
        if (_menu.value == GameMenu.None) _menu.value = GameMenu.Pause
    }

    /** Muestra el menú sin tocar la sesión (la pausa ya ocurrió por otra vía). */
    fun revealPauseMenu() {
        if (_game.value != null && _menu.value == GameMenu.None) _menu.value = GameMenu.Pause
    }

    /** Reanuda y cierra el menú. */
    fun continueGame() {
        val game = _game.value ?: return
        if (_busy.value) return
        _menu.value = GameMenu.None
        game.resume()
    }

    /**
     * `ON_PAUSE`, `ON_STOP` o pérdida del foco de audio: pausa y vacía; el menú se abre al ver la pausa. Nunca
     * lanza: una sesión que ya estaba en pausa (o que se cierra a la vez) no puede tumbar la app.
     */
    fun onBackground() {
        val game = _game.value ?: return
        val result = try {
            game.pause()
        } catch (_: Exception) {
            return
        }
        onFlushResult(result)
    }

    /** Resultado de un vaciado hecho por el ciclo de vida: si no quedó a salvo, aviso (el indicador persiste solo). */
    fun onFlushResult(result: FlushResult) {
        if (!result.isSafe) _notices.tryEmit(GameNotice.SavePending)
    }

    // ------------------------------------------------------------------ estados

    fun openStates() {
        if (_game.value == null) return
        _menu.value = GameMenu.States
        refreshStates()
    }

    fun closeStates() {
        if (_menu.value == GameMenu.States) _menu.value = GameMenu.Pause
    }

    /** «Personalizar controles»: el juego sigue en pausa y el menú deja paso al editor. */
    fun openControlsEditor() {
        if (_game.value != null && _menu.value == GameMenu.Pause) _menu.value = GameMenu.Editor
    }

    fun closeControlsEditor() {
        if (_menu.value == GameMenu.Editor) _menu.value = GameMenu.Pause
    }

    fun refreshStates() {
        val game = _game.value ?: return
        scope.launch {
            operations.withLock {
                val entries = try {
                    withContext(io) { game.states() }
                } catch (_: Exception) {
                    emptyMap()
                }
                _states.value = _states.value.copy(entries = entries)
            }
        }
    }

    fun saveState(slot: StateSlot) = stateOperation { game ->
        game.saveState(slot)
        GameNotice.StateSaved(slot)
    }

    /** [saveCurrentToAuto] `false` = «Cargar sin guardar» (K14): no toca la ranura automática. */
    fun loadState(slot: StateSlot, saveCurrentToAuto: Boolean = true) = stateOperation { game ->
        game.loadState(slot, saveCurrentToAuto)
        GameNotice.StateLoaded(slot)
    }

    fun deleteState(slot: StateSlot) = stateOperation { game ->
        game.deleteState(slot)
        GameNotice.StateDeleted(slot)
    }

    private fun stateOperation(block: (GameSession) -> GameNotice) {
        val game = _game.value ?: return
        scope.launch {
            operations.withLock {
                if (_game.value !== game) return@withLock
                _states.value = _states.value.copy(busy = true)
                _busy.value = true
                val notice = try {
                    withContext(io) { block(game) }
                } catch (error: StateError) {
                    GameNotice.StateFailed(error)
                } catch (error: Exception) {
                    GameNotice.StateFailed(StateError.Io(error))
                }
                val entries = try {
                    withContext(io) { game.states() }
                } catch (_: Exception) {
                    _states.value.entries
                }
                _states.value = StatesUi(entries, busy = false)
                _busy.value = false
                _notices.tryEmit(notice)
                // Tras cargar un estado el juego sigue en pausa con el menú abierto (Continuar lo reanuda).
            }
        }
    }

    // ------------------------------------------------------------------ salir

    /**
     * Sale de la partida. Sin [force], un fallo de la copia local abre el diálogo de reintento y la sesión
     * sigue abierta (SPEC §6). Con [force] (tras la segunda confirmación de riesgo) cierra igualmente.
     */
    fun exit(force: Boolean = false) {
        val game = _game.value ?: return
        if (_busy.value) return
        _busy.value = true
        scope.launch {
            operations.withLock {
                val result = try {
                    withContext(io) { game.exit(force) }
                } catch (error: Exception) {
                    ExitResult.LocalSaveFailed(error)
                }
                _busy.value = false
                when (result) {
                    ExitResult.Clean -> {
                        _menu.value = GameMenu.None
                        _dialog.value = null
                        _game.value = null
                    }
                    is ExitResult.LocalSaveFailed -> _dialog.value = GameDialog.ExitSaveFailed(result.error)
                }
            }
        }
    }

    /** "Salir sin guardar…": pide la segunda confirmación, en estilo de riesgo. */
    fun requestRiskyExit() {
        val current = _dialog.value as? GameDialog.ExitSaveFailed ?: return
        _dialog.value = current.copy(confirmingRisk = true)
    }

    fun cancelRiskyExit() {
        val current = _dialog.value as? GameDialog.ExitSaveFailed ?: return
        _dialog.value = current.copy(confirmingRisk = false)
    }

    /** "Seguir jugando" desde el diálogo de fallo: cierra el diálogo y reanuda. */
    fun keepPlaying() {
        _dialog.value = null
        continueGame()
    }

    /**
     * El ViewModel se destruye (la actividad termina de verdad, no una rotación). No hay UI que confirme un riesgo,
     * así que NUNCA se fuerza una salida: se pausa lo que corre (rápido, aquí) y el vaciado, el estado AUTO y el
     * cierre pasan a [rescue], fuera del hilo principal, bajo el mismo Mutex que las operaciones (espera a la que
     * esté en curso). Si el guardado no se confirma tras reintentar, la sesión queda abierta con un estado de
     * rescate escrito ([GameSession.rescueExit]) y pasa al [OrphanSessionRegistry] de la app, que la mantiene
     * bloqueada (sin reabrir ni restaurar), reintenta y la cierra sola cuando el guardado se confirma.
     */
    override fun onCleared() {
        val game = _game.value ?: return
        _game.value = null
        if (game.isClosed) return
        try {
            if (game.session.state.value == com.joelbermudez.pocketgb.emulator.SessionState.Running) game.session.pause()
        } catch (_: Exception) {
        }
        // La huella queda bloqueada YA (síncrono, antes de que el rescate corra): hasta que la sesión se cierre nadie
        // puede abrirla ni restaurarla. El registro de la app hereda la sesión si el rescate no logra cerrarla.
        orphans.claim(game)
        try {
            rescue {
                val outcome = try {
                    kotlinx.coroutines.runBlocking { operations.withLock { game.rescueExit(rescueAttempts, rescueRetryDelayMs) } }
                } catch (_: Throwable) {
                    RescueOutcome.KeptOpen
                }
                orphans.settle(game, outcome)
            }
        } catch (_: Throwable) {
            // A5V6-H4: si el rescate no puede ni encolarse (executor rechazado, Error), la sesión ya reclamada pasa de
            // inmediato al reintento del registro: nunca queda retenida sin nadie que la cierre.
            orphans.settle(game, RescueOutcome.KeptOpen)
        }
    }

    // ------------------------------------------------------------------ portada (A6-L3, K9)

    private var coverSink: ((String, IntArray) -> Unit)? = null

    /** Quién guarda la portada al cerrar una partida (huella, fotograma). Sin sumidero no se captura nada. */
    fun setCoverSink(sink: ((fingerprint: String, pixels: IntArray) -> Unit)?) {
        coverSink = sink
    }

    private fun attachCover(game: GameSession) {
        game.parkedFrameCallback = { pixels -> onClosed(game.fingerprint, pixels) }
    }

    /** Gancho de portada: lo llama [GameSession.tryClose] con la sesión aparcada. Mejor esfuerzo. */
    internal fun onClosed(fingerprint: String, pixels: IntArray) {
        coverSink?.invoke(fingerprint, pixels)
    }
}

class GameplayViewModelFactory(
    context: Context,
    private val library: LibraryViewModel,
) : ViewModelProvider.Factory {
    private val appContext = context.applicationContext

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(GameplayViewModel::class.java)) { "ViewModel desconocido: $modelClass" }
        val resolver = appContext.contentResolver
        val folders = LibraryFolderStore(appContext)
        val launcher = GameLauncher(
            roms = ContentResolverRomSource(resolver),
            savesDirectory = File(appContext.filesDir, "saves"),
            statesRoot = File(appContext.filesDir, "states"),
            mirrors = SafMirrorLocator(resolver, folders),
            hasFolderPermission = folders::hasPersistedPermission,
            emulationFor = GameplaySettingsRepository.shared(appContext).emulationProvider(),
        )
        val artwork = ArtworkStore.shared(appContext)
        return GameplayViewModel(
            launcher = launcher,
            recordPlayed = { entry, fingerprint, at -> library.recordPlayed(entry, fingerprint, at) },
        ).also { it.setCoverSink { fingerprint, pixels -> artwork.saveAsync(fingerprint, pixels) } } as T
    }
}

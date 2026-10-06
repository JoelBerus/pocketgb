package com.joelbermudez.pocketgb.game

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.joelbermudez.pocketgb.library.ContentResolverRomSource
import com.joelbermudez.pocketgb.library.LibraryFolderStore
import com.joelbermudez.pocketgb.library.LibraryViewModel
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.saves.SaveLoadWarning
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
enum class GameMenu { None, Pause, States }

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
                        recordPlayed(entry, game.fingerprint, now())
                        try {
                            game.start()
                        } catch (error: Exception) {
                            game.close()
                            _dialog.value = GameDialog.OpenFailed(OpenError.Core(error))
                            return@launch
                        }
                        _menu.value = GameMenu.None
                        _states.value = StatesUi()
                        _game.value = game
                        watch(game)
                        result.warning?.let { _dialog.value = GameDialog.LoadWarning(it) }
                    }
                }
            } finally {
                _opening.value = false
            }
        }
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

    /** `ON_PAUSE`, `ON_STOP` o pérdida del foco de audio: pausa y vacía; el menú se abre al ver la pausa. */
    fun onBackground() {
        val game = _game.value ?: return
        val result = game.pause()
        if (!result.isSafeForUi()) _notices.tryEmit(GameNotice.SavePending)
    }

    private fun com.joelbermudez.pocketgb.saves.FlushResult.isSafeForUi() =
        this == com.joelbermudez.pocketgb.saves.FlushResult.Saved || this == com.joelbermudez.pocketgb.saves.FlushResult.Unchanged

    // ------------------------------------------------------------------ estados

    fun openStates() {
        if (_game.value == null) return
        _menu.value = GameMenu.States
        refreshStates()
    }

    fun closeStates() {
        if (_menu.value == GameMenu.States) _menu.value = GameMenu.Pause
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

    fun loadState(slot: StateSlot) = stateOperation { game ->
        game.loadState(slot)
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

    override fun onCleared() {
        _game.value?.closeBestEffort()
        _game.value = null
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
        )
        return GameplayViewModel(
            launcher = launcher,
            recordPlayed = { entry, fingerprint, at -> library.recordPlayed(entry, fingerprint, at) },
        ) as T
    }
}

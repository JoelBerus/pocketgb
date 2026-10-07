package com.joelbermudez.pocketgb.settings

import android.content.Context
import com.joelbermudez.pocketgb.emulator.EmulationOptions
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Fuente de verdad de los ajustes de gameplay para toda la app. La carga y el guardado van en [io],
 * serializados por un [Mutex]; un guardado fallido deja el valor en memoria y se expone en [persistFailure]
 * como aviso no bloqueante (se reintenta con el siguiente cambio).
 */
class GameplaySettingsRepository(
    private val store: GameplaySettingsStore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow(GameplaySettingsData())
    val state: StateFlow<GameplaySettingsData> = _state.asStateFlow()

    private val _persistFailure = MutableStateFlow<IOException?>(null)
    val persistFailure: StateFlow<IOException?> = _persistFailure.asStateFlow()

    private val mutex = Mutex()
    private var loaded = false

    init {
        scope.launch { mutex.withLock { ensureLoaded() } }
    }

    /** Proveedor para `GameLauncher.emulationFor`: global + por juego (huella) a opciones del núcleo al abrir (A6-H1). */
    fun emulationProvider(): (String, Boolean) -> EmulationOptions =
        { fingerprint, isCgbRom -> _state.value.emulation(fingerprint, isCgbRom).toOptions() }

    /** N8: proveedor para `GameLauncher.gbaOptionsFor`: tipo de partida, reloj y BIOS del juego de GBA (por huella). */
    fun gbaOptionsProvider(): (String) -> com.joelbermudez.pocketgb.emulator.GbaOptions =
        { fingerprint -> _state.value.gbaOptions(fingerprint) }

    /** Aplica [change] sobre el valor actual (ya sanitizado) y lo guarda. */
    fun update(change: (GameplaySettingsData) -> GameplaySettingsData): Job = scope.launch {
        mutex.withLock {
            ensureLoaded()
            val next = change(_state.value).sanitized()
            if (next == _state.value && _persistFailure.value == null) return@withLock
            _state.value = next
            persist(next)
        }
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        try {
            _state.value = withContext(io) { store.load() }
            loaded = true
        } catch (error: IOException) {
            _persistFailure.value = error // se usa lo que haya en memoria y se reintenta la carga más tarde
        }
    }

    private suspend fun persist(data: GameplaySettingsData) {
        // Sin carga correcta no se escribe: pisaría con valores por defecto lo que el usuario tenía.
        if (!loaded) return
        _persistFailure.value = try {
            withContext(io) { store.save(data) }
            null
        } catch (error: IOException) {
            error
        }
    }

    companion object {
        @Volatile
        private var shared: GameplaySettingsRepository? = null

        /** Una sola instancia por proceso: sobrevive a la recreación de la actividad. */
        fun shared(context: Context): GameplaySettingsRepository = shared ?: synchronized(this) {
            shared ?: GameplaySettingsRepository(
                GameplaySettingsFile(File(context.applicationContext.filesDir, "gameplay-settings.json")),
                CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            ).also { shared = it }
        }
    }
}

package com.joelbermudez.pocketgb.emulator

import android.view.Surface
import com.joelbermudez.pocketgb.audio.AudioState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** Estado serializado del núcleo junto con la captura de pantalla del mismo instante (RGBA8888, 160x144). */
class SavedState(val bytes: ByteArray, val pixels: IntArray)

/**
 * Dueño del handle nativo de una partida. Invariantes de hilo (A5, reglas duras 3, 4 y 6):
 *
 * - **I1**: las transiciones (`load`, `start`, `pause`, `resume`, `stop`) y las operaciones que exigen la
 *   sesión aparcada (`loadSram`, `saveState`, `loadStateRaw`) se llaman solo desde el hilo principal.
 * - **I2**: el único otro hilo que toca la sesión es el de guardado (`pocketgb-saves`), y solo con
 *   [sramDirtySequence] y [copySram]. Ambas están protegidas por el mutex nativo y, aquí, por un lock de
 *   lectura que [close] toma en escritura: nunca se usa el handle tras liberarlo.
 * - **I3**: quien posee el hilo de guardado lo une **antes** de llamar a [close]; `handle` es `@Volatile`.
 * - **I4**: copiar la SRAM ([copySram]) y escribirla en disco ocurren en el mismo hilo de guardado, en ese
 *   orden, para que una copia vieja nunca pise a otra más nueva.
 *
 * Con la sesión corriendo solo el hilo nativo toca el core: [copySram] le pide una instantánea (espera como
 * mucho 1 s) y el estado, el framebuffer y la carga de SRAM solo se permiten aparcada.
 */
class EmulatorSession : AutoCloseable {
    @Volatile
    private var handle = NativeLibrary.nativeSessionCreate().also {
        if (it == 0L) throw CoreError.OutOfMemory()
    }
    private val handleLock = ReentrantReadWriteLock()
    @Volatile
    private var loadedInfo: RomInfo? = null
    private val mutableState = MutableStateFlow<SessionState>(SessionState.New)
    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    val frameCount: Long
        get() = NativeLibrary.nativeSessionFrameCount(requireHandle())

    val requestedButtons: Int
        get() = NativeLibrary.nativeSessionRequestedButtons(requireHandle())

    val appliedButtons: Int
        get() = NativeLibrary.nativeSessionAppliedButtons(requireHandle())

    val speed: Int
        get() = NativeLibrary.nativeSessionSpeed(requireHandle())

    val audioState: AudioState
        get() = AudioState.fromNative(NativeLibrary.nativeSessionAudioState(requireHandle()))

    val audioFramesProduced: Long
        get() = NativeLibrary.nativeSessionAudioFramesProduced(requireHandle())

    val audioFramesConsumed: Long
        get() = NativeLibrary.nativeSessionAudioFramesConsumed(requireHandle())

    /** Información del cartucho cargado; solo existe tras [load]. */
    val info: RomInfo
        get() = loadedInfo ?: throw CoreError.NoRom()

    /** Tamaño del `.sav` de esta sesión: RAM [+48 con RTC]. */
    val sramSaveSize: Int
        get() = NativeLibrary.nativeSessionSramSize(requireHandle())

    /**
     * Carga el ROM y devuelve su información. [unixTimeSeconds] inicializa el reloj del MBC3: el llamador
     * real pasa la hora actual (con 0 el RTC arranca sin hora válida, solo para tests y depuración).
     */
    fun load(rom: ByteArray, unixTimeSeconds: Long = 0L): RomInfo {
        requireState("cargar", SessionState.New)
        if (rom.size < CoreBridge.MIN_ROM_BYTES) throw CoreError.RomTooSmall()
        if (rom.size > CoreBridge.MAX_ROM_BYTES) throw CoreError.RomTooLarge()
        val nativeHandle = requireHandle()
        checkNative("cargar", NativeLibrary.nativeSessionLoad(nativeHandle, rom, unixTimeSeconds))
        val ints = IntArray(10)
        val fingerprint = ByteArray(32)
        val title = ByteArray(17)
        checkNative("leer la cabecera", NativeLibrary.nativeSessionRomInfo(nativeHandle, ints, fingerprint, title))
        val read = romInfoFromNative(ints, fingerprint, title)
        loadedInfo = read
        mutableState.value = SessionState.Ready
        return read
    }

    /** Carga la partida del cartucho. Solo con la sesión sin arrancar o en pausa (I1). */
    fun loadSram(data: ByteArray) {
        requireParkedForSram("cargar la partida")
        checkNative("cargar la partida", NativeLibrary.nativeSessionSramLoad(requireHandle(), data))
    }

    /** Crece cada vez que el juego guarda. Puede llamarse desde el hilo de guardado (I2). */
    fun sramDirtySequence(): Long = handleLock.read { NativeLibrary.nativeSessionSramDirtySeq(requireHandle()) }

    /**
     * Copia consistente de la SRAM [+RTC]. Con la sesión corriendo la entrega el hilo nativo (espera como
     * mucho 1 s: [SessionError.SnapshotTimeout]); aparcada, se copia directo. Hilo de guardado (I2, I4).
     */
    fun copySram(): ByteArray = handleLock.read {
        val nativeHandle = requireHandle()
        val out = ByteArray(NativeLibrary.nativeSessionSramSize(nativeHandle))
        checkNative("copiar la partida", NativeLibrary.nativeSessionSramCopy(nativeHandle, out))
        out
    }

    /** Estado del núcleo y captura de pantalla. Exige la sesión en pausa. */
    fun saveState(): SavedState {
        requirePaused("guardar el estado")
        val nativeHandle = requireHandle()
        val holder = arrayOfNulls<ByteArray>(1)
        checkNative("guardar el estado", NativeLibrary.nativeSessionStateSave(nativeHandle, holder))
        val bytes = holder[0] ?: throw CoreError.StateCorrupt()
        val pixels = IntArray(CoreBridge.FRAME_PIXELS)
        checkNative("copiar la pantalla", NativeLibrary.nativeSessionCopyFrame(nativeHandle, pixels))
        return SavedState(bytes, pixels)
    }

    /**
     * Aplica un estado sin más: un estado dañado o de otro ROM se rechaza antes de tocar nada, y nunca
     * sustituye la SRAM por su cuenta (la persistencia la decide `GameSession`). Exige la sesión en pausa.
     */
    fun loadStateRaw(data: ByteArray) {
        requirePaused("cargar el estado")
        checkNative("cargar el estado", NativeLibrary.nativeSessionStateLoad(requireHandle(), data))
    }

    fun start() {
        requireState("iniciar", SessionState.Ready)
        checkNativeControl("iniciar", NativeLibrary.nativeSessionStart(requireHandle()))
        mutableState.value = SessionState.Running
    }

    fun pause() {
        requireState("pausar", SessionState.Running)
        checkNativeControl("pausar", NativeLibrary.nativeSessionPause(requireHandle()))
        mutableState.value = SessionState.Paused
    }

    fun resume() {
        requireState("reanudar", SessionState.Paused)
        checkNativeControl("reanudar", NativeLibrary.nativeSessionResume(requireHandle()))
        mutableState.value = SessionState.Running
    }

    fun stop() {
        val current = mutableState.value
        if (current == SessionState.Stopped) return
        if (current !in listOf(SessionState.Ready, SessionState.Running, SessionState.Paused)) {
            throw SessionError.InvalidTransition("detener", current)
        }
        checkNativeControl("detener", NativeLibrary.nativeSessionStop(requireHandle()))
        mutableState.value = SessionState.Stopped
    }

    fun attachSurface(surface: Surface) {
        NativeLibrary.nativeSessionAttachSurface(requireHandle(), surface)
    }

    fun detachSurface() {
        val nativeHandle = handle
        if (nativeHandle == 0L) return
        NativeLibrary.nativeSessionDetachSurface(nativeHandle)
    }

    fun setTouchButtons(mask: Int) {
        NativeLibrary.nativeSessionSetTouchButtons(requireHandle(), mask and 0xFF)
    }

    fun setPhysicalButtons(mask: Int) {
        NativeLibrary.nativeSessionSetPhysicalButtons(requireHandle(), mask and 0xFF)
    }

    fun setSpeed(factor: Int) {
        NativeLibrary.nativeSessionSetSpeed(requireHandle(), factor)
    }

    override fun close() {
        // El lock de escritura espera a que acaben las lecturas del hilo de guardado (I2/I3).
        handleLock.write {
            val nativeHandle = handle
            if (nativeHandle == 0L) return
            handle = 0L
            NativeLibrary.nativeSessionDestroy(nativeHandle)
            mutableState.value = SessionState.Closed
        }
    }

    private fun checkNative(action: String, result: Int) {
        when (result) {
            0 -> Unit
            NS_BUSY -> throw SessionError.NotParked(action, mutableState.value)
            NS_TIMEOUT -> throw SessionError.SnapshotTimeout()
            else -> CoreError.fromResult(result)?.let { throw it }
        }
    }

    private fun requirePaused(action: String) {
        val current = mutableState.value
        if (current != SessionState.Paused) throw SessionError.NotParked(action, current)
    }

    private fun requireParkedForSram(action: String) {
        val current = mutableState.value
        if (current != SessionState.Paused && current != SessionState.Ready) throw SessionError.NotParked(action, current)
    }

    private fun checkNativeControl(action: String, result: Int) {
        when (result) {
            0 -> Unit
            1 -> throw SessionError.InvalidTransition(action, mutableState.value)
            else -> throw SessionError.NativeThread()
        }
    }

    private fun requireState(action: String, expected: SessionState) {
        if (mutableState.value != expected) {
            throw SessionError.InvalidTransition(action, mutableState.value)
        }
    }

    private fun requireHandle(): Long = handle.takeIf { it != 0L } ?: throw CoreError.Closed()

    private companion object {
        /** Códigos de `native_session.h`: sesión no aparcada y espera de instantánea agotada. */
        const val NS_BUSY = -1
        const val NS_TIMEOUT = -2
    }
}

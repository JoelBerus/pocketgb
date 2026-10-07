package com.joelbermudez.pocketgb.emulator

import android.view.Surface
import com.joelbermudez.pocketgb.audio.AudioState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** Estado serializado del núcleo junto con la captura de pantalla del mismo instante (RGBA8888, [EmulatorSession.screen]). */
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
 * - **Handle**: TODA llamada nativa que usa el handle ocurre bajo `handleLock.read` (la lectura es compartida,
 *   así que no se estorban); [close] toma el lock de escritura y espera a que acaben. Comprobar `handle == 0`
 *   fuera del lock y llamar después deja una ventana de uso tras liberar (A5 auditoría, DeepSeek H1 / Opus H2).
 * - **I4**: copiar la SRAM ([copySram]) y escribirla en disco ocurren en el mismo hilo de guardado, en ese
 *   orden, para que una copia vieja nunca pise a otra más nueva.
 *
 * Con la sesión corriendo solo el hilo nativo toca el core: [copySram] le pide una instantánea (espera como
 * mucho 1 s) y el estado, el framebuffer y la carga de SRAM solo se permiten aparcada.
 *
 * **Consola (N8):** [console] se fija al crearla y elige el núcleo nativo (`core/` o `gba/`); un juego de GB se
 * abre con [load] y uno de GBA con [loadGba]. Todo lo demás es común: [screen] da el tamaño del framebuffer, las
 * máscaras de botones se recortan a [Console.buttonMask] (L y R en los bits 9 y 8 en GBA) y el `.sav` de GBA es el
 * medio con los 16 bytes del RTC al final, como en iOS.
 */
open class EmulatorSession(val console: Console = Console.GB) : AutoCloseable {
    @Volatile
    private var handle = NativeLibrary.nativeSessionCreateConsole(console.native).also {
        if (it == 0L) throw CoreError.OutOfMemory()
    }
    internal val handleLock = ReentrantReadWriteLock()
    @Volatile
    private var loadedInfo: RomInfo? = null
    private val mutableState = MutableStateFlow<SessionState>(SessionState.New)
    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    val frameCount: Long
        get() = withHandle { NativeLibrary.nativeSessionFrameCount(it) }

    val requestedButtons: Int
        get() = withHandle { NativeLibrary.nativeSessionRequestedButtons(it) }

    val appliedButtons: Int
        get() = withHandle { NativeLibrary.nativeSessionAppliedButtons(it) }

    val speed: Int
        get() = withHandle { NativeLibrary.nativeSessionSpeed(it) }

    val audioState: AudioState
        get() = AudioState.fromNative(withHandle { NativeLibrary.nativeSessionAudioState(it) })

    val audioFramesProduced: Long
        get() = withHandle { NativeLibrary.nativeSessionAudioFramesProduced(it) }

    val audioFramesConsumed: Long
        get() = withHandle { NativeLibrary.nativeSessionAudioFramesConsumed(it) }

    /** Información del cartucho cargado; solo existe tras [load] o [loadGba]. */
    val info: RomInfo
        get() = loadedInfo ?: throw CoreError.NoRom()

    /** Tamaño del framebuffer de la consola: 160×144 (GB) o 240×160 (GBA). */
    val screen: ScreenSize
        get() = console.screen

    /**
     * Tamaño del `.sav` de esta sesión en este instante: GB, RAM [+48 con RTC]; GBA, medio [+16 con RTC]. En GBA con
     * EEPROM sin ajuste puede pasar de 512 a 8192 (+16) cuando el `.sav` o la primera DMA lo confirman.
     */
    val sramSaveSize: Int
        get() = withHandle { NativeLibrary.nativeSessionSramSize(it) }

    /**
     * Carga el ROM y devuelve su información. [unixTimeSeconds] inicializa el reloj del MBC3: el llamador
     * real pasa la hora actual (con 0 el RTC arranca sin hora válida, solo para tests y depuración).
     */
    fun load(rom: ByteArray, unixTimeSeconds: Long = 0L, options: EmulationOptions = EmulationOptions()): RomInfo {
        requireConsole("cargar un ROM de Game Boy", Console.GB)
        requireState("cargar", SessionState.New)
        if (rom.size < CoreBridge.MIN_ROM_BYTES) throw CoreError.RomTooSmall()
        if (rom.size > CoreBridge.MAX_ROM_BYTES) throw CoreError.RomTooLarge()
        val ints = IntArray(10)
        val fingerprint = ByteArray(32)
        val title = ByteArray(17)
        withHandle { nativeHandle ->
            checkNative(
                "cargar",
                NativeLibrary.nativeSessionLoad(
                    nativeHandle, rom, unixTimeSeconds, options.model.native, options.compatPalette,
                ),
            )
            checkNative("leer la cabecera", NativeLibrary.nativeSessionRomInfo(nativeHandle, ints, fingerprint, title))
        }
        val read = romInfoFromNative(ints, fingerprint, title)
        loadedInfo = read
        mutableState.value = SessionState.Ready
        return read
    }

    /**
     * Game Boy Advance: carga el ROM (≤ 32 MiB) y devuelve su información. [bios] es el `gba_bios.bin` del usuario
     * (o null): solo se usa con [GbaOptions.useBios] y si es la oficial; si no, el núcleo emula la BIOS y
     * [RomInfo.biosLoaded] es false (el estado para la UI lo da [GbaBios.status]). [unixTimeSeconds] es la hora UTC
     * actual (el puente la pasa a hora local para el RTC); 0 solo en tests.
     */
    fun loadGba(
        rom: ByteArray,
        unixTimeSeconds: Long = 0L,
        options: GbaOptions = GbaOptions(),
        bios: ByteArray? = null,
    ): RomInfo {
        requireConsole("cargar un ROM de GBA", Console.GBA)
        requireState("cargar", SessionState.New)
        if (rom.size < Console.GBA.minRomBytes) throw CoreError.RomTooSmall()
        if (rom.size > Console.GBA.maxRomBytes) throw CoreError.RomTooLarge(Console.GBA.maxRomBytes / (1024 * 1024))
        val ints = IntArray(GBA_INFO_INTS)
        val fingerprint = ByteArray(32)
        val title = ByteArray(GBA_INFO_TITLE)
        val codes = ByteArray(GBA_INFO_CODES)
        withHandle { nativeHandle ->
            checkNative(
                "cargar",
                NativeLibrary.nativeSessionLoadGba(
                    nativeHandle, rom, bios.takeIf { options.useBios }, unixTimeSeconds,
                    options.saveType.native, options.rtc.native,
                ),
            )
            checkNative(
                "leer la cabecera",
                NativeLibrary.nativeSessionGbaRomInfo(nativeHandle, ints, fingerprint, title, codes),
            )
        }
        val read = gbaRomInfoFromNative(ints, fingerprint, title, codes)
        loadedInfo = read
        mutableState.value = SessionState.Ready
        return read
    }

    /** Carga la partida del cartucho. Solo con la sesión sin arrancar o en pausa (I1). */
    fun loadSram(data: ByteArray) {
        requireParkedForSram("cargar la partida")
        withHandle { checkNative("cargar la partida", NativeLibrary.nativeSessionSramLoad(it, data)) }
    }

    /** Crece cada vez que el juego guarda. Puede llamarse desde el hilo de guardado (I2). */
    fun sramDirtySequence(): Long = withHandle { NativeLibrary.nativeSessionSramDirtySeq(it) }

    /**
     * Copia consistente de la SRAM [+RTC]. Con la sesión corriendo la entrega el hilo nativo (espera como
     * mucho 1 s: [SessionError.SnapshotTimeout]); aparcada, se copia directo. Hilo de guardado (I2, I4).
     * Mide lo que medía el `.sav` en ese instante (en GBA con EEPROM, 512 B u 8 KiB [+16]).
     */
    fun copySram(): ByteArray = withHandle { nativeHandle ->
        val holder = arrayOfNulls<ByteArray>(1)
        checkNative("copiar la partida", NativeLibrary.nativeSessionSramCopy(nativeHandle, holder))
        holder[0] ?: throw CoreError.BufferTooSmall()
    }

    /** Estado del núcleo y captura de pantalla. Exige la sesión en pausa. */
    open fun saveState(): SavedState {
        requirePaused("guardar el estado")
        return withHandle { nativeHandle ->
            val holder = arrayOfNulls<ByteArray>(1)
            checkNative("guardar el estado", NativeLibrary.nativeSessionStateSave(nativeHandle, holder))
            val bytes = holder[0] ?: throw CoreError.StateCorrupt()
            val pixels = IntArray(screen.pixelCount)
            checkNative("copiar la pantalla", NativeLibrary.nativeSessionCopyFrame(nativeHandle, pixels))
            SavedState(bytes, pixels)
        }
    }

    /** Solo el fotograma actual (RGBA8888, [screen]), sin serializar el estado. Exige la sesión en pausa (A6-H4). */
    open fun copyFrame(): IntArray {
        requirePaused("copiar la pantalla")
        return withHandle { nativeHandle ->
            val pixels = IntArray(screen.pixelCount)
            checkNative("copiar la pantalla", NativeLibrary.nativeSessionCopyFrame(nativeHandle, pixels))
            pixels
        }
    }

    /**
     * Aplica un estado sin más: un estado dañado o de otro ROM se rechaza antes de tocar nada, y nunca
     * sustituye la SRAM por su cuenta (la persistencia la decide `GameSession`). Exige la sesión en pausa.
     */
    open fun loadStateRaw(data: ByteArray) {
        requirePaused("cargar el estado")
        withHandle { checkNative("cargar el estado", NativeLibrary.nativeSessionStateLoad(it, data)) }
    }

    /**
     * A9 · continuación exacta: estado del núcleo con la sesión aparcada, también SIN arrancar (READY), para retomar el
     * estado automático antes de crear el hilo. No incluye la captura de pantalla ([saveState] sí).
     */
    fun saveStateParked(): ByteArray {
        requireParkedForSram("guardar el estado")
        return withHandle { nativeHandle ->
            val holder = arrayOfNulls<ByteArray>(1)
            checkNative("guardar el estado", NativeLibrary.nativeSessionStateSave(nativeHandle, holder))
            holder[0] ?: throw CoreError.StateCorrupt()
        }
    }

    /** Como [loadStateRaw] pero también con la sesión sin arrancar (READY). Nunca persiste la SRAM por su cuenta. */
    fun loadStateParked(data: ByteArray) {
        requireParkedForSram("cargar el estado")
        withHandle { checkNative("cargar el estado", NativeLibrary.nativeSessionStateLoad(it, data)) }
    }

    /**
     * Lleva el reloj del cartucho a la hora actual [unixSeconds] (UTC). GB: adelanta el del MBC3 (nunca lo retrasa; sin
     * RTC no hace nada). GBA: el RTC vuelve a la hora local más el desplazamiento que fijó el juego. Tras retomar un
     * estado, que trae la hora del momento en que se guardó (iOS D81-H5). Sesión sin arrancar o en pausa.
     */
    fun syncRtc(unixSeconds: Long) {
        requireParkedForSram("ajustar el reloj")
        withHandle { checkNative("ajustar el reloj", NativeLibrary.nativeSessionSetRtcTime(it, unixSeconds)) }
    }

    fun start() {
        requireState("iniciar", SessionState.Ready)
        withHandle { checkNativeControl("iniciar", NativeLibrary.nativeSessionStart(it)) }
        mutableState.value = SessionState.Running
    }

    open fun pause() {
        requireState("pausar", SessionState.Running)
        withHandle { checkNativeControl("pausar", NativeLibrary.nativeSessionPause(it)) }
        mutableState.value = SessionState.Paused
    }

    fun resume() {
        requireState("reanudar", SessionState.Paused)
        withHandle { checkNativeControl("reanudar", NativeLibrary.nativeSessionResume(it)) }
        mutableState.value = SessionState.Running
    }

    fun stop() {
        val current = mutableState.value
        if (current == SessionState.Stopped) return
        if (current !in listOf(SessionState.Ready, SessionState.Running, SessionState.Paused)) {
            throw SessionError.InvalidTransition("detener", current)
        }
        withHandle { checkNativeControl("detener", NativeLibrary.nativeSessionStop(it)) }
        mutableState.value = SessionState.Stopped
    }

    fun attachSurface(surface: Surface) {
        withHandle { NativeLibrary.nativeSessionAttachSurface(it, surface) }
    }

    fun detachSurface() {
        withHandleOrNull { NativeLibrary.nativeSessionDetachSurface(it) }
    }

    /**
     * Entrada de los controles. Tras cerrar la sesión (la salida cierra antes de que Compose retire la vista)
     * un toque tardío se ignora en vez de lanzar: no puede tumbar la app. La máscara se recorta a
     * [Console.buttonMask]: en GB los bits 0..7; en GBA también R (bit 8) y L (bit 9), [GbaButtonBits].
     */
    fun setTouchButtons(mask: Int) {
        withHandleOrNull { NativeLibrary.nativeSessionSetTouchButtons(it, mask and console.buttonMask) }
    }

    fun setPhysicalButtons(mask: Int) {
        withHandleOrNull { NativeLibrary.nativeSessionSetPhysicalButtons(it, mask and console.buttonMask) }
    }

    fun setSpeed(factor: Int) {
        withHandle { NativeLibrary.nativeSessionSetSpeed(it, factor) }
    }

    /** Paleta de compatibilidad pedida (0 = automática). */
    val compatPalette: Int
        get() = withHandle { NativeLibrary.nativeSessionCompatPalette(it) }

    val volume: Float
        get() = withHandle { NativeLibrary.nativeSessionVolume(it) }

    val scaleMode: ScaleMode
        get() = withHandle { ScaleMode.entries.first { mode -> mode.native == NativeLibrary.nativeSessionScaleMode(it) } }

    /**
     * Cambia la paleta de compatibilidad sin reiniciar (K8): solo con la ROM en compatibilidad CGB
     * ([RomInfo.cgbCompat]); la aplica el hilo nativo entre frames. Hilo principal (I1).
     */
    fun setCompatPalette(id: Int) {
        if (id !in 0..CoreBridge.COMPAT_PALETTES) throw CoreError.InvalidArgument()
        if (!info.cgbCompat) throw CoreError.NotCompatibilityMode()
        withHandle { checkNative("cambiar la paleta", NativeLibrary.nativeSessionSetCompatPalette(it, id)) }
    }

    /** Volumen lineal. Solo se aceptan valores finitos en 0..1; el nativo vuelve a recortar. */
    fun setVolume(gain: Float) {
        require(gain.isFinite() && gain in 0f..1f) { "Volumen fuera de 0..1: $gain" }
        withHandleOrNull { NativeLibrary.nativeSessionSetVolume(it, gain) }
    }

    fun setScaleMode(mode: ScaleMode) {
        withHandleOrNull { NativeLibrary.nativeSessionSetScaleMode(it, mode.native) }
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
            NS_INVALID -> throw CoreError.InvalidArgument()
            NS_NOT_COMPAT -> throw CoreError.NotCompatibilityMode()
            else -> CoreError.fromResult(result, console)?.let { throw it }
        }
    }

    private fun requireConsole(action: String, expected: Console) {
        if (console != expected) throw SessionError.WrongConsole(action, console)
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

    /** Ejecuta [block] con el handle vivo y el lock de lectura tomado: [close] espera a que acabe. */
    private inline fun <T> withHandle(block: (Long) -> T): T = handleLock.read {
        block(handle.takeIf { it != 0L } ?: throw CoreError.Closed())
    }

    /** Solo pruebas: llamadas JNI directas con el handle vivo, bajo el mismo lock de lectura que el resto. */
    internal fun <T> withNativeHandle(block: (Long) -> T): T = withHandle(block)

    /** Como [withHandle] pero un no-op si la sesión ya se cerró (entrada tardía de la UI). */
    private inline fun withHandleOrNull(block: (Long) -> Unit) {
        handleLock.read {
            val nativeHandle = handle
            if (nativeHandle != 0L) block(nativeHandle)
        }
    }

    private companion object {
        /** Códigos de `native_session.h`: sesión no aparcada y espera de instantánea agotada. */
        const val NS_BUSY = -1
        const val NS_TIMEOUT = -2
        const val NS_INVALID = -3
        const val NS_NOT_COMPAT = -4
    }
}

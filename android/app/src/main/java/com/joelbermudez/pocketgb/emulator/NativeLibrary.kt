package com.joelbermudez.pocketgb.emulator

import android.view.Surface

internal object NativeLibrary {
    init {
        System.loadLibrary("pocketgb")
    }

    @JvmStatic
    external fun nativeCreate(): Long

    @JvmStatic
    external fun nativeDestroy(handle: Long)

    @JvmStatic
    external fun nativeLoadRom(
        handle: Long,
        rom: ByteArray,
        model: Int,
        sampleRate: Int,
        unixTime: Long,
        compatPalette: Int,
    ): Int

    @JvmStatic
    external fun nativeRomTitle(handle: Long): String

    @JvmStatic
    external fun nativeRomMetadata(handle: Long): IntArray

    @JvmStatic
    external fun nativeRomFingerprint(handle: Long): ByteArray

    @JvmStatic
    external fun nativeRunFrame(handle: Long)

    @JvmStatic
    external fun nativeCopyFrame(handle: Long, destination: IntArray): Int

    @JvmStatic
    external fun nativeSessionCreate(): Long

    @JvmStatic
    external fun nativeSessionDestroy(handle: Long)

    @JvmStatic
    external fun nativeSessionLoad(handle: Long, rom: ByteArray, unixTime: Long, model: Int, compatPalette: Int): Int

    /** Rellena [ints] (10 campos), [fingerprint] (32) y [title] (17, ASCII terminado en NUL). Solo sesión sin arrancar. */
    @JvmStatic
    external fun nativeSessionRomInfo(handle: Long, ints: IntArray, fingerprint: ByteArray, title: ByteArray): Int

    @JvmStatic
    external fun nativeSessionSramSize(handle: Long): Int

    @JvmStatic
    external fun nativeSessionSramLoad(handle: Long, data: ByteArray): Int

    @JvmStatic
    external fun nativeSessionSramDirtySeq(handle: Long): Long

    /** Entrega la partida de ese instante en `holder[0]`, del tamaño exacto (en GBA puede crecer de 512 B a 8 KiB). */
    @JvmStatic
    external fun nativeSessionSramCopy(handle: Long, holder: Array<ByteArray?>): Int

    /** Entrega el estado en `holder[0]`. */
    @JvmStatic
    external fun nativeSessionStateSave(handle: Long, holder: Array<ByteArray?>): Int

    @JvmStatic
    external fun nativeSessionStateLoad(handle: Long, data: ByteArray): Int

    @JvmStatic
    external fun nativeSessionCopyFrame(handle: Long, out: IntArray): Int

    @JvmStatic
    external fun nativeSessionStart(handle: Long): Int

    @JvmStatic
    external fun nativeSessionPause(handle: Long): Int

    @JvmStatic
    external fun nativeSessionResume(handle: Long): Int

    @JvmStatic
    external fun nativeSessionStop(handle: Long): Int

    @JvmStatic
    external fun nativeSessionFrameCount(handle: Long): Long

    @JvmStatic
    external fun nativeSessionSetTouchButtons(handle: Long, mask: Int)

    @JvmStatic
    external fun nativeSessionSetPhysicalButtons(handle: Long, mask: Int)

    @JvmStatic
    external fun nativeSessionRequestedButtons(handle: Long): Int

    @JvmStatic
    external fun nativeSessionAppliedButtons(handle: Long): Int

    @JvmStatic
    external fun nativeSessionSetSpeed(handle: Long, speed: Int)

    @JvmStatic
    external fun nativeSessionSpeed(handle: Long): Int

    /** Paleta de compatibilidad en caliente; 0 = automática. Devuelve 0, 17 (rango) o -4 (la ROM no está en compatibilidad CGB). */
    @JvmStatic
    external fun nativeSessionSetCompatPalette(handle: Long, id: Int): Int

    @JvmStatic
    external fun nativeSessionCompatPalette(handle: Long): Int

    /** Ganancia lineal; el nativo ignora no finitos y recorta a [0,1]. */
    @JvmStatic
    external fun nativeSessionSetVolume(handle: Long, gain: Float)

    @JvmStatic
    external fun nativeSessionVolume(handle: Long): Float

    /** 0 = entero, 1 = llenar; otro valor se ignora. */
    @JvmStatic
    external fun nativeSessionSetScaleMode(handle: Long, mode: Int)

    @JvmStatic
    external fun nativeSessionScaleMode(handle: Long): Int

    @JvmStatic
    external fun nativeSessionAudioState(handle: Long): Int

    @JvmStatic
    external fun nativeSessionAudioFramesProduced(handle: Long): Long

    @JvmStatic
    external fun nativeSessionAudioFramesConsumed(handle: Long): Long

    @JvmStatic
    external fun nativeSessionAttachSurface(handle: Long, surface: Surface)

    @JvmStatic
    external fun nativeSessionDetachSurface(handle: Long)

    // ---- N8: consolas y Game Boy Advance ----

    /** `(ancho shl 16) or alto` del framebuffer de la consola (`Console.native`); 0 si no existe. */
    @JvmStatic
    external fun nativeConsoleScreenSize(console: Int): Int

    /** ¿Es [bios] la BIOS oficial de GBA (16 KiB y SHA-256)? La misma comprobación que hace la carga. */
    @JvmStatic
    external fun nativeGbaBiosIsOfficial(bios: ByteArray?): Boolean

    /** Sesión de la consola [console] (`Console.native`); 0 si no existe o falta memoria. */
    @JvmStatic
    external fun nativeSessionCreateConsole(console: Int): Long

    /** `(ancho shl 16) or alto` del framebuffer de la sesión. */
    @JvmStatic
    external fun nativeSessionScreenSize(handle: Long): Int

    /** El mayor `.sav` posible de la ROM cargada (en GBA con EEPROM sin ajuste, 8 KiB [+16] aunque hoy mida 512 B). */
    @JvmStatic
    external fun nativeSessionSramCapacity(handle: Long): Int

    /**
     * Carga un ROM de GBA en una sesión de GBA. [bios] (opcional) solo se usa si es la oficial; si no, HLE.
     * [unixTime] es la hora UTC: el puente la pasa a hora local para el RTC. [saveType] = `GbaSaveType.native`,
     * [rtc] = `GbaRtc.native`; fuera de rango, 17.
     */
    @JvmStatic
    external fun nativeSessionLoadGba(
        handle: Long,
        rom: ByteArray,
        bios: ByteArray?,
        unixTime: Long,
        saveType: Int,
        rtc: Int,
    ): Int

    /** Rellena [ints] (8), [fingerprint] (32), [title] (13) y [codes] (8). Solo sesión GBA cargada y sin arrancar. */
    @JvmStatic
    external fun nativeSessionGbaRomInfo(
        handle: Long,
        ints: IntArray,
        fingerprint: ByteArray,
        title: ByteArray,
        codes: ByteArray,
    ): Int

    /** Núcleo GBA suelto (`CoreBridge(Console.GBA)`). */
    @JvmStatic
    external fun nativeGbaCreate(): Long

    @JvmStatic
    external fun nativeGbaDestroy(handle: Long)

    @JvmStatic
    external fun nativeGbaLoadRom(handle: Long, rom: ByteArray, bios: ByteArray?, unixTime: Long, saveType: Int, rtc: Int): Int

    @JvmStatic
    external fun nativeGbaRomInfo(
        handle: Long,
        ints: IntArray,
        fingerprint: ByteArray,
        title: ByteArray,
        codes: ByteArray,
        requestedSaveType: Int,
    ): Int

    @JvmStatic
    external fun nativeGbaRunFrame(handle: Long)

    @JvmStatic
    external fun nativeGbaCopyFrame(handle: Long, destination: IntArray): Int
}

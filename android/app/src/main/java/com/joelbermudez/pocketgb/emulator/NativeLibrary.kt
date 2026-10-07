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

    @JvmStatic
    external fun nativeSessionSramCopy(handle: Long, out: ByteArray): Int

    /** Entrega el estado en `holder[0]`. */
    @JvmStatic
    external fun nativeSessionStateSave(handle: Long, holder: Array<ByteArray?>): Int

    @JvmStatic
    external fun nativeSessionStateLoad(handle: Long, data: ByteArray): Int

    @JvmStatic
    external fun nativeSessionCopyFrame(handle: Long, out: IntArray): Int

    /** A9: reloj del MBC3 a [unixTime] (segundos), nunca hacia atrás. Solo con la sesión aparcada. */
    @JvmStatic
    external fun nativeSessionSetRtcTime(handle: Long, unixTime: Long): Int

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
}

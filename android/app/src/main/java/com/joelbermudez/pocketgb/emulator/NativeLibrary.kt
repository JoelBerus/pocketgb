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
    external fun nativeSessionLoad(handle: Long, rom: ByteArray, unixTime: Long): Int

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

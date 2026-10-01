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
    external fun nativeSessionLoad(handle: Long, rom: ByteArray): Int

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
    external fun nativeSessionAttachSurface(handle: Long, surface: Surface)

    @JvmStatic
    external fun nativeSessionDetachSurface(handle: Long)
}

package com.joelbermudez.pocketgb.emulator

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
}

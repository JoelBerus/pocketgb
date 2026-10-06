package com.joelbermudez.pocketgb.emulator

class CoreBridge : AutoCloseable {
    private var handle: Long = NativeLibrary.nativeCreate().also {
        if (it == 0L) throw CoreError.OutOfMemory()
    }

    fun loadRom(rom: ByteArray, options: CoreOptions = CoreOptions()): RomInfo {
        if (options.compatPalette !in 0..COMPAT_PALETTES) throw CoreError.InvalidArgument()
        if (rom.size < MIN_ROM_BYTES) throw CoreError.RomTooSmall()
        if (rom.size > MAX_ROM_BYTES) throw CoreError.RomTooLarge()
        val nativeHandle = requireHandle()
        CoreError.fromResult(
            NativeLibrary.nativeLoadRom(
                handle = nativeHandle,
                rom = rom,
                model = options.model.nativeValue,
                sampleRate = options.sampleRate,
                unixTime = options.unixTime,
                compatPalette = options.compatPalette,
            ),
        )?.let { throw it }
        val metadata = NativeLibrary.nativeRomMetadata(nativeHandle)
        check(metadata.size == METADATA_FIELDS) { "Metadatos nativos incompletos" }
        return RomInfo(
            title = NativeLibrary.nativeRomTitle(nativeHandle),
            cgbFlag = metadata[0],
            cartType = metadata[1],
            romBytes = metadata[2],
            sramBytes = metadata[3],
            hasBattery = metadata[4] != 0,
            hasRtc = metadata[5] != 0,
            headerChecksumOk = metadata[6] != 0,
            globalChecksumOk = metadata[7] != 0,
            cgbMode = metadata[8] != 0,
            cgbCompat = metadata[9] != 0,
            fingerprint = NativeLibrary.nativeRomFingerprint(nativeHandle),
        )
    }

    fun runFrame() {
        NativeLibrary.nativeRunFrame(requireHandle())
    }

    fun copyFrame(destination: IntArray) {
        require(destination.size == FRAME_PIXELS) { "El framebuffer debe tener $FRAME_PIXELS píxeles" }
        CoreError.fromResult(NativeLibrary.nativeCopyFrame(requireHandle(), destination))?.let { throw it }
    }

    override fun close() {
        val nativeHandle = handle
        if (nativeHandle == 0L) return
        handle = 0L
        NativeLibrary.nativeDestroy(nativeHandle)
    }

    private fun requireHandle(): Long = handle.takeIf { it != 0L } ?: throw CoreError.Closed()

    companion object {
        const val SCREEN_WIDTH = 160
        const val SCREEN_HEIGHT = 144
        const val FRAME_PIXELS = SCREEN_WIDTH * SCREEN_HEIGHT
        const val MIN_ROM_BYTES = 0x150
        const val COMPAT_PALETTES = 12
        const val MAX_ROM_BYTES = 8 * 1024 * 1024
        private const val METADATA_FIELDS = 10
    }
}

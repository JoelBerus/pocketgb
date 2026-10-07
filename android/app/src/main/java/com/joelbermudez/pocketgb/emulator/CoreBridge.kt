package com.joelbermudez.pocketgb.emulator

/**
 * Núcleo suelto, sin hilo ni audio: lectura de la cabecera para la biblioteca y frames deterministas en las pruebas.
 * [console] elige el núcleo (`core/` o `gba/`) y fija [screen]; no es thread-safe (un solo hilo).
 */
class CoreBridge(val console: Console = Console.GB) : AutoCloseable {
    private var handle: Long = when (console) {
        Console.GB -> NativeLibrary.nativeCreate()
        Console.GBA -> NativeLibrary.nativeGbaCreate()
    }.also {
        if (it == 0L) throw CoreError.OutOfMemory()
    }

    /** Tamaño del framebuffer de [console]: 160×144 o 240×160. */
    val screen: ScreenSize get() = console.screen

    fun loadRom(rom: ByteArray, options: CoreOptions = CoreOptions()): RomInfo {
        requireConsole(Console.GB, "cargar un ROM de Game Boy")
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

    /**
     * Game Boy Advance: carga el ROM (≤ 32 MiB) con [options]; [bios] solo se usa si es la oficial ([GbaBios]) y
     * [GbaOptions.useBios]. [unixTimeSeconds] es UTC (el RTC cuenta hora local). Cada carga usa un núcleo nuevo
     * (N8-H2): una BIOS o un ROM de una carga anterior nunca siguen activos en la siguiente.
     */
    fun loadGbaRom(
        rom: ByteArray,
        options: GbaOptions = GbaOptions(),
        bios: ByteArray? = null,
        unixTimeSeconds: Long = 0L,
    ): RomInfo {
        requireConsole(Console.GBA, "cargar un ROM de GBA")
        if (rom.size < Console.GBA.minRomBytes) throw CoreError.RomTooSmall()
        if (rom.size > Console.GBA.maxRomBytes) throw CoreError.RomTooLarge(Console.GBA.maxRomBytes / (1024 * 1024))
        val previous = requireHandle()
        val nativeHandle = NativeLibrary.nativeGbaCreate().takeIf { it != 0L } ?: throw CoreError.OutOfMemory()
        handle = nativeHandle
        NativeLibrary.nativeGbaDestroy(previous)
        checkResult(
            NativeLibrary.nativeGbaLoadRom(
                nativeHandle, rom, bios.takeIf { options.useBios }, unixTimeSeconds, options.saveType.native, options.rtc.native,
            ),
        )
        val ints = IntArray(GBA_INFO_INTS)
        val fingerprint = ByteArray(32)
        val title = ByteArray(GBA_INFO_TITLE)
        val codes = ByteArray(GBA_INFO_CODES)
        checkResult(NativeLibrary.nativeGbaRomInfo(nativeHandle, ints, fingerprint, title, codes, options.saveType.native))
        return gbaRomInfoFromNative(ints, fingerprint, title, codes)
    }

    fun runFrame() {
        val nativeHandle = requireHandle()
        when (console) {
            Console.GB -> NativeLibrary.nativeRunFrame(nativeHandle)
            Console.GBA -> NativeLibrary.nativeGbaRunFrame(nativeHandle)
        }
    }

    fun copyFrame(destination: IntArray) {
        require(destination.size == screen.pixelCount) { "El framebuffer debe tener ${screen.pixelCount} píxeles" }
        val nativeHandle = requireHandle()
        checkResult(
            when (console) {
                Console.GB -> NativeLibrary.nativeCopyFrame(nativeHandle, destination)
                Console.GBA -> NativeLibrary.nativeGbaCopyFrame(nativeHandle, destination)
            },
        )
    }

    override fun close() {
        val nativeHandle = handle
        if (nativeHandle == 0L) return
        handle = 0L
        when (console) {
            Console.GB -> NativeLibrary.nativeDestroy(nativeHandle)
            Console.GBA -> NativeLibrary.nativeGbaDestroy(nativeHandle)
        }
    }

    private fun requireHandle(): Long = handle.takeIf { it != 0L } ?: throw CoreError.Closed()

    private fun requireConsole(expected: Console, action: String) {
        if (console != expected) throw SessionError.WrongConsole(action, console)
    }

    private fun checkResult(code: Int) {
        CoreError.fromResult(code, console)?.let { throw it }
    }

    companion object {
        /**
         * Pantalla de **Game Boy** (= `Console.GB.screen`): la usan los llamadores que aún no distinguen consola
         * (vídeo, portadas, miniaturas); el lote «N8 Kotlin» los pasa a [screen] / [Console.screen].
         */
        const val SCREEN_WIDTH = 160
        const val SCREEN_HEIGHT = 144
        const val FRAME_PIXELS = SCREEN_WIDTH * SCREEN_HEIGHT
        const val MIN_ROM_BYTES = 0x150
        const val COMPAT_PALETTES = 12
        const val MAX_ROM_BYTES = 8 * 1024 * 1024
        private const val METADATA_FIELDS = 10
    }
}

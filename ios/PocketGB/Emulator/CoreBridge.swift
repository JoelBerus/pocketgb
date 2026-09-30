import Foundation
import PocketGBCore

/// Error del núcleo convertido a Swift. Los textos son para mostrar a Joel.
enum CoreError: Error, Equatable, CustomStringConvertible {
    case nullArgument, outOfMemory, romTooSmall, romTooLarge, romTruncated
    case badRomSizeCode, badRamSizeCode, unsupportedMBC, cgbOnly, noROM
    case sramSize, stateMagic, stateVersion, stateROMMismatch, stateCorrupt
    case bufferTooSmall
    case unknown(UInt32)

    /// `nil` si `r == GB_OK`.
    init?(_ r: gb_result) {
        switch r {
        case GB_OK: return nil
        case GB_ERR_NULL_ARG: self = .nullArgument
        case GB_ERR_OUT_OF_MEMORY: self = .outOfMemory
        case GB_ERR_ROM_TOO_SMALL: self = .romTooSmall
        case GB_ERR_ROM_TOO_LARGE: self = .romTooLarge
        case GB_ERR_ROM_TRUNCATED: self = .romTruncated
        case GB_ERR_BAD_ROM_SIZE_CODE: self = .badRomSizeCode
        case GB_ERR_BAD_RAM_SIZE_CODE: self = .badRamSizeCode
        case GB_ERR_UNSUPPORTED_MBC: self = .unsupportedMBC
        case GB_ERR_CGB_ONLY: self = .cgbOnly
        case GB_ERR_NO_ROM: self = .noROM
        case GB_ERR_SRAM_SIZE: self = .sramSize
        case GB_ERR_STATE_MAGIC: self = .stateMagic
        case GB_ERR_STATE_VERSION: self = .stateVersion
        case GB_ERR_STATE_ROM_MISMATCH: self = .stateROMMismatch
        case GB_ERR_STATE_CORRUPT: self = .stateCorrupt
        case GB_ERR_BUFFER_TOO_SMALL: self = .bufferTooSmall
        default: self = .unknown(r.rawValue)
        }
    }

    var description: String {
        switch self {
        case .romTooSmall, .romTruncated, .badRomSizeCode, .badRamSizeCode:
            return "El archivo no parece un ROM de Game Boy válido."
        case .romTooLarge: return "El ROM supera los 8 MiB."
        case .unsupportedMBC: return "Tipo de cartucho no soportado todavía."
        case .cgbOnly: return "Este juego es solo de Game Boy Color (llega en M8)."
        case .sramSize: return "La partida guardada tiene un tamaño distinto al esperado."
        case .outOfMemory: return "Sin memoria para cargar el ROM."
        case .unknown(let code): return "Error desconocido del núcleo (\(code))."
        case .stateMagic, .stateCorrupt: return "El estado está dañado."
        case .stateVersion: return "El estado es de una versión anterior de PocketGB."
        case .stateROMMismatch: return "El estado es de otro juego, o de otro modo (Game Boy o Game Boy Color)."
        case .nullArgument, .noROM, .bufferTooSmall:
            return "Error interno del núcleo."
        }
    }
}

/// Datos de la cabecera que necesita la app.
struct RomInfo: Sendable {
    let title: String
    let cartType: UInt8
    let sramBytes: Int
    let hasBattery: Bool
    let hasRTC: Bool
    let headerChecksumOK: Bool
    /// Primeros 32 caracteres hex del SHA-256 del ROM (docs/02 §Datos persistentes).
    let fingerprint: String
}

/// Dueño del puntero `gb*`. La instancia del núcleo no es thread-safe:
/// tras `EmulatorSession.start` solo se usa desde el hilo de emulación.
final class CoreBridge {
    private let g: OpaquePointer

    init() throws(CoreError) {
        guard let g = gb_create() else { throw .outOfMemory }
        self.g = g
    }

    deinit { gb_destroy(g) }

    /// Copia el ROM dentro del núcleo (`gb_load_rom` no retiene `data`).
    /// - Parameters:
    ///   - colorForGameBoy: un juego de Game Boy se ejecuta en una Game Boy Color con paleta
    ///     de color (`GB_MODEL_CGB`); si no, cada juego en su consola (`GB_MODEL_AUTO`).
    ///   - compatPalette: 0 = automática; 1…12 = combinaciones del arranque de la CGB.
    func loadROM(_ data: Data, unixTime: Int64, sampleRate: UInt32 = 0, colorForGameBoy: Bool = false,
                 compatPalette: UInt8 = 0) throws(CoreError) -> RomInfo {
        var opts = gb_options()
        gb_options_default(&opts)
        opts.sample_rate = sampleRate
        opts.unix_time = unixTime
        opts.model = colorForGameBoy ? GB_MODEL_CGB : GB_MODEL_AUTO
        opts.compat_palette = compatPalette
        let r = data.withUnsafeBytes { raw in
            gb_load_rom(g, raw.bindMemory(to: UInt8.self).baseAddress, raw.count, &opts)
        }
        if let e = CoreError(r) { throw e }

        var info = gb_rom_info()
        if let e = CoreError(gb_rom_info_get(g, &info)) { throw e }
        let title = withUnsafeBytes(of: info.title) { raw in
            String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self)
        }
        let fingerprint = withUnsafeBytes(of: info.fingerprint) { raw in
            raw.prefix(16).map { String(format: "%02x", $0) }.joined()
        }
        return RomInfo(title: title, cartType: info.cart_type, sramBytes: Int(info.sram_bytes),
                       hasBattery: info.has_battery, hasRTC: info.has_rtc,
                       headerChecksumOK: info.header_checksum_ok, fingerprint: fingerprint)
    }

    func setButtons(_ mask: UInt8) { gb_set_buttons(g, mask) }
    func runFrame() { gb_run_frame(g) }
    var cpuLocked: Bool { gb_cpu_locked(g) }

    /// Copia el framebuffer RGBA8888 (160×144) a `dst`.
    func copyFramebuffer(to dst: UnsafeMutablePointer<UInt32>) {
        guard let src = gb_framebuffer(g) else { return }
        dst.update(from: src, count: FrameBuffers.pixelCount)
    }

    /// Drena frames estéreo intercalados al buffer que posee la sesión.
    func readAudio(into dst: UnsafeMutablePointer<Int16>, maxFrames: Int) -> Int {
        Int(gb_audio_read(g, dst, maxFrames))
    }

    // MARK: SRAM

    var sramSaveSize: Int { gb_sram_save_size(g) }
    var sramDirty: Bool { gb_sram_dirty(g) }
    func clearSRAMDirty() { gb_sram_clear_dirty(g) }

    func sramLoad(_ data: Data) throws(CoreError) {
        let r = data.withUnsafeBytes { raw in
            gb_sram_load(g, raw.bindMemory(to: UInt8.self).baseAddress, raw.count)
        }
        if let e = CoreError(r) { throw e }
    }

    func sramSave() throws(CoreError) -> Data {
        var out = Data(count: sramSaveSize)
        let count = out.count
        let r = out.withUnsafeMutableBytes { raw in
            gb_sram_save(g, raw.bindMemory(to: UInt8.self).baseAddress, count)
        }
        if let e = CoreError(r) { throw e }
        return out
    }

    func setRTCTime(_ unixTime: Int64) { gb_rtc_set_time(g, unixTime) }

    // MARK: Save states

    func stateSave() throws(CoreError) -> Data {
        var out = Data(count: gb_state_size(g))
        let count = out.count
        let r = out.withUnsafeMutableBytes { raw in
            gb_state_save(g, raw.bindMemory(to: UInt8.self).baseAddress, count)
        }
        if let e = CoreError(r) { throw e }
        return out
    }

    /// Carga en dos pasadas: si falla (firma, versión, otro ROM, corrupto), el núcleo
    /// queda como estaba, SRAM incluida.
    func stateLoad(_ data: Data) throws(CoreError) {
        let r = data.withUnsafeBytes { raw in
            gb_state_load(g, raw.bindMemory(to: UInt8.self).baseAddress, raw.count)
        }
        if let e = CoreError(r) { throw e }
    }
}

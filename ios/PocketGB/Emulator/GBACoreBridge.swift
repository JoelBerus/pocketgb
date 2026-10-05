import Foundation
import PocketGBACore

extension CoreError {
    /// `nil` si `r == GBA_OK`. Los errores comunes usan los mismos casos que el núcleo GB.
    init?(gba r: gba_result) {
        switch r {
        case GBA_OK: return nil
        case GBA_ERR_NULL_ARG: self = .nullArgument
        case GBA_ERR_OUT_OF_MEMORY: self = .outOfMemory
        case GBA_ERR_ROM_TOO_SMALL: self = .gbaBadHeader
        case GBA_ERR_ROM_TOO_LARGE: self = .gbaRomTooLarge
        case GBA_ERR_BAD_HEADER: self = .gbaBadHeader
        case GBA_ERR_NO_ROM: self = .noROM
        case GBA_ERR_BIOS_SIZE: self = .biosSize
        case GBA_ERR_SAVE_SIZE: self = .sramSize
        case GBA_ERR_STATE_MAGIC: self = .stateMagic
        case GBA_ERR_STATE_VERSION: self = .stateVersion
        case GBA_ERR_STATE_ROM_MISMATCH: self = .stateROMMismatch
        case GBA_ERR_STATE_CORRUPT: self = .stateCorrupt
        case GBA_ERR_BUFFER_TOO_SMALL: self = .bufferTooSmall
        default: self = .unknown(r.rawValue)
        }
    }
}

/// Dueño del puntero `gba*` (núcleo `gba/`, docs/10-gba-spec.md).
///
/// La partida es un único bloque: los bytes del medio (SRAM, Flash o EEPROM, compatibles
/// con mGBA/VBA) y, si el cartucho tiene RTC, sus 16 bytes al final. Así el `.sav` sigue
/// una sola ruta atómica con backups y un solo espejo junto al ROM (regla dura 6).
final class GBACoreBridge: ConsoleCore {
    let console = Console.gameBoyAdvance
    private let g: OpaquePointer
    private var hasRTC = false

    /// SHA-256 de la BIOS oficial (GBA, GBA SP, Micro y Game Boy Player).
    static let knownBIOSSHA256 = "fd2547724b505f487e6dcb29ec2ecff3af35a841a77ab2e85fd87350abd36570"
    static let biosBytes = Int(GBA_BIOS_BYTES)
    static let rtcBytes = Int(GBA_RTC_BYTES)
    static let maxROMBytes = 32 * 1024 * 1024   // GBA_ROM_MAX_BYTES

    init() throws(CoreError) {
        guard let g = gba_create() else { throw .outOfMemory }
        self.g = g
    }

    deinit { gba_destroy(g) }

    /// - Parameters:
    ///   - bios: volcado propio del usuario ya validado (`BIOSFile`); `nil` = BIOS HLE.
    ///   - unixTime: hora UTC; el RTC del GBA cuenta hora local.
    func loadROM(_ data: Data, bios: Data?, unixTime: Int64, sampleRate: UInt32 = 0) throws(CoreError) -> RomInfo {
        if let bios {
            let r = bios.withUnsafeBytes { raw in
                gba_load_bios(g, raw.bindMemory(to: UInt8.self).baseAddress, raw.count)
            }
            if let e = CoreError(gba: r) { throw e }
        }
        var opts = gba_options()
        gba_options_default(&opts)
        opts.sample_rate = sampleRate
        opts.unix_time = Self.localTime(unixTime)
        let r = data.withUnsafeBytes { raw in
            gba_load_rom(g, raw.bindMemory(to: UInt8.self).baseAddress, raw.count, &opts)
        }
        if let e = CoreError(gba: r) { throw e }

        var info = gba_rom_info()
        if let e = CoreError(gba: gba_rom_info_get(g, &info)) { throw e }
        let title = withUnsafeBytes(of: info.title) { raw in
            String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self)
        }
        let fingerprint = withUnsafeBytes(of: info.fingerprint) { raw in
            raw.prefix(16).map { String(format: "%02x", $0) }.joined()
        }
        hasRTC = info.has_rtc
        let eeprom = info.save_type == GBA_SAVE_EEPROM512 || info.save_type == GBA_SAVE_EEPROM8K
        var rom = RomInfo(title: title, cartType: 0, sramBytes: Int(info.save_bytes),
                          hasBattery: info.save_bytes > 0 || info.has_rtc, hasRTC: info.has_rtc,
                          headerChecksumOK: info.header_checksum_ok, fingerprint: fingerprint)
        rom.console = .gameBoyAdvance
        rom.eeprom = eeprom
        rom.biosLoaded = info.bios_loaded
        return rom
    }

    /// Tamaños de `.sav` que acepta `sramLoad`: el medio (EEPROM: 512 B u 8 KiB) y, con RTC,
    /// también con sus 16 bytes al final.
    static func validSaveSizes(_ info: RomInfo) -> Set<Int> {
        let media: Set<Int> = info.eeprom ? [512, 8192] : [info.sramBytes]
        guard info.hasRTC else { return media }
        return media.union(media.map { $0 + rtcBytes })
    }

    func setButtons(_ mask: UInt8) { gba_set_buttons(g, UInt16(mask)) }
    func runFrame() { gba_run_frame(g) }
    /// El ARM7TDMI no tiene un estado de bloqueo como el `STOP`/opcode ilegal del LR35902.
    var cpuLocked: Bool { false }

    func copyFramebuffer(to dst: UnsafeMutablePointer<UInt32>) {
        guard let src = gba_framebuffer(g) else { return }
        dst.update(from: src, count: ScreenSize.gameBoyAdvance.pixelCount)
    }

    func readAudio(into dst: UnsafeMutablePointer<Int16>, maxFrames: Int) -> Int {
        Int(gba_audio_read(g, dst, maxFrames))
    }

    // MARK: Partida

    var sramSaveSize: Int { gba_save_size(g) + (hasRTC ? Self.rtcBytes : 0) }
    var sramDirty: Bool { gba_save_dirty(g) }
    func clearSRAMDirty() { gba_save_clear_dirty(g) }

    /// Acepta el medio solo o el medio + RTC. Ante un tamaño inesperado no cambia nada.
    func sramLoad(_ data: Data) throws(CoreError) {
        var media = data
        var rtc: Data?
        if hasRTC, data.count > Self.rtcBytes, Self.isMediaSize(data.count - Self.rtcBytes) {
            media = data.prefix(data.count - Self.rtcBytes)
            rtc = data.suffix(Self.rtcBytes)
        }
        guard Self.isMediaSize(media.count) else { throw .sramSize }
        if let rtc {
            // El RTC primero: si no es válido no se toca la partida.
            let r = rtc.withUnsafeBytes { raw in
                gba_rtc_load(g, raw.bindMemory(to: UInt8.self).baseAddress, raw.count)
            }
            if let e = CoreError(gba: r) { throw e }
        }
        let r = media.withUnsafeBytes { raw in
            gba_save_load(g, raw.bindMemory(to: UInt8.self).baseAddress, raw.count)
        }
        if let e = CoreError(gba: r) { throw e }
    }

    private static func isMediaSize(_ n: Int) -> Bool {
        [512, 8192, 32 * 1024, 64 * 1024, 128 * 1024].contains(n)
    }

    func sramSave() throws(CoreError) -> Data {
        let mediaBytes = gba_save_size(g)
        var out = Data(count: mediaBytes + (hasRTC ? Self.rtcBytes : 0))
        let r = out.withUnsafeMutableBytes { raw in
            gba_save_write(g, raw.bindMemory(to: UInt8.self).baseAddress, mediaBytes)
        }
        if let e = CoreError(gba: r) { throw e }
        if hasRTC {
            let r2 = out.withUnsafeMutableBytes { raw in
                gba_rtc_save(g, raw.bindMemory(to: UInt8.self).baseAddress! + mediaBytes, Self.rtcBytes)
            }
            if let e = CoreError(gba: r2) { throw e }
        }
        return out
    }

    func setRTCTime(_ unixTime: Int64) { gba_rtc_set_time(g, Self.localTime(unixTime)) }

    private static func localTime(_ unixTime: Int64) -> Int64 {
        unixTime + Int64(TimeZone.current.secondsFromGMT(for: Date(timeIntervalSince1970: TimeInterval(unixTime))))
    }

    // MARK: Save states

    func stateSave() throws(CoreError) -> Data {
        var out = Data(count: gba_state_size(g))
        let count = out.count
        let r = out.withUnsafeMutableBytes { raw in
            gba_state_save(g, raw.bindMemory(to: UInt8.self).baseAddress, count)
        }
        if let e = CoreError(gba: r) { throw e }
        return out
    }

    /// Validado sobre una copia: si falla, el núcleo queda como estaba, partida incluida.
    func stateLoad(_ data: Data) throws(CoreError) {
        let r = data.withUnsafeBytes { raw in
            gba_state_load(g, raw.bindMemory(to: UInt8.self).baseAddress, raw.count)
        }
        if let e = CoreError(gba: r) { throw e }
    }
}

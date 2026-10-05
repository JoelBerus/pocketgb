import Foundation
import Testing
@testable import PocketGB

/// G7: Game Boy Advance en la app (biblioteca, sesión y partidas). El ROM es sintético:
/// cabecera mínima y un programa ARM que escribe un byte en la SRAM y se queda en bucle.
@MainActor
struct GBATests {
    let dir: URL

    init() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    /// ROM de 1 KiB: `b 0xC0` en la entrada, cabecera con el byte fijo 0x96 y su complemento,
    /// cadena `SRAM_V113` (SRAM de 32 KiB) y, en 0xC0, `*(u8*)0x0E000000 = value; b .`.
    static func rom(title: String = "POCKETGBA", value: UInt8 = 0x42) -> Data {
        var rom = [UInt8](repeating: 0, count: 0x400)
        func word(_ at: Int, _ w: UInt32) {
            for k in 0..<4 { rom[at + k] = UInt8(truncatingIfNeeded: w >> (8 * UInt32(k))) }
        }
        word(0x00, 0xEA00_002E)                     // b 0x080000C0
        for (i, c) in title.utf8.prefix(12).enumerated() { rom[0xA0 + i] = c }
        rom[0xB2] = 0x96
        var sum: UInt8 = 0
        for i in 0xA0...0xBC { sum = sum &- rom[i] }
        rom[0xBD] = sum &- 0x19
        word(0xC0, 0xE3A0_040E)                     // mov r0, #0x0E000000
        word(0xC4, 0xE3A0_1000 | UInt32(value))     // mov r1, #value
        word(0xC8, 0xE5C0_1000)                     // strb r1, [r0]
        word(0xCC, 0xEAFF_FFFE)                     // b .
        let tag = Array("SRAM_V113".utf8)
        rom.replaceSubrange(0x200..<(0x200 + tag.count), with: tag)
        return Data(rom)
    }

    @Test func headerParsesTitleAndChecksum() {
        let info = RomHeader.parseGBA(Self.rom(title: "ARM TEST"))
        #expect(info == .init(title: "ARM TEST", isColor: false, checksumOK: true))
        var bad = Self.rom()
        bad[0xB2] = 0
        #expect(RomHeader.parseGBA(bad) == nil)
        #expect(RomHeader.parseGBA(Data(count: 0x80)) == nil)
    }

    @Test func scannerListsGBAAndChecksItsLimit() throws {
        try Self.rom(title: "ALFA").write(to: dir.appendingPathComponent("alfa.gba"))
        var bad = Self.rom()
        bad[0xB2] = 0
        try bad.write(to: dir.appendingPathComponent("roto.gba"))
        let big = dir.appendingPathComponent("grande.gba")
        FileManager.default.createFile(atPath: big.path, contents: nil)
        let handle = try FileHandle(forWritingTo: big)
        try handle.truncate(atOffset: UInt64(GBACoreBridge.maxROMBytes + 1))   // disperso: no ocupa disco
        try handle.close()
        let entries = LibraryScanner.scan(folder: dir)
        let alfa = try #require(entries.first { $0.id == "alfa.gba" })
        #expect(alfa.title == "ALFA" && alfa.badge == .gba && alfa.console == .gameBoyAdvance && alfa.isPlayable)
        #expect(entries.first { $0.id == "roto.gba" }?.problem == .invalidHeaderGBA)
        #expect(entries.first { $0.id == "grande.gba" }?.problem == .tooLargeGBA)
        #expect(LibraryQuery.matches(alfa, filter: .gba, isFavorite: false))
        #expect(!LibraryQuery.matches(alfa, filter: .gb, isFavorite: false))
    }

    @Test func coreRunsTheROMAndSavesSRAM() throws {
        let core = try GBACoreBridge()
        let info = try core.loadROM(Self.rom(), bios: nil, unixTime: 0)
        #expect(info.console == .gameBoyAdvance && info.title == "POCKETGBA" && info.headerChecksumOK)
        #expect(info.hasBattery && info.sramBytes == 32 * 1024 && !info.hasRTC && !info.biosLoaded)
        #expect(EmulatorSession.validSaveSizes(info) == [32 * 1024])
        core.runFrame()
        let sram = try core.sramSave()
        #expect(sram.count == 32 * 1024 && sram[0] == 0x42)
        #expect(throws: CoreError.sramSize) { try core.sramLoad(Data(count: 1000)) }
        #expect(try core.sramSave() == sram)

        var pixels = [UInt32](repeating: 0, count: ScreenSize.gameBoyAdvance.pixelCount)
        pixels.withUnsafeMutableBufferPointer { core.copyFramebuffer(to: $0.baseAddress!) }
        #expect(ScreenSize(pixelCount: pixels.count) == .gameBoyAdvance)
    }

    @Test func statesRoundTripAndRejectGBStates() throws {
        let core = try GBACoreBridge()
        _ = try core.loadROM(Self.rom(), bios: nil, unixTime: 0)
        core.runFrame()
        let state = try core.stateSave()
        try core.stateLoad(state)
        let other = try GBACoreBridge()
        _ = try other.loadROM(Self.rom(title: "OTRO"), bios: nil, unixTime: 0)
        #expect(throws: CoreError.stateROMMismatch) { try other.stateLoad(state) }
        #expect(throws: CoreError.stateMagic) { try core.stateLoad(Data("PGBS-demo".utf8)) }
    }

    /// Criterio de G7: una partida GBA con un tamaño que no corresponde no se carga ni se
    /// sobrescribe, aunque el juego escriba en la SRAM.
    @Test func wrongSizeGBASaveIsNeverOverwritten() throws {
        let probe = try GBACoreBridge()
        let fingerprint = try probe.loadROM(Self.rom(), bios: nil, unixTime: 0).fingerprint
        let store = SaveStore(directory: dir, fingerprint: fingerprint)
        let foreign = Data(repeating: 0x5A, count: 1000)
        try store.save(foreign)

        let session = try EmulatorSession(romData: Self.rom(), savesDirectory: dir,
                                          console: .gameBoyAdvance, onAudioInterrupted: {})
        #expect(session.loadWarning == .localWrongSize)
        #expect(session.frames.size == .gameBoyAdvance)
        session.start()
        Thread.sleep(forTimeInterval: 0.5)
        session.pause()
        session.stop()
        #expect(try store.load() == foreign)
    }

    /// Auditoría Codex G7-2/G7-4: un espejo más nuevo con el tamaño de OTRO medio (64/128 KiB
    /// en un cartucho SRAM de 32 KiB, medio + RTC sin RTC…) no se instala ni desplaza la local.
    @Test func newerMirrorOfAnotherMediumNeverReplacesTheLocalSave() throws {
        let probe = try GBACoreBridge()
        let info = try probe.loadROM(Self.rom(), bios: nil, unixTime: 0)
        let validSizes = EmulatorSession.validSaveSizes(info)
        #expect(validSizes == [32 * 1024])
        // El puente tampoco acepta esos tamaños aunque sean "de algún medio GBA".
        for size in [512, 8192, 64 * 1024, 128 * 1024, 32 * 1024 + 16] {
            #expect(throws: CoreError.sramSize) { try probe.sramLoad(Data(count: size)) }
        }
        let store = SaveStore(directory: dir, fingerprint: info.fingerprint)
        let local = Data(repeating: 0x11, count: 32 * 1024)
        try store.save(local)
        let mirror = SaveMirror(url: dir.appendingPathComponent("juego.sav"))
        for size in [1000, 512, 8192, 64 * 1024, 128 * 1024, 32 * 1024 + 16] {
            let foreign = Data(repeating: 0x77, count: size)
            let outcome = try SaveOpening.prepare(
                store: store, mirror: mirror,
                snapshot: .read(foreign, Date().addingTimeInterval(3600)), validSizes: validSizes)
            #expect(outcome.data == local, "tamaño \(size)")
            #expect(try store.load() == local, "tamaño \(size)")
            #expect(store.backups().isEmpty, "tamaño \(size)")
        }
    }

    /// Con RTC, el medio y el medio + 16 bytes son válidos; ningún otro tamaño.
    @Test func rtcCartridgeAcceptsOnlyItsMediumWithOrWithoutTheClock() {
        var info = RomInfo(title: "RTC", cartType: 0, sramBytes: 32 * 1024, hasBattery: true, hasRTC: true,
                           headerChecksumOK: true, fingerprint: "x")
        info.console = .gameBoyAdvance
        #expect(GBACoreBridge.validSaveSizes(info) == [32 * 1024, 32 * 1024 + 16])
        var eeprom = RomInfo(title: "EE", cartType: 0, sramBytes: 512, hasBattery: true, hasRTC: true,
                             headerChecksumOK: true, fingerprint: "y")
        eeprom.console = .gameBoyAdvance
        eeprom.eeprom = true
        #expect(GBACoreBridge.validSaveSizes(eeprom) == [512, 8192, 512 + 16, 8192 + 16])
    }

    @Test func sessionSavesGBASRAMThroughTheNormalPath() throws {
        let session = try EmulatorSession(romData: Self.rom(value: 0x7E), savesDirectory: dir,
                                          console: .gameBoyAdvance, onAudioInterrupted: {})
        #expect(session.loadWarning == nil)
        session.start()
        let store = SaveStore(directory: dir, fingerprint: session.info.fingerprint)
        var saved: Data?
        for _ in 0..<10 where saved?.first != 0x7E {
            Thread.sleep(forTimeInterval: 0.3)
            session.pause()
            saved = try store.load()
            session.resume()
        }
        session.stop()
        #expect(saved?.count == 32 * 1024)
        #expect(saved?.first == 0x7E)
    }

    @Test func biosIsOnlyUsedWhenItIsTheOfficialDump() throws {
        #expect(BIOSFile.read(folder: nil).status == .absent)
        #expect(BIOSFile.read(folder: dir).status == .absent)
        try Data(repeating: 0, count: GBACoreBridge.biosBytes)
            .write(to: dir.appendingPathComponent(BIOSFile.fileName))
        let read = BIOSFile.read(folder: dir)
        #expect(read.status == .invalid && read.data == nil)
        #expect(!BIOSFile.isOfficial(Data(count: 10)))
    }

    @Test func perGameOverridesForceSaveTypeAndRTC() throws {
        var data = GameplaySettingsData()
        data.perGame["x.gba"] = GameOverrides(gbaSaveType: 3, gbaRTC: 2, gbaUseBIOS: false)
        let o = data.emulation(for: "x.gba")
        #expect(o.gbaSaveType == 3 && o.gbaRTC == 2 && !o.gbaUseBIOS)
        let global = data.emulation(for: "otro.gba")
        #expect(global.gbaSaveType == 0 && global.gbaRTC == 0 && global.gbaUseBIOS)
        // Se guardan y se leen; un juego sin ajustes nunca deja una entrada vacía.
        let decoded = try JSONDecoder().decode(GameplaySettingsData.self, from: JSONEncoder().encode(data))
        #expect(decoded.perGame["x.gba"]?.gbaSaveType == 3)
        #expect(GameOverrides().isEmpty && !GameOverrides(gbaRTC: 1).isEmpty)

        // El tipo forzado cambia el tamaño de la partida que el núcleo espera: Flash de 64 KiB
        // en lugar de la SRAM de 32 KiB detectada. Un .sav de 32 KiB ya no se acepta ni se pisa.
        let core = try GBACoreBridge()
        let info = try core.loadROM(Self.rom(), bios: nil, unixTime: 0, saveType: o.gbaSaveType, rtc: o.gbaRTC)
        #expect(info.sramBytes == 64 * 1024 && !info.hasRTC)
        #expect(EmulatorSession.validSaveSizes(info) == [64 * 1024])
    }
}

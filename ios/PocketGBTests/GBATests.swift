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
    static func rom(title: String = "POCKETGBA", value: UInt8 = 0x42, tag: String = "SRAM_V113") -> Data {
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
        let tag = Array(tag.utf8)
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
        let data = GameplaySettingsData()
        // N1a: los ajustes van por huella en LibraryPreferences; aquí solo se resuelven.
        let forced = GameOverrides(gbaSaveType: 3, gbaRTC: 2, gbaUseBIOS: false)
        let o = data.emulation(with: forced)
        #expect(o.gbaSaveType == 3 && o.gbaRTC == 2 && !o.gbaUseBIOS)
        let global = data.emulation(with: nil)
        #expect(global.gbaSaveType == 0 && global.gbaRTC == 0 && global.gbaUseBIOS)
        // Se guardan y se leen por huella; un juego sin ajustes nunca deja una entrada vacía.
        let prefs = LibraryPreferences(fileURL: nil)
        var game = LibraryPreferencesTests.entry("x.gba", "X", color: false)
        game.fingerprint = "fp-x"
        prefs.setOverrides(forced, for: game)
        let decoded = try JSONDecoder().decode(LibraryPreferencesData.self, from: JSONEncoder().encode(prefs.data))
        #expect(decoded.games["fp-x"]?.overrides.gbaSaveType == 3)
        prefs.setOverrides(GameOverrides(), for: game)
        #expect(prefs.data.games["fp-x"] == nil)
        #expect(GameOverrides().isEmpty && !GameOverrides(gbaRTC: 1).isEmpty)

        // El tipo forzado cambia el tamaño de la partida que el núcleo espera: Flash de 64 KiB
        // en lugar de la SRAM de 32 KiB detectada. Un .sav de 32 KiB ya no se acepta ni se pisa.
        let core = try GBACoreBridge()
        let info = try core.loadROM(Self.rom(), bios: nil, unixTime: 0, saveType: o.gbaSaveType, rtc: o.gbaRTC)
        #expect(info.sramBytes == 64 * 1024 && !info.hasRTC)
        #expect(EmulatorSession.validSaveSizes(info) == [64 * 1024])
    }

    // MARK: - Continuación exacta con EEPROM autodetectada (INT-H1, INT-H2)

    /// Estado automático de una sesión EEPROM sin ajuste cuyo `.sav` local de 8 KiB es `save`.
    private func eepromAutoState(_ save: Data) throws -> (rom: Data, state: Data) {
        let rom = Self.rom(tag: "EEPROM_V124")
        let probe = try GBACoreBridge()
        let fingerprint = try probe.loadROM(rom, bios: nil, unixTime: 0).fingerprint
        try SaveStore(directory: dir, fingerprint: fingerprint).save(save)
        let s = try EmulatorSession(romData: rom, savesDirectory: dir, console: .gameBoyAdvance, onAudioInterrupted: {})
        s.start(); s.pause()
        let state = try s.saveState().state
        s.stop()
        return (rom, state)
    }

    @Test func eepromAutoStateDifferingPastByte512IsRejectedWithoutWriting() throws {
        let old = Data((0..<8192).map { UInt8(truncatingIfNeeded: $0 &* 7 &+ 1) })
        let (rom, state) = try eepromAutoState(old)
        var newer = old
        newer[4096] ^= 0xFF
        let probe = try GBACoreBridge()
        let fingerprint = try probe.loadROM(rom, bios: nil, unixTime: 0).fingerprint
        try SaveStore(directory: dir, fingerprint: fingerprint).save(newer)
        let before = try diskState()
        let session = try EmulatorSession(romData: rom, savesDirectory: dir, console: .gameBoyAdvance, onAudioInterrupted: {})
        #expect(throws: EmulatorSession.StateError.notCurrent) { try session.start(restoring: state) }
        #expect(try diskState() == before)
    }

    @Test func eepromAutoStateMatchingTheSaveResumesWithoutWriting() throws {
        let save = Data((0..<8192).map { UInt8(truncatingIfNeeded: $0 &* 7 &+ 1) })
        let (rom, state) = try eepromAutoState(save)
        let before = try diskState()
        let session = try EmulatorSession(romData: rom, savesDirectory: dir, console: .gameBoyAdvance, onAudioInterrupted: {})
        try session.start(restoring: state)
        session.pause(); session.stop()
        #expect(try diskState() == before)
    }

    /// INT-H2: un `.auto` de otra configuración (aquí, con BIOS real) se trata como no vigente.
    @Test func automaticStateOfAnotherConfigurationIsNotCurrent() throws {
        let rom = Self.rom()
        let bios = Data(count: GBACoreBridge.biosBytes)
        let other = try GBACoreBridge()
        _ = try other.loadROM(rom, bios: bios, unixTime: 0)
        let state = try other.stateSave()
        let session = try EmulatorSession(romData: rom, savesDirectory: dir, console: .gameBoyAdvance, onAudioInterrupted: {})
        #expect(throws: EmulatorSession.StateError.notCurrent) { try session.start(restoring: state) }
    }

    // MARK: - Tipo de partida forzado, de extremo a extremo (G8-H2, G8-H3)

    /// Todos los archivos bajo `dir` (ruta relativa → contenido): local, espejo y backups.
    private func diskState() throws -> [String: Data] {
        var files: [String: Data] = [:]
        let enumerator = FileManager.default.enumerator(at: dir, includingPropertiesForKeys: [.isRegularFileKey])
        while let url = enumerator?.nextObject() as? URL {
            guard (try url.resourceValues(forKeys: [.isRegularFileKey])).isRegularFile == true else { continue }
            files[String(url.path.dropFirst(dir.path.count))] = try Data(contentsOf: url)
        }
        return files
    }

    /// Abre una sesión GBA con `emulation` sobre un `.sav` local y un espejo ya escritos, la
    /// ejecuta (start → pause → stop) y devuelve la sesión y los archivos antes y después.
    private func runSession(emulation: EmulationOptions, local: Data, mirrorData: Data?,
                            mirrorAge: TimeInterval = 3600) throws
        -> (session: EmulatorSession, before: [String: Data], after: [String: Data]) {
        let probe = try GBACoreBridge()
        let fingerprint = try probe.loadROM(Self.rom(), bios: nil, unixTime: 0).fingerprint
        let store = SaveStore(directory: dir, fingerprint: fingerprint)
        try store.save(local)
        let mirror = SaveMirror(url: dir.appendingPathComponent("juego.sav"))
        var snapshot = SaveMirror.Snapshot.absent
        if let mirrorData {
            try mirrorData.write(to: mirror.url)
            try FileManager.default.setAttributes([.modificationDate: Date().addingTimeInterval(mirrorAge)],
                                                  ofItemAtPath: mirror.url.path)
            snapshot = mirror.snapshot()
        }
        let before = try diskState()
        let session = try EmulatorSession(romData: Self.rom(), savesDirectory: dir, mirror: mirror,
                                          mirrorSnapshot: snapshot, emulation: emulation,
                                          console: .gameBoyAdvance, onAudioInterrupted: {})
        session.start()
        Thread.sleep(forTimeInterval: 0.3)
        session.pause()
        session.stop()
        return (session, before, try diskState())
    }

    /// Un `.sav` de 32 KiB (SRAM detectada) y su espejo no coinciden con ningún otro tipo forzado:
    /// nada cambia en disco, aunque el juego escriba en la SRAM.
    @Test(arguments: [
        ("Flash 64 KiB", UInt8(3), UInt8(0)), ("Flash 128 KiB", 4, 0),
        ("EEPROM 512 B", 5, 0), ("EEPROM 8 KiB", 6, 0),
        ("Sin partida", 1, 0), ("Sin partida con reloj", 1, 1), ("Sin partida sin reloj", 1, 2),
        ("Flash 64 KiB con reloj", 3, 1), ("EEPROM 512 B con reloj", 5, 1),
    ])
    func forcedMismatchingMediumLeavesLocalMirrorAndBackupsUntouched(_ name: String, saveType: UInt8, rtc: UInt8) throws {
        let local = Data(repeating: 0x11, count: 32 * 1024)
        let mirrorData = Data(repeating: 0x22, count: 32 * 1024)
        let result = try runSession(emulation: EmulationOptions(colorForGameBoy: false, compatPalette: 0,
                                                                gbaSaveType: saveType, gbaRTC: rtc),
                                    local: local, mirrorData: mirrorData)
        #expect(result.before.values.contains(local) && result.before.values.contains(mirrorData), "\(name)")
        #expect(result.after == result.before, "\(name): el disco cambió")
        // G8-H5: el aviso nombra «Ajustes del juego», también con «Sin partida» (sin batería).
        let warning = try #require(result.session.gameSettingsWarning, "\(name): sin aviso")
        #expect(warning.message.contains("Ajustes del juego"))
    }

    /// «Sin partida» + reloj: el `.sav` de solo 16 bytes del RTC es válido y se carga (G8-H5, b).
    @Test func noSaveWithClockAcceptsAndLoadsTheClockOnlySave() throws {
        let forced = EmulationOptions(colorForGameBoy: false, compatPalette: 0, gbaSaveType: 1, gbaRTC: 1)
        let core = try GBACoreBridge()
        let info = try core.loadROM(Self.rom(), bios: nil, unixTime: 0, saveType: 1, rtc: 1)
        #expect(info.sramBytes == 0 && info.hasRTC && info.hasBattery)
        #expect(EmulatorSession.validSaveSizes(info) == [16])
        try core.sramLoad(try core.sramSave())
        #expect(throws: CoreError.sramSize) { try core.sramLoad(Data()) }
        #expect(throws: CoreError.sramSize) { try core.sramLoad(Data(count: 17)) }
        // Y sin reloj, «Sin partida» no acepta ningún .sav.
        let plain = try GBACoreBridge().loadROM(Self.rom(), bios: nil, unixTime: 0, saveType: 1)
        #expect(EmulatorSession.validSaveSizes(plain).isEmpty)

        // Sin espejo previo, la sesión lo crea con la misma partida; la local no cambia.
        let clockOnly = try core.sramSave()
        let result = try runSession(emulation: forced, local: clockOnly, mirrorData: nil)
        #expect(result.session.loadWarning == nil && result.session.gameSettingsWarning == nil)
        let localPath = "/\(result.session.info.fingerprint).sav"
        #expect(result.after[localPath] == clockOnly && result.before[localPath] == clockOnly)
        #expect(result.after["/juego.sav"] == clockOnly)
    }

    /// G8-H9: un estado de otra configuración (tipo de partida, reloj o BIOS) no se carga y el
    /// error no dice «dañado».
    @Test func statesOfAnotherConfigurationAreRejectedWithTheirOwnError() throws {
        let bios = Data(count: GBACoreBridge.biosBytes)
        let real = try GBACoreBridge(), emulated = try GBACoreBridge(), flash = try GBACoreBridge()
        _ = try real.loadROM(Self.rom(), bios: bios, unixTime: 0)
        _ = try emulated.loadROM(Self.rom(), bios: nil, unixTime: 0)
        _ = try flash.loadROM(Self.rom(), bios: nil, unixTime: 0, saveType: 3)
        let withBIOS = try real.stateSave(), withoutBIOS = try emulated.stateSave(), asFlash = try flash.stateSave()
        #expect(throws: CoreError.stateConfig) { try emulated.stateLoad(withBIOS) }
        #expect(throws: CoreError.stateConfig) { try real.stateLoad(withoutBIOS) }
        #expect(throws: CoreError.stateConfig) { try emulated.stateLoad(asFlash) }
        try emulated.stateLoad(withoutBIOS)
        try real.stateLoad(withBIOS)
        #expect(!CoreError.stateConfig.description.contains("dañado"))
    }

    /// G8-H8 y H7: los ajustes por juego fuera de rango se descartan al leerlos y «Automático»
    /// ya no existe como valor guardado.
    @Test func storedPerGameSettingsOutOfRangeAreDiscarded() throws {
        let json = #"""
        {"perGame":{"a.gba":{"gbaSaveType":200,"gbaRTC":9,"gbaUseBIOS":true},
                    "b.gba":{"gbaSaveType":0,"gbaRTC":0},
                    "c.gba":{"gbaSaveType":6,"gbaRTC":2,"gbaUseBIOS":false}}}
        """#
        let data = try JSONDecoder().decode(GameplaySettingsData.self, from: Data(json.utf8))
        #expect(data.perGame["a.gba"]?.isEmpty == true)
        #expect(data.perGame["b.gba"]?.isEmpty == true)
        #expect(data.perGame["c.gba"] == GameOverrides(gbaSaveType: 6, gbaRTC: 2, gbaUseBIOS: false))
        let a = data.emulation(with: data.perGame["a.gba"])
        #expect(a.gbaSaveType == 0 && a.gbaRTC == 0)
    }

    /// Caso de G8-H2: EEPROM 512 B forzada, local de 512 B y espejo de 8 KiB más reciente. Antes
    /// se instalaba el espejo (la local pasaba a backup) y luego el núcleo lo rechazaba.
    @Test func forcedEEPROM512IgnoresANewerMirrorOf8KiB() throws {
        let local = Data(repeating: 0x33, count: 512)
        let mirrorData = Data(repeating: 0x44, count: 8192)
        let result = try runSession(emulation: EmulationOptions(colorForGameBoy: false, compatPalette: 0,
                                                                gbaSaveType: 5),
                                    local: local, mirrorData: mirrorData)
        #expect(EmulatorSession.validSaveSizes(result.session.info) == [512])
        #expect(result.after == result.before)
        #expect(result.after.values.filter { $0 == local }.count == 1)
        #expect(!result.after.keys.contains { $0.contains("backups") })
        #expect(result.session.loadWarning == .mirrorIgnored)
    }

    @Test func forcedSRAMMatchingTheLocalSaveStillSavesNormally() throws {
        let local = Data(repeating: 0x00, count: 32 * 1024)
        let result = try runSession(emulation: EmulationOptions(colorForGameBoy: false, compatPalette: 0,
                                                                gbaSaveType: 2),
                                    local: local, mirrorData: nil)
        #expect(result.session.loadWarning == nil)
        let fingerprint = result.session.info.fingerprint
        let saved = try #require(try SaveStore(directory: dir, fingerprint: fingerprint).load())
        #expect(saved.count == 32 * 1024 && saved[0] == 0x42)
    }
}

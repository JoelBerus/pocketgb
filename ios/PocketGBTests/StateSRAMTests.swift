import Foundation
import Testing
@testable import PocketGB

/// D5: save states y SRAM (D-README §7): un estado dañado o de otro juego no toca la SRAM;
/// uno válido la sustituye y se guarda por la ruta normal, con backup de la anterior.
@MainActor
struct StateSRAMTests {
    let dir: URL

    init() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    /// ROM sintético MBC1 + RAM + batería que escribe `value` en $A000 y se queda en bucle;
    /// con `counting`, incrementa ($A000) sin parar (la SRAM cambia mientras corre).
    static func rom(title: String, value: UInt8, counting: Bool = false) -> Data {
        var rom = LibraryScannerTests.rom(title: title)
        rom[0x100] = 0xC3; rom[0x101] = 0x50; rom[0x102] = 0x01   // JP $0150
        rom[0x147] = 0x03   // MBC1 + RAM + batería
        rom[0x149] = 0x02   // 8 KiB
        let program: [UInt8] = [0x3E, 0x0A, 0xEA, 0x00, 0x00,   // habilitar RAM
                                0x3E, value, 0xEA, 0x00, 0xA0,  // ($A000) = value
                                0x18, 0xFE]                     // JR -2
        // Recorre toda la SRAM incrementando cada byte (una pasada ≈ 5 frames): dos
        // instantáneas en momentos distintos nunca coinciden por casualidad.
        let counter: [UInt8] = [0x3E, 0x0A, 0xEA, 0x00, 0x00,   // habilitar RAM
                                0x21, 0x00, 0xA0,               // $0155: LD HL,$A000
                                0x7E,                           // $0158: LD A,(HL)
                                0x3C,                           // INC A
                                0x22,                           // LD (HL+),A
                                0x7C,                           // LD A,H
                                0xFE, 0xC0,                     // CP $C0
                                0x20, 0xF8,                     // JR NZ,$0158
                                0x18, 0xF3]                     // JR $0155
        let code = counting ? counter : program
        rom.replaceSubrange(0x150..<(0x150 + code.count), with: code)
        var checksum: UInt8 = 0
        for i in 0x134...0x14C { checksum = checksum &- rom[i] &- 1 }
        rom[0x14D] = checksum
        return rom
    }

    @Test func coreRejectsCorruptAndForeignStatesWithoutTouchingSRAM() throws {
        let core = try CoreBridge()
        _ = try core.loadROM(Self.rom(title: "ESTADOS", value: 0x11), unixTime: 0)
        var sram = Data(repeating: 0, count: core.sramSaveSize)
        sram[0] = 0xAA
        try core.sramLoad(sram)
        let good = try core.stateSave()

        var corrupt = good
        corrupt[corrupt.count / 2] ^= 0xFF
        #expect(throws: CoreError.stateCorrupt) { try core.stateLoad(corrupt) }
        #expect(throws: CoreError.stateMagic) { try core.stateLoad(Data("nada".utf8)) }
        #expect(try core.sramSave() == sram)

        let other = try CoreBridge()
        _ = try other.loadROM(Self.rom(title: "OTRO JUEGO", value: 0x22), unixTime: 0)
        let foreign = try other.stateSave()
        #expect(throws: CoreError.stateROMMismatch) { try core.stateLoad(foreign) }
        #expect(try core.sramSave() == sram)
    }

    @Test func loadingAStateSavesItsSRAMWithBackupOfThePrevious() throws {
        let session = try EmulatorSession(romData: Self.rom(title: "CONTADOR", value: 0, counting: true),
                                          savesDirectory: dir, onAudioInterrupted: {})
        session.start()
        session.pause()                          // flush síncrono: partida X en disco
        let store = SaveStore(directory: dir, fingerprint: session.info.fingerprint)
        let x = try #require(try store.load())
        let saved = try session.saveState()      // el estado lleva la SRAM X
        #expect(saved.pixels.count == FrameBuffers.pixelCount)

        // Un estado dañado se rechaza y la partida no cambia.
        var altered = saved.state
        altered[altered.count / 3] ^= 0x01
        #expect(throws: CoreError.self) { try session.loadState(altered) }
        #expect(try store.load() == x)
        #expect(store.backups().isEmpty)

        // Seguir jugando: el contador avanza y la pausa guarda Y.
        session.resume()
        Thread.sleep(forTimeInterval: 0.3)
        session.pause()
        let y = try #require(try store.load())
        #expect(y != x)

        // Cargar el estado: su SRAM (X) se guarda en el acto y Y queda como backup.
        let backupsBefore = store.backups().count
        try session.loadState(saved.state)
        #expect(try store.load() == x)
        #expect(store.backups().count == backupsBefore + 1)
        #expect(try Data(contentsOf: store.backupURL(1)) == y)
        session.stop()
    }

    /// Auditoría D2-D5 Codex, H2: si la partida del estado no se puede guardar, la carga
    /// falla de forma visible y el juego vuelve a como estaba (disco y núcleo).
    @Test func loadStateFailsVisiblyAndRollsBackWhenSavingFails() throws {
        let session = try EmulatorSession(romData: Self.rom(title: "CONTADOR", value: 0, counting: true),
                                          savesDirectory: dir, onAudioInterrupted: {})
        session.start()
        session.pause()
        let store = SaveStore(directory: dir, fingerprint: session.info.fingerprint)
        let saved = try session.saveState()                     // SRAM X
        session.resume()
        Thread.sleep(forTimeInterval: 0.3)
        session.pause()
        let y = try #require(try store.load())                  // SRAM Y en disco

        // Carpeta de partidas de solo lectura: la escritura atómica no puede crear su temporal.
        try FileManager.default.setAttributes([.posixPermissions: 0o555], ofItemAtPath: dir.path)
        defer { try? FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: dir.path) }
        #expect(throws: EmulatorSession.StateError.saveFailed) { try session.loadState(saved.state) }
        #expect(try store.load() == y)

        // El núcleo volvió a Y: con la carpeta ya escribible, la pausa no cambia el disco.
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: dir.path)
        let current = try session.saveState()
        #expect(current.state != saved.state)
        session.resume()
        session.pause()
        session.stop()
    }

    @Test func statesNeedAPausedSession() throws {
        let session = try EmulatorSession(romData: Self.rom(title: "ESTADOS", value: 1), savesDirectory: dir,
                                          onAudioInterrupted: {})
        session.start()
        #expect(throws: EmulatorSession.StateError.notPaused) { _ = try session.saveState() }
        session.stop()
    }
}

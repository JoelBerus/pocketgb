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

    /// Reanuda y pausa hasta que la partida en disco cambie (en el simulador del CI el
    /// audio puede tardar en arrancar y los primeros frames llegar tarde). Máximo ≈ 3 s.
    static func playUntilSaveChanges(_ session: EmulatorSession, store: SaveStore, from old: Data) throws -> Data {
        for _ in 0..<10 {
            session.resume()
            Thread.sleep(forTimeInterval: 0.3)
            session.pause()
            if let now = try store.load(), now != old { return now }
        }
        Issue.record("La partida no cambió tras 3 s de juego")
        return old
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
        let y = try Self.playUntilSaveChanges(session, store: store, from: x)

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
        let x = try #require(try store.load())
        let saved = try session.saveState()                     // SRAM X
        let y = try Self.playUntilSaveChanges(session, store: store, from: x)   // SRAM Y en disco

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

    @Test func automaticStateMustExistBeValidAndNotPredateTheSave() throws {
        let states = StateStore(directory: dir.appendingPathComponent("states", isDirectory: true))
        #expect(states.automaticEntry(newerThan: nil) == nil)

        try states.save(Data("PGBS-valid".utf8), thumbnail: nil, to: .auto)
        let entry = try #require(states.automaticEntry(newerThan: nil))
        #expect(!entry.corrupt)
        #expect(states.automaticEntry(newerThan: entry.date.addingTimeInterval(-1)) != nil)
        #expect(states.automaticEntry(newerThan: entry.date.addingTimeInterval(1)) == nil)

        try states.save(Data("ROTO".utf8), thumbnail: nil, to: .auto)
        #expect(states.automaticEntry(newerThan: nil) == nil)
    }

    /// Caso legítimo: `closeGame` vacía la SRAM antes de guardar el estado automático, así
    /// que su SRAM coincide con la partida en disco y la reanudación se acepta sin reescribirla.
    @Test func startRestoringResumesWhenStateSRAMMatchesTheSave() throws {
        let rom = Self.rom(title: "REANUDAR", value: 0x11)
        let (state, x) = try automaticState(rom: rom, savesDirectory: dir)
        let store = SaveStore(directory: dir, fingerprint: try CoreBridge().loadROM(rom, unixTime: 0).fingerprint)
        let backupsBefore = store.backups().count

        let writes = MirrorWrites()
        let mirror = try mirrorFile(x, newer: false)
        let resumed = try EmulatorSession(romData: rom, savesDirectory: dir, mirror: mirror,
                                          mirrorSnapshot: mirror.snapshot(),
                                          mirrorWriter: { try writes.record($0, to: mirror) },
                                          onAudioInterrupted: {})
        try resumed.start(restoring: state)
        resumed.pause()
        #expect(try store.load() == x)
        #expect(store.backups().count == backupsBefore)
        #expect(writes.count == 0)   // D81-H7: una reanudación idéntica no reescribe el espejo
        resumed.stop()
    }

    private func mirrorFile(_ data: Data?, newer: Bool) throws -> SaveMirror {
        let mirror = SaveMirror(url: dir.appendingPathComponent("Juegos/juego.sav"))
        try FileManager.default.createDirectory(at: mirror.url.deletingLastPathComponent(),
                                                withIntermediateDirectories: true)
        if let data {
            try data.write(to: mirror.url)
            if newer {
                try FileManager.default.setAttributes([.modificationDate: Date().addingTimeInterval(120)],
                                                      ofItemAtPath: mirror.url.path)
            }
        }
        return mirror
    }

    /// Crea un auto-estado con SRAM X en `savesDirectory` y devuelve el estado y X.
    private func automaticState(rom: Data, savesDirectory: URL) throws -> (state: Data, x: Data) {
        try FileManager.default.createDirectory(at: savesDirectory, withIntermediateDirectories: true)
        let s = try EmulatorSession(romData: rom, savesDirectory: savesDirectory, onAudioInterrupted: {})
        s.start(); s.pause()
        let store = SaveStore(directory: savesDirectory, fingerprint: s.info.fingerprint)
        let x = try #require(try store.load())
        let state = try s.saveState().state
        s.stop()
        return (state, x)
    }

    private func externalSave(_ x: Data) -> Data {
        var m = x
        m[1] = 0x77
        return m
    }

    /// D81-H1 (a): el espejo es más nuevo que la copia local; el auto-estado lleva la SRAM vieja.
    @Test func newerMirrorRejectsAutomaticStateWithoutTouchingAnySave() throws {
        let rom = Self.rom(title: "ESPEJO", value: 0x11)
        let (state, x) = try automaticState(rom: rom, savesDirectory: dir)
        let external = externalSave(x)
        let mirror = try mirrorFile(external, newer: true)
        let mirrorBefore = try Data(contentsOf: mirror.url)

        let writes = MirrorWrites()
        let session = try EmulatorSession(romData: rom, savesDirectory: dir, mirror: mirror,
                                          mirrorSnapshot: mirror.snapshot(),
                                          mirrorWriter: { try writes.record($0, to: mirror) },
                                          onAudioInterrupted: {})
        #expect(throws: EmulatorSession.StateError.notCurrent) { try session.start(restoring: state) }
        let store = SaveStore(directory: dir, fingerprint: session.info.fingerprint)
        #expect(try store.load() == external)
        #expect(try Data(contentsOf: mirror.url) == mirrorBefore)
        #expect(writes.count == 0)
    }

    /// D81-H1 (b): sin `.sav` local, solo el espejo junto al ROM.
    @Test func mirrorWithoutLocalSaveRejectsAutomaticState() throws {
        let rom = Self.rom(title: "ESPEJO", value: 0x11)
        let other = dir.appendingPathComponent("otro", isDirectory: true)
        let (state, x) = try automaticState(rom: rom, savesDirectory: other)
        let external = externalSave(x)
        let mirror = try mirrorFile(external, newer: false)

        let session = try EmulatorSession(romData: rom, savesDirectory: dir, mirror: mirror,
                                          mirrorSnapshot: mirror.snapshot(), onAudioInterrupted: {})
        #expect(throws: EmulatorSession.StateError.notCurrent) { try session.start(restoring: state) }
        let store = SaveStore(directory: dir, fingerprint: session.info.fingerprint)
        #expect(try store.load() == external)
        #expect(try Data(contentsOf: mirror.url) == external)
    }

    /// D81-H1/H6: tras rechazar un estado no vigente el núcleo vuelve a como estaba: si la
    /// sesión arranca después, la pausa guarda la partida buena, no la SRAM del estado.
    @Test func staleStateRollsBackTheCore() throws {
        let rom = Self.rom(title: "REVERTIR", value: 0x11)
        let (state, x) = try automaticState(rom: rom, savesDirectory: dir)
        let newer = externalSave(x)
        let store = SaveStore(directory: dir, fingerprint: try CoreBridge().loadROM(rom, unixTime: 0).fingerprint)
        try store.save(newer)

        let session = try EmulatorSession(romData: rom, savesDirectory: dir, onAudioInterrupted: {})
        #expect(throws: EmulatorSession.StateError.notCurrent) { try session.start(restoring: state) }
        session.start()
        session.pause()
        #expect(try store.load() == newer)
        session.stop()
    }

    @Test func startRestoringRejectsCorruptAndForeignStateWithoutChangingSave() throws {
        let rom = Self.rom(title: "REANUDAR", value: 0x11)
        let storeSession = try EmulatorSession(romData: rom, savesDirectory: dir, onAudioInterrupted: {})
        storeSession.start(); storeSession.pause(); storeSession.stop()
        let store = SaveStore(directory: dir, fingerprint: storeSession.info.fingerprint)
        let before = try #require(try store.load())

        let corrupt = try EmulatorSession(romData: rom, savesDirectory: dir, onAudioInterrupted: {})
        #expect(throws: CoreError.self) { try corrupt.start(restoring: Data("ROTO".utf8)) }
        #expect(try store.load() == before)

        let otherCore = try CoreBridge()
        _ = try otherCore.loadROM(Self.rom(title: "OTRO", value: 0x22), unixTime: 0)
        let foreign = try otherCore.stateSave()
        let foreignSession = try EmulatorSession(romData: rom, savesDirectory: dir, onAudioInterrupted: {})
        #expect(throws: CoreError.stateROMMismatch) { try foreignSession.start(restoring: foreign) }
        #expect(try store.load() == before)
    }
}

/// Cuenta las escrituras que llegan al espejo (el escritor de prueba también escribe el archivo).
final class MirrorWrites: @unchecked Sendable {
    private let lock = NSLock()
    private var n = 0
    var count: Int { lock.lock(); defer { lock.unlock() }; return n }
    func record(_ data: Data, to mirror: SaveMirror) throws {
        lock.lock(); n += 1; lock.unlock()
        try mirror.write(data)
    }
}

import Foundation
import Testing
@testable import PocketGB

/// M9 lote 2: `SRAMPersistence` sin hilo de emulación y con reloj inyectado.
final class FakeClock: @unchecked Sendable {
    private let lock = NSLock()
    private var ticks: UInt64 = 1_000
    var now: UInt64 { lock.lock(); defer { lock.unlock() }; return ticks }
    func advance(seconds: Double) {
        lock.lock(); ticks += UInt64(seconds * SRAMPersistence.ticksPerSecond); lock.unlock()
    }
}

@MainActor
struct SRAMPersistenceTests {
    let dir: URL
    let clock = FakeClock()

    init() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    struct Opened {
        let core: CoreBridge
        let info: RomInfo
        let persister: SRAMPersistence
        let store: SaveStore
    }

    /// Escribe $42 en $A000 y deshabilita la RAM: el núcleo marca la SRAM como "sucia" (el juego guardó).
    static func savingROM() -> Data {
        var p = LinkTestROMs.Program()
        p.emit(0x3E, 0x0A, 0xEA, 0x00, 0x00)      // habilitar RAM
        p.emit(0x3E, 0x42, 0xEA, 0x00, 0xA0)      // ($A000) = $42
        p.emit(0xAF, 0xEA, 0x00, 0x00)            // deshabilitar RAM
        let loop = p.here
        p.jr(loop)
        return LinkTestROMs.rom(title: "GUARDA", program: p, cartType: 0x03, ramCode: 0x02)
    }

    func open(saving: Bool = false) throws -> Opened {
        let core = try CoreBridge()
        let info = try core.loadROM(saving ? Self.savingROM() : StateSRAMTests.rom(title: "PERSISTE", value: 0x11),
                                    unixTime: 0)
        let (persister, warning) = try SRAMPersistence.open(core: core, info: info, savesDirectory: dir, mirror: nil,
                                                            snapshot: .absent, mirrorWriter: nil)
        #expect(warning == nil)
        let clock = self.clock
        let opened = try #require(persister)
        // Reloj inyectado: `open` usa el real, así que se rehace con el falso sobre el mismo destino.
        _ = opened
        let target = SaveTarget(local: SaveStore(directory: dir, fingerprint: info.fingerprint), mirror: nil)
        let injected = SRAMPersistence(core: core, target: target, clock: { clock.now })
        return Opened(core: core, info: info, persister: injected,
                      store: SaveStore(directory: dir, fingerprint: info.fingerprint))
    }

    func sram(_ byte: UInt8, size: Int = 8_192) -> Data { Data(repeating: byte, count: size) }

    @Test func withoutASaveFileAFlushWritesNothing() throws {
        let o = try open()
        o.persister.prime()
        #expect(o.persister.flush(sync: true))
        #expect(try o.store.load() == nil)
        #expect(!FileManager.default.fileExists(atPath: o.store.saveURL.path))
    }

    @Test func changedSRAMIsWrittenAndLeavesTheBackup() throws {
        let o = try open()
        o.persister.prime()
        try o.core.sramLoad(sram(0x01))
        #expect(o.persister.flush(sync: true))
        #expect(try o.store.load() == sram(0x01))
        #expect(o.store.backups().isEmpty)
        try o.core.sramLoad(sram(0x02))
        #expect(o.persister.flush(sync: true))
        #expect(try o.store.load() == sram(0x02))
        #expect(try Data(contentsOf: o.store.backupURL(1)) == sram(0x01))
    }

    @Test func unchangedSRAMDoesNotRotateBackups() throws {
        let o = try open()
        o.persister.prime()
        try o.core.sramLoad(sram(0x01))
        #expect(o.persister.flush(sync: true))
        try o.core.sramLoad(sram(0x02))
        #expect(o.persister.flush(sync: true))
        let backups = o.store.backups().count
        #expect(o.persister.flush(sync: true))
        #expect(o.persister.flush(sync: true))
        #expect(o.store.backups().count == backups)
        #expect(try o.store.load() == sram(0x02))
    }

    @Test func readOnlyFolderReturnsFalseAndTheNextFlushRetries() throws {
        let o = try open()
        o.persister.prime()
        try o.core.sramLoad(sram(0x07))
        try FileManager.default.setAttributes([.posixPermissions: 0o555], ofItemAtPath: dir.path)
        defer { try? FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: dir.path) }
        #expect(!o.persister.flush(sync: true))
        #expect(try o.store.load() == nil)
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: dir.path)
        #expect(o.persister.flush(sync: true))
        #expect(try o.store.load() == sram(0x07))
    }

    /// M9L2-H1: tras una escritura ASÍNCRONA fallida, `retryIfFailed` fuerza la reescritura sin flush síncrono.
    @Test func retryAfterAFailedAsyncWriteRewritesWithoutASyncFlush() throws {
        let o = try open(saving: true)
        o.persister.prime()
        for _ in 0..<5 { o.core.runFrame() }        // el juego escribe en la SRAM
        #expect(o.core.sramDirty)
        try FileManager.default.setAttributes([.posixPermissions: 0o555], ofItemAtPath: dir.path)
        defer { try? FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: dir.path) }
        o.persister.check()                          // flanco
        clock.advance(seconds: 1.1)
        o.persister.check()                          // escritura asíncrona: falla en la cola
        o.persister.waitForPendingWrites()
        #expect(try o.store.load() == nil)
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: dir.path)
        o.persister.retryIfFailed()
        o.persister.check()
        clock.advance(seconds: 1.1)
        o.persister.check()                          // reintento tras el debounce
        o.persister.waitForPendingWrites()
        #expect(try o.store.load() == o.core.sramSave())
    }

    @Test func debounceWritesOneSecondAfterTheGameSaved() throws {
        let o = try open(saving: true)
        o.persister.prime()
        for _ in 0..<5 { o.core.runFrame() }        // el juego escribe en la SRAM
        #expect(o.core.sramDirty)
        o.persister.check()                          // flanco: arranca el debounce
        clock.advance(seconds: 0.5)
        o.persister.check()
        o.persister.waitForPendingWrites()
        #expect(try o.store.load() == nil)
        clock.advance(seconds: 0.6)
        o.persister.check()
        o.persister.waitForPendingWrites()
        #expect(try o.store.load() == o.core.sramSave())
    }

    @Test func safetyNetFlushesAfterSixtySecondsWithoutAnEdge() throws {
        let o = try open()
        o.persister.prime()
        try o.core.sramLoad(sram(0x33))
        o.core.clearSRAMDirty()
        clock.advance(seconds: 59)
        o.persister.check()
        o.persister.waitForPendingWrites()
        #expect(try o.store.load() == nil)
        clock.advance(seconds: 2)
        o.persister.check()
        o.persister.waitForPendingWrites()
        #expect(try o.store.load() == sram(0x33))
    }

    @Test func factoryWithWrongSizeLocalSaveReturnsNoPersisterAndLeavesTheFileAlone() throws {
        let core = try CoreBridge()
        let info = try core.loadROM(StateSRAMTests.rom(title: "TAMANO", value: 1), unixTime: 0)
        let store = SaveStore(directory: dir, fingerprint: info.fingerprint)
        let wrong = Data(repeating: 0x5A, count: 100)
        try wrong.write(to: store.saveURL)
        let result = try SRAMPersistence.open(core: core, info: info, savesDirectory: dir, mirror: nil,
                                              snapshot: .absent, mirrorWriter: nil)
        #expect(result.persister == nil)
        #expect(result.warning == .localWrongSize)
        #expect(try Data(contentsOf: store.saveURL) == wrong)
    }

    @Test func factoryForwardsMirrorNotDownloaded() throws {
        let core = try CoreBridge()
        let info = try core.loadROM(StateSRAMTests.rom(title: "ICLOUD", value: 1), unixTime: 0)
        let mirror = SaveMirror(url: dir.appendingPathComponent("Juegos/juego.sav"))
        #expect(throws: SaveOpening.Refusal.mirrorNotDownloaded) {
            _ = try SRAMPersistence.open(core: core, info: info, savesDirectory: dir, mirror: mirror,
                                         snapshot: .unavailable, mirrorWriter: nil)
        }
    }

    @Test func factoryWithoutBatteryHasNothingToSave() throws {
        let core = try CoreBridge()
        let info = try core.loadROM(LinkTestROMs.romOnly(title: "SIN BATERIA"), unixTime: 0)
        let result = try SRAMPersistence.open(core: core, info: info, savesDirectory: dir, mirror: nil,
                                              snapshot: .absent, mirrorWriter: nil)
        #expect(result.persister == nil && result.warning == nil)
    }
}

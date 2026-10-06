import Foundation
import Testing
@testable import PocketGB

/// Núcleo falso: ~804 frames de silencio por `runFrame` (como un APU a 48 kHz), SRAM en memoria y
/// recuento de `shutdown()`.
final class FakeCore: ConsoleCore, @unchecked Sendable {
    let console = Console.gameBoy
    private let lock = NSLock()
    private var sram: Data
    private var shutdowns = 0

    init(sramBytes: Int = 8_192) { sram = Data(count: sramBytes) }

    var shutdownCount: Int { lock.lock(); defer { lock.unlock() }; return shutdowns }
    func setSRAM(_ data: Data) { lock.lock(); sram = data; lock.unlock() }

    func shutdown() { lock.lock(); shutdowns += 1; lock.unlock() }
    func setButtons(_ mask: UInt16) {}
    func runFrame() { produceFrame() }
    var cpuLocked: Bool { false }
    func copyFramebuffer(to dst: UnsafeMutablePointer<UInt32>) { dst.update(repeating: 0, count: ScreenSize.gameBoy.pixelCount) }
    func readAudio(into dst: UnsafeMutablePointer<Int16>, maxFrames: Int) -> Int {
        // 804 frames por runFrame: se entregan una sola vez.
        lock.lock(); defer { lock.unlock() }
        guard owed > 0 else { return 0 }
        let n = min(owed, maxFrames)
        owed -= n
        dst.update(repeating: 0, count: n * 2)
        return n
    }
    private var owed = 0
    var sramSaveSize: Int { lock.lock(); defer { lock.unlock() }; return sram.count }
    var sramFooterBytes: Int { 0 }
    var sramDirty: Bool { false }
    func clearSRAMDirty() {}
    func sramLoad(_ data: Data) throws(CoreError) { setSRAM(data) }
    func sramSave() throws(CoreError) -> Data { lock.lock(); defer { lock.unlock() }; return sram }
    func setRTCTime(_ unixTime: Int64) {}
    func stateSave() throws(CoreError) -> Data { Data() }
    func stateLoad(_ data: Data) throws(CoreError) {}

    func produceFrame() { lock.lock(); owed += 804; lock.unlock() }
}

@MainActor
struct EmulatorSessionContractTests {
    static let info = RomInfo(title: "FALSO", cartType: 0x03, sramBytes: 8_192, hasBattery: true, hasRTC: false,
                              headerChecksumOK: true, fingerprint: "falso")

    static func tempDir() throws -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    @Test func stopCallsShutdownExactlyOnce() throws {
        let core = FakeCore()
        let session = EmulatorSession(core: core, info: Self.info, persisters: [], loadWarning: nil,
                                      onAudioInterrupted: {})
        session.start()
        session.pause()
        session.resume()
        session.stop()
        #expect(core.shutdownCount == 1)
    }

    @Test func pauseFlushesEveryPersisterEvenIfOneFails() throws {
        let dirA = try Self.tempDir(), dirB = try Self.tempDir()
        let coreA = FakeCore(), coreB = FakeCore()
        let storeA = SaveStore(directory: dirA, fingerprint: "a"), storeB = SaveStore(directory: dirB, fingerprint: "b")
        let persisters = [SRAMPersistence(core: coreA, target: SaveTarget(local: storeA, mirror: nil)),
                          SRAMPersistence(core: coreB, target: SaveTarget(local: storeB, mirror: nil))]
        let pair = PairCore(coreA, coreB)
        let session = EmulatorSession(core: pair, info: Self.info, persisters: persisters, loadWarning: nil,
                                      onAudioInterrupted: {})
        session.start()   // `prime` ya corrió: lo que se cambia ahora es lo que falta por guardar
        let dataA = Data(repeating: 0xA1, count: 8_192), dataB = Data(repeating: 0xB2, count: 8_192)
        coreA.setSRAM(dataA)
        coreB.setSRAM(dataB)
        try FileManager.default.setAttributes([.posixPermissions: 0o555], ofItemAtPath: dirA.path)
        defer { try? FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: dirA.path) }
        session.pause()
        #expect(try storeB.load() == dataB)     // B se guardó aunque A falló
        #expect(try storeA.load() == nil)
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: dirA.path)
        session.stop()                          // el flush final reintenta A
        #expect(try storeA.load() == dataA)
    }
}

/// Núcleo compuesto mínimo para la prueba de varios persisters: avanza los dos falsos.
final class PairCore: ConsoleCore, @unchecked Sendable {
    let console = Console.gameBoy
    let a: FakeCore, b: FakeCore
    init(_ a: FakeCore, _ b: FakeCore) { self.a = a; self.b = b }
    func setButtons(_ mask: UInt16) {}
    func runFrame() { a.runFrame() }
    var cpuLocked: Bool { false }
    func copyFramebuffer(to dst: UnsafeMutablePointer<UInt32>) { a.copyFramebuffer(to: dst) }
    func readAudio(into dst: UnsafeMutablePointer<Int16>, maxFrames: Int) -> Int { a.readAudio(into: dst, maxFrames: maxFrames) }
    var sramSaveSize: Int { 0 }
    var sramFooterBytes: Int { 0 }
    var sramDirty: Bool { false }
    func clearSRAMDirty() {}
    func sramLoad(_ data: Data) throws(CoreError) { throw .linkUnsupported }
    func sramSave() throws(CoreError) -> Data { throw .linkUnsupported }
    func setRTCTime(_ unixTime: Int64) {}
    func stateSave() throws(CoreError) -> Data { throw .linkUnsupported }
    func stateLoad(_ data: Data) throws(CoreError) { throw .linkUnsupported }
    func shutdown() { a.shutdown() }
}

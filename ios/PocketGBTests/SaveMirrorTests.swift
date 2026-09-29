import Foundation
import Testing
@testable import PocketGB

private final class MirrorWriteRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var values: [Data] = []

    func append(_ data: Data) {
        lock.lock()
        values.append(data)
        lock.unlock()
    }

    var snapshot: [Data] {
        lock.lock()
        defer { lock.unlock() }
        return values
    }
}

private final class BlockingMirrorWriter: @unchecked Sendable {
    let started = DispatchSemaphore(value: 0)
    let unblock = DispatchSemaphore(value: 0)
    let finished = DispatchSemaphore(value: 0)
    private let mirror: SaveMirror

    init(mirror: SaveMirror) {
        self.mirror = mirror
    }

    func write(_ data: Data) throws {
        started.signal()
        unblock.wait()
        try mirror.write(data)
        finished.signal()
    }
}

private func waitUntil(timeout: TimeInterval = 2, _ condition: () -> Bool) -> Bool {
    let deadline = Date().addingTimeInterval(timeout)
    while Date() < deadline {
        if condition() { return true }
        Thread.sleep(forTimeInterval: 0.01)
    }
    return condition()
}

/// D2: carga local/espejo (docs/04 §Saves "Carga al abrir un ROM"), espejo que falla,
/// backups y restauración.
struct SaveMirrorTests {
    typealias C = SaveResolution.Candidate
    let dir: URL
    let valid: (Int) -> Bool = { $0 == 4 }
    let old = Date(timeIntervalSince1970: 1_000)
    let new = Date(timeIntervalSince1970: 2_000)

    init() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    // MARK: SaveResolution (decisión pura)

    @Test func nothingSaved() {
        #expect(SaveResolution.resolve(local: nil, mirror: nil, isValidSize: valid) == .none)
    }

    @Test func localOnlyIsUsedAndMirrorCreated() {
        let r = SaveResolution.resolve(local: C(data: Data([1, 1, 1, 1]), date: old), mirror: nil, isValidSize: valid)
        #expect(r == .load(data: Data([1, 1, 1, 1]), backupOther: nil, installLocal: false,
                           updateMirror: true, mirrorIgnored: false, quarantineLocal: false))
    }

    @Test func mirrorOnlyIsImportedIntoLocal() {
        let r = SaveResolution.resolve(local: nil, mirror: C(data: Data([2, 2, 2, 2]), date: old), isValidSize: valid)
        #expect(r == .load(data: Data([2, 2, 2, 2]), backupOther: nil, installLocal: true,
                           updateMirror: false, mirrorIgnored: false, quarantineLocal: false))
    }

    @Test func newerMirrorWinsAndLocalIsKeptByTheInstall() {
        let r = SaveResolution.resolve(local: C(data: Data([1, 1, 1, 1]), date: old),
                                       mirror: C(data: Data([2, 2, 2, 2]), date: new), isValidSize: valid)
        #expect(r == .load(data: Data([2, 2, 2, 2]), backupOther: nil, installLocal: true,
                           updateMirror: false, mirrorIgnored: false, quarantineLocal: false))
    }

    @Test func newerLocalWinsAndMirrorIsBackedUp() {
        let r = SaveResolution.resolve(local: C(data: Data([1, 1, 1, 1]), date: new),
                                       mirror: C(data: Data([2, 2, 2, 2]), date: old), isValidSize: valid)
        #expect(r == .load(data: Data([1, 1, 1, 1]), backupOther: Data([2, 2, 2, 2]), installLocal: false,
                           updateMirror: true, mirrorIgnored: false, quarantineLocal: false))
    }

    @Test func missingDatesPreferLocal() {
        let r = SaveResolution.resolve(local: C(data: Data([1, 1, 1, 1]), date: nil),
                                       mirror: C(data: Data([2, 2, 2, 2]), date: nil), isValidSize: valid)
        #expect(r == .load(data: Data([1, 1, 1, 1]), backupOther: Data([2, 2, 2, 2]), installLocal: false,
                           updateMirror: true, mirrorIgnored: false, quarantineLocal: false))
    }

    @Test func wrongSizeMirrorIsNeverTouched() {
        // Solo el espejo, con tamaño incorrecto: no se carga y la sesión no guarda.
        #expect(SaveResolution.resolve(local: nil, mirror: C(data: Data([9]), date: new), isValidSize: valid)
                == .wrongSize(fromMirror: true))
        // Con local válida: se usa la local y el espejo queda ignorado (aunque sea más nuevo).
        let r = SaveResolution.resolve(local: C(data: Data([1, 1, 1, 1]), date: old),
                                       mirror: C(data: Data([9]), date: new), isValidSize: valid)
        #expect(r == .load(data: Data([1, 1, 1, 1]), backupOther: nil, installLocal: false,
                           updateMirror: false, mirrorIgnored: true, quarantineLocal: false))
    }

    @Test func wrongSizeLocalIsNotLoaded() {
        #expect(SaveResolution.resolve(local: C(data: Data([9]), date: new), mirror: nil, isValidSize: valid)
                == .wrongSize(fromMirror: false))
    }

    // MARK: SaveTarget y SaveMirror

    @Test func mirrorFailureKeepsLocalAndRetries() throws {
        let store = SaveStore(directory: dir, fingerprint: "ab")
        // La carpeta del espejo no existe: la escritura del espejo falla.
        let mirror = SaveMirror(url: dir.appendingPathComponent("no-existe/juego.sav"))
        let attemptFinished = DispatchSemaphore(value: 0)
        let target = SaveTarget(local: store, mirror: mirror) { data in
            defer { attemptFinished.signal() }
            try mirror.write(data)
        }
        try target.persistLocal(Data([1, 2, 3, 4]))
        #expect(attemptFinished.wait(timeout: .now() + 2) == .success)
        #expect(try store.load() == Data([1, 2, 3, 4]))
        #expect(target.mirrorPending)
        // Vuelve el acceso: el reintento escribe el espejo.
        try FileManager.default.createDirectory(at: mirror.url.deletingLastPathComponent(),
                                                withIntermediateDirectories: true)
        target.retryMirrorIfNeeded(Data([1, 2, 3, 4]))
        #expect(attemptFinished.wait(timeout: .now() + 2) == .success)
        #expect(waitUntil { !target.mirrorPending })
        #expect(try mirror.read() == Data([1, 2, 3, 4]))
    }

    @Test func persistWritesBothCopies() throws {
        let store = SaveStore(directory: dir, fingerprint: "cd")
        let mirror = SaveMirror(url: dir.appendingPathComponent("juego.sav"))
        let mirrorFinished = DispatchSemaphore(value: 0)
        let target = SaveTarget(local: store, mirror: mirror) { data in
            try mirror.write(data)
            mirrorFinished.signal()
        }
        try target.persistLocal(Data([7, 7, 7, 7]))
        #expect(mirrorFinished.wait(timeout: .now() + 2) == .success)
        #expect(try store.load() == Data([7, 7, 7, 7]))
        #expect(try mirror.read() == Data([7, 7, 7, 7]))
        #expect(waitUntil { !target.mirrorPending })
    }

    @Test func blockedMirrorDoesNotBlockLocalFlushAndCoalescesLatest() throws {
        let store = SaveStore(directory: dir, fingerprint: "blocked")
        let mirror = try mirrorFile(nil)
        let mirrorStarted = DispatchSemaphore(value: 0)
        let unblockMirror = DispatchSemaphore(value: 0)
        let mirrorFinished = DispatchSemaphore(value: 0)
        let firstLocalFlushFinished = DispatchSemaphore(value: 0)
        let secondLocalFlushFinished = DispatchSemaphore(value: 0)
        let thirdLocalFlushFinished = DispatchSemaphore(value: 0)
        let recorder = MirrorWriteRecorder()
        let target = SaveTarget(local: store, mirror: mirror) { data in
            mirrorStarted.signal()
            unblockMirror.wait()
            recorder.append(data)
            mirrorFinished.signal()
        }

        DispatchQueue.global().async {
            try? target.persistLocal(Data([1]))
            firstLocalFlushFinished.signal()
        }
        #expect(mirrorStarted.wait(timeout: .now() + 2) == .success)
        #expect(firstLocalFlushFinished.wait(timeout: .now() + 2) == .success)

        DispatchQueue.global().async {
            try? target.persistLocal(Data([2]))
            secondLocalFlushFinished.signal()
        }
        #expect(secondLocalFlushFinished.wait(timeout: .now() + 2) == .success)
        DispatchQueue.global().async {
            try? target.persistLocal(Data([3]))
            thirdLocalFlushFinished.signal()
        }
        #expect(thirdLocalFlushFinished.wait(timeout: .now() + 2) == .success)
        #expect(try store.load() == Data([3]))

        unblockMirror.signal()
        #expect(mirrorFinished.wait(timeout: .now() + 2) == .success)
        unblockMirror.signal()
        #expect(mirrorFinished.wait(timeout: .now() + 2) == .success)
        #expect(recorder.snapshot == [Data([1]), Data([3])])
    }

    @Test func staleOwnedMirrorCannotReplaceNewerLocalWhenGameReopens() throws {
        let store = SaveStore(directory: dir, fingerprint: "owned-\(UUID().uuidString)")
        let mirror = try mirrorFile(nil)
        let blocked = BlockingMirrorWriter(mirror: mirror)
        let firstSession = SaveTarget(local: store, mirror: mirror, mirrorWriter: blocked.write)
        let d1 = Data([1, 1, 1, 1])
        let d2 = Data([2, 2, 2, 2])

        try firstSession.persistLocal(d1)
        #expect(blocked.started.wait(timeout: .now() + 2) == .success)
        try firstSession.persistLocal(d2)
        blocked.unblock.signal()
        #expect(blocked.finished.wait(timeout: .now() + 2) == .success)
        #expect(blocked.started.wait(timeout: .now() + 2) == .success)
        #expect(try mirror.read() == d1)
        #expect(try store.load() == d2)

        let reopened = try SaveOpening.prepare(store: store, mirror: mirror, snapshot: mirror.snapshot(),
                                               validSizes: [4])
        #expect(reopened.data == d2)
        #expect(store.backups().isEmpty)
        reopened.target?.retryMirrorIfNeeded(d2)

        blocked.unblock.signal()
        #expect(blocked.finished.wait(timeout: .now() + 2) == .success)
        let idle = DispatchSemaphore(value: 0)
        reopened.target?.whenMirrorIdle { idle.signal() }
        #expect(idle.wait(timeout: .now() + 2) == .success)
        #expect(try mirror.read() == d2)
    }

    @Test func newerExternalMirrorWinsAndBacksUpLocal() throws {
        let store = SaveStore(directory: dir, fingerprint: "external-\(UUID().uuidString)")
        let mirror = try mirrorFile(nil)
        let own = Data([1, 1, 1, 1])
        let local = Data([2, 2, 2, 2])
        let external = Data([3, 3, 3, 3])
        let firstWrite = DispatchSemaphore(value: 0)
        let target = SaveTarget(local: store, mirror: mirror) { data in
            try mirror.write(data)
            firstWrite.signal()
        }
        try target.persistLocal(own)
        #expect(firstWrite.wait(timeout: .now() + 2) == .success)
        #expect(waitUntil { !target.mirrorPending })

        try store.save(local)
        try FileManager.default.setAttributes([.modificationDate: old], ofItemAtPath: store.saveURL.path)
        try mirror.write(external)
        try FileManager.default.setAttributes([.modificationDate: new], ofItemAtPath: mirror.url.path)

        let reopened = try SaveOpening.prepare(store: store, mirror: mirror, snapshot: mirror.snapshot(),
                                               validSizes: [4])
        #expect(reopened.data == external)
        #expect(try store.load() == external)
        #expect(try Data(contentsOf: store.backupURL(1)) == local)
    }

    @Test @MainActor func synchronousSessionFlushDoesNotWaitForBlockedRealMirror() throws {
        let mirror = try mirrorFile(nil)
        let blocked = BlockingMirrorWriter(mirror: mirror)
        var rom = LibraryScannerTests.rom(title: "BARRIER")
        rom[0x100] = 0xC3; rom[0x101] = 0x50; rom[0x102] = 0x01 // JP $0150
        rom[0x147] = 0x03 // MBC1 + RAM + batería
        rom[0x149] = 0x02 // 8 KiB SRAM
        let program: [UInt8] = [
            0x3E, 0x0A,             // LD A,$0A (habilitar RAM)
            0xEA, 0x00, 0x00,       // LD ($0000),A
            0x3E, 0x42,             // LD A,$42
            0xEA, 0x00, 0xA0,       // LD ($A000),A
            0x18, 0xFE              // JR -2
        ]
        rom.replaceSubrange(0x150..<(0x150 + program.count), with: program)
        var checksum: UInt8 = 0
        for i in 0x134...0x14C { checksum = checksum &- rom[i] &- 1 }
        rom[0x14D] = checksum

        let session = try EmulatorSession(
            romData: rom,
            savesDirectory: dir,
            mirror: mirror,
            mirrorSnapshot: .absent,
            mirrorWriter: blocked.write,
            onAudioInterrupted: {}
        )
        session.start()
        session.pause()
        #expect(blocked.started.wait(timeout: .now() + 2) == .success)
        let store = SaveStore(directory: dir, fingerprint: session.info.fingerprint)
        let saved = try #require(store.load())
        #expect(saved.count == 8 * 1024)
        #expect(saved[0] == 0x42)

        blocked.unblock.signal()
        #expect(blocked.finished.wait(timeout: .now() + 2) == .success)
        session.stop()
    }

    // MARK: Backups y restauración (docs/04 §Restaurar)

    @Test func restoreBacksUpCurrentFirst() throws {
        let store = SaveStore(directory: dir, fingerprint: "ef")
        try store.save(Data([1]))
        try store.save(Data([2]))
        try store.save(Data([3]))          // actual 3, .1 = 2, .2 = 1
        try store.restore(backup: 2)       // vuelve la 1
        #expect(try store.load() == Data([1]))
        #expect(try Data(contentsOf: store.backupURL(1)) == Data([3]))   // la actual no se pierde
        #expect(store.backups().count == 3)
    }

    @Test func addBackupDoesNotTouchCurrent() throws {
        let store = SaveStore(directory: dir, fingerprint: "01")
        try store.save(Data([1]))
        try store.addBackup(Data([8]))
        #expect(try store.load() == Data([1]))
        #expect(try Data(contentsOf: store.backupURL(1)) == Data([8]))
        for i in 0..<6 { try store.addBackup(Data([UInt8(20 + i)])) }
        #expect(store.backups().count == SaveStore.keepBackups)
        #expect(try Data(contentsOf: store.backupURL(1)) == Data([25]))
    }

    @Test func savesIndexListsGamesWithTitles() throws {
        let store = SaveStore(directory: dir, fingerprint: "aa")
        try store.save(Data([1]))
        let index = SavesIndex(directory: dir)
        index.record(fingerprint: "aa", title: "ALPHA", fileName: "alpha.gb")
        let games = index.savedGames()
        #expect(games.count == 1 && games[0].fingerprint == "aa" && games[0].record?.title == "ALPHA")
    }

    @Test func wrongSizeLocalWithValidMirrorIsQuarantined() {
        let r = SaveResolution.resolve(local: C(data: Data([9]), date: new),
                                       mirror: C(data: Data([2, 2, 2, 2]), date: old), isValidSize: valid)
        #expect(r == .load(data: Data([2, 2, 2, 2]), backupOther: nil, installLocal: true,
                           updateMirror: false, mirrorIgnored: false, quarantineLocal: true))
    }

    // MARK: SaveOpening: la decisión aplicada con E/S real (auditoría D2, H1/H3/H4/H9)

    private func mirrorFile(_ data: Data?) throws -> SaveMirror {
        let mirror = SaveMirror(url: dir.appendingPathComponent("Juegos/juego.sav"))
        try FileManager.default.createDirectory(at: mirror.url.deletingLastPathComponent(),
                                                withIntermediateDirectories: true)
        if let data { try data.write(to: mirror.url) }
        return mirror
    }

    @Test func iCloudOnlyMirrorIsUnavailableAndNeverWritten() throws {
        let mirror = try mirrorFile(nil)
        try Data("plist".utf8).write(to: mirror.placeholderURL)          // ".juego.sav.icloud"
        #expect(mirror.snapshot(polls: 0) == .unavailable)
        // Sin partida local: no se abre (se empezaría de cero y se pisaría la de iCloud).
        let store = SaveStore(directory: dir, fingerprint: "a1")
        #expect(throws: SaveOpening.Refusal.mirrorNotDownloaded) {
            _ = try SaveOpening.prepare(store: store, mirror: mirror, snapshot: .unavailable, validSizes: [4])
        }
        // Con partida local: se usa la local, el espejo queda fuera y se avisa.
        try store.save(Data([1, 1, 1, 1]))
        let outcome = try SaveOpening.prepare(store: store, mirror: mirror, snapshot: .unavailable, validSizes: [4])
        #expect(outcome.data == Data([1, 1, 1, 1]) && outcome.warning == .mirrorUnavailable)
        try outcome.target?.persistLocal(Data([3, 3, 3, 3]))
        #expect(!FileManager.default.fileExists(atPath: mirror.url.path))   // nunca se escribió
    }

    @Test func absentMirrorSnapshot() throws {
        #expect(try mirrorFile(nil).snapshot() == .absent)
        let mirror = try mirrorFile(Data([5, 5, 5, 5]))
        guard case let .read(data, _) = mirror.snapshot() else {
            Issue.record("se esperaba .read")
            return
        }
        #expect(data == Data([5, 5, 5, 5]))
    }

    @Test func wrongSizeFilesAreKeptByteForByte() throws {
        // Espejo con tamaño incorrecto y sin local: la sesión no guarda y el espejo queda intacto.
        let store = SaveStore(directory: dir, fingerprint: "b2")
        let mirror = try mirrorFile(Data([9, 9, 9]))
        let only = try SaveOpening.prepare(store: store, mirror: mirror, snapshot: mirror.snapshot(), validSizes: [4])
        #expect(only.data == nil && only.target == nil && only.warning == .mirrorWrongSizeOnly)
        #expect(try Data(contentsOf: mirror.url) == Data([9, 9, 9]))
        // Local con tamaño incorrecto y espejo válido: la local se aparta intacta.
        let store2 = SaveStore(directory: dir, fingerprint: "c3")
        try store2.save(Data([7, 7, 7]))
        let good = try mirrorFile(Data([4, 4, 4, 4]))
        let outcome = try SaveOpening.prepare(store: store2, mirror: good, snapshot: good.snapshot(), validSizes: [4])
        #expect(outcome.data == Data([4, 4, 4, 4]) && outcome.warning == .localQuarantined)
        #expect(try store2.load() == Data([4, 4, 4, 4]))
        let quarantined = try FileManager.default.contentsOfDirectory(atPath: store2.backupsDirectory.path)
            .filter { $0.hasPrefix("c3.wrong-size-") }
        #expect(quarantined.count == 1)
        #expect(try Data(contentsOf: store2.backupsDirectory.appendingPathComponent(quarantined[0])) == Data([7, 7, 7]))
    }

    @Test func losingMirrorGoesToBackupOnceOnly() throws {
        let store = SaveStore(directory: dir, fingerprint: "d4")
        try store.save(Data([1, 1, 1, 1]))
        let mirror = try mirrorFile(Data([2, 2, 2, 2]))
        try FileManager.default.setAttributes([.modificationDate: old], ofItemAtPath: mirror.url.path)
        for _ in 0..<3 {   // tres aperturas con un espejo que no se puede actualizar
            let outcome = try SaveOpening.prepare(store: store, mirror: mirror, snapshot: mirror.snapshot(),
                                                  validSizes: [4])
            #expect(outcome.data == Data([1, 1, 1, 1]))
        }
        #expect(try Data(contentsOf: store.backupURL(1)) == Data([2, 2, 2, 2]))
        #expect(store.backups().count == 1)   // sin duplicados
    }
}

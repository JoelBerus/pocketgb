import Foundation
import Testing
@testable import PocketGB

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
        let target = SaveTarget(local: store, mirror: mirror)
        try target.persist(Data([1, 2, 3, 4]))
        #expect(try store.load() == Data([1, 2, 3, 4]))
        #expect(target.mirrorPending)
        // Vuelve el acceso: el reintento escribe el espejo.
        try FileManager.default.createDirectory(at: mirror.url.deletingLastPathComponent(),
                                                withIntermediateDirectories: true)
        target.retryMirrorIfNeeded(Data([1, 2, 3, 4]))
        #expect(!target.mirrorPending)
        #expect(try mirror.read() == Data([1, 2, 3, 4]))
    }

    @Test func persistWritesBothCopies() throws {
        let store = SaveStore(directory: dir, fingerprint: "cd")
        let mirror = SaveMirror(url: dir.appendingPathComponent("juego.sav"))
        let target = SaveTarget(local: store, mirror: mirror)
        try target.persist(Data([7, 7, 7, 7]))
        #expect(try store.load() == Data([7, 7, 7, 7]))
        #expect(try mirror.read() == Data([7, 7, 7, 7]))
        #expect(!target.mirrorPending)
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
        #expect(mirror.snapshot() == .unavailable)
        // Sin partida local: no se abre (se empezaría de cero y se pisaría la de iCloud).
        let store = SaveStore(directory: dir, fingerprint: "a1")
        #expect(throws: SaveOpening.Refusal.mirrorNotDownloaded) {
            _ = try SaveOpening.prepare(store: store, mirror: mirror, snapshot: .unavailable, validSizes: [4])
        }
        // Con partida local: se usa la local, el espejo queda fuera y se avisa.
        try store.save(Data([1, 1, 1, 1]))
        let outcome = try SaveOpening.prepare(store: store, mirror: mirror, snapshot: .unavailable, validSizes: [4])
        #expect(outcome.data == Data([1, 1, 1, 1]) && outcome.warning == .mirrorUnavailable)
        try outcome.target?.persist(Data([3, 3, 3, 3]))
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

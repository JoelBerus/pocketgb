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
                           updateMirror: true, mirrorIgnored: false))
    }

    @Test func mirrorOnlyIsImportedIntoLocal() {
        let r = SaveResolution.resolve(local: nil, mirror: C(data: Data([2, 2, 2, 2]), date: old), isValidSize: valid)
        #expect(r == .load(data: Data([2, 2, 2, 2]), backupOther: nil, installLocal: true,
                           updateMirror: false, mirrorIgnored: false))
    }

    @Test func newerMirrorWinsAndLocalIsKeptByTheInstall() {
        let r = SaveResolution.resolve(local: C(data: Data([1, 1, 1, 1]), date: old),
                                       mirror: C(data: Data([2, 2, 2, 2]), date: new), isValidSize: valid)
        #expect(r == .load(data: Data([2, 2, 2, 2]), backupOther: nil, installLocal: true,
                           updateMirror: false, mirrorIgnored: false))
    }

    @Test func newerLocalWinsAndMirrorIsBackedUp() {
        let r = SaveResolution.resolve(local: C(data: Data([1, 1, 1, 1]), date: new),
                                       mirror: C(data: Data([2, 2, 2, 2]), date: old), isValidSize: valid)
        #expect(r == .load(data: Data([1, 1, 1, 1]), backupOther: Data([2, 2, 2, 2]), installLocal: false,
                           updateMirror: true, mirrorIgnored: false))
    }

    @Test func missingDatesPreferLocal() {
        let r = SaveResolution.resolve(local: C(data: Data([1, 1, 1, 1]), date: nil),
                                       mirror: C(data: Data([2, 2, 2, 2]), date: nil), isValidSize: valid)
        #expect(r == .load(data: Data([1, 1, 1, 1]), backupOther: Data([2, 2, 2, 2]), installLocal: false,
                           updateMirror: true, mirrorIgnored: false))
    }

    @Test func wrongSizeMirrorIsNeverTouched() {
        // Solo el espejo, con tamaño incorrecto: no se carga y la sesión no guarda.
        #expect(SaveResolution.resolve(local: nil, mirror: C(data: Data([9]), date: new), isValidSize: valid)
                == .wrongSize(fromMirror: true))
        // Con local válida: se usa la local y el espejo queda ignorado (aunque sea más nuevo).
        let r = SaveResolution.resolve(local: C(data: Data([1, 1, 1, 1]), date: old),
                                       mirror: C(data: Data([9]), date: new), isValidSize: valid)
        #expect(r == .load(data: Data([1, 1, 1, 1]), backupOther: nil, installLocal: false,
                           updateMirror: false, mirrorIgnored: true))
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
}

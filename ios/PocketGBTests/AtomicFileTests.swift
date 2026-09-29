import Foundation
import Testing
@testable import PocketGB

/// Invariantes de docs/04 §Saves ("Tests obligatorios (M6)"), sobre un directorio temporal.
struct AtomicFileTests {
    let dir: URL
    let store: SaveStore

    init() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        store = SaveStore(directory: dir, fingerprint: "00112233445566778899aabbccddeeff")
    }

    @Test func firstSaveCreatesFileWithoutBackups() throws {
        try store.save(Data([1, 2, 3]))
        #expect(try Data(contentsOf: store.saveURL) == Data([1, 2, 3]))
        #expect(!FileManager.default.fileExists(atPath: store.backupURL(1).path))
    }

    @Test func replaceKeepsPreviousInBackupOne() throws {
        try store.save(Data([1]))
        try store.save(Data([2]))
        #expect(try Data(contentsOf: store.saveURL) == Data([2]))
        #expect(try Data(contentsOf: store.backupURL(1)) == Data([1]))
    }

    @Test func sameContentDoesNotRotate() throws {
        try store.save(Data([1]))
        try store.save(Data([2]))
        try store.save(Data([2]))
        #expect(try Data(contentsOf: store.backupURL(1)) == Data([1]))
        #expect(!FileManager.default.fileExists(atPath: store.backupURL(2).path))
    }

    @Test func rotatesAtMostFiveBackups() throws {
        for i in 0..<8 { try store.save(Data([UInt8(i)])) }
        #expect(try Data(contentsOf: store.saveURL) == Data([7]))
        #expect(try Data(contentsOf: store.backupURL(1)) == Data([6]))
        #expect(try Data(contentsOf: store.backupURL(5)) == Data([2]))
        #expect(!FileManager.default.fileExists(atPath: store.backupURL(6).path))
    }

    @Test func orphanTmpWithoutSaveIsInstalled() throws {
        let tmp = store.saveURL.appendingPathExtension("tmp")
        try AtomicFile.writeSynced(Data([9, 9]), to: tmp)
        try store.recoverOrphans(expectedSize: 2)
        #expect(try Data(contentsOf: store.saveURL) == Data([9, 9]))
        #expect(!FileManager.default.fileExists(atPath: tmp.path))
    }

    @Test func orphanTmpWithExistingSaveIsDeleted() throws {
        try store.save(Data([1, 1]))
        let tmp = store.saveURL.appendingPathExtension("tmp")
        try AtomicFile.writeSynced(Data([9, 9]), to: tmp)
        try store.recoverOrphans(expectedSize: 2)
        #expect(try Data(contentsOf: store.saveURL) == Data([1, 1]))
        #expect(!FileManager.default.fileExists(atPath: tmp.path))
    }

    @Test func orphanTmpWithWrongSizeIsDeleted() throws {
        let tmp = store.saveURL.appendingPathExtension("tmp")
        try AtomicFile.writeSynced(Data([9]), to: tmp)
        try store.recoverOrphans(expectedSize: 2)
        #expect(!FileManager.default.fileExists(atPath: store.saveURL.path))
        #expect(!FileManager.default.fileExists(atPath: tmp.path))
    }

    // MARK: docs/04 §Saves "Tests obligatorios": fallo tras cada paso 2–4 del reemplazo.

    @Test(arguments: [2, 3, 4])
    func failureAfterStepKeepsPreviousSave(step: Int) throws {
        try store.save(Data([1, 1]))
        try store.save(Data([2, 2]))   // hay un .1 para que la rotación (paso 3) haga algo
        #expect(throws: AtomicFile.InjectedFailure(step: step)) {
            try AtomicFile.write(Data([3, 3]), to: store.saveURL, keep: SaveStore.keepBackups,
                                 backup: store.backupURL, failAfterStep: step)
        }
        #expect(try Data(contentsOf: store.saveURL) == Data([2, 2]))
        // Al arrancar, el .tmp huérfano se descarta porque el .sav existe.
        try store.recoverOrphans(expectedSize: 2)
        #expect(try Data(contentsOf: store.saveURL) == Data([2, 2]))
        #expect(!FileManager.default.fileExists(atPath: store.saveURL.appendingPathExtension("tmp").path))
    }

    @Test func interruptedFirstSaveIsRecoveredOnLaunch() throws {
        #expect(throws: AtomicFile.InjectedFailure(step: 2)) {
            try AtomicFile.write(Data([5, 5]), to: store.saveURL, backup: store.backupURL, failAfterStep: 2)
        }
        #expect(!FileManager.default.fileExists(atPath: store.saveURL.path))
        try store.recoverOrphans(expectedSize: 2)
        #expect(try Data(contentsOf: store.saveURL) == Data([5, 5]))
    }

    @Test func sevenSavesKeepBackupsOneToFive() throws {
        for i in 0..<7 { try store.save(Data([UInt8(i)])) }
        for n in 1...5 {
            #expect(try Data(contentsOf: store.backupURL(n)) == Data([UInt8(6 - n)]))
        }
        #expect(!FileManager.default.fileExists(atPath: store.backupURL(6).path))
    }
}

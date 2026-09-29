import Foundation

/// Partida local de un ROM: `Saves/<huella>.sav` y `Saves/backups/<huella>.<n>.sav`.
/// Versión básica de M4 (sin espejo en iCloud ni restauración; eso es M6).
struct SaveStore: Sendable {
    let directory: URL
    let fingerprint: String

    var saveURL: URL { directory.appendingPathComponent("\(fingerprint).sav") }
    var backupsDirectory: URL { directory.appendingPathComponent("backups", isDirectory: true) }
    func backupURL(_ n: Int) -> URL { backupsDirectory.appendingPathComponent("\(fingerprint).\(n).sav") }

    /// `Application Support/Saves`, creada si no existe.
    static func defaultDirectory() throws -> URL {
        let base = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                               appropriateFor: nil, create: true)
        let dir = base.appendingPathComponent("Saves", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    func load() throws -> Data? {
        guard FileManager.default.fileExists(atPath: saveURL.path) else { return nil }
        return try Data(contentsOf: saveURL)
    }

    func save(_ data: Data) throws {
        try AtomicFile.write(data, to: saveURL, backup: backupURL)
    }

    /// Paso 6 de docs/04 §Saves: un `.sav.tmp` huérfano se instala si no hay
    /// `.sav` y tiene el tamaño esperado; si no, se borra. Los `.1.tmp` se borran.
    func recoverOrphans(expectedSize: Int) throws {
        let fm = FileManager.default
        let tmp = saveURL.appendingPathExtension("tmp")
        if fm.fileExists(atPath: tmp.path) {
            let size = (try? fm.attributesOfItem(atPath: tmp.path)[.size] as? Int) ?? -1
            if !fm.fileExists(atPath: saveURL.path) && size == expectedSize {
                try AtomicFile.rename(tmp, saveURL)
                try AtomicFile.syncDirectory(directory)
            } else {
                try fm.removeItem(at: tmp)
            }
        }
        let backupTmp = backupURL(1).deletingPathExtension().appendingPathExtension("tmp")
        if fm.fileExists(atPath: backupTmp.path) {
            try fm.removeItem(at: backupTmp)
        }
    }
}

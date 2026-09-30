import Foundation
import CryptoKit

/// Partida local de un ROM: `Saves/<huella>.sav` y `Saves/backups/<huella>.<n>.sav`.
/// La copia local es la autoritativa (docs/04 §Saves); el espejo junto al ROM es `SaveMirror`.
struct SaveStore: Sendable {
    static let keepBackups = 5

    let directory: URL
    let fingerprint: String

    var saveURL: URL { directory.appendingPathComponent("\(fingerprint).sav") }
    var mirrorHistoryURL: URL { directory.appendingPathComponent("\(fingerprint).mirror-history.json") }
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
        try AtomicFile.write(data, to: saveURL, keep: Self.keepBackups, backup: backupURL)
    }

    /// Registro de lo que PocketGB escribió en el espejo (auditoría D2-D5 Codex, H1):
    /// - `successful`: contenido y fecha de modificación del espejo observada justo después
    ///   de escribirlo. Solo esa pareja identifica una escritura propia terminada tarde.
    /// - `pending`: write-ahead de la escritura en curso (el proceso pudo morir entre el
    ///   reemplazo remoto y la confirmación).
    private struct MirrorHistory: Codable {
        struct Written: Codable, Equatable {
            let hash: String
            let date: Date?
        }
        var successful: [Written] = []
        var pending: [String] = []

        init() {}

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            // Formato anterior (solo huellas, sin fecha): nunca prueba una escritura propia.
            if let entries = try? c.decode([Written].self, forKey: .successful) {
                successful = entries
            } else {
                successful = ((try? c.decode([String].self, forKey: .successful)) ?? []).map { Written(hash: $0, date: nil) }
            }
            pending = (try? c.decode([String].self, forKey: .pending)) ?? []
        }
    }

    /// ¿Es este espejo (contenido + fecha) una escritura de PocketGB?
    /// - Sí, si coincide con una escritura confirmada **y** su fecha es la que se observó
    ///   al escribirla: nadie lo ha tocado después, así que una fecha más nueva que la
    ///   copia local solo significa que una escritura asíncrona vieja terminó tarde.
    /// - Sí, si es la última escritura sin confirmar (el proceso murió a mitad).
    /// - No en cualquier otro caso: un contenido antiguo restaurado a mano, o copiado desde
    ///   otro dispositivo, tiene fecha nueva y se resuelve por fecha, con backup del perdedor.
    func recognizesOwnedMirror(_ data: Data, date: Date?) -> Bool {
        let hash = Self.contentHash(data)
        let history = mirrorHistory()
        if history.pending.first == hash { return true }
        guard let date else { return false }
        return history.successful.contains { entry in
            entry.hash == hash && entry.date.map { abs($0.timeIntervalSince(date)) < 0.001 } == true
        }
    }

    func recordMirrorAttempt(_ data: Data) throws {
        let hash = Self.contentHash(data)
        var history = mirrorHistory()
        history.pending.removeAll { $0 == hash }
        history.pending.insert(hash, at: 0)
        history.pending = Array(history.pending.prefix(8))
        try writeMirrorHistory(history)
    }

    /// - Parameter observedDate: fecha de modificación del espejo leída justo después de escribirlo.
    func recordSuccessfulMirror(_ data: Data, observedDate: Date?) throws {
        let hash = Self.contentHash(data)
        var history = mirrorHistory()
        history.pending.removeAll { $0 == hash }
        history.successful.removeAll { $0.hash == hash }
        history.successful.insert(.init(hash: hash, date: observedDate), at: 0)
        history.successful = Array(history.successful.prefix(8))
        try writeMirrorHistory(history)
    }

    private func writeMirrorHistory(_ history: MirrorHistory) throws {
        let encoded = try JSONEncoder().encode(history)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let tmp = mirrorHistoryURL.appendingPathExtension("tmp")
        try AtomicFile.writeSynced(encoded, to: tmp)
        try AtomicFile.rename(tmp, mirrorHistoryURL)
        try AtomicFile.syncDirectory(directory)
    }

    private func mirrorHistory() -> MirrorHistory {
        guard let data = try? Data(contentsOf: mirrorHistoryURL) else { return MirrorHistory() }
        if let history = try? JSONDecoder().decode(MirrorHistory.self, from: data) { return history }
        return MirrorHistory()
    }

    private static func contentHash(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    var modificationDate: Date? { Self.modificationDate(saveURL) }

    static func modificationDate(_ url: URL) -> Date? {
        (try? FileManager.default.attributesOfItem(atPath: url.path)[.modificationDate]) as? Date
    }

    /// Backups existentes (1 = el más reciente), con su fecha.
    func backups() -> [(index: Int, date: Date?)] {
        (1...Self.keepBackups).compactMap { n in
            let url = backupURL(n)
            guard FileManager.default.fileExists(atPath: url.path) else { return nil }
            return (n, Self.modificationDate(url))
        }
    }

    /// Guarda `data` como backup `.1` (rotando los demás) sin tocar la partida actual.
    /// Se usa cuando el espejo junto al ROM pierde frente a la copia local.
    /// Si esa copia ya está entre los backups no se añade otra vez: así un espejo que
    /// no se puede actualizar no desplaza el historial en cada apertura (auditoría D2, H4).
    func addBackup(_ data: Data) throws {
        let fm = FileManager.default
        for n in 1...Self.keepBackups where (try? Data(contentsOf: backupURL(n))) == data { return }
        try fm.createDirectory(at: backupsDirectory, withIntermediateDirectories: true)
        let oldest = backupURL(Self.keepBackups)
        if fm.fileExists(atPath: oldest.path) { try fm.removeItem(at: oldest) }
        for n in stride(from: Self.keepBackups - 1, through: 1, by: -1) where fm.fileExists(atPath: backupURL(n).path) {
            try AtomicFile.rename(backupURL(n), backupURL(n + 1))
        }
        let tmp = backupURL(1).deletingPathExtension().appendingPathExtension("tmp")
        try AtomicFile.writeSynced(data, to: tmp)
        try AtomicFile.rename(tmp, backupURL(1))
        try AtomicFile.syncDirectory(backupsDirectory)
    }

    /// Aparta la partida actual (tamaño incorrecto) fuera de la rotación de backups:
    /// `backups/<huella>.wrong-size-<unix>.sav`. Nunca se borra (auditoría D2, H3).
    func quarantineCurrent(now: Date = Date()) throws {
        guard let data = try load() else { return }
        try FileManager.default.createDirectory(at: backupsDirectory, withIntermediateDirectories: true)
        let url = quarantineURL(now)
        let tmp = url.appendingPathExtension("tmp")
        try AtomicFile.writeSynced(data, to: tmp)
        try AtomicFile.rename(tmp, url)
        try AtomicFile.syncDirectory(backupsDirectory)
    }

    func quarantineURL(_ date: Date) -> URL {
        // Con un sufijo único: dos cuarentenas en el mismo segundo no se pisan (auditoría D2, N3).
        backupsDirectory.appendingPathComponent(
            "\(fingerprint).wrong-size-\(Int(date.timeIntervalSince1970))-\(UUID().uuidString.prefix(8)).sav")
    }

    /// Restaura el backup `n`: la partida actual pasa antes a ser el backup `.1`
    /// (lo hace `AtomicFile.write`), así restaurar nunca pierde nada (docs/04 §Restaurar).
    func restore(backup n: Int) throws {
        let data = try Data(contentsOf: backupURL(n))
        try save(data)
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

import Foundation

/// Escritura atómica con backups rotativos (docs/04 §Saves, "Cómo se guarda").
/// Invariante: si ya existía el destino, en todo instante existe uno completo.
enum AtomicFile {
    struct IOError: Error, CustomStringConvertible {
        let operation: String
        let path: String
        let code: Int32
        var description: String { "\(operation) \(path): \(String(cString: strerror(code)))" }
    }

    /// Fallo simulado para los tests (docs/04 §Saves: "reemplazo con fallo tras cada paso 2–4").
    struct InjectedFailure: Error, Equatable {
        let step: Int
    }

    /// - Parameters:
    ///   - url: destino (`<huella>.sav`).
    ///   - backup: URL del backup `n` (1…`keep`), p. ej. `backups/<huella>.<n>.sav`.
    ///   - failAfterStep: solo tests; lanza `InjectedFailure` tras completar el paso 2, 3 o 4.
    static func write(_ data: Data, to url: URL, keep: Int = 5, backup: (Int) -> URL,
                      failAfterStep: Int? = nil) throws {
        let fm = FileManager.default
        let exists = fm.fileExists(atPath: url.path)

        // 1. Contenido igual al actual: nada que hacer (no rota backups).
        if exists, let current = try? Data(contentsOf: url), current == data { return }

        // 2. Escribir el temporal completo y fsync.
        let tmp = url.appendingPathExtension("tmp")
        try writeSynced(data, to: tmp)
        #if DEBUG
        if failAfterStep == 2 { throw InjectedFailure(step: 2) }
        #endif

        if exists {
            // 3. Rotar solo backups: n-1 → n, …, 1 → 2.
            try fm.createDirectory(at: backup(1).deletingLastPathComponent(), withIntermediateDirectories: true)
            for n in stride(from: keep - 1, through: 1, by: -1) where fm.fileExists(atPath: backup(n).path) {
                try rename(backup(n), backup(n + 1))
            }
            #if DEBUG
            if failAfterStep == 3 { throw InjectedFailure(step: 3) }
            #endif
            // 4. Copiar (no mover) el actual a .1 vía .1.tmp.
            let current = try Data(contentsOf: url)
            let backupTmp = backup(1).deletingPathExtension().appendingPathExtension("tmp")
            try writeSynced(current, to: backupTmp)
            try rename(backupTmp, backup(1))
            try syncDirectory(backup(1).deletingLastPathComponent())
            #if DEBUG
            if failAfterStep == 4 { throw InjectedFailure(step: 4) }
            #endif
        }

        // 5. Instalar: rename(2) reemplaza de forma atómica o crea.
        try rename(tmp, url)
        try syncDirectory(url.deletingLastPathComponent())
    }

    static func writeSynced(_ data: Data, to url: URL) throws {
        let fd = open(url.path, O_WRONLY | O_CREAT | O_TRUNC, 0o644)
        guard fd >= 0 else { throw IOError(operation: "open", path: url.path, code: errno) }
        defer { close(fd) }
        try data.withUnsafeBytes { raw in
            var offset = 0
            while offset < raw.count {
                let n = Darwin.write(fd, raw.baseAddress! + offset, raw.count - offset)
                if n < 0 {
                    if errno == EINTR { continue }
                    throw IOError(operation: "write", path: url.path, code: errno)
                }
                offset += n
            }
        }
        guard fsync(fd) == 0 else { throw IOError(operation: "fsync", path: url.path, code: errno) }
    }

    static func rename(_ from: URL, _ to: URL) throws {
        guard Darwin.rename(from.path, to.path) == 0 else {
            throw IOError(operation: "rename", path: from.path, code: errno)
        }
    }

    static func syncDirectory(_ dir: URL) throws {
        let fd = open(dir.path, O_RDONLY)
        guard fd >= 0 else { throw IOError(operation: "open", path: dir.path, code: errno) }
        defer { close(fd) }
        guard fsync(fd) == 0 else { throw IOError(operation: "fsync", path: dir.path, code: errno) }
    }
}

import CryptoKit
import Foundation

/// Huella de un ROM sin abrirlo (N1a, plan §3.1): los primeros 16 bytes, en hex, del SHA-256
/// que calcula el núcleo al cargarlo (`CoreBridge`/`GBACoreBridge`). Debe ser **idéntica**,
/// porque partidas, estados, portadas y metadatos van por ella:
/// - Game Boy: el núcleo solo hashea los bytes que declara la cabecera (`32 KiB << rom[0x148]`)
///   y descarta el excedente del archivo (`core/src/cart.c`, `cart_load`).
/// - Game Boy Advance: el archivo entero (`gba/src/gba.c`, `gba_load_rom`).
/// Si el núcleo rechazaría el archivo antes de hashearlo (tamaño o cabecera), no hay huella.
enum RomFingerprint {
    /// Bloque de lectura: memoria acotada aunque el ROM mida 32 MiB.
    static let chunkBytes = 1 << 20

    /// Bytes que hashea el núcleo, o nil si lo rechazaría antes (mismas comprobaciones, en el
    /// mismo orden, que `cart_load` y `gba_load_rom`).
    static func hashedLength(console: Console, header: Data, fileSize: Int) -> Int? {
        let bytes = [UInt8](header.prefix(RomHeader.minimumBytes))
        switch console {
        case .gameBoyAdvance:
            guard fileSize >= RomHeader.gbaMinimumBytes, fileSize <= GBACoreBridge.maxROMBytes,
                  bytes.count > 0xB2, bytes[0xB2] == 0x96 else { return nil }
            return fileSize
        case .gameBoy:
            guard fileSize >= RomHeader.minimumBytes, fileSize <= LibraryScanner.maxROMBytes,
                  bytes.count > 0x148 else { return nil }
            let code = Int(bytes[0x148])
            guard code <= 8 else { return nil }
            let size = (32 * 1024) << code
            return size <= fileSize ? size : nil
        }
    }

    static func hex<D: Sequence>(_ digest: D) -> String where D.Element == UInt8 {
        digest.prefix(16).map { String(format: "%02x", $0) }.joined()
    }

    /// Desde los bytes ya leídos (al abrir un juego, o en los tests).
    static func compute(data: Data, console: Console) -> String? {
        guard let length = hashedLength(console: console, header: data, fileSize: data.count) else { return nil }
        return hex(SHA256.hash(data: data.prefix(length)))
    }

    /// ¿Se puede leer sin descargar nada? Archivos locales o de iCloud ya descargados
    /// (`.current`/`.downloaded`). Un placeholder o un `.notDownloaded` nunca se lee: la lectura
    /// coordinada forzaría la descarga.
    static func isLocallyAvailable(_ url: URL) -> Bool {
        let keys: Set<URLResourceKey> = [.isUbiquitousItemKey, .ubiquitousItemDownloadingStatusKey, .isRegularFileKey]
        guard let values = try? url.resourceValues(forKeys: keys), values.isRegularFile == true else { return false }
        guard values.isUbiquitousItem == true else { return true }
        return values.ubiquitousItemDownloadingStatus != .notDownloaded
    }

    /// Lectura coordinada en bloques de 1 MiB, fuera del hilo principal. Cancelable entre
    /// bloques (`CancellationError`). nil si el archivo no está disponible sin descargar o si
    /// el núcleo no lo aceptaría.
    static func compute(url: URL, console: Console) throws -> String? {
        guard isLocallyAvailable(url) else { return nil }
        var result: Result<String?, Error> = .success(nil)
        var coordError: NSError?
        NSFileCoordinator(filePresenter: nil).coordinate(readingItemAt: url, options: [], error: &coordError) { u in
            result = Result { try hashFile(u, console: console) }
        }
        if let coordError { throw coordError }
        return try result.get()
    }

    private static func hashFile(_ url: URL, console: Console) throws -> String? {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        let fileSize = Int(try handle.seekToEnd())
        try handle.seek(toOffset: 0)
        let header = try handle.read(upToCount: RomHeader.minimumBytes) ?? Data()
        guard let length = hashedLength(console: console, header: header, fileSize: fileSize) else { return nil }
        try handle.seek(toOffset: 0)
        var hasher = SHA256()
        var remaining = length
        while remaining > 0 {
            try Task.checkCancellation()
            guard let chunk = try handle.read(upToCount: min(chunkBytes, remaining)), !chunk.isEmpty else {
                return nil   // el archivo se acortó mientras se leía
            }
            hasher.update(data: chunk)
            remaining -= chunk.count
        }
        return hex(hasher.finalize())
    }
}

/// Caché persistida (ruta, tamaño, fecha de modificación) → huella (N1a, plan §3.1), en
/// `Application Support/Library/fingerprint-cache.json`. Es solo una caché: si se pierde o
/// se daña, se vuelve a calcular; nunca guarda metadatos del usuario.
struct FingerprintCacheData: Codable, Equatable, Sendable {
    struct Item: Codable, Equatable, Sendable {
        var size: Int
        var modified: Date?
        var fingerprint: String
    }

    enum Lookup: Equatable, Sendable {
        /// Mismo tamaño y misma fecha: la huella vale.
        case verified(String)
        /// La ruta tenía otra huella con otro tamaño o fecha: solo es una pista hasta recalcular.
        case stale(String)
        case missing
    }

    static let currentVersion = 1
    var version = currentVersion
    /// Carpeta de la biblioteca a la que se refieren las rutas; otra carpeta vacía la caché.
    var root: String?
    var items: [String: Item] = [:]

    init() {}

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        version = (try? c.decodeIfPresent(Int.self, forKey: .version)) ?? Self.currentVersion
        root = try? c.decodeIfPresent(String.self, forKey: .root)
        items = (try? c.decodeIfPresent([String: Item].self, forKey: .items)) ?? [:]
    }

    func lookup(path: String, size: Int, modified: Date?) -> Lookup {
        guard let item = items[path] else { return .missing }
        if item.size == size, let a = item.modified, let b = modified, abs(a.timeIntervalSince(b)) < 0.001 {
            return .verified(item.fingerprint)
        }
        return .stale(item.fingerprint)
    }

    static func defaultURL() -> URL? {
        LibraryPreferences.defaultFileURL()?.deletingLastPathComponent().appendingPathComponent("fingerprint-cache.json")
    }

    static func load(_ url: URL?) -> FingerprintCacheData {
        guard let url, let raw = try? Data(contentsOf: url),
              let decoded = try? JSONDecoder().decode(FingerprintCacheData.self, from: raw) else { return .init() }
        return decoded
    }

    /// Escritura atómica (temporal + fsync + rename).
    static func write(_ encoded: Data, to url: URL) throws {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let tmp = url.appendingPathExtension("tmp")
        try AtomicFile.writeSynced(encoded, to: tmp)
        try AtomicFile.rename(tmp, url)
    }
}

/// Identidad de los juegos de la biblioteca a partir de la caché (funciones puras, se prueban
/// sin disco ni actor).
enum LibraryIdentity {
    /// Un archivo cuya huella hay que calcular en segundo plano.
    struct Job: Equatable, Sendable {
        let path: String
        let url: URL
        let console: Console
        let size: Int
        let modified: Date?
    }

    struct Resolution: Sendable {
        var entries: [RomEntry]
        /// Huellas confirmadas por la caché (mismo tamaño y fecha), por ruta.
        var verified: [String: String]
        /// Archivos que hay que hashear.
        var jobs: [Job]
    }

    /// Asigna a cada juego la huella de la caché (verificada, o provisional si la ruta cambió de
    /// tamaño o fecha o no se puede leer ahora) y devuelve los que hay que hashear: solo los que
    /// se pueden jugar ya (locales o descargados) y sin huella verificada.
    static func resolve(_ entries: [RomEntry], cache: FingerprintCacheData) -> Resolution {
        var result = entries
        var verified: [String: String] = [:]
        var jobs: [Job] = []
        for i in result.indices {
            let entry = result[i]
            let lookup = cache.lookup(path: entry.id, size: entry.sizeBytes, modified: entry.modificationDate)
            switch lookup {
            case .verified(let fp):
                result[i].fingerprint = fp
                verified[entry.id] = fp
                continue
            case .stale(let fp):
                result[i].fingerprint = fp
            case .missing:
                break
            }
            if entry.isPlayable {
                jobs.append(Job(path: entry.id, url: entry.url, console: entry.console,
                                size: entry.sizeBytes, modified: entry.modificationDate))
            }
        }
        markDuplicates(&result)
        return Resolution(entries: result, verified: verified, jobs: jobs)
    }

    /// Misma huella en varias rutas: cada copia sabe en qué otras rutas está.
    static func markDuplicates(_ entries: inout [RomEntry]) {
        var byFingerprint: [String: [String]] = [:]
        for entry in entries {
            if let fp = entry.fingerprint { byFingerprint[fp, default: []].append(entry.id) }
        }
        for i in entries.indices {
            let others = entries[i].fingerprint.flatMap { byFingerprint[$0] }?
                .filter { $0 != entries[i].id }
                .sorted { $0.localizedStandardCompare($1) == .orderedAscending } ?? []
            if entries[i].duplicatePaths != others { entries[i].duplicatePaths = others }
        }
    }
}

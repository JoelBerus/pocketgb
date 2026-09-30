import Foundation
import Observation
import os

/// Dónde se guarda el bookmark de la carpeta (inyectable para los tests).
protocol BookmarkStorage: Sendable {
    func load() -> Data?
    func save(_ data: Data)
}

struct UserDefaultsBookmarkStorage: BookmarkStorage {
    static let key = "libraryFolderBookmark"
    func load() -> Data? { UserDefaults.standard.data(forKey: Self.key) }
    func save(_ data: Data) { UserDefaults.standard.set(data, forKey: Self.key) }
}

/// Estado de la carpeta de la biblioteca.
enum LibraryPhase: Equatable {
    /// Primer arranque: aún no hay carpeta.
    case noFolder
    /// Había carpeta, pero el bookmark ya no se resuelve o no hay acceso.
    case unavailable(folderName: String?)
    /// Carpeta accesible (los juegos están en `LibraryStore.entries`).
    case ready(folderName: String)
}

/// Biblioteca por carpeta (docs/04 §Biblioteca, D-README §4): bookmark de la carpeta
/// elegida en Archivos o iCloud Drive, escaneo coordinado y estados de iCloud.
/// Nunca copia, mueve ni modifica los ROMs.
@MainActor @Observable
final class LibraryStore {
    struct ScanProgress: Equatable {
        let done: Int
        let total: Int
    }

    private(set) var phase: LibraryPhase = .noFolder
    private(set) var entries: [RomEntry] = []
    private(set) var scanProgress: ScanProgress?
    /// Aviso breve tras un escaneo con novedades ("2 juegos nuevos").
    private(set) var summary: String?

    var isScanning: Bool { scanProgress != nil }

    private let storage: BookmarkStorage
    private var folderURL: URL?
    private var accessingURL: URL?
    private var scanGeneration = 0
    /// Último escaneo terminado: un aviso de progreso que llegue tarde no lo reabre (auditoría D2, H7).
    private var finishedGeneration = 0
    private static let knownKey = "libraryKnownROMs"
    private let log = Logger(subsystem: "com.joelbermudez.pocketgb", category: "library")

    init(storage: BookmarkStorage = UserDefaultsBookmarkStorage()) {
        self.storage = storage
    }

    /// Al arrancar: resuelve el bookmark guardado y escanea.
    func restore() {
        guard let data = storage.load() else {
            // Sin bookmark: "no disponible" se queda como está (reintentar no lo borra; auditoría D2, N1).
            if case .unavailable = phase { return }
            phase = .noFolder
            return
        }
        do {
            var stale = false
            let url = try URL(resolvingBookmarkData: data, options: [], relativeTo: nil,
                              bookmarkDataIsStale: &stale)
            beginAccess(url)
            if stale, let fresh = try? url.bookmarkData(options: .minimalBookmark,
                                                          includingResourceValuesForKeys: nil, relativeTo: nil) {
                storage.save(fresh)
            }
            guard (try? url.checkResourceIsReachable()) == true else {
                phase = .unavailable(folderName: url.lastPathComponent)
                return
            }
            folderURL = url
            phase = .ready(folderName: url.lastPathComponent)
            refresh()
        } catch {
            log.error("Bookmark de la carpeta sin resolver: \(error.localizedDescription, privacy: .public)")
            phase = .unavailable(folderName: nil)
        }
    }

    /// Carpeta elegida en el selector de documentos.
    func choose(folder url: URL) {
        beginAccess(url)
        do {
            let data = try url.bookmarkData(options: .minimalBookmark,
                                            includingResourceValuesForKeys: nil, relativeTo: nil)
            storage.save(data)
        } catch {
            log.error("No se pudo crear el bookmark: \(error.localizedDescription, privacy: .public)")
        }
        folderURL = url
        entries = []
        phase = .ready(folderName: url.lastPathComponent)
        refresh()
    }

    /// Vuelve a escanear (al volver a primer plano o a petición).
    func refresh() {
        guard let folder = folderURL else { return }
        scanGeneration += 1
        let generation = scanGeneration
        scanProgress = ScanProgress(done: 0, total: 0)
        Task.detached(priority: .userInitiated) { [weak self] in
            let found = LibraryScanner.scan(folder: folder) { done, total in
                guard done == total || done % 8 == 0 else { return }
                Task { @MainActor [weak self] in
                    guard let self, self.scanGeneration == generation,
                          self.finishedGeneration != generation else { return }
                    self.scanProgress = ScanProgress(done: done, total: total)
                }
            }
            await self?.finishScan(found, generation: generation)
        }
    }

    /// Descarga un ROM que solo está en iCloud y reescanea al terminar.
    func download(_ entry: RomEntry) {
        guard entry.cloud == .notDownloaded else { return }
        if let i = entries.firstIndex(where: { $0.id == entry.id }) {
            entries[i].cloud = .downloading
        }
        let url = entry.url
        do {
            try FileManager.default.startDownloadingUbiquitousItem(at: url)
        } catch {
            log.error("Descarga de iCloud sin iniciar: \(error.localizedDescription, privacy: .public)")
        }
        Task.detached(priority: .utility) { [weak self] in
            // La lectura coordinada espera a que el archivo esté descargado.
            _ = try? LibraryScanner.readHeader(url)
            await self?.refresh()
        }
    }

    private func finishScan(_ found: [RomEntry], generation: Int) {
        guard generation == scanGeneration else { return }
        finishedGeneration = generation
        let defaults = UserDefaults.standard
        let known = Set(defaults.stringArray(forKey: Self.knownKey) ?? [])
        var result = found
        var newCount = 0
        if !known.isEmpty {
            for i in result.indices where !known.contains(result[i].id) {
                result[i].isNew = true
                newCount += 1
            }
        }
        defaults.set(Array(known.union(found.map(\.id))), forKey: Self.knownKey)
        entries = result
        scanProgress = nil
        if newCount > 0 { showSummary(newCount == 1 ? "1 juego nuevo" : "\(newCount) juegos nuevos") }
    }

    private func showSummary(_ text: String) {
        summary = text
        Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(2.5))
            if self?.summary == text { self?.summary = nil }
        }
    }

    private func beginAccess(_ url: URL) {
        if let old = accessingURL, old != url { old.stopAccessingSecurityScopedResource() }
        if accessingURL != url, url.startAccessingSecurityScopedResource() {
            accessingURL = url
        }
    }

    #if DEBUG
    /// Estado de demostración para capturas (sin disco ni iCloud).
    func applyDemo(phase: LibraryPhase, entries: [RomEntry], progress: ScanProgress? = nil,
                   summary: String? = nil) {
        self.phase = phase
        self.entries = entries
        scanProgress = progress
        self.summary = summary
    }
    #endif
}

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
    /// El último escaneo llegó al tope de juegos (`maxEntries`) o de elementos recorridos
    /// (`maxVisitedItems`). Se muestra en Ajustes › Biblioteca mientras dure.
    private(set) var limitReached = false
    /// Calculando huellas en segundo plano (N1a).
    private(set) var isHashing = false

    var isScanning: Bool { scanProgress != nil }

    /// Huellas recién conocidas (ruta → huella), por lotes: la app une ahí los metadatos
    /// provisionales por ruta (`LibraryPreferences.adopt`).
    @ObservationIgnored var onFingerprintsResolved: (([String: String]) -> Void)?

    private let storage: BookmarkStorage
    private var folderURL: URL?
    /// Carpeta de la biblioteca (BIOS opcional de GBA en su raíz).
    var rootURL: URL? { folderURL }
    private var accessingURL: URL?
    private var scanGeneration = 0
    /// Último escaneo terminado: un aviso de progreso que llegue tarde no lo reabre (auditoría D2, H7).
    private var finishedGeneration = 0
    private static let knownKey = "libraryKnownROMs"
    private let knownDefaults: UserDefaults
    private let log = Logger(subsystem: "com.joelbermudez.pocketgb", category: "library")

    /// Caché (ruta, tamaño, fecha) → huella; `cacheURL == nil` = solo en memoria.
    @ObservationIgnored private var cache: FingerprintCacheData
    @ObservationIgnored private let cacheURL: URL?
    @ObservationIgnored private let cacheQueue = DispatchQueue(label: "PocketGB.fingerprint-cache", qos: .utility)
    @ObservationIgnored private var scanTask: Task<Void, Never>?
    @ObservationIgnored private var hashTask: Task<Void, Never>?
    /// Cada arranque de la cola tiene su número: uno cancelado no marca el fin del siguiente.
    @ObservationIgnored private var hashRun = 0
    @ObservationIgnored private var unsavedCacheItems = 0
    /// Con un juego abierto el cálculo se pausa (no compite con la emulación; auditoría N1, H7).
    @ObservationIgnored private(set) var hashingPaused = false

    init(storage: BookmarkStorage = UserDefaultsBookmarkStorage(),
         cacheURL: URL? = FingerprintCacheData.defaultURL(),
         knownDefaults: UserDefaults = .standard) {
        self.storage = storage
        self.cacheURL = cacheURL
        self.knownDefaults = knownDefaults
        cache = FingerprintCacheData.load(cacheURL)
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

    /// Vuelve a escanear (al volver a primer plano o a petición). Cancela el cálculo de
    /// huellas pendiente: el escaneo nuevo lo vuelve a pedir con lo que siga sin huella.
    func refresh() {
        guard let folder = folderURL else { return }
        scanGeneration += 1
        let generation = scanGeneration
        scanProgress = ScanProgress(done: 0, total: 0)
        hashTask?.cancel()
        scanTask = Task.detached(priority: .userInitiated) { [weak self] in
            let found = LibraryScanner.scanResult(folder: folder) { done, total in
                guard done == total || done % 8 == 0 else { return }
                Task { @MainActor [weak self] in
                    guard let self, self.scanGeneration == generation,
                          self.finishedGeneration != generation else { return }
                    self.scanProgress = ScanProgress(done: done, total: total)
                }
            }
            await self?.finishScan(found, folder: folder, generation: generation)
        }
    }

    /// Espera al escaneo y al cálculo de huellas en curso (tests).
    func waitUntilIdle() async {
        await scanTask?.value
        await hashTask?.value
        cacheQueue.sync {}
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

    private func finishScan(_ found: LibraryScanner.ScanResult, folder: URL, generation: Int) {
        guard generation == scanGeneration else { return }
        finishedGeneration = generation
        let defaults = knownDefaults
        let known = Set(defaults.stringArray(forKey: Self.knownKey) ?? [])
        var result = found.entries
        var newCount = 0
        if !known.isEmpty {
            for i in result.indices where !known.contains(result[i].id) {
                result[i].isNew = true
                newCount += 1
            }
        }
        defaults.set(Array(known.union(result.map(\.id))), forKey: Self.knownKey)

        // Identidad (N1a): huellas de la caché; las que faltan se calculan en segundo plano.
        let root = folder.resolvingSymlinksInPath().path
        if cache.root != root {
            cache = FingerprintCacheData()
            cache.root = root
        }
        if !found.limitReached {
            // Solo se recuerdan las rutas que siguen en la carpeta.
            let present = Set(result.map(\.id))
            cache.items = cache.items.filter { present.contains($0.key) }
        }
        let resolved = LibraryIdentity.resolve(result, cache: cache)
        entries = resolved.entries
        limitReached = found.limitReached
        scanProgress = nil
        persistCache()
        // Solo lo confirmado: una huella provisional (archivo cambiado o sin descargar) no
        // se lleva los metadatos provisionales de la ruta.
        if !resolved.verified.isEmpty { onFingerprintsResolved?(resolved.verified) }
        startHashing(resolved.jobs, generation: generation)

        if found.limitReached {
            showSummary("La carpeta es demasiado grande: no se leyó entera")
        } else if newCount > 0 {
            showSummary(newCount == 1 ? "1 juego nuevo" : "\(newCount) juegos nuevos")
        }
    }

    // MARK: - Huellas en segundo plano (N1a)

    /// Calcula las huellas que faltan fuera del hilo principal, con prioridad baja y lectura
    /// coordinada, solo de archivos locales o ya descargados (`RomFingerprint`). Se cancela
    /// con un escaneo nuevo; los resultados llegan por lotes.
    private func startHashing(_ jobs: [LibraryIdentity.Job], generation: Int) {
        hashTask?.cancel()
        hashRun += 1
        let run = hashRun
        guard !jobs.isEmpty, !hashingPaused else {
            isHashing = false
            return
        }
        isHashing = true
        hashTask = Task.detached(priority: .utility) { [weak self] in
            var batch: [(job: LibraryIdentity.Job, fingerprint: String)] = []
            var lastFlush = ContinuousClock.now
            for job in jobs {
                if Task.isCancelled { break }
                do {
                    if let fp = try RomFingerprint.compute(url: job.url, console: job.console) {
                        batch.append((job, fp))
                    }
                } catch is CancellationError {
                    break
                } catch {
                    // Un archivo que no se puede leer se queda sin huella (como en el escaneo).
                }
                // Lotes de 32 archivos o 0,75 s: menos trabajo en el hilo principal por archivo.
                if batch.count >= 32 || ContinuousClock.now - lastFlush > .milliseconds(750) {
                    let done = batch
                    batch = []
                    lastFlush = .now
                    if !done.isEmpty { await self?.applyFingerprints(done, generation: generation) }
                }
            }
            let rest = batch
            await self?.finishHashing(rest, generation: generation, run: run)
        }
    }

    private func finishHashing(_ rest: [(job: LibraryIdentity.Job, fingerprint: String)], generation: Int, run: Int) {
        // Lo calculado antes de una pausa o cancelación vale igual (misma carpeta).
        if !rest.isEmpty { applyFingerprints(rest, generation: generation) }
        guard generation == scanGeneration else { return }
        unsavedCacheItems = 0
        persistCache()
        if run == hashRun { isHashing = false }
    }

    /// Pausa o reanuda el cálculo de huellas (la app lo pausa mientras hay un juego abierto). Al
    /// reanudar se vuelve a pedir solo lo que siga sin huella verificada.
    func setHashingPaused(_ paused: Bool) {
        guard paused != hashingPaused else { return }
        hashingPaused = paused
        if paused {
            hashTask?.cancel()
            isHashing = false
        } else if finishedGeneration == scanGeneration, !entries.isEmpty {
            startHashing(LibraryIdentity.resolve(entries, cache: cache).jobs, generation: scanGeneration)
        }
    }

    private func applyFingerprints(_ results: [(job: LibraryIdentity.Job, fingerprint: String)], generation: Int) {
        guard generation == scanGeneration, !results.isEmpty else { return }
        var index: [String: Int] = [:]
        for (i, entry) in entries.enumerated() { index[entry.id] = i }
        var updated = entries
        var resolved: [String: String] = [:]
        for (job, fp) in results {
            cache.items[job.path] = job.cacheItem(fp)
            resolved[job.path] = fp
            if let i = index[job.path] {
                updated[i].fingerprint = fp
                updated[i].fingerprintVerified = true
            }
        }
        LibraryIdentity.markDuplicates(&updated)
        entries = updated
        // La caché entera se codifica en el hilo principal: no en cada lote, sino cada 256
        // huellas y al terminar (si la app se cierra antes, solo se recalcula).
        unsavedCacheItems += results.count
        if unsavedCacheItems >= 256 {
            unsavedCacheItems = 0
            persistCache()
        }
        onFingerprintsResolved?(resolved)
    }

    /// Huella que dio el núcleo al abrir el juego: es la de verdad para esa ruta.
    func learnFingerprint(_ fingerprint: String, forPath path: String) {
        guard let i = entries.firstIndex(where: { $0.id == path }) else { return }
        let entry = entries[i]
        cache.items[path] = .init(size: entry.sizeBytes, modified: entry.modificationDate,
                                  changed: entry.attributeModificationDate, fingerprint: fingerprint)
        guard entry.fingerprint != fingerprint || !entry.fingerprintVerified else {
            persistCache()
            return
        }
        var updated = entries
        updated[i].fingerprint = fingerprint
        updated[i].fingerprintVerified = true
        LibraryIdentity.markDuplicates(&updated)
        entries = updated
        persistCache()
    }

    /// Resultado de confirmar la huella de un juego antes de escribir sus etiquetas o su categoría.
    enum FingerprintConfirmation: Equatable, Sendable {
        case confirmed(String)
        /// No se puede leer ahora sin descargarlo (iCloud) o el archivo no es un ROM que acepte el núcleo.
        case unavailable
    }

    /// N4: confirma la huella de un juego leyéndolo (fuera del hilo principal, lectura coordinada y
    /// solo si está en el iPhone: nunca fuerza una descarga). La huella pasa a la caché y a la
    /// biblioteca y, como las del escaneo, se lleva los metadatos provisionales de la ruta.
    func confirmFingerprint(for path: String) async -> FingerprintConfirmation {
        guard let entry = entries.first(where: { $0.id == path }) else { return .unavailable }
        if entry.fingerprintVerified, let fp = entry.fingerprint { return .confirmed(fp) }
        guard entry.isPlayable else { return .unavailable }
        let url = entry.url
        let console = entry.console
        let computed = await Task.detached(priority: .userInitiated) {
            try? RomFingerprint.compute(url: url, console: console)
        }.value
        guard let fp = computed else { return .unavailable }
        learnFingerprint(fp, forPath: path)
        onFingerprintsResolved?([path: fp])
        return .confirmed(fp)
    }

    private func persistCache() {
        guard let cacheURL, let encoded = try? JSONEncoder().encode(cache) else { return }
        let log = self.log
        cacheQueue.async {
            do {
                try FingerprintCacheData.write(encoded, to: cacheURL)
            } catch {
                // Es una caché: si no se guarda, la próxima vez se recalcula.
                log.error("Caché de huellas sin guardar: \(error.localizedDescription, privacy: .public)")
            }
        }
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

/// Cableado de la identidad (N1a) entre la biblioteca, las preferencias y la app: las huellas que
/// llegan del escaneo se llevan los metadatos provisionales por ruta, y los problemas del archivo
/// de preferencias se avisan (al arrancar y al escribir). Separado de `AppState` para probarlo.
@MainActor
enum LibraryIdentityWiring {
    /// - Parameters:
    ///   - alert: muestra un aviso (título, texto).
    ///   - fingerprintsResolved: huellas recién conocidas, después de unir los metadatos.
    static func connect(library: LibraryStore, prefs: LibraryPreferences,
                        alert: @escaping (String, String) -> Void,
                        fingerprintsResolved: @escaping (Set<String>) -> Void) {
        library.onFingerprintsResolved = { [weak prefs] resolved in
            prefs?.adopt(resolved)
            fingerprintsResolved(Set(resolved.values))
        }
        prefs.onIssue = { issue in alert(issue.title, issue.message) }
        if let issue = prefs.issue { alert(issue.title, issue.message) }
    }
}

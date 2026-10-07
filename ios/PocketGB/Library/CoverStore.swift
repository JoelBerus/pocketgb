import Foundation
import Observation
import UIKit

/// Lo que se dibuja: la fuente y su imagen (`nil` en `.generated`, o mientras se lee).
struct ShownCover: Equatable {
    let kind: CoverKind
    let image: UIImage?

    static let generated = ShownCover(kind: .generated, image: nil)
}

/// N5 · todas las fuentes de portada de un juego y la elección (paridad con Android, `CoverRepository`).
/// Las imágenes son entrada no confiable: se leen con tope, se validan por firma y dimensiones, se reducen
/// con ImageIO (`CoverDecoder`) y, ante cualquier fallo, se pasa a la siguiente fuente hasta la generada.
/// Nada de esto toca partidas ni estados; la app no tiene red (las imágenes solo llegan del usuario).
///
/// - `captures`: último fotograma al cerrar (K9), `Application Support/Artwork/`.
/// - `pinned`: captura fijada con «Usar como portada», `ArtworkPinned/` (el cierre no la pisa).
/// - `imported`: copia reducida (≤ 1024 px, PNG) de la imagen importada, `Covers/Imported/`.
/// - `folderCache`: copia reducida de la imagen de la carpeta, por ruta + sello, `Covers/Folder/` (no se
///   vuelve a leer —en iCloud, a descargar— mientras el sello no cambie).
/// - `settings`: elección por juego y preferencia global, `Covers/settings.json`.
@MainActor @Observable
final class CoverStore {
    let captures: GameArtworkStore
    let pinned: GameArtworkStore
    let imported: GameArtworkStore
    let folderCache: GameArtworkStore
    private(set) var settings: CoverSettings
    @ObservationIgnored private var settingsFile: CoverSettingsFile
    /// Claves de imágenes de la carpeta que ya fallaron en esta ejecución: no se reintentan.
    private(set) var failedFolder: Set<String> = []
    /// Claves que se están leyendo: dos tarjetas del mismo juego no leen la imagen a la vez.
    @ObservationIgnored private var readingFolder: Set<String> = []
    @ObservationIgnored private let readFolderImage: @Sendable (URL) -> Data?
    private let queue = DispatchQueue(label: "PocketGB.covers", qos: .utility)

    init(captures: GameArtworkStore, pinned: GameArtworkStore, imported: GameArtworkStore,
         folderCache: GameArtworkStore, settingsURL: URL?,
         readFolderImage: @escaping @Sendable (URL) -> Data? = { CoverDecoder.readLimited($0) }) {
        self.captures = captures
        self.pinned = pinned
        self.imported = imported
        self.folderCache = folderCache
        var file = CoverSettingsFile(url: settingsURL)
        settings = file.load()
        settingsFile = file
        self.readFolderImage = readFolderImage
    }

    /// Las carpetas de siempre; con `inMemory`, todo en memoria (tests y capturas).
    static func standard(captures: GameArtworkStore, inMemory: Bool) -> CoverStore {
        func dir(_ path: String) -> URL? { inMemory ? nil : GameArtworkStore.supportDirectory(path) }
        return CoverStore(captures: captures,
                          pinned: GameArtworkStore(directory: dir("ArtworkPinned")),
                          imported: GameArtworkStore(directory: dir("Covers/Imported"), untrusted: true),
                          folderCache: GameArtworkStore(directory: dir("Covers/Folder"), untrusted: true),
                          settingsURL: inMemory ? nil : CoverSettingsFile.defaultURL())
    }

    /// Carpetas que cuenta Ajustes › Almacenamiento › Portadas (sin `settings.json`, N5A-3).
    var directories: [URL] {
        [captures, pinned, imported, folderCache].compactMap(\.directoryURL)
    }

    // MARK: Resolución

    static func folderKey(_ entry: RomEntry) -> String? {
        guard entry.coverURL != nil, let stamp = entry.coverStamp else { return nil }
        return SidecarCover.cacheKey(path: entry.id, stamp: stamp)
    }

    func availability(_ entry: RomEntry, fingerprint: String?) -> CoverAvailability {
        let folder = Self.folderKey(entry)
        return CoverAvailability(
            imported: imported.has(fingerprint),
            sidecar: folder.map { !failedFolder.contains($0) && !folderCache.missing.contains($0) } ?? false,
            capture: pinned.has(fingerprint) || captures.has(fingerprint))
    }

    func choice(for fingerprint: String?) -> CoverChoice { settings.choice(for: fingerprint) }

    /// Fuente que se verá si todas se leen bien (sin decodificar).
    func resolve(_ entry: RomEntry, fingerprint: String?) -> CoverKind {
        CoverResolver.resolve(choice(for: fingerprint), settings.preference, availability(entry, fingerprint: fingerprint))
    }

    /// Lo que se ve ahora: la primera candidata ya leída. Una candidata que aún se está leyendo detiene la
    /// búsqueda (se ve la generada un instante, nunca otra fuente que luego cambia); una que falló se salta.
    func shown(_ entry: RomEntry, fingerprint: String?) -> ShownCover {
        let order = CoverResolver.candidates(choice(for: fingerprint), settings.preference,
                                             availability(entry, fingerprint: fingerprint))
        for kind in order {
            switch kind {
            case .imported:
                if let image = imported.image(for: fingerprint) { return ShownCover(kind: kind, image: image) }
                if imported.has(fingerprint) { return ShownCover(kind: kind, image: nil) }
            case .sidecar:
                guard let key = Self.folderKey(entry) else { continue }
                if let image = folderCache.image(for: key) { return ShownCover(kind: kind, image: image) }
                if !failedFolder.contains(key), !folderCache.missing.contains(key) { return ShownCover(kind: kind, image: nil) }
            case .capture:
                if let image = pinned.image(for: fingerprint) { return ShownCover(kind: kind, image: image) }
                if pinned.has(fingerprint) { return ShownCover(kind: kind, image: nil) }
                if let image = captures.image(for: fingerprint) { return ShownCover(kind: kind, image: image) }
                if captures.has(fingerprint) { return ShownCover(kind: kind, image: nil) }
            case .generated:
                return .generated
            }
        }
        return .generated
    }

    /// Pide la lectura que necesita `shown`: solo la de la fuente que se verá (una imagen de la carpeta en
    /// iCloud no se lee si gana otra). Si esa lectura falla, `shown` pasa a la siguiente y la vista vuelve a
    /// llamar aquí (su `.task` depende de la fuente mostrada).
    func load(_ entry: RomEntry, fingerprint: String?) {
        let shown = shown(entry, fingerprint: fingerprint)
        guard shown.image == nil else { return }
        switch shown.kind {
        case .imported: if let fingerprint { imported.load(fingerprint) }
        case .sidecar: loadFolderImage(entry)
        case .capture:
            if let fingerprint { pinned.has(fingerprint) ? pinned.load(fingerprint) : captures.load(fingerprint) }
        case .generated: break
        }
    }

    private func loadFolderImage(_ entry: RomEntry) {
        guard let key = Self.folderKey(entry), let url = entry.coverURL,
              folderCache.image(for: key) == nil, !failedFolder.contains(key) else { return }
        if folderCache.has(key) {
            folderCache.load(key)     // copia reducida ya guardada (se decodifica como no confiable)
            return
        }
        guard !folderCache.missing.contains(key), readingFolder.insert(key).inserted else { return }
        let read = readFolderImage
        queue.async {
            let png = read(url).flatMap(CoverDecoder.reduce)
            let image = png.flatMap { UIImage(data: $0) }
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.readingFolder.remove(key)
                if let png, let image {
                    self.folderCache.saveEncoded(key, png: png, image: image)
                } else {
                    self.failedFolder.insert(key)
                }
            }
        }
    }

    /// N5A-2: tras un escaneo completo, borra las copias de imágenes de la carpeta que ya no corresponden a
    /// ninguna entrada (la imagen cambió de sello, se movió o se borró). Nunca toca la carpeta del usuario.
    @discardableResult
    func pruneFolderCache(_ entries: [RomEntry]) -> Int {
        folderCache.retainOnly(Set(entries.compactMap(Self.folderKey)))
    }

    // MARK: Cambios

    /// Importa una imagen (bytes tal como llegaron, no confiables): reduce fuera del hilo principal, guarda
    /// la copia y elige «Imagen». `false` si la imagen no vale o no se pudo guardar (no cambia nada).
    func importImage(_ data: Data, fingerprint: String) async -> Bool {
        let reduced = await Task.detached(priority: .userInitiated) { () -> (Data, UIImage)? in
            guard let png = CoverDecoder.reduce(data), let image = UIImage(data: png) else { return nil }
            return (png, image)
        }.value
        guard let (png, image) = reduced, imported.saveEncoded(fingerprint, png: png, image: image) else { return false }
        setChoice(.image, for: fingerprint)
        return true
    }

    /// Quita la imagen importada; si la elección era «Imagen» y no queda otra imagen, vuelve a Automática.
    func removeImported(_ entry: RomEntry, fingerprint: String) {
        imported.remove(fingerprint)
        if choice(for: fingerprint) == .image && entry.coverURL == nil { setChoice(.auto, for: fingerprint) }
    }

    /// «Usar como portada» (pausa): fija el fotograma y elige «Captura». `false` si el fotograma no vale.
    func pinCapture(fingerprint: String, pixels: [UInt32]) -> Bool {
        guard pinned.save(fingerprint: fingerprint, pixels: pixels) else { return false }
        setChoice(.capture, for: fingerprint)
        return true
    }

    /// Suelta la captura fijada: vuelve a usarse el último fotograma al cerrar.
    func unpinCapture(fingerprint: String) {
        pinned.remove(fingerprint)
    }

    func setChoice(_ choice: CoverChoice, for fingerprint: String) {
        guard CoverSettingsFile.isValidKey(fingerprint) else { return }
        update(settings.with(choice, for: fingerprint))
    }

    func setPreference(_ preference: CoverPreference) {
        var next = settings
        next.preference = preference
        update(next)
    }

    private func update(_ next: CoverSettings) {
        guard next != settings else { return }
        settings = next
        settingsFile.save(next)
    }

    /// Borra todas las portadas guardadas por la app (nunca las imágenes de la carpeta del usuario).
    /// La elección por juego y la preferencia se conservan.
    func removeAll() {
        for store in [captures, pinned, imported, folderCache] { store.removeAll() }
        failedFolder.removeAll()
    }

    func waitForPendingWork() {
        queue.sync {}
        for store in [captures, pinned, imported, folderCache] { store.waitForPendingWork() }
    }

    #if DEBUG
    /// Capturas: guarda en memoria una imagen pintada en código como importada o como imagen de la carpeta.
    func applyDemo(imported image: UIImage, fingerprint: String) {
        imported.saveEncoded(fingerprint, png: Data(), image: image)
    }

    func applyDemo(folder image: UIImage, entry: RomEntry) {
        if let key = Self.folderKey(entry) { folderCache.saveEncoded(key, png: Data(), image: image) }
    }

    func applyDemo(settings: CoverSettings) {
        self.settings = settings
    }
    #endif
}

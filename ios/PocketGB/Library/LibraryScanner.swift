import Foundation

/// Enumera los ROMs de la carpeta de la biblioteca (docs/04 §Biblioteca, D-README §4, N1b):
/// solo `.gb`/`.gbc`/`.gba`, en la carpeta y en sus subcarpetas hasta `maxFolderDepth` niveles,
/// sin abrir ni ejecutar los ROMs y sin modificarlos. Se llama fuera del hilo principal.
///
/// Nombres reservados (ND11): lo que empieza por `.` se ignora; `PocketGB/` en la raíz es de la
/// app (intercambio y exportados) y no se escanea; las carpetas que empiezan por `_` quedan
/// apartadas (p. ej. `_Revisar/`). Los enlaces simbólicos a carpetas no se siguen.
enum LibraryScanner {
    static let maxROMBytes = 8 * 1024 * 1024
    static let extensions: Set<String> = ["gb", "gbc", "gba"]
    /// Niveles de carpeta bajo la raíz: un juego en `A/B/C/D/E/` se lee; en `A/…/F/`, no.
    static let maxFolderDepth = 5
    /// Tope de juegos por escaneo: una carpeta enorme no bloquea la app.
    static let maxEntries = 5_000
    /// Tope de elementos recorridos (archivos y carpetas de cualquier tipo): un árbol enorme sin
    /// ROMs tampoco bloquea el escaneo (auditoría N1, H11).
    static let maxVisitedItems = 50_000
    /// Carpeta de la app en la raíz de la biblioteca (ND11).
    static let appFolderName = "PocketGB"

    /// Límite por consola: 8 MiB en Game Boy y 32 MiB en Game Boy Advance.
    static func romLimit(for console: Console) -> Int {
        console == .gameBoyAdvance ? GBACoreBridge.maxROMBytes : maxROMBytes
    }

    private static let keys: [URLResourceKey] = [
        .isRegularFileKey, .isDirectoryKey, .isSymbolicLinkKey, .fileSizeKey,
        .contentModificationDateKey, .attributeModificationDateKey,
        .isUbiquitousItemKey, .ubiquitousItemDownloadingStatusKey,
        .ubiquitousItemIsDownloadingKey,
    ]

    /// Resultado de un escaneo: los juegos y si se alcanzó el tope de `maxEntries`.
    struct ScanResult: Sendable {
        var entries: [RomEntry]
        var limitReached: Bool
        /// N5iA-4: la carpeta se leyó entera (accesible, sin carpetas ilegibles ni ROMs ilegibles y sin tope).
        /// Solo entonces se purgan las copias de portadas que ya no se usan.
        var complete = false
    }

    /// - Parameter progress: se llama con (procesados, total) al avanzar.
    static func scan(folder: URL, progress: (Int, Int) -> Void = { _, _ in }) -> [RomEntry] {
        scanResult(folder: folder, progress: progress).entries
    }

    static func scanResult(folder: URL, limit: Int = maxEntries, visitLimit: Int = maxVisitedItems,
                           progress: (Int, Int) -> Void = { _, _ in }) -> ScanResult {
        let found = listing(in: folder, limit: limit, visitLimit: visitLimit)
        var entries: [RomEntry] = []
        entries.reserveCapacity(found.candidates.count)
        for (i, candidate) in found.candidates.enumerated() {
            var e = entry(for: candidate.url, relativePath: candidate.relative, placeholder: candidate.placeholder)
            if let cover = candidate.cover {
                e.coverURL = cover.url
                e.coverStamp = SidecarCover.stamp(cover)
            }
            entries.append(e)
            progress(i + 1, found.candidates.count)
        }
        entries.sort { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
        let complete = found.rootReadable && found.unreadableFolders == 0 && !found.limitReached
            && !entries.contains { $0.problem == .unreadable }
        return ScanResult(entries: entries, limitReached: found.limitReached, complete: complete)
    }

    struct Candidate: Equatable, Sendable {
        /// URL real del ROM (sin el `.icloud` de un placeholder antiguo).
        let url: URL
        let relative: String
        /// Era un placeholder `.Nombre.gb.icloud` (iCloud sin descargar).
        let placeholder: Bool
        /// N5: imagen de portada junto al ROM, vista solo en el listado.
        var cover: SidecarCover.Sibling? = nil
    }

    struct Listing: Sendable {
        var candidates: [Candidate] = []
        var limitReached = false
        /// Elementos recorridos (para el tope `maxVisitedItems`).
        var visited = 0
        /// N5iA-4: la carpeta raíz se pudo listar; carpetas que no.
        var rootReadable = false
        var unreadableFolders = 0
    }

    /// Archivos candidatos: la carpeta y sus subcarpetas hasta `maxFolderDepth` niveles.
    static func romFiles(in folder: URL) -> [Candidate] {
        listing(in: folder).candidates
    }

    static func listing(in folder: URL, limit: Int = maxEntries, visitLimit: Int = maxVisitedItems) -> Listing {
        var listing = Listing()
        walk(folder, folders: [], limit: limit, visitLimit: visitLimit, into: &listing)
        return listing
    }

    /// ¿Se salta esta carpeta? `atRoot`: es hija directa de la carpeta de la biblioteca.
    static func isReservedFolder(_ name: String, atRoot: Bool) -> Bool {
        if name.hasPrefix(".") || name.hasPrefix("_") { return true }
        // Sin distinguir mayúsculas: iCloud Drive y APFS no las distinguen.
        return atRoot && name.compare(appFolderName, options: .caseInsensitive) == .orderedSame
    }

    private static func walk(_ directory: URL, folders: [String], limit: Int, visitLimit: Int,
                             into listing: inout Listing) {
        guard !listing.limitReached else { return }
        guard let items = try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: keys) else {
            listing.unreadableFolders += 1
            return
        }
        if folders.isEmpty { listing.rootReadable = true }
        // Orden estable: con el tope, siempre quedan fuera los mismos.
        let sorted = items.sorted { $0.lastPathComponent.compare($1.lastPathComponent, options: .literal) == .orderedAscending }
        let prefix = folders.isEmpty ? "" : folders.joined(separator: "/") + "/"
        // N5: ROMs e imágenes de esta carpeta, para la portada junto al ROM (sin leer ninguna imagen).
        var here: [Int] = []
        var images: [SidecarCover.Sibling] = []
        defer { attachCovers(&listing, indices: here, images: images) }
        for item in sorted {
            listing.visited += 1
            guard listing.visited <= visitLimit else {
                listing.limitReached = true
                return
            }
            let values = try? item.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
            if values?.isDirectory == true && values?.isSymbolicLink != true {
                let name = item.lastPathComponent
                guard !isReservedFolder(name, atRoot: folders.isEmpty), folders.count < maxFolderDepth else { continue }
                walk(item, folders: folders + [name], limit: limit, visitLimit: visitLimit, into: &listing)
                if listing.limitReached { return }
            } else if values?.isDirectory != true, let c = candidate(item, prefix: prefix) {
                guard listing.candidates.count < limit else {
                    listing.limitReached = true
                    return
                }
                here.append(listing.candidates.count)
                listing.candidates.append(c)
            } else if values?.isDirectory != true, let image = imageSibling(item) {
                images.append(image)
            }
        }
    }

    /// Una imagen candidata a portada (por la extensión; el contenido se valida al verse). Incluye el
    /// placeholder antiguo de iCloud (`.Nombre.png.icloud`) sin descargarlo.
    private static func imageSibling(_ url: URL) -> SidecarCover.Sibling? {
        var name = url.lastPathComponent
        var real = url
        if name.hasPrefix("."), name.hasSuffix(".icloud") {
            name = String(name.dropFirst().dropLast(".icloud".count))
            real = url.deletingLastPathComponent().appendingPathComponent(name)
        } else if name.hasPrefix(".") {
            return nil
        }
        guard SidecarCover.extensions.contains((name as NSString).pathExtension.lowercased()) else { return nil }
        let values = try? url.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey])
        let isPlaceholder = real != url
        return SidecarCover.Sibling(name: name, url: real, size: isPlaceholder ? nil : values?.fileSize,
                                    modified: values?.contentModificationDate)
    }

    private static func attachCovers(_ listing: inout Listing, indices: [Int], images: [SidecarCover.Sibling]) {
        guard !images.isEmpty else { return }
        for i in indices {
            let name = listing.candidates[i].url.lastPathComponent
            listing.candidates[i].cover = SidecarCover.find(romName: name, siblings: images, romsInFolder: indices.count)
        }
    }

    private static func candidate(_ url: URL, prefix: String) -> Candidate? {
        var name = url.lastPathComponent
        var real = url
        var placeholder = false
        // Placeholder clásico de iCloud: ".Nombre.gb.icloud".
        if name.hasPrefix("."), name.hasSuffix(".icloud") {
            name = String(name.dropFirst().dropLast(".icloud".count))
            real = url.deletingLastPathComponent().appendingPathComponent(name)
            placeholder = true
        } else if name.hasPrefix(".") {
            return nil
        }
        let ext = (name as NSString).pathExtension.lowercased()
        guard extensions.contains(ext) else { return nil }
        return Candidate(url: real, relative: prefix + name, placeholder: placeholder)
    }

    private static func entry(for url: URL, relativePath: String, placeholder: Bool) -> RomEntry {
        let fileName = url.lastPathComponent
        let fallbackTitle = (fileName as NSString).deletingPathExtension
        let isColorByName = url.pathExtension.lowercased() == "gbc"
        let console = Console(fileName: fileName)
        let isGBA = console == .gameBoyAdvance
        let values = try? url.resourceValues(forKeys: Set(keys))
        let mirrorDate = SaveStore.modificationDate(SaveMirror(romURL: url).url)

        func make(title: String?, isColor: Bool?, size: Int, checksumOK: Bool,
                  cloud: RomEntry.CloudState, problem: RomEntry.Problem?) -> RomEntry {
            let shown = (title?.isEmpty ?? true) ? fallbackTitle : (title ?? fallbackTitle)
            return RomEntry(id: relativePath, url: url, fileName: fileName, title: shown,
                            isColor: isColor ?? isColorByName, sizeBytes: size, headerChecksumOK: checksumOK,
                            cloud: cloud, problem: problem, mirrorSaveDate: mirrorDate,
                            modificationDate: values?.contentModificationDate,
                            attributeModificationDate: values?.attributeModificationDate)
        }

        // Sin descargar: se muestra con el nombre del archivo y el estado de nube.
        let status = values?.ubiquitousItemDownloadingStatus
        // `.downloaded` tiene datos locales (quizá no la última versión): es jugable (auditoría D2, H11).
        if placeholder || (values?.isUbiquitousItem == true && status == .notDownloaded) {
            let downloading = values?.ubiquitousItemIsDownloading == true
            return make(title: nil, isColor: nil, size: 0, checksumOK: true,
                        cloud: downloading ? .downloading : .notDownloaded, problem: nil)
        }
        let size = values?.fileSize ?? 0
        if size > romLimit(for: console) {
            return make(title: nil, isColor: nil, size: size, checksumOK: true, cloud: .current,
                        problem: isGBA ? .tooLargeGBA : .tooLarge)
        }
        guard let head = try? readHeader(url) else {
            return make(title: nil, isColor: nil, size: size, checksumOK: true, cloud: .current, problem: .unreadable)
        }
        guard let info = isGBA ? RomHeader.parseGBA(head) : RomHeader.parse(head) else {
            return make(title: nil, isColor: nil, size: size, checksumOK: true, cloud: .current,
                        problem: isGBA ? .invalidHeaderGBA : .invalidHeader)
        }
        return make(title: info.title, isColor: info.isColor, size: size, checksumOK: info.checksumOK,
                    cloud: .current, problem: nil)
    }

    /// Lee solo la cabecera (0x150 bytes) con lectura coordinada; no modifica el archivo.
    static func readHeader(_ url: URL) throws -> Data {
        try coordinatedRead(url) { u in
            let handle = try FileHandle(forReadingFrom: u)
            defer { try? handle.close() }
            return try handle.read(upToCount: RomHeader.minimumBytes) ?? Data()
        }
    }

    /// Lee el ROM completo con lectura coordinada (al abrir un juego). Rechaza más de
    /// `limit` (8 MiB por defecto; 32 MiB en GBA).
    static func readROM(_ url: URL, limit: Int = maxROMBytes) throws -> Data {
        try coordinatedRead(url) { u in
            // Lectura acotada: aunque el archivo crezca tras mirar su tamaño, nunca se
            // cargan más de `limit` + 1 bytes en memoria (auditoría D2, H13).
            let handle = try FileHandle(forReadingFrom: u)
            defer { try? handle.close() }
            let data = try handle.read(upToCount: limit + 1) ?? Data()
            guard data.count <= limit else { throw CocoaError(.fileReadTooLarge) }
            return data
        }
    }

    private static func coordinatedRead(_ url: URL, _ body: (URL) throws -> Data) throws -> Data {
        var result: Result<Data, Error> = .failure(CocoaError(.fileReadUnknown))
        var coordError: NSError?
        NSFileCoordinator(filePresenter: nil).coordinate(readingItemAt: url, options: [], error: &coordError) { u in
            result = Result { try body(u) }
        }
        if let coordError { throw coordError }
        return try result.get()
    }
}

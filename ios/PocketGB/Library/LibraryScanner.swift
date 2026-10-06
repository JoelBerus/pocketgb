import Foundation

/// Enumera los ROMs de la carpeta de la biblioteca (docs/04 §Biblioteca, D-README §4):
/// solo `.gb`/`.gbc`/`.gba`, en la carpeta y en sus subcarpetas directas (profundidad 1),
/// sin abrir ni ejecutar los ROMs y sin modificarlos. Se llama fuera del hilo principal.
enum LibraryScanner {
    static let maxROMBytes = 8 * 1024 * 1024
    static let extensions: Set<String> = ["gb", "gbc", "gba"]

    /// Límite por consola: 8 MiB en Game Boy y 32 MiB en Game Boy Advance.
    static func romLimit(for console: Console) -> Int {
        console == .gameBoyAdvance ? GBACoreBridge.maxROMBytes : maxROMBytes
    }

    private static let keys: [URLResourceKey] = [
        .isRegularFileKey, .isDirectoryKey, .fileSizeKey,
        .isUbiquitousItemKey, .ubiquitousItemDownloadingStatusKey,
        .ubiquitousItemIsDownloadingKey,
    ]

    /// - Parameter progress: se llama con (procesados, total) al avanzar.
    static func scan(folder: URL, progress: (Int, Int) -> Void = { _, _ in }) -> [RomEntry] {
        let candidates = romFiles(in: folder)
        var entries: [RomEntry] = []
        entries.reserveCapacity(candidates.count)
        for (i, candidate) in candidates.enumerated() {
            entries.append(entry(for: candidate.url, relativePath: candidate.relative,
                                 placeholder: candidate.placeholder))
            progress(i + 1, candidates.count)
        }
        return entries.sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
    }

    struct Candidate: Equatable {
        /// URL real del ROM (sin el `.icloud` de un placeholder antiguo).
        let url: URL
        let relative: String
        /// Era un placeholder `.Nombre.gb.icloud` (iCloud sin descargar).
        let placeholder: Bool
    }

    /// Archivos candidatos: la carpeta y sus subcarpetas directas.
    static func romFiles(in folder: URL) -> [Candidate] {
        var result: [Candidate] = []
        let fm = FileManager.default
        guard let top = try? fm.contentsOfDirectory(at: folder, includingPropertiesForKeys: keys) else {
            return []
        }
        for item in top {
            let values = try? item.resourceValues(forKeys: [.isDirectoryKey])
            if values?.isDirectory == true {
                guard !item.lastPathComponent.hasPrefix(".") else { continue }
                let inner = (try? fm.contentsOfDirectory(at: item, includingPropertiesForKeys: keys)) ?? []
                for file in inner {
                    if let c = candidate(file, prefix: item.lastPathComponent + "/") { result.append(c) }
                }
            } else if let c = candidate(item, prefix: "") {
                result.append(c)
            }
        }
        return result
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
                            cloud: cloud, problem: problem, mirrorSaveDate: mirrorDate)
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

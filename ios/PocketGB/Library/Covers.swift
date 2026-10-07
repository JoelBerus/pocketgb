import CoreGraphics
import CryptoKit
import Foundation
import ImageIO
import UniformTypeIdentifiers

/// N5 · portadas (paridad con Android, `library/artwork/Covers.kt`). Lo de este archivo es puro (sin UI)
/// y se prueba en los tests unitarios.
///
/// Fuentes de una portada:
/// - `.imported`: imagen importada en la app (Fotos o Archivos), guardada reducida (≤ 1024 px, PNG), por huella;
/// - `.sidecar`: imagen junto al ROM en la carpeta (`<nombre del ROM>.png|jpg|jpeg|webp`, o `portada.*` /
///   `cover.*` si la carpeta tiene un solo juego);
/// - `.capture`: captura del juego (la fijada con «Usar como portada» desde la pausa o, si no, el último
///   fotograma al cerrar, K9), por huella;
/// - `.generated`: la portada generada (color y glifo). Siempre está y es el último recurso.
enum CoverKind: String, Sendable, CaseIterable {
    case imported, sidecar, capture, generated

    var title: String {
        switch self {
        case .imported: "Imagen importada"
        case .sidecar: "Imagen de la carpeta"
        case .capture: "Captura del juego"
        case .generated: "Generada"
        }
    }
}

/// Elección por juego (centro de ajustes › Portada).
enum CoverChoice: String, Codable, Sendable, CaseIterable, Identifiable {
    case auto, image, capture, generated

    var id: String { rawValue }

    var title: String {
        switch self {
        case .auto: "Automática"
        case .image: "Imagen"
        case .capture: "Captura"
        case .generated: "Generada"
        }
    }
}

/// Elección global (Ajustes › Biblioteca › Portadas): qué gana en «Automática» cuando hay imagen y captura.
enum CoverPreference: String, Codable, Sendable, CaseIterable, Identifiable {
    case images, captures

    var id: String { rawValue }

    var title: String {
        switch self {
        case .images: "Preferir imágenes"
        case .captures: "Preferir capturas"
        }
    }
}

/// Qué fuentes tiene un juego ahora mismo (sin decodificar nada).
struct CoverAvailability: Equatable, Sendable {
    var imported = false
    var sidecar = false
    var capture = false

    var hasImage: Bool { imported || sidecar }
}

enum CoverResolver {
    /// Fuentes a probar, en orden; la última es siempre `.generated`. Quien dibuja prueba cada una y, si una
    /// falla al leerse o decodificarse, pasa a la siguiente: ante cualquier fallo se acaba en la generada.
    /// - Imagen: la importada gana a la de la carpeta (es un gesto explícito en la app).
    /// - Una elección explícita (Imagen o Captura) sin esa fuente da la generada: nunca muestra otra fuente
    ///   que el usuario no eligió.
    /// - Automática sigue la preferencia global.
    static func candidates(_ choice: CoverChoice, _ preference: CoverPreference,
                           _ available: CoverAvailability) -> [CoverKind] {
        var images: [CoverKind] = []
        if available.imported { images.append(.imported) }
        if available.sidecar { images.append(.sidecar) }
        let captures: [CoverKind] = available.capture ? [.capture] : []
        let ordered: [CoverKind]
        switch choice {
        case .generated: ordered = []
        case .image: ordered = images
        case .capture: ordered = captures
        case .auto: ordered = preference == .images ? images + captures : captures + images
        }
        return ordered + [.generated]
    }

    /// La fuente que se verá si todas las candidatas se leen bien.
    static func resolve(_ choice: CoverChoice, _ preference: CoverPreference,
                        _ available: CoverAvailability) -> CoverKind {
        candidates(choice, preference, available)[0]
    }
}

/// Imagen junto al ROM (N5): `<nombre del ROM>.<ext>` o, si la carpeta tiene un solo juego, `portada.*`/`cover.*`.
enum SidecarCover {
    /// Extensiones por prioridad. El contenido se valida después por sus bytes: la extensión no basta.
    static let extensions = ["png", "jpg", "jpeg", "webp"]
    private static let folderNames = ["portada", "cover"]

    /// Un archivo de la misma carpeta, tal como lo da el listado (sin leerlo).
    struct Sibling: Equatable, Sendable {
        /// Nombre visible (sin el `.…icloud` de un placeholder antiguo de iCloud).
        let name: String
        let url: URL
        let size: Int?
        let modified: Date?
    }

    /// Busca la portada de `romName` entre `siblings` (la misma carpeta), sin distinguir mayúsculas.
    /// `romsInFolder` es cuántos ROMs hay en esa carpeta: `portada.*`/`cover.*` solo valen con uno.
    /// Ignora ocultos (`.`) y archivos vacíos. Desempate estable: extensión por prioridad y luego nombre.
    static func find(romName: String, siblings: [Sibling], romsInFolder: Int) -> Sibling? {
        let base = (romName as NSString).deletingPathExtension
        let files = siblings.filter { !$0.name.hasPrefix(".") && $0.size != 0 }
        func pick(_ stem: String) -> Sibling? {
            for ext in extensions {
                let target = "\(stem).\(ext)"
                let matches = files.filter { $0.name.compare(target, options: .caseInsensitive) == .orderedSame }
                if let best = matches.min(by: { $0.name < $1.name }) { return best }
            }
            return nil
        }
        if let own = pick(base) { return own }
        guard romsInFolder == 1 else { return nil }
        for name in folderNames { if let found = pick(name) { return found } }
        return nil
    }

    /// Sello de una imagen de la carpeta: si cambia (otra imagen, editada), se vuelve a leer.
    static func stamp(_ sibling: Sibling) -> String {
        "\(sibling.name)|\(sibling.size ?? -1)|\(Int64((sibling.modified?.timeIntervalSince1970 ?? 0) * 1000))"
    }

    /// Clave de la copia en caché: SHA-256 (hex) de ruta + sello.
    static func cacheKey(path: String, stamp: String) -> String {
        SHA256.hash(data: Data("\(path)|\(stamp)".utf8)).map { String(format: "%02x", $0) }.joined()
    }
}

/// Formatos reconocidos por sus primeros bytes (nunca por la extensión). HEIC solo se acepta al importar
/// (diferencia con Android: Fotos guarda así las fotos del iPhone); la carpeta, como Android.
enum CoverFormat: Sendable {
    case png, jpeg, webp, heic

    /// Formatos de la imagen de la carpeta (y de cualquier lectura que no sea importar): como Android.
    static let folder: Set<CoverFormat> = [.png, .jpeg, .webp]
    /// Al importar (Fotos o Archivos) se acepta también HEIC (N5iA-1: solo aquí).
    static let imported: Set<CoverFormat> = [.png, .jpeg, .webp, .heic]
    /// Copias reducidas que guarda la app: siempre PNG.
    static let stored: Set<CoverFormat> = [.png]
}

/// Reglas de la imagen como entrada no confiable (N5): tope de bytes, formato por firma, dimensiones
/// máximas por la cabecera (sin decodificar) y miniatura acotada (`kCGImageSourceThumbnailMaxPixelSize`).
enum CoverImageRules {
    /// Tope de lo que se lee de una imagen (importada o de la carpeta).
    static let maxBytes = 15 * 1024 * 1024
    /// Lado máximo de la copia guardada.
    static let targetSide = 1024
    /// Lado máximo que se acepta en la cabecera.
    static let maxSide = 16_384
    /// Píxeles máximos de la cabecera (100 MP).
    static let maxPixels = 100_000_000

    static func sniff(_ data: Data) -> CoverFormat? {
        let b = [UInt8](data.prefix(12))
        func at(_ i: Int) -> Int { i < b.count ? Int(b[i]) : -1 }
        if b.count >= 8, b[0..<8] == [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A] { return .png }
        if b.count >= 3, at(0) == 0xFF, at(1) == 0xD8, at(2) == 0xFF { return .jpeg }
        if b.count >= 12, b[0..<4] == Array("RIFF".utf8)[...], b[8..<12] == Array("WEBP".utf8)[...] { return .webp }
        if b.count >= 12, b[4..<8] == Array("ftyp".utf8)[...] {
            let brand = String(decoding: b[8..<12], as: UTF8.self)
            if ["heic", "heix", "hevc", "heim", "heis", "mif1", "msf1"].contains(brand) { return .heic }
        }
        return nil
    }

    static func dimensionsOK(width: Int, height: Int) -> Bool {
        (1...maxSide).contains(width) && (1...maxSide).contains(height) && width * height <= maxPixels
    }
}

/// N5 · decodificación con ImageIO de una imagen no confiable:
/// 1. tope de `maxBytes` y formato por la firma;
/// 2. dimensiones de la cabecera con `CGImageSourceCopyPropertiesAtIndex` (no decodifica nada);
/// 3. miniatura con `kCGImageSourceThumbnailMaxPixelSize` (ImageIO submuestrea al decodificar: nunca
///    reserva el mapa de bits entero) y copia PNG (sin pérdida; conserva la transparencia).
/// Cualquier fallo devuelve `nil`: quien llama pasa a la siguiente fuente o a la portada generada.
enum CoverDecoder {
    /// `formats`: firmas aceptadas (por defecto las de la carpeta; HEIC solo al importar, N5iA-1).
    static func decode(_ data: Data, formats: Set<CoverFormat> = CoverFormat.folder,
                       maxSide: Int = CoverImageRules.targetSide) -> CGImage? {
        guard !data.isEmpty, data.count <= CoverImageRules.maxBytes,
              let format = CoverImageRules.sniff(data), formats.contains(format) else { return nil }
        let options = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithData(data as CFData, options),
              CGImageSourceGetCount(source) > 0,
              let props = CGImageSourceCopyPropertiesAtIndex(source, 0, options) as? [CFString: Any],
              let width = (props[kCGImagePropertyPixelWidth] as? NSNumber)?.intValue,
              let height = (props[kCGImagePropertyPixelHeight] as? NSNumber)?.intValue,
              CoverImageRules.dimensionsOK(width: width, height: height) else { return nil }
        let thumb: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: maxSide,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, thumb as CFDictionary),
              image.width > 0, image.height > 0, max(image.width, image.height) <= maxSide else { return nil }
        return image
    }

    static func pngData(_ image: CGImage) -> Data? {
        let out = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(out as CFMutableData, UTType.png.identifier as CFString,
                                                          1, nil) else { return nil }
        CGImageDestinationAddImage(dest, image, nil)
        guard CGImageDestinationFinalize(dest) else { return nil }
        return out as Data
    }

    /// Copia reducida lista para guardar, o `nil` si la imagen no vale.
    static func reduce(_ data: Data, formats: Set<CoverFormat> = CoverFormat.folder) -> Data? {
        decode(data, formats: formats).flatMap(pngData)
    }

    /// Lee como mucho `maxBytes` de `url` (lectura coordinada: una imagen de iCloud sin descargar se
    /// descarga, solo esa y solo al verse). `nil` si es mayor (no se sigue leyendo) o si falla.
    static func readLimited(_ url: URL, limit: Int = CoverImageRules.maxBytes) -> Data? {
        var result: Data?
        var coordError: NSError?
        NSFileCoordinator(filePresenter: nil).coordinate(readingItemAt: url, options: [], error: &coordError) { u in
            guard let handle = try? FileHandle(forReadingFrom: u) else { return }
            defer { try? handle.close() }
            guard let data = try? handle.read(upToCount: limit + 1), data.count <= limit else { return }
            result = data
        }
        return coordError == nil ? result : nil
    }
}

/// N5 · elección por huella y preferencia global, por dispositivo (ND12), en un archivo propio
/// (`Application Support/Covers/settings.json`) para no subir el formato de las preferencias de la biblioteca.
struct CoverSettings: Codable, Equatable, Sendable {
    static let formatVersion = 1

    var formatVersion = CoverSettings.formatVersion
    var preference: CoverPreference = .images
    var choices: [String: CoverChoice] = [:]

    func choice(for fingerprint: String?) -> CoverChoice {
        fingerprint.flatMap { choices[$0] } ?? .auto
    }

    func with(_ choice: CoverChoice, for fingerprint: String) -> CoverSettings {
        var copy = self
        copy.choices[fingerprint] = choice == .auto ? nil : choice
        return copy
    }

    init() {}

    /// Tolerante: campos ausentes o valores desconocidos (versión futura) toman su valor por defecto.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        formatVersion = try c.decode(Int.self, forKey: .formatVersion)
        preference = (try? c.decode(CoverPreference.self, forKey: .preference)) ?? .images
        let raw = (try? c.decode([String: String].self, forKey: .choices)) ?? [:]
        choices = raw.compactMapValues(CoverChoice.init(rawValue:))
    }
}

/// Persistencia de `CoverSettings`: escritura atómica; un archivo ilegible se aparta como
/// `settings.corrupt-<fecha>.json` y se empieza de cero (solo son preferencias de presentación: nunca toca
/// partidas); uno de una versión futura se lee con tolerancia y no se sobrescribe.
struct CoverSettingsFile {
    let url: URL?
    private(set) var writeProtected = false

    init(url: URL?) {
        self.url = url
    }

    static func defaultURL() -> URL? {
        GameArtworkStore.supportDirectory("Covers")?.appendingPathComponent("settings.json")
    }

    /// Huellas válidas: las del núcleo (32 hex) o claves de demostración en minúsculas.
    static func isValidKey(_ key: String) -> Bool {
        !key.isEmpty && key.count <= 64 && key.allSatisfy { $0.isASCII && ($0.isLowercase || $0.isNumber || $0 == "-") }
    }

    mutating func load(now: Date = Date()) -> CoverSettings {
        guard let url, let data = try? Data(contentsOf: url) else { return CoverSettings() }
        guard var decoded = try? JSONDecoder().decode(CoverSettings.self, from: data) else {
            let stamp = Int(now.timeIntervalSince1970)
            let aside = url.deletingLastPathComponent().appendingPathComponent("settings.corrupt-\(stamp).json")
            try? FileManager.default.moveItem(at: url, to: aside)
            return CoverSettings()
        }
        if decoded.formatVersion > CoverSettings.formatVersion { writeProtected = true }
        decoded.choices = decoded.choices.filter { Self.isValidKey($0.key) }
        return decoded
    }

    /// `false` si no se pudo escribir (o está protegido ante una versión futura).
    @discardableResult
    func save(_ settings: CoverSettings) -> Bool {
        guard let url else { return true }
        guard !writeProtected else { return false }
        do {
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.sortedKeys]
            try encoder.encode(settings).write(to: url, options: .atomic)
            return true
        } catch {
            return false
        }
    }
}

import Foundation

/// Un archivo `.gb`/`.gbc`/`.gba` de la carpeta de la biblioteca (SPEC §4, docs/04 §Biblioteca).
/// Es un valor inmutable que el escáner crea fuera del hilo principal.
struct RomEntry: Identifiable, Hashable, Sendable {
    enum CloudState: Hashable, Sendable {
        /// Descargado y listo para abrir.
        case current
        /// Solo en iCloud: hay que descargarlo antes de jugar.
        case notDownloaded
        /// Descarga en curso.
        case downloading
    }

    enum Problem: Hashable, Sendable {
        /// Supera los 8 MiB: no es un ROM de Game Boy.
        case tooLarge
        /// Demasiado corto o sin cabecera de Game Boy.
        case invalidHeader
        /// `.gba` de más de 32 MiB.
        case tooLargeGBA
        /// `.gba` demasiado corto o sin el byte fijo 0x96 de la cabecera.
        case invalidHeaderGBA
        /// No se pudo leer (permisos, error de coordinación).
        case unreadable

        var message: String {
            switch self {
            case .tooLarge: "Supera los 8 MiB, así que no es un ROM de Game Boy."
            case .invalidHeader: "No tiene una cabecera de Game Boy válida."
            case .tooLargeGBA: "Supera los 32 MiB, así que no es un ROM de Game Boy Advance."
            case .invalidHeaderGBA: "No tiene una cabecera de Game Boy Advance válida."
            case .unreadable: "No se pudo leer el archivo."
            }
        }
    }

    /// Ruta relativa a la carpeta (p. ej. "Pokemon Red.gb" o "Pokémon/1ª generación/Pokemon Red.gb").
    let id: String
    let url: URL
    let fileName: String
    /// Título de la cabecera, o el nombre del archivo si no se pudo leer.
    let title: String
    let isColor: Bool
    let sizeBytes: Int
    let headerChecksumOK: Bool
    var cloud: CloudState
    let problem: Problem?
    /// Fecha del `.sav` junto al ROM, si existe.
    let mirrorSaveDate: Date?
    /// Apareció en este escaneo por primera vez.
    var isNew = false
    /// Fecha de modificación del archivo: con el tamaño, valida la caché de huellas (N1a).
    var modificationDate: Date?
    /// Huella del ROM (los 32 hex del SHA-256 del núcleo) si ya se conoce: de la caché, del
    /// cálculo en segundo plano o de la última apertura. Partidas, estados, portadas y
    /// metadatos van por ella; la ruta (`id`) solo identifica el archivo.
    var fingerprint: String?
    /// Otras rutas de la carpeta con la misma huella (duplicados, N1a). Comparten metadatos,
    /// partida, estados y portada.
    var duplicatePaths: [String] = []

    var isDuplicate: Bool { !duplicatePaths.isEmpty }

    /// Carpetas desde la raíz hasta el archivo, sin el archivo (N1b). `[]` = en la raíz (sin
    /// categoría); el primer nivel es la categoría y los siguientes, subcategorías.
    var folderPath: [String] { Self.folders(of: id) }

    /// Carpeta relativa en la que está ("" si está en la raíz).
    var subfolder: String { folderPath.joined(separator: "/") }

    /// «Pokémon › 2ª generación · archivo.gb», o solo el archivo en la raíz.
    var locationText: String {
        folderPath.isEmpty ? fileName : "\(folderPath.joined(separator: " › ")) · \(fileName)"
    }

    static func folders(of path: String) -> [String] {
        Array(path.split(separator: "/").dropLast().map(String.init))
    }

    /// Una ruta relativa para leer: «Pokémon › 2ª generación › archivo.gb».
    static func displayPath(_ path: String) -> String {
        path.split(separator: "/").joined(separator: " › ")
    }

    var isPlayable: Bool { problem == nil && cloud == .current }

    /// Por la extensión: `.gba` es Game Boy Advance.
    var console: Console { Console(fileName: fileName) }

    var badge: ConsoleBadge {
        console == .gameBoyAdvance ? .gba : (isColor ? .gbc : .gb)
    }
}

/// Chip de consola de la biblioteca (SPEC §8): texto, nunca solo color.
enum ConsoleBadge: Sendable {
    case gb, gbc, gba

    var label: String {
        switch self {
        case .gb: "GB"
        case .gbc: "GBC"
        case .gba: "GBA"
        }
    }

    var name: String {
        switch self {
        case .gb: "Game Boy"
        case .gbc: "Game Boy Color"
        case .gba: "Game Boy Advance"
        }
    }
}

/// Lectura de la cabecera del cartucho (docs/03 §Cabecera), solo para mostrar.
/// La validación de verdad la hace el núcleo al cargar el ROM.
enum RomHeader {
    static let minimumBytes = 0x150

    struct Info: Equatable, Sendable {
        let title: String
        let isColor: Bool
        let checksumOK: Bool
    }

    static func parse(_ data: Data) -> Info? {
        guard data.count >= minimumBytes else { return nil }
        let bytes = [UInt8](data.prefix(minimumBytes))
        let cgbFlag = bytes[0x143]
        let isColor = cgbFlag & 0x80 != 0
        // Título: 16 bytes, o 15 si 0x143 es el flag CGB; se corta en el primer 0.
        let titleEnd = isColor ? 0x143 : 0x144
        let raw = bytes[0x134..<titleEnd].prefix { $0 != 0 }
        guard !raw.isEmpty else { return Info(title: "", isColor: isColor, checksumOK: checksumOK(bytes)) }
        let printable = raw.map { $0 >= 0x20 && $0 < 0x7F ? Character(UnicodeScalar($0)) : "?" }
        let title = String(printable).trimmingCharacters(in: .whitespaces)
        return Info(title: title, isColor: isColor, checksumOK: checksumOK(bytes))
    }

    /// Cabecera de Game Boy Advance (GBATEK): título en 0xA0..0xAB, byte fijo 0x96 en 0xB2
    /// y complemento en 0xBD. Sin el byte fijo no se considera un ROM de GBA.
    static func parseGBA(_ data: Data) -> Info? {
        guard data.count >= gbaMinimumBytes else { return nil }
        let bytes = [UInt8](data.prefix(gbaMinimumBytes))
        guard bytes[0xB2] == 0x96 else { return nil }
        var sum: UInt8 = 0
        for i in 0xA0...0xBC { sum = sum &- bytes[i] }
        let checksumOK = sum &- 0x19 == bytes[0xBD]
        let raw = bytes[0xA0..<0xAC].prefix { $0 != 0 }
        let printable = raw.map { $0 >= 0x20 && $0 < 0x7F ? Character(UnicodeScalar($0)) : "?" }
        let title = String(printable).trimmingCharacters(in: .whitespaces)
        return Info(title: title, isColor: false, checksumOK: checksumOK)
    }

    static let gbaMinimumBytes = 0xC0

    private static func checksumOK(_ bytes: [UInt8]) -> Bool {
        var x: UInt8 = 0
        for i in 0x134...0x14C { x = x &- bytes[i] &- 1 }
        return x == bytes[0x14D]
    }
}

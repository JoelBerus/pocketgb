import Foundation

/// Un archivo `.gb`/`.gbc` de la carpeta de la biblioteca (SPEC §4, docs/04 §Biblioteca).
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
        /// No se pudo leer (permisos, error de coordinación).
        case unreadable

        var message: String {
            switch self {
            case .tooLarge: "Supera los 8 MiB, así que no es un ROM de Game Boy."
            case .invalidHeader: "No tiene una cabecera de Game Boy válida."
            case .unreadable: "No se pudo leer el archivo."
            }
        }
    }

    /// Ruta relativa a la carpeta (p. ej. "Pokemon Red.gb" o "Rojo/Pokemon Red.gb").
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

    /// Carpeta relativa en la que está ("" si está en la raíz).
    var subfolder: String {
        let parts = id.split(separator: "/")
        return parts.count > 1 ? parts.dropLast().joined(separator: "/") : ""
    }

    var isPlayable: Bool { problem == nil && cloud == .current }
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

    private static func checksumOK(_ bytes: [UInt8]) -> Bool {
        var x: UInt8 = 0
        for i in 0x134...0x14C { x = x &- bytes[i] &- 1 }
        return x == bytes[0x14D]
    }
}

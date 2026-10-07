import CryptoKit
import Foundation

/// BIOS opcional de Game Boy Advance: el volcado propio del usuario, `gba_bios.bin` en la
/// raíz de la carpeta de la biblioteca (nunca en el repo ni en la app, regla dura 1).
/// Solo se usa si su SHA-256 es el de la BIOS oficial; si no, el núcleo emula la BIOS (HLE).
enum BIOSFile {
    static let fileName = "gba_bios.bin"

    enum Status: Equatable, Sendable {
        /// No hay `gba_bios.bin`: BIOS emulada.
        case absent
        /// Volcado oficial verificado: se usa.
        case valid
        /// Hay archivo pero no es la BIOS oficial (tamaño o SHA-256): se ignora.
        case invalid

        var settingsText: String {
            switch self {
            case .absent: "Emulada (sin \(BIOSFile.fileName) en la carpeta de juegos)"
            case .valid: "BIOS oficial verificada"
            case .invalid: "\(BIOSFile.fileName) no es la BIOS oficial: se usa la emulada"
            }
        }
    }

    /// Lee y valida la BIOS de `folder`. Llamar fuera del hilo principal.
    static func read(folder: URL?) -> (data: Data?, status: Status) {
        guard let folder else { return (nil, .absent) }
        let url = folder.appendingPathComponent(fileName)
        guard FileManager.default.fileExists(atPath: url.path) else { return (nil, .absent) }
        guard let data = try? Data(contentsOf: url) else { return (nil, .invalid) }
        return isOfficial(data) ? (data, .valid) : (nil, .invalid)
    }

    static func isOfficial(_ data: Data) -> Bool {
        guard data.count == GBACoreBridge.biosBytes else { return false }
        let digest = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        return digest == GBACoreBridge.knownBIOSSHA256
    }
}

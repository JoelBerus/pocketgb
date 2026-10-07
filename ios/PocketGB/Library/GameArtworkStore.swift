import CoreGraphics
import Foundation
import ImageIO
import Observation
import UIKit
import UniformTypeIdentifiers

/// Portadas locales (SPEC §11): el último frame de una sesión válida, guardado en
/// `Application Support/Artwork/<huella>.png` con reemplazo atómico. Nunca junto al ROM
/// y nunca desde la red. Codificar y leer PNG se hace en una cola aparte.
///
/// N5: la misma clase guarda también las capturas fijadas (`ArtworkPinned/`), las imágenes importadas
/// (`Covers/Imported/`) y las copias de las imágenes de la carpeta (`Covers/Folder/`). Con `untrusted`,
/// lo leído se decodifica con `CoverDecoder` (tope, firma, dimensiones y miniatura), nunca con `UIImage(data:)`.
@MainActor @Observable
final class GameArtworkStore {
    private(set) var images: [String: UIImage] = [:]
    /// Claves cuya lectura falló: quien dibuja pasa a la siguiente fuente.
    private(set) var missing: Set<String> = []
    @ObservationIgnored private var loading: Set<String> = []
    /// Claves con archivo en disco (se lista una vez al crear; luego se mantiene al guardar y borrar).
    private(set) var onDisk: Set<String> = []
    private let directory: URL?
    private let untrusted: Bool
    private let queue = DispatchQueue(label: "PocketGB.artwork", qos: .utility)

    /// Con `directory == nil` (tests y capturas DEBUG) solo vive en memoria.
    init(directory: URL?, untrusted: Bool = false) {
        self.directory = directory
        self.untrusted = untrusted
        if let directory {
            let files = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
            onDisk = Set(files.filter { $0.hasSuffix(".png") }.map { String($0.dropLast(4)) })
        }
    }

    static func defaultDirectory() -> URL? {
        supportDirectory("Artwork")
    }

    /// `Application Support/<ruta>` (no se crea hasta escribir).
    nonisolated static func supportDirectory(_ path: String) -> URL? {
        guard let base = try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                                      appropriateFor: nil, create: true) else { return nil }
        return base.appendingPathComponent(path, isDirectory: true)
    }

    func fileURL(for fingerprint: String) -> URL? {
        directory?.appendingPathComponent("\(fingerprint).png")
    }

    func image(for fingerprint: String?) -> UIImage? {
        fingerprint.flatMap { images[$0] }
    }

    /// Hay portada con esta clave (en memoria o en disco) y no falló al leerse.
    func has(_ key: String?) -> Bool {
        guard let key else { return false }
        if images[key] != nil { return true }
        return onDisk.contains(key) && !missing.contains(key)
    }

    /// Claves guardadas (en disco o, sin carpeta, en memoria).
    var keys: Set<String> { onDisk.union(images.keys) }

    /// Carga la portada del disco si aún no está en memoria (desde `.task` de la vista).
    func load(_ fingerprint: String) {
        guard images[fingerprint] == nil, !missing.contains(fingerprint), !loading.contains(fingerprint),
              let url = fileURL(for: fingerprint) else { return }
        loading.insert(fingerprint)
        let untrusted = untrusted
        queue.async {
            let image = Self.readImage(url, untrusted: untrusted)
            Task { @MainActor [weak self] in self?.finishLoad(fingerprint, image: image) }
        }
    }

    nonisolated private static func readImage(_ url: URL, untrusted: Bool) -> UIImage? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        if untrusted {
            // Solo copias propias en PNG (N5iA-1): cualquier otra cosa en la carpeta se ignora.
            return CoverDecoder.decode(data, formats: CoverFormat.stored).map { UIImage(cgImage: $0) }
        }
        return UIImage(data: data)
    }

    private func finishLoad(_ fingerprint: String, image: UIImage?) {
        loading.remove(fingerprint)
        if let image {
            images[fingerprint] = image
        } else {
            missing.insert(fingerprint)
        }
    }

    /// Guarda un frame (RGBA8888, 160×144 o 240×160) como portada. Un frame de un solo color
    /// (pantalla en blanco o negro al arrancar) no es una captura válida y se descarta.
    @discardableResult
    func save(fingerprint: String, pixels: [UInt32]) -> Bool {
        guard ScreenSize(pixelCount: pixels.count) != nil, !Self.isBlank(pixels),
              let cg = Self.makeImage(pixels) else { return false }
        images[fingerprint] = UIImage(cgImage: cg)
        missing.remove(fingerprint)
        guard let url = fileURL(for: fingerprint) else { return true }
        onDisk.insert(fingerprint)
        queue.async {
            guard let image = Self.makeImage(pixels), let png = Self.pngData(image) else { return }
            try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(),
                                                     withIntermediateDirectories: true)
            try? png.write(to: url, options: .atomic)
        }
        return true
    }

    /// N5: guarda una copia ya reducida (PNG de `CoverDecoder.reduce`) con su imagen decodificada.
    /// Escritura atómica en la cola, fuera del hilo principal (N5iA-3); `false` si no se pudo escribir.
    @discardableResult
    func saveEncoded(_ key: String, png: Data, image: UIImage) async -> Bool {
        if let url = fileURL(for: key) {
            let queue = queue
            let ok = await withCheckedContinuation { (done: CheckedContinuation<Bool, Never>) in
                queue.async {
                    do {
                        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(),
                                                                withIntermediateDirectories: true)
                        try png.write(to: url, options: .atomic)
                        done.resume(returning: true)
                    } catch {
                        done.resume(returning: false)
                    }
                }
            }
            guard ok else { return false }
            onDisk.insert(key)
        }
        images[key] = image
        missing.remove(key)
        return true
    }

    /// N5: borra la portada de `key` (si la hay).
    func remove(_ key: String) {
        images[key] = nil
        missing.remove(key)
        onDisk.remove(key)
        guard let url = fileURL(for: key) else { return }
        queue.async { try? FileManager.default.removeItem(at: url) }
    }

    /// N5A-2: borra las copias cuya clave no esté en `keep` (y temporales). Devuelve cuántas.
    @discardableResult
    func retainOnly(_ keep: Set<String>) -> Int {
        let stale = keys.subtracting(keep)
        for key in stale { remove(key) }
        if let directory {
            queue.async {
                let files = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
                for file in files where !file.hasSuffix(".png") || !keep.contains(String(file.dropLast(4))) {
                    try? FileManager.default.removeItem(at: directory.appendingPathComponent(file))
                }
            }
        }
        return stale.count
    }

    /// Borra todas las portadas (Ajustes › Almacenamiento). Se regeneran al jugar.
    func removeAll() {
        images.removeAll()
        missing.removeAll()
        onDisk.removeAll()
        guard let directory else { return }
        queue.async {
            let files = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
            for file in files where file.pathExtension == "png" { try? FileManager.default.removeItem(at: file) }
        }
    }

    var directoryURL: URL? { directory }

    /// Espera a que terminen las escrituras y lecturas pendientes (tests).
    func waitForPendingWork() {
        queue.sync {}
    }

    nonisolated static func isBlank(_ pixels: [UInt32]) -> Bool {
        guard let first = pixels.first else { return true }
        return !pixels.contains { $0 != first }
    }

    /// Los bytes del frame en memoria son R, G, B, A (el mismo formato `rgba8Unorm` del render).
    /// El tamaño (Game Boy o Game Boy Advance) sale del número de píxeles.
    nonisolated static func makeImage(_ pixels: [UInt32]) -> CGImage? {
        guard let screen = ScreenSize(pixelCount: pixels.count) else { return nil }
        let width = screen.width, height = screen.height
        let data = pixels.withUnsafeBufferPointer { Data(buffer: $0) }
        guard let provider = CGDataProvider(data: data as CFData),
              let space = CGColorSpace(name: CGColorSpace.sRGB) else { return nil }
        return CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32,
                       bytesPerRow: width * 4, space: space,
                       bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue),
                       provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent)
    }

    nonisolated static func pngData(_ image: CGImage) -> Data? {
        let out = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(out as CFMutableData, UTType.png.identifier as CFString,
                                                          1, nil) else { return nil }
        CGImageDestinationAddImage(dest, image, nil)
        guard CGImageDestinationFinalize(dest) else { return nil }
        return out as Data
    }

    #if DEBUG
    /// Capturas: una imagen ya decodificada, solo en memoria.
    func applyDemo(key: String, image: UIImage) {
        images[key] = image
    }

    /// Portada de demostración: arte abstracto generado, nunca material de un juego.
    func applyDemo(fingerprint: String) {
        if let cg = Self.makeImage(Self.demoPixels()) {
            images[fingerprint] = UIImage(cgImage: cg)
        }
    }

    /// Portada de demostración con otros píxeles (N3: captura blanca, captura GBA de 240×160).
    func applyDemo(fingerprint: String, pixels: [UInt32]) {
        if let cg = Self.makeImage(pixels) {
            images[fingerprint] = UIImage(cgImage: cg)
        }
    }

    /// N3: una captura casi blanca (el peor caso para leer el título encima): fondo blanco, un
    /// rombo gris muy claro y unas barras grises abajo.
    nonisolated static func demoWhitePixels() -> [UInt32] {
        let width = FrameBuffers.width, height = FrameBuffers.height
        var pixels = [UInt32](repeating: 0xFFFF_FFFF, count: width * height)
        for y in 0..<height {
            for x in 0..<width {
                if abs(x - 80) + abs(y - 56) < 40 { pixels[y * width + x] = 0xFFEE_EEEE }
                if y > 116, y < 124, x > 24, x < 136 { pixels[y * width + x] = 0xFFC8_C8C8 }
                if y > 128, y < 134, x > 44, x < 116 { pixels[y * width + x] = 0xFFD8_D8D8 }
            }
        }
        return pixels
    }

    /// N3: arte abstracto de Game Boy Advance (240×160) con un marco en los bordes, para ver que
    /// la captura 3:2 no se estira (en el detalle se ve entera; en las tarjetas, recortada a 10:9).
    nonisolated static func demoGBAPixels() -> [UInt32] {
        let width = ScreenSize.gameBoyAdvance.width, height = ScreenSize.gameBoyAdvance.height
        // 0xAABBGGRR: en memoria quedan R, G, B, A.
        let sky: [UInt32] = [0xFFF0_C060, 0xFFE0_A040, 0xFFC8_8030]
        var pixels = [UInt32](repeating: 0, count: width * height)
        for y in 0..<height {
            for x in 0..<width {
                var color = sky[min(y / 40, 2)]
                let dx = x - 120, dy = y - 70
                if dx * dx + dy * dy < 34 * 34 { color = 0xFF30_D0F8 }                 // sol
                if y > 112 { color = ((x / 12) + (y / 12)) % 2 == 0 ? 0xFF30_8840 : 0xFF20_6830 }
                if x < 6 || x >= width - 6 || y < 6 || y >= height - 6 { color = 0xFF40_2018 } // marco
                pixels[y * width + x] = color
            }
        }
        return pixels
    }

    /// Rombos concéntricos en los cuatro tonos verdes del DMG, con un "horizonte".
    nonisolated static func demoPixels() -> [UInt32] {
        // 0xAABBGGRR: en memoria quedan R, G, B, A.
        let shades: [UInt32] = [0xFF0F_BC9B, 0xFF0F_AC8B, 0xFF30_6230, 0xFF0F_380F]
        var pixels = [UInt32](repeating: 0, count: FrameBuffers.pixelCount)
        for y in 0..<FrameBuffers.height {
            for x in 0..<FrameBuffers.width {
                let d = abs(x - 80) + abs(y - 60)
                var shade = (d / 12) % 4
                if y > 104 { shade = ((x / 8) + (y / 8)) % 2 == 0 ? 2 : 3 }
                pixels[y * FrameBuffers.width + x] = shades[shade]
            }
        }
        return pixels
    }
    #endif
}

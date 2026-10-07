import CoreGraphics
import Foundation
import ImageIO
import Observation
import UIKit
import UniformTypeIdentifiers

/// Portadas locales (SPEC §11): el último frame de una sesión válida, guardado en
/// `Application Support/Artwork/<huella>.png` con reemplazo atómico. Nunca junto al ROM
/// y nunca desde la red. Codificar y leer PNG se hace en una cola aparte.
@MainActor @Observable
final class GameArtworkStore {
    private(set) var images: [String: UIImage] = [:]
    @ObservationIgnored private var missing: Set<String> = []
    @ObservationIgnored private var loading: Set<String> = []
    private let directory: URL?
    private let queue = DispatchQueue(label: "PocketGB.artwork", qos: .utility)

    /// Con `directory == nil` (tests y capturas DEBUG) solo vive en memoria.
    init(directory: URL?) {
        self.directory = directory
    }

    static func defaultDirectory() -> URL? {
        guard let base = try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                                      appropriateFor: nil, create: true) else { return nil }
        return base.appendingPathComponent("Artwork", isDirectory: true)
    }

    func fileURL(for fingerprint: String) -> URL? {
        directory?.appendingPathComponent("\(fingerprint).png")
    }

    func image(for fingerprint: String?) -> UIImage? {
        fingerprint.flatMap { images[$0] }
    }

    /// Carga la portada del disco si aún no está en memoria (desde `.task` de la vista).
    func load(_ fingerprint: String) {
        guard images[fingerprint] == nil, !missing.contains(fingerprint), !loading.contains(fingerprint),
              let url = fileURL(for: fingerprint) else { return }
        loading.insert(fingerprint)
        queue.async {
            let data = try? Data(contentsOf: url)
            Task { @MainActor [weak self] in self?.finishLoad(fingerprint, data: data) }
        }
    }

    private func finishLoad(_ fingerprint: String, data: Data?) {
        loading.remove(fingerprint)
        if let data, let image = UIImage(data: data) {
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
        queue.async {
            guard let image = Self.makeImage(pixels), let png = Self.pngData(image) else { return }
            try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(),
                                                     withIntermediateDirectories: true)
            try? png.write(to: url, options: .atomic)
        }
        return true
    }

    /// Borra todas las portadas (Ajustes › Almacenamiento). Se regeneran al jugar.
    func removeAll() {
        images.removeAll()
        missing.removeAll()
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

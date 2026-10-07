import SwiftUI

/// Semilla estable de un juego: su huella si se conoce; si no, su ruta en la carpeta.
/// FNV-1a de 64 bits: a diferencia de `hashValue`, no cambia entre lanzamientos.
enum PlaceholderSeed {
    static func value(_ key: String) -> UInt64 {
        var hash: UInt64 = 0xCBF2_9CE4_8422_2325
        for byte in key.utf8 {
            hash ^= UInt64(byte)
            hash = hash &* 0x0000_0100_0000_01B3
        }
        return hash
    }

    /// Índice del color `Placeholder*` (4 colores).
    static func colorIndex(_ key: String) -> Int { Int(value(key) % 4) }

    /// Glifo abstracto 5×5, simétrico en horizontal (como un invader o un sello).
    /// Siempre tiene al menos una celda encendida por fila central para no quedar vacío.
    static func glyph(_ key: String) -> [[Bool]] {
        let bits = value(key) >> 8
        var cells = [[Bool]](repeating: [Bool](repeating: false, count: 5), count: 5)
        for row in 0..<5 {
            for col in 0..<3 {
                let on = (bits >> UInt64(row * 3 + col)) & 1 == 1
                cells[row][col] = on
                cells[row][4 - col] = on
            }
        }
        cells[2][2] = true
        return cells
    }

    /// Título abreviado: iniciales de hasta dos palabras ("DMG-ACID2" → "DA").
    static func initials(_ title: String) -> String {
        let words = title.split { !$0.isLetter && !$0.isNumber }
        let letters = words.prefix(2).compactMap(\.first).map { String($0).uppercased() }
        return letters.isEmpty ? "?" : letters.joined()
    }
}

/// Portada generada (SPEC §11): color y glifo derivados de la huella, título abreviado y
/// chip GB/GBC/GBA. No imita arte comercial. Es contenido (L1): sin vidrio.
struct GamePlaceholderView: View {
    let seed: String
    let title: String
    let badge: ConsoleBadge
    var compact = false

    var body: some View {
        let color = PocketColor.placeholders[PlaceholderSeed.colorIndex(seed)]
        let glyph = PlaceholderSeed.glyph(seed)
        ZStack {
            color
            Canvas { context, size in
                let side = min(size.width, size.height) * (compact ? 0.6 : 0.42)
                let cell = side / 5
                let origin = CGPoint(x: (size.width - side) / 2,
                                     y: (size.height - side) / 2 - (compact ? 0 : size.height * 0.08))
                for row in 0..<5 {
                    for col in 0..<5 where glyph[row][col] {
                        let rect = CGRect(x: origin.x + CGFloat(col) * cell, y: origin.y + CGFloat(row) * cell,
                                          width: cell, height: cell).insetBy(dx: cell * 0.08, dy: cell * 0.08)
                        context.fill(Path(roundedRect: rect, cornerRadius: cell * 0.2),
                                     with: .color(.white.opacity(0.85)))
                    }
                }
            }
            if !compact {
                VStack {
                    Spacer()
                    HStack(alignment: .bottom) {
                        Text(PlaceholderSeed.initials(title))
                            .font(.headline.monospaced())
                        Spacer()
                        Text(badge.label)
                            .font(.caption2.monospaced().weight(.semibold))
                            .padding(.horizontal, PocketSpacing.xs)
                            .padding(.vertical, 2)
                            .overlay(Capsule().strokeBorder(.white.opacity(0.8), lineWidth: 1))
                    }
                    .foregroundStyle(.white)
                    .padding(PocketSpacing.xs)
                }
            }
        }
        .accessibilityElement()
        .accessibilityLabel("Sin captura, portada generada para \(title)")
    }
}

/// Portada de un juego (SPEC §8, `GameArtworkView`): la captura local si existe o el
/// placeholder, con muestreo nearest para los píxeles. N3a: nunca se deforma. Con `.console`
/// (detalle) el marco tiene la proporción de la consola (10:9 GB/GBC, 3:2 GBA) y la captura se ve
/// entera; con `.card` (cuadrícula, lista, carriles) el marco es 10:9 para todas y la captura lo
/// rellena centrada.
struct GameArtworkView: View {
    @Environment(AppState.self) private var state
    let entry: RomEntry
    var cornerRadius: CGFloat = PocketRadius.cover
    var compact = false
    var style: ArtworkStyle = .card

    var body: some View {
        let fingerprint = state.libraryPrefs.fingerprint(of: entry)
        let title = state.libraryPrefs.displayTitle(entry)
        Color.clear
            .aspectRatio(style.frameAspectRatio(for: entry.console), contentMode: .fit)
            .overlay {
                if let image = state.artwork.image(for: fingerprint) {
                    Image(uiImage: image)
                        .resizable()
                        .interpolation(.none)
                        .aspectRatio(contentMode: .fill)
                        .accessibilityLabel("Captura de \(title)")
                } else {
                    GamePlaceholderView(seed: fingerprint ?? entry.id, title: title,
                                        badge: entry.badge, compact: compact)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
            .task(id: fingerprint) {
                if let fingerprint { state.artwork.load(fingerprint) }
            }
    }
}

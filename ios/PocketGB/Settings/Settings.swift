import Foundation

/// Paletas de color para juegos de Game Boy en modo Color (SPEC §9, `settings-emulation`).
/// 0 = la que elegiría la Game Boy Color por el título; 1…12 = las combinaciones de
/// botones del arranque de la CGB (docs/03, `gb_options.compat_palette`).
enum CompatPalette {
    static let count = 12
    static func title(_ id: UInt8) -> String {
        let names = ["Automática", "→", "←", "↑", "↓", "→ + A", "← + A", "↑ + A", "↓ + A",
                     "→ + B", "← + B", "↑ + B", "↓ + B"]
        return Int(id) < names.count ? names[Int(id)] : names[0]
    }
}

/// Ajustes por juego (D6): `nil` = usa el ajuste global. Se muestran como "Global" o
/// "Personalizado" con texto, nunca solo con color (SPEC §13).
struct GameOverrides: Codable, Equatable, Sendable {
    var colorForGameBoy: Bool?
    var compatPalette: UInt8?

    var isEmpty: Bool { colorForGameBoy == nil && compatPalette == nil }
}

/// Opciones de emulación resueltas para abrir un juego.
struct EmulationOptions: Equatable, Sendable {
    /// Juegos de Game Boy (DMG) en una Game Boy Color, con paleta de color.
    var colorForGameBoy: Bool
    var compatPalette: UInt8
}

extension GameplaySettingsData {
    /// Global + lo personalizado del juego.
    func emulation(for gameID: String?) -> EmulationOptions {
        let override = gameID.flatMap { perGame[$0] }
        return EmulationOptions(colorForGameBoy: override?.colorForGameBoy ?? colorForGameBoy,
                                compatPalette: override?.compatPalette ?? compatPalette)
    }
}

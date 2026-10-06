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
    // Game Boy Advance (G8): tipo de partida forzado (`GBA_SAVE_*`), reloj y uso de la BIOS propia.
    var gbaSaveType: UInt8?
    var gbaRTC: UInt8?
    var gbaUseBIOS: Bool?

    var isEmpty: Bool {
        colorForGameBoy == nil && compatPalette == nil && gbaSaveType == nil && gbaRTC == nil && gbaUseBIOS == nil
    }
}

extension GameOverrides {
    /// Los ajustes guardados se validan: valores fuera de rango (`save_type` 0…6, `rtc` 0…2) se
    /// descartan, y "Automático" (0), "con la BIOS propia" (true) o "Detectado" ya no se guardan:
    /// equivalen a no personalizar.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        colorForGameBoy = try? c.decodeIfPresent(Bool.self, forKey: .colorForGameBoy)
        let palette = try? c.decodeIfPresent(UInt8.self, forKey: .compatPalette)
        compatPalette = palette.flatMap { Int($0) <= CompatPalette.count ? $0 : nil }
        let type = try? c.decodeIfPresent(UInt8.self, forKey: .gbaSaveType)
        gbaSaveType = type.flatMap { (1...6).contains($0) ? $0 : nil }
        let rtc = try? c.decodeIfPresent(UInt8.self, forKey: .gbaRTC)
        gbaRTC = rtc.flatMap { (1...2).contains($0) ? $0 : nil }
        let bios = try? c.decodeIfPresent(Bool.self, forKey: .gbaUseBIOS)
        gbaUseBIOS = bios == false ? false : nil
    }
}

/// Opciones de emulación resueltas para abrir un juego.
struct EmulationOptions: Equatable, Sendable {
    /// Juegos de Game Boy (DMG) en una Game Boy Color, con paleta de color.
    var colorForGameBoy: Bool
    var compatPalette: UInt8
    /// Game Boy Advance: 0 = automático (`GBA_SAVE_AUTO`); el resto, el tipo forzado.
    var gbaSaveType: UInt8 = 0
    /// 0 = automático, 1 = con reloj, 2 = sin reloj (`GBA_RTC_*`).
    var gbaRTC: UInt8 = 0
    /// Usar `gba_bios.bin` si es la BIOS oficial; false = siempre la BIOS emulada.
    var gbaUseBIOS = true
}

extension GameplaySettingsData {
    /// Global + lo personalizado del juego.
    func emulation(for gameID: String?) -> EmulationOptions {
        let override = gameID.flatMap { perGame[$0] }
        return EmulationOptions(colorForGameBoy: override?.colorForGameBoy ?? colorForGameBoy,
                                compatPalette: override?.compatPalette ?? compatPalette,
                                gbaSaveType: override?.gbaSaveType ?? 0, gbaRTC: override?.gbaRTC ?? 0,
                                gbaUseBIOS: override?.gbaUseBIOS ?? true)
    }
}

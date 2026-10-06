import Foundation

/// Aviso al abrir un juego de Game Boy Advance cuyo `.sav` (local o junto al ROM) no coincide con el
/// tipo de partida o el reloj fijados en «Ajustes del juego» (G8-H5). No hay pérdida de datos:
/// ese `.sav` no se toca. El aviso nombra el ajuste como causa y dice cómo recuperarlo.
struct GameSettingsSaveWarning: Equatable, Sendable {
    let title: String
    let message: String

    /// - Parameters:
    ///   - validSizes: tamaños de `.sav` que acepta el cartucho con los ajustes forzados
    ///     (`EmulatorSession.validSaveSizes`); vacío con «Sin partida» y sin reloj.
    ///   - existingSizes: tamaños de los `.sav` que existen (local y espejo).
    /// - Returns: `nil` si no hay ajustes forzados o todo `.sav` existente coincide.
    static func check(forced: Bool, validSizes: Set<Int>, existingSizes: [Int]) -> GameSettingsSaveWarning? {
        guard forced, existingSizes.contains(where: { !validSizes.contains($0) }) else { return nil }
        if validSizes.isEmpty {
            return GameSettingsSaveWarning(
                title: "Partida sin usar por los ajustes del juego",
                message: "«Ajustes del juego» fuerza «Sin partida», así que este juego no guarda. Ya existe una partida guardada (.sav): no se toca. Pon el tipo de partida en «Detectado» en «Ajustes del juego» para recuperarla.")
        }
        return GameSettingsSaveWarning(
            title: "Partida distinta de los ajustes del juego",
            message: "El archivo de partida (.sav) no coincide con el tipo de partida o el reloj fijados en «Ajustes del juego». No se tocará; si no hay otra partida válida, esta sesión no guardará. Pon esos ajustes en «Detectado» para recuperarla.")
    }
}

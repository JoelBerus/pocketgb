import CoreGraphics
import Foundation
import Observation

/// Visibilidad de los controles táctiles (SPEC §9, `gameplay-landscape-hidden`).
enum ControlsVisibility: String, Codable, CaseIterable, Sendable {
    /// Siempre visibles.
    case always
    /// Se desvanecen tras 3 s sin tocar y vuelven con cualquier toque.
    case touch
    /// Ocultos (para jugar con mando): solo queda el botón de menú.
    case hidden

    var title: String {
        switch self {
        case .always: "Siempre"
        case .touch: "Al tocar"
        case .hidden: "Ocultos"
        }
    }
}

/// Dibujo de la cruceta: la cruz del Game Boy o cuatro flechas separadas (estilo mando de
/// PlayStation). Solo cambia el aspecto: la lógica de 8 direcciones es la misma.
enum DpadStyle: String, Codable, CaseIterable, Sendable {
    case cross, separated

    var title: String { self == .cross ? "Game Boy" : "Flechas separadas" }
}

/// Ajustes de controles y pantalla del gameplay (D4).
struct GameplaySettingsData: Codable, Equatable, Sendable {
    /// Opacidad visual en horizontal: 30, 50, 70 o 100 %. No cambia el área táctil.
    var opacity: Int = 70
    var visibility: ControlsVisibility = .always
    var haptics = true
    /// Escala de tamaño de los controles: 0,85 / 1 / 1,15.
    var sizeScale: Double = 1
    var portraitLayout = ControlsLayout.defaults(.portrait)
    var landscapeLayout = ControlsLayout.defaults(.landscape)
    /// Disposición propia de Game Boy Advance (imagen 3:2, con L/R). Las claves antiguas
    /// `portraitLayout`/`landscapeLayout` siguen siendo las de Game Boy.
    var gbaPortraitLayout = ControlsLayout.defaults(.portrait, shoulders: true)
    var gbaLandscapeLayout = ControlsLayout.defaults(.landscape, shoulders: true)
    /// Horizontal: solo múltiplos enteros de 160×144 (píxeles idénticos).
    var integerScaleLandscape = true
    var dpadStyle: DpadStyle = .cross
    /// Cuánto ángulo ocupan las diagonales de la cruceta táctil (N2). El mando no lo usa.
    var dpadDiagonals: DpadDiagonals = .reduced
    // Audio (D6): volumen del juego y si suena con el interruptor de silencio.
    var volume: Double = 1
    var playsInSilentMode = false
    // Emulación (D6): juegos de Game Boy en color y su paleta; ajustes por juego.
    var colorForGameBoy = false
    var compatPalette: UInt8 = 0
    var perGame: [String: GameOverrides] = [:]

    static let opacities = [30, 50, 70, 100]
    static let sizeScales: [(title: String, value: Double)] = [("Pequeño", 0.85), ("Normal", 1), ("Grande", 1.15)]

    init() {}

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let defaults = GameplaySettingsData()
        let rawOpacity = (try? c.decodeIfPresent(Int.self, forKey: .opacity)) ?? defaults.opacity
        opacity = Self.opacities.contains(rawOpacity) ? rawOpacity : defaults.opacity
        visibility = (try? c.decodeIfPresent(ControlsVisibility.self, forKey: .visibility)) ?? defaults.visibility
        haptics = (try? c.decodeIfPresent(Bool.self, forKey: .haptics)) ?? defaults.haptics
        let rawScale = (try? c.decodeIfPresent(Double.self, forKey: .sizeScale)) ?? defaults.sizeScale
        sizeScale = min(max(rawScale, 0.85), 1.15)
        portraitLayout = (try? c.decodeIfPresent(ControlsLayout.self, forKey: .portraitLayout)) ?? defaults.portraitLayout
        landscapeLayout = (try? c.decodeIfPresent(ControlsLayout.self, forKey: .landscapeLayout)) ?? defaults.landscapeLayout
        gbaPortraitLayout = (try? c.decodeIfPresent(ControlsLayout.self, forKey: .gbaPortraitLayout))
            ?? defaults.gbaPortraitLayout
        gbaLandscapeLayout = (try? c.decodeIfPresent(ControlsLayout.self, forKey: .gbaLandscapeLayout))
            ?? defaults.gbaLandscapeLayout
        integerScaleLandscape = (try? c.decodeIfPresent(Bool.self, forKey: .integerScaleLandscape))
            ?? defaults.integerScaleLandscape
        dpadStyle = (try? c.decodeIfPresent(DpadStyle.self, forKey: .dpadStyle)) ?? defaults.dpadStyle
        dpadDiagonals = (try? c.decodeIfPresent(DpadDiagonals.self, forKey: .dpadDiagonals)) ?? defaults.dpadDiagonals
        volume = min(max((try? c.decodeIfPresent(Double.self, forKey: .volume)) ?? defaults.volume, 0), 1)
        playsInSilentMode = (try? c.decodeIfPresent(Bool.self, forKey: .playsInSilentMode)) ?? defaults.playsInSilentMode
        colorForGameBoy = (try? c.decodeIfPresent(Bool.self, forKey: .colorForGameBoy)) ?? defaults.colorForGameBoy
        let palette = (try? c.decodeIfPresent(UInt8.self, forKey: .compatPalette)) ?? defaults.compatPalette
        compatPalette = Int(palette) <= CompatPalette.count ? palette : 0
        perGame = (try? c.decodeIfPresent([String: GameOverrides].self, forKey: .perGame)) ?? [:]
    }

    /// Disposición de una consola y orientación (`shoulders`: Game Boy Advance).
    func layout(_ orientation: ControlsOrientation, shoulders: Bool = false) -> ControlsLayout {
        switch (orientation, shoulders) {
        case (.portrait, false): portraitLayout
        case (.landscape, false): landscapeLayout
        case (.portrait, true): gbaPortraitLayout
        case (.landscape, true): gbaLandscapeLayout
        }
    }

    mutating func setLayout(_ layout: ControlsLayout, _ orientation: ControlsOrientation, shoulders: Bool) {
        switch (orientation, shoulders) {
        case (.portrait, false): portraitLayout = layout
        case (.landscape, false): landscapeLayout = layout
        case (.portrait, true): gbaPortraitLayout = layout
        case (.landscape, true): gbaLandscapeLayout = layout
        }
    }
}

/// Almacén observable de los ajustes de gameplay. Se guarda en `UserDefaults` (clave
/// `gameplaySettings`); con `defaults == nil` (capturas DEBUG) vive solo en memoria.
@MainActor @Observable
final class GameplaySettings {
    private(set) var data: GameplaySettingsData
    @ObservationIgnored private let defaults: UserDefaults?
    static let key = "gameplaySettings"

    init(defaults: UserDefaults?) {
        self.defaults = defaults
        if let raw = defaults?.data(forKey: Self.key),
           let decoded = try? JSONDecoder().decode(GameplaySettingsData.self, from: raw) {
            data = decoded
        } else {
            data = GameplaySettingsData()
        }
    }

    func update(_ change: (inout GameplaySettingsData) -> Void) {
        var copy = data
        change(&copy)
        guard copy != data else { return }
        data = copy
        if let defaults, let encoded = try? JSONEncoder().encode(copy) {
            defaults.set(encoded, forKey: Self.key)
        }
    }

    /// Guarda un control movido en el editor, solo para esa orientación y esa consola.
    func move(_ id: ControlID, to relative: CGPoint, orientation: ControlsOrientation, shoulders: Bool = false) {
        let clamped = CGPoint(x: min(max(relative.x, 0), 1), y: min(max(relative.y, 0), 1))
        update { data in
            var layout = data.layout(orientation, shoulders: shoulders)
            layout.centers[id] = clamped
            data.setLayout(layout, orientation, shoulders: shoulders)
        }
    }

    /// Cambia el tamaño de un control (en pasos del 10 %), solo para esa orientación y consola.
    func resize(_ id: ControlID, by delta: CGFloat, orientation: ControlsOrientation, shoulders: Bool = false) {
        update { data in
            var layout = data.layout(orientation, shoulders: shoulders)
            let value = ((layout.scale(id) + delta) * 10).rounded() / 10
            layout.scales[id] = min(max(value, ControlsLayout.scaleRange.lowerBound), ControlsLayout.scaleRange.upperBound)
            data.setLayout(layout, orientation, shoulders: shoulders)
        }
    }

    /// Separación de las flechas separadas (pasos del 10 %, 0,7…1,5), solo para esa orientación y consola.
    func respaceArrows(by delta: CGFloat, orientation: ControlsOrientation, shoulders: Bool = false) {
        update { data in
            var layout = data.layout(orientation, shoulders: shoulders)
            let value = ((layout.spacing + delta) * 10).rounded() / 10
            layout.arrowSpacing = min(max(value, ControlsLayout.arrowSpacingRange.lowerBound),
                                      ControlsLayout.arrowSpacingRange.upperBound)
            data.setLayout(layout, orientation, shoulders: shoulders)
        }
    }

    /// Ajustes de un juego; al quedar todo en "Global" se borra la entrada.
    func setOverrides(_ overrides: GameOverrides, for gameID: String) {
        update { data in
            data.perGame[gameID] = overrides.isEmpty ? nil : overrides
        }
    }

    func resetLayout(_ orientation: ControlsOrientation, shoulders: Bool = false) {
        update { $0.setLayout(.defaults(orientation, shoulders: shoulders), orientation, shoulders: shoulders) }
    }

    #if DEBUG
    /// `-controlOpacity`, `-controlsVisibility`, `-dpadStyle`, `-dpadDiagonals`, `-arrowSpacing`:
    /// estado fijo para capturas, sin persistir.
    func applyDebugArguments() {
        var copy = data
        if let raw = DebugArguments.value("-controlOpacity"), let value = Int(raw),
           GameplaySettingsData.opacities.contains(value) {
            copy.opacity = value
        }
        if let raw = DebugArguments.value("-controlsVisibility"), let value = ControlsVisibility(rawValue: raw) {
            copy.visibility = value
        }
        if let raw = DebugArguments.value("-dpadStyle"), let value = DpadStyle(rawValue: raw) {
            copy.dpadStyle = value
        }
        if let raw = DebugArguments.value("-dpadDiagonals"), let value = DpadDiagonals(rawValue: raw) {
            copy.dpadDiagonals = value
        }
        // `-arrowSpacing 0.7`: la misma separación en las cuatro disposiciones (capturas N2).
        if let raw = DebugArguments.value("-arrowSpacing"), let value = Double(raw) {
            for (orientation, shoulders) in [(ControlsOrientation.portrait, false), (.landscape, false), (.portrait, true), (.landscape, true)] {
                var layout = copy.layout(orientation, shoulders: shoulders)
                layout.arrowSpacing = CGFloat(value)
                copy.setLayout(layout, orientation, shoulders: shoulders)
            }
        }
        data = copy
    }
    #endif
}

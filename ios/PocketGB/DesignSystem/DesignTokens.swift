import SwiftUI

/// Tokens de diseño (docs/diseno/SPEC.md §7). Los colores viven en Assets.xcassets
/// con variante clara y oscura; las vistas no repiten valores hex.
enum PocketColor {
    /// Nombres de los color sets (los comprueba DesignTokensTests).
    static let assetNames = [
        "BackgroundBase", "BackgroundElevated", "GameplayBackground", "AccentPrimary",
        "Danger", "ControlScrim", "ControlAWarm", "ControlBCool",
        "PlaceholderBlue", "PlaceholderGreen", "PlaceholderAmber", "PlaceholderViolet",
    ]

    static let backgroundBase = Color("BackgroundBase")
    static let backgroundElevated = Color("BackgroundElevated")
    static let gameplayBackground = Color("GameplayBackground")
    static let accent = Color("AccentPrimary")
    static let danger = Color("Danger")
    static let controlScrim = Color("ControlScrim")
    static let controlAWarm = Color("ControlAWarm")
    static let controlBCool = Color("ControlBCool")
    static let placeholders = [
        Color("PlaceholderBlue"), Color("PlaceholderGreen"),
        Color("PlaceholderAmber"), Color("PlaceholderViolet"),
    ]
}

/// Espaciado (SPEC §7.2). Margen normal 16, grid 12, separación mínima entre controles 8.
enum PocketSpacing {
    static let xxs: CGFloat = 4
    static let xs: CGFloat = 8
    static let sm: CGFloat = 12
    static let md: CGFloat = 16
    static let lg: CGFloat = 20
    static let xl: CGFloat = 24
    static let xxl: CGFloat = 32
    /// Touch target mínimo (44×44 pt).
    static let minTouch: CGFloat = 44
}

/// Radios (SPEC §7.3). Las sheets usan el radio del sistema.
enum PocketRadius {
    static let cover: CGFloat = 14
    static let thumbnail: CGFloat = 8
    static let group: CGFloat = 14
    static let saveStatePreview: CGFloat = 12
}

/// Motion (SPEC §7.5). Ninguna animación bloquea input, flush ni rotación.
@MainActor
enum PocketMotion {
    static let controlPress = Animation.easeOut(duration: 0.07)
    static let controlRelease = Animation.easeOut(duration: 0.09)
    static let hudMorph = Animation.spring(duration: 0.22)
    static let toastIn = Animation.easeOut(duration: 0.18)
    static let toastVisible: Duration = .seconds(2.5)
    static let rotation = Animation.spring(duration: 0.3)
    static let reducedMotionFade = Animation.easeInOut(duration: 0.2)
    static let newArtwork = Animation.easeOut(duration: 0.18)
    static let controlsAutohide: Duration = .seconds(3)

    /// Política central de Reduce Motion (SPEC §13): el ajuste del sistema o, en DEBUG,
    /// `-reduceMotion` para las capturas.
    static func reducesMotion(system: Bool) -> Bool {
        #if DEBUG
        if DebugArguments.reduceMotion { return true }
        #endif
        return system
    }
}

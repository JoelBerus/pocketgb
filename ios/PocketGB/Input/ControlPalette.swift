import CoreGraphics

/// Color sin premultiplicar (componentes 0…1). Puro, sin UIKit, para poder medir el contraste
/// en los tests con los mismos valores que dibuja `ControlVisualView` (N2-H1).
struct PaletteColor: Equatable, Sendable {
    var r: CGFloat, g: CGFloat, b: CGFloat, a: CGFloat

    static func white(_ w: CGFloat, alpha: CGFloat = 1) -> PaletteColor { PaletteColor(r: w, g: w, b: w, a: alpha) }
    static func black(alpha: CGFloat) -> PaletteColor { PaletteColor(r: 0, g: 0, b: 0, a: alpha) }
    static func rgb(_ hex: UInt32) -> PaletteColor {
        PaletteColor(r: CGFloat((hex >> 16) & 0xFF) / 255, g: CGFloat((hex >> 8) & 0xFF) / 255,
                     b: CGFloat(hex & 0xFF) / 255, a: 1)
    }

    /// El mismo color con su alfa multiplicado (la `opacity` de la capa que lo dibuja).
    func faded(_ opacity: CGFloat) -> PaletteColor { PaletteColor(r: r, g: g, b: b, a: a * opacity) }

    /// Este color compuesto encima de `bottom` (que se trata como opaco).
    func over(_ bottom: PaletteColor) -> PaletteColor {
        PaletteColor(r: r * a + bottom.r * (1 - a), g: g * a + bottom.g * (1 - a), b: b * a + bottom.b * (1 - a), a: 1)
    }

    /// Luminancia relativa (WCAG 2.x) del color opaco.
    var luminance: CGFloat {
        func channel(_ c: CGFloat) -> CGFloat { c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4) }
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    }

    /// Relación de contraste WCAG entre dos colores opacos (1…21).
    static func contrast(_ x: PaletteColor, _ y: PaletteColor) -> CGFloat {
        let (l1, l2) = (x.luminance, y.luminance)
        return (max(l1, l2) + 0.05) / (min(l1, l2) + 0.05)
    }
}

/// Aspecto de un control según la orientación, la opacidad elegida y Reduce Transparency
/// (SPEC §10.3). Una sola fuente para el dibujo y para `DpadContrastTests`.
struct ControlPalette: Equatable, Sendable {
    enum Surface: Sendable { case solidGlass, clearGlass }

    let surface: Surface
    /// 0,3…1 (30–100 %); solo cuenta en horizontal (`clearGlass`).
    let opacity: CGFloat
    let reduceTransparency: Bool

    /// Etiquetas, cruz y flechas: nunca por debajo del 70 %.
    var labelAlpha: CGFloat {
        reduceTransparency || surface == .solidGlass ? 1 : max(0.7, opacity)
    }

    /// Alfa de la vista de vidrio (cero con Reduce Transparency: no hay vidrio).
    var glassAlpha: CGFloat {
        if reduceTransparency { return 0 }
        return surface == .solidGlass ? 1 : max(opacity, 0.3)
    }

    /// Relleno de la forma, encima del vidrio: sólido con Reduce Transparency, un velo oscuro
    /// en horizontal y casi nada en vertical. `pressed` solo para A, B, Start, Select, L y R.
    func fill(pressed: Bool) -> PaletteColor {
        if reduceTransparency { return .white(pressed ? 0.24 : 0.1, alpha: 0.94) }
        switch surface {
        case .solidGlass: return .white(1, alpha: pressed ? 0.22 : 0.04)
        case .clearGlass: return pressed ? .white(1, alpha: 0.25) : .black(alpha: 0.2 + 0.2 * opacity)
        }
    }

    var border: PaletteColor {
        if reduceTransparency { return .white(1, alpha: 0.85) }
        return surface == .solidGlass ? .white(1, alpha: 0.2) : .white(1, alpha: 0.3 + 0.35 * opacity)
    }

    var borderWidth: CGFloat { reduceTransparency ? 1.5 : 1 }

    var shadowOpacity: CGFloat {
        if reduceTransparency || surface == .solidGlass { return 0.35 }
        return 0.3 + 0.3 * opacity
    }

    var shadowRadius: CGFloat { surface == .solidGlass && !reduceTransparency ? 5 : 4 }

    // MARK: - Cruceta (N2-H1: contraste pulsado/neutro ≥ 3:1)

    /// Cruz en reposo: blanca, a `labelAlpha`.
    var crossNeutral: PaletteColor { .white(1, alpha: labelAlpha) }
    /// Brazo pulsado: gris oscuro **opaco**, sin atenuar por la opacidad (antes, negro al 34 %
    /// multiplicado por `labelAlpha`: 1,7:1 en horizontal).
    static let crossPressed = PaletteColor.white(0.32)
    /// Triángulo de cada brazo: oscuro sobre la cruz blanca y blanco sobre el brazo pulsado.
    var crossIcon: PaletteColor { .white(0.1, alpha: 0.45) }
    static let crossPressedIcon = PaletteColor.white(1)
    static let dimpleFill = PaletteColor.black(alpha: 0.06)
    static let dimpleStroke = PaletteColor.black(alpha: 0.1)

    /// Flecha separada pulsada: disco casi blanco (encima del relleno) con el triángulo oscuro.
    static let arrowPressed = PaletteColor.white(1, alpha: 0.9)
    static let arrowIcon = PaletteColor.white(1)
    static let arrowPressedIcon = PaletteColor.white(0.12)

    /// Color en reposo de una flecha separada sobre `backdrop` (lo que se ve a través del vidrio).
    func arrowNeutral(over backdrop: PaletteColor) -> PaletteColor { fill(pressed: false).over(backdrop) }
    /// Color de una flecha pulsada sobre `backdrop`.
    func arrowPressedColor(over backdrop: PaletteColor) -> PaletteColor { Self.arrowPressed.over(arrowNeutral(over: backdrop)) }
    /// Color de la cruz en reposo sobre `backdrop` (el círculo de la cruceta).
    func crossNeutralColor(over backdrop: PaletteColor) -> PaletteColor { crossNeutral.over(backdrop) }
}

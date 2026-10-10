import CoreGraphics
import Testing
@testable import PocketGB

/// N2-H1: el brazo o la flecha pulsada contrasta ≥ 3:1 (WCAG) con lo que no está pulsado, en
/// vertical y en horizontal (también al 30 %) y con Reduce Transparency. Se calcula con los
/// colores y alfas de `ControlPalette`, los mismos que usa `ControlVisualView` para dibujar.
/// El vidrio no se puede simular: se usan los fondos sobre los que van los controles, medidos
/// en las capturas del simulador (docs/auditorias/N2-ios-evidencia.md §3).
struct DpadContrastTests {
    /// Fondo de los controles en vertical (asset `GameplayBackground`).
    static let gameplayBackground = PaletteColor.rgb(0x101217)
    /// Vidrio regular sobre ese fondo (vertical): dentro del círculo de la cruceta se mide
    /// (31, 33, 36), que es este color con el relleno blanco al 4 % encima.
    static let portraitGlass = PaletteColor.rgb(0x16181B)
    /// Vidrio claro sobre el margen negro de la imagen (horizontal, donde va la cruceta): con
    /// el velo encima se medía 9 de gris en las flechas en reposo.
    static let landscapeGlass = PaletteColor.rgb(0x0E0E0E)

    static let opacities: [CGFloat] = [0.3, 0.5, 0.7, 1]

    static var palettes: [(String, ControlPalette, [PaletteColor])] {
        var all: [(String, ControlPalette, [PaletteColor])] = []
        all.append(("vertical", ControlPalette(surface: .solidGlass, opacity: 0.7, reduceTransparency: false),
                    [gameplayBackground, portraitGlass]))
        for o in opacities {
            all.append(("horizontal \(Int(o * 100)) %", ControlPalette(surface: .clearGlass, opacity: o, reduceTransparency: false),
                        [.rgb(0x000000), landscapeGlass]))
        }
        for surface in [ControlPalette.Surface.solidGlass, .clearGlass] {
            // Sin vidrio: el relleno sólido al 94 % tapa casi todo lo de debajo (negro o blanco).
            all.append(("Reduce Transparency \(surface)", ControlPalette(surface: surface, opacity: 0.3, reduceTransparency: true),
                        [.rgb(0x000000), gameplayBackground, .rgb(0xFFFFFF)]))
        }
        return all
    }

    @Test func pressedCrossArmContrastsAtLeast3To1() {
        for (name, palette, backdrops) in Self.palettes {
            // La cruz blanca va sobre el círculo; el brazo pulsado es opaco. Sobre blanco la cruz
            // también es blanca: se comprueba igual (por si la cruceta se mueve sobre el juego).
            for backdrop in backdrops + [.rgb(0xFFFFFF)] {
                let neutral = palette.crossNeutralColor(over: backdrop)
                let pressed = ControlPalette.crossPressed
                let ratio = PaletteColor.contrast(neutral, pressed)
                #expect(ratio >= 3, "\(name): \(ratio)")
            }
            // El triángulo blanco del brazo pulsado se lee sobre el gris.
            #expect(PaletteColor.contrast(ControlPalette.crossPressedIcon, ControlPalette.crossPressed) >= 4.5)
        }
    }

    @Test func pressedArrowContrastsAtLeast3To1() {
        for (name, palette, backdrops) in Self.palettes {
            for backdrop in backdrops {
                let neutral = palette.arrowNeutral(over: backdrop)
                let pressed = palette.arrowPressedColor(over: backdrop)
                let ratio = PaletteColor.contrast(neutral, pressed)
                #expect(ratio >= 3, "\(name): \(ratio)")
                // El triángulo oscuro de la flecha pulsada se lee sobre el disco claro.
                #expect(PaletteColor.contrast(ControlPalette.arrowPressedIcon.over(pressed), pressed) >= 4.5)
            }
        }
    }

    /// Lo que medía la auditoría con los colores de antes (negro al 34 % × `labelAlpha` sobre la
    /// cruz; blanco al 28 % en las flechas): por debajo de 3:1. Así el test no es tautológico.
    @Test func previousColorsFailedTheThreshold() {
        let landscape = ControlPalette(surface: .clearGlass, opacity: 0.7, reduceTransparency: false)
        let neutral = landscape.crossNeutralColor(over: Self.landscapeGlass)
        let oldPressed = PaletteColor.black(alpha: 0.34 * landscape.labelAlpha).over(neutral)
        #expect(PaletteColor.contrast(neutral, oldPressed) < 2)
        let arrow = landscape.arrowNeutral(over: Self.landscapeGlass)
        #expect(PaletteColor.contrast(arrow, PaletteColor.white(1, alpha: 0.28).over(arrow)) < 3)
    }

    @Test func contrastMathMatchesWCAG() {
        #expect(abs(PaletteColor.contrast(.rgb(0x000000), .rgb(0xFFFFFF)) - 21) < 0.01)
        #expect(abs(PaletteColor.contrast(.rgb(0x777777), .rgb(0xFFFFFF)) - 4.48) < 0.01)
        #expect(PaletteColor.white(1, alpha: 0.5).over(.rgb(0x000000)) == .white(0.5))
    }
}

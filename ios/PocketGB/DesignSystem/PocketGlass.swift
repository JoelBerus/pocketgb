import SwiftUI

/// Vidrio con alternativa accesible (SPEC §13, Reduce Transparency): con el ajuste del
/// sistema (o `-reduceTransparency` en DEBUG), los botones pasan a `.bordered` y las
/// superficies propias a un relleno sólido con borde. La jerarquía no cambia.
enum PocketGlassPolicy {
    @MainActor
    static func reducesTransparency(system: Bool) -> Bool {
        #if DEBUG
        if DebugArguments.reduceTransparency { return true }
        #endif
        return system
    }
}

private struct PocketGlassButton: ViewModifier {
    let prominent: Bool
    @Environment(\.accessibilityReduceTransparency) private var system

    func body(content: Content) -> some View {
        if PocketGlassPolicy.reducesTransparency(system: system) {
            if prominent {
                content.buttonStyle(.borderedProminent)
            } else {
                content.buttonStyle(.bordered)
            }
        } else if prominent {
            content.buttonStyle(.glassProminent)
        } else {
            content.buttonStyle(.glass)
        }
    }
}

private struct PocketGlassSurface<S: Shape>: ViewModifier {
    let shape: S
    let interactive: Bool
    @Environment(\.accessibilityReduceTransparency) private var system

    func body(content: Content) -> some View {
        if PocketGlassPolicy.reducesTransparency(system: system) {
            content
                .background(PocketColor.backgroundElevated, in: shape)
                .overlay(shape.stroke(.primary.opacity(0.25), lineWidth: 1))
        } else {
            content.glassEffect(interactive ? .regular.interactive() : .regular, in: shape)
        }
    }
}

extension View {
    /// `.glass` / `.glassProminent`, o `.bordered` / `.borderedProminent` sin transparencia.
    func pocketGlassButton(prominent: Bool = false) -> some View {
        modifier(PocketGlassButton(prominent: prominent))
    }

    /// `glassEffect(.regular)` o superficie sólida sin transparencia.
    func pocketGlass(in shape: some Shape, interactive: Bool = false) -> some View {
        modifier(PocketGlassSurface(shape: shape, interactive: interactive))
    }
}

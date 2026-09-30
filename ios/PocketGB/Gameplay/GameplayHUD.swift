import SwiftUI

/// HUD de gameplay (SPEC §8, `GameplayHUD`): el botón de menú se transforma en una fila de
/// acciones de vidrio dentro de un único `GlassEffectContainer` (morph con `glassEffectID`;
/// con Reduce Motion, fundido). No pausa la emulación y se pliega solo a los 3 s.
struct GameplayHUD: View {
    @Environment(AppState.self) private var state
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion
    @Namespace private var glass

    private var reduceMotion: Bool { PocketMotion.reducesMotion(system: systemReduceMotion) }

    var body: some View {
        GlassEffectContainer(spacing: PocketSpacing.xs) {
            HStack(spacing: PocketSpacing.xs) {
                if state.hudExpanded {
                    action("Pausa", "pause.fill", id: "pause") { state.pauseGame() }
                    action("Estados", "square.stack", id: "states") {
                        state.pauseGame()
                        state.pausePath = [.states]
                    }
                    action("Controles", "slider.horizontal.3", id: "controls") {
                        state.hudExpanded = false
                        state.editingControls = true
                    }
                    action("Salir", "xmark", id: "exit") { state.closeGame() }
                }
                Button {
                    toggle()
                } label: {
                    Image(systemName: state.hudExpanded ? "chevron.up" : "ellipsis")
                        .font(.body.weight(.bold))
                        .frame(width: 40, height: 40)
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .foregroundStyle(.white)
                .glassEffect(.regular.interactive(), in: Circle())
                .glassEffectID("menu", in: glass)
                .accessibilityLabel(state.hudExpanded ? "Cerrar menú" : "Abrir menú")
                .accessibilityIdentifier("hud-menu")
            }
        }
        .frame(minWidth: PocketSpacing.minTouch, minHeight: PocketSpacing.minTouch)
        .task(id: state.hudExpanded) {
            guard state.hudExpanded else { return }
            try? await Task.sleep(for: PocketMotion.controlsAutohide)
            if !Task.isCancelled { withAnimation(animation) { state.hudExpanded = false } }
        }
    }

    private var animation: Animation { reduceMotion ? PocketMotion.reducedMotionFade : PocketMotion.hudMorph }

    private func toggle() {
        withAnimation(animation) { state.hudExpanded.toggle() }
    }

    private func action(_ title: String, _ symbol: String, id: String,
                        perform: @escaping () -> Void) -> some View {
        Button(action: perform) {
            Image(systemName: symbol)
                .font(.body.weight(.semibold))
                .frame(width: 44, height: 40)
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .foregroundStyle(.white)
        .glassEffect(.regular.interactive(), in: Capsule())
        .glassEffectID(id, in: glass)
        .transition(reduceMotion ? .opacity : .identity)
        .accessibilityLabel(title)
    }
}

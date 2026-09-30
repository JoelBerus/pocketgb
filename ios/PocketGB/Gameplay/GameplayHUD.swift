import SwiftUI

/// Botón de menú del gameplay: un único botón de vidrio que pausa el juego y abre la sheet
/// de pausa con las opciones (decisión de Joel, 2026-09-30: sustituye a la fila desplegable
/// del HUD de D5, que no pausaba).
struct GameplayHUD: View {
    @Environment(AppState.self) private var state

    var body: some View {
        GlassEffectContainer(spacing: PocketSpacing.xs) {
            HStack(spacing: PocketSpacing.xs) {
                Button {
                    state.pauseGame()
                } label: {
                    Image(systemName: "pause.fill")
                        .font(.body.weight(.bold))
                        .frame(width: 44, height: 44)
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .foregroundStyle(.white)
                .pocketGlass(in: Circle(), interactive: true)
                .accessibilityLabel("Pausa y opciones")
                .accessibilityIdentifier("hud-menu")

                // Avance rápido ×1 → ×2 → ×4. Texto además del símbolo (SPEC §13).
                Button {
                    state.cycleSpeed()
                } label: {
                    HStack(spacing: 2) {
                        Image(systemName: state.gameSpeed > 1 ? "forward.fill" : "forward")
                        if state.gameSpeed > 1 { Text("×\(state.gameSpeed)").monospacedDigit() }
                    }
                    .font(.subheadline.weight(.bold))
                    .padding(.horizontal, PocketSpacing.sm)
                    .frame(minWidth: 44, minHeight: 44)
                    .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .foregroundStyle(state.gameSpeed > 1 ? PocketColor.controlAWarm : .white)
                .pocketGlass(in: Capsule(), interactive: true)
                .accessibilityLabel("Avance rápido")
                .accessibilityValue(state.gameSpeed > 1 ? "×\(state.gameSpeed)" : "Desactivado")
                .accessibilityIdentifier("hud-speed")
            }
        }
    }
}

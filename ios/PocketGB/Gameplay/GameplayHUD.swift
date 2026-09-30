import SwiftUI

/// Botón de menú del gameplay: un único botón de vidrio que pausa el juego y abre la sheet
/// de pausa con las opciones (decisión de Joel, 2026-09-30: sustituye a la fila desplegable
/// del HUD de D5, que no pausaba).
struct GameplayHUD: View {
    @Environment(AppState.self) private var state

    var body: some View {
        Button {
            state.pauseGame()
        } label: {
            Image(systemName: "pause.fill")
                .font(.body.weight(.bold))
                .frame(width: 40, height: 40)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(.white)
        .glassEffect(.regular.interactive(), in: Circle())
        .frame(minWidth: PocketSpacing.minTouch, minHeight: PocketSpacing.minTouch)
        .accessibilityLabel("Pausa y opciones")
        .accessibilityIdentifier("hud-menu")
    }
}

import SwiftUI

/// Sheet de pausa (SPEC §9, `gameplay-pause`): la emulación ya está parada al mostrarse.
/// "Continuar" es la acción dominante; "Salir del juego" va separada. Material del sistema,
/// sin vidrio propio en el fondo.
struct PauseView: View {
    @Environment(AppState.self) private var state

    var body: some View {
        @Bindable var state = state
        NavigationStack(path: $state.pausePath) {
            List {
                Section {
                    Button {
                        state.resume()
                    } label: {
                        Label("Continuar", systemImage: "play.fill")
                            .font(.headline)
                            .frame(maxWidth: .infinity, minHeight: PocketSpacing.minTouch)
                    }
                    .buttonStyle(.glassProminent)
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets())
                    .accessibilityIdentifier("pause-resume")
                }
                Section {
                    NavigationLink(value: PauseRoute.states) {
                        Label("Estados guardados", systemImage: "square.stack")
                    }
                    Button("Personalizar controles", systemImage: "slider.horizontal.3") {
                        state.editingControls = true
                        state.resumeKeepingEditorPaused()
                    }
                } footer: {
                    Text("La partida del juego se guarda sola; los estados son capturas completas que puedes cargar cuando quieras.")
                }
                Section {
                    Button("Salir del juego", systemImage: "rectangle.portrait.and.arrow.right", role: .destructive) {
                        state.closeGame()
                    }
                } footer: {
                    Text("Al salir se guarda la partida y un estado automático.")
                }
            }
            .navigationTitle(state.session?.info.title.isEmpty == false ? state.session!.info.title : "Pausa")
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(for: PauseRoute.self) { route in
                switch route {
                case .states: SaveStatesView()
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }
}

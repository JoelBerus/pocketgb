import SwiftUI

/// Sheet de pausa (SPEC §9, `gameplay-pause`): la emulación ya está parada al mostrarse.
/// "Continuar" es la acción dominante; "Salir del juego" va separada. Material del sistema,
/// sin vidrio propio en el fondo.
struct PauseView: View {
    @Environment(AppState.self) private var state
    /// N5: resultado de «Usar como portada» (nil = sin tocar).
    @State private var pinned: Bool?

    private var pauseTitle: String {
        if let link = state.link { return link.activeTitle }
        return state.session?.info.title.isEmpty == false ? state.session!.info.title : "Pausa"
    }

    var body: some View {
        @Bindable var state = state
        NavigationStack(path: $state.pausePath) {
            List {
                Section {
                    Button {
                        state.resume()
                    } label: {
                        HStack(spacing: PocketSpacing.xs) {
                            Image(systemName: "play.fill")
                            Text("Continuar")
                        }
                            .foregroundStyle(.white)
                            .font(.headline)
                            .frame(maxWidth: .infinity, minHeight: PocketSpacing.minTouch)
                    }
                    .pocketGlassButton(prominent: true)
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets())
                    .accessibilityIdentifier("pause-resume")
                }
                if let link = state.link {
                    // Cable link (M9): sin estados guardados (§1.6).
                    Section {
                        Button("Cambiar a \(link.peerTitle) y continuar", systemImage: "arrow.left.arrow.right") {
                            state.switchLinkSide()
                            state.resume()
                        }
                        .accessibilityIdentifier("pause-link-switch")
                        Button("Personalizar controles", systemImage: "slider.horizontal.3") {
                            state.editingControls = true
                            state.resumeKeepingEditorPaused()
                        }
                    } footer: {
                        Text("Con el cable link no hay momentos: cargar uno en un juego rompería la conexión con el otro. Cada juego guarda su partida sola.")
                    }
                    Section {
                        Button("Salir del cable", systemImage: "rectangle.portrait.and.arrow.right", role: .destructive) {
                            state.closeGame()
                        }
                        .accessibilityIdentifier("pause-link-exit")
                    } footer: {
                        Text("Al salir se guardan las dos partidas.")
                    }
                } else {
                    Section {
                        NavigationLink(value: PauseRoute.moments) {
                            Label("Momentos", systemImage: "bookmark")
                        }
                        .accessibilityIdentifier("pause-moments")
                        Button("Personalizar controles", systemImage: "slider.horizontal.3") {
                            state.editingControls = true
                            state.resumeKeepingEditorPaused()
                        }
                        // N5: fija la escena en pantalla como portada (no toca la partida).
                        Button(pinned == true ? "Portada fijada" : "Usar como portada",
                               systemImage: pinned == true ? "checkmark.circle" : "photo.badge.checkmark") {
                            pinned = state.pinCurrentFrameAsCover()
                        }
                        .accessibilityIdentifier("pause-use-as-cover")
                    } footer: {
                        if pinned == false {
                            Text("Esta escena es de un solo color y no sirve como portada. Prueba con otra.")
                        } else {
                            Text("La partida del juego se guarda sola. Los momentos guardan este instante exacto para volver a él cuando quieras. «Usar como portada» fija la escena actual como portada del juego.")
                        }
                    }
                    Section {
                        Button("Salir del juego", systemImage: "rectangle.portrait.and.arrow.right", role: .destructive) {
                            state.closeGame()
                        }
                    } footer: {
                        Text("Al salir se guarda la partida y un estado automático.")
                    }
                }
            }
            .navigationTitle(pauseTitle)
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(for: PauseRoute.self) { route in
                switch route {
                case .moments: MomentsView(context: .pause)
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }
}

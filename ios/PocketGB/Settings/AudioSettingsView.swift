import SwiftUI

/// Ajustes › Audio (SPEC §9, `settings-audio`): volumen y modo silencio. Sin audio en
/// segundo plano: al salir de la app el juego se pausa.
struct AudioSettingsView: View {
    @Environment(AppState.self) private var state

    var body: some View {
        let gameplay = state.gameplay
        Form {
            Section {
                LabeledContent("Volumen") {
                    Slider(value: Binding(get: { gameplay.data.volume },
                                          set: { v in gameplay.update { $0.volume = v } }),
                           in: 0...1) {
                        Text("Volumen")
                    } minimumValueLabel: {
                        Image(systemName: "speaker.fill").accessibilityHidden(true)
                    } maximumValueLabel: {
                        Image(systemName: "speaker.wave.3.fill").accessibilityHidden(true)
                    }
                    .frame(maxWidth: 220)
                }
                Toggle("Sonar con el modo silencio",
                       isOn: Binding(get: { gameplay.data.playsInSilentMode },
                                     set: { v in gameplay.update { $0.playsInSilentMode = v } }))
            } footer: {
                Text(gameplay.data.playsInSilentMode
                     ? "El juego suena aunque el iPhone esté en silencio."
                     : "Con el interruptor de silencio activado el juego no suena, y se mezcla con la música de otras apps.")
            }
            Section {
                LabeledContent("En segundo plano", value: "Pausa")
            } footer: {
                Text("Al salir de PocketGB el juego se pausa y la partida se guarda. No hay audio en segundo plano.")
            }
        }
        .scrollContentBackground(.hidden)
        .background(PocketColor.backgroundBase.ignoresSafeArea())
        .navigationTitle("Audio")
    }
}

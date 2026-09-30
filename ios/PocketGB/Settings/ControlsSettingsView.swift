import SwiftUI

/// Ajustes › Controles (SPEC §9, `settings-controls`): opacidad, tamaño, visibilidad,
/// háptica y disposición. La opacidad es solo visual: el área táctil no cambia.
struct ControlsSettingsView: View {
    @Environment(AppState.self) private var state

    private var gameplay: GameplaySettings { state.gameplay }

    var body: some View {
        Form {
            Section {
                Picker("Opacidad", selection: binding(\.opacity)) {
                    ForEach(GameplaySettingsData.opacities, id: \.self) { Text("\($0) %").tag($0) }
                }
                .pickerStyle(.segmented)
            } header: {
                Text("Opacidad en horizontal")
            } footer: {
                Text("Cambia solo el aspecto sobre el juego: cada control conserva su área táctil y su sombra para leerse sobre escenas claras.")
            }
            Section("Controles") {
                Picker("Tamaño", selection: binding(\.sizeScale)) {
                    ForEach(GameplaySettingsData.sizeScales, id: \.value) { Text($0.title).tag($0.value) }
                }
                Picker("Mostrar", selection: binding(\.visibility)) {
                    ForEach(ControlsVisibility.allCases, id: \.self) { Text($0.title).tag($0) }
                }
                Toggle("Háptica", isOn: binding(\.haptics))
            }
            Section {
                ForEach(ControlsOrientation.allCases, id: \.self) { orientation in
                    Button("Restablecer disposición \(orientation.title.lowercased())",
                           systemImage: "arrow.counterclockwise") {
                        gameplay.resetLayout(orientation)
                    }
                    .disabled(gameplay.data.layout(orientation) == .defaults(orientation))
                }
            } header: {
                Text("Disposición")
            } footer: {
                Text("Para mover los controles, abre el menú del juego (…) y elige “Personalizar controles”. Vertical y horizontal se guardan por separado.")
            }
        }
        .scrollContentBackground(.hidden)
        .background(PocketColor.backgroundBase.ignoresSafeArea())
        .navigationTitle("Controles")
    }

    private func binding<T>(_ path: WritableKeyPath<GameplaySettingsData, T>) -> Binding<T> {
        Binding(get: { gameplay.data[keyPath: path] },
                set: { value in gameplay.update { $0[keyPath: path] = value } })
    }
}

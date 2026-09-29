import SwiftUI

/// Preferencia de apariencia de la app (SPEC §9, `settings-appearance`).
enum AppearancePreference: String, CaseIterable, Identifiable {
    case system, light, dark

    static let storageKey = "appearance"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .system: "Sistema"
        case .light: "Claro"
        case .dark: "Oscuro"
        }
    }

    /// nil = seguir al sistema.
    var colorScheme: ColorScheme? {
        switch self {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }
}

struct AppearanceSettingsView: View {
    @AppStorage(AppearancePreference.storageKey) private var appearance = AppearancePreference.system.rawValue

    var body: some View {
        Form {
            Section {
                Picker("Apariencia", selection: $appearance) {
                    ForEach(AppearancePreference.allCases) { option in
                        Text(option.title).tag(option.rawValue)
                    }
                }
                .pickerStyle(.inline)
                .labelsHidden()
            } header: {
                Text("Biblioteca y ajustes")
            } footer: {
                Text("El juego siempre se muestra sobre fondo oscuro, sea cual sea el modo, para no deslumbrar ni restar contraste a los controles.")
            }
        }
        .scrollContentBackground(.hidden)
        .background(PocketColor.backgroundBase.ignoresSafeArea())
        .navigationTitle("Apariencia")
        .navigationBarTitleDisplayMode(.inline)
    }
}

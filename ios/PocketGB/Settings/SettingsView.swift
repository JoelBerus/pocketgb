import SwiftUI

/// Tab Ajustes: lista nativa agrupada (SPEC §4, `settings-main`). Las secciones que
/// llegan en hitos posteriores (D4–D6) se muestran como "Próximamente", sin navegar.
struct SettingsView: View {
    @Environment(AppState.self) private var state

    var body: some View {
        @Bindable var state = state
        NavigationStack(path: $state.settingsPath) {
            Form {
                Section("Juego") {
                    PendingRow(title: "Emulación", systemImage: "cpu")
                    NavigationLink(value: SettingsRoute.controls) {
                        Label("Controles", systemImage: "gamecontroller")
                    }
                    PendingRow(title: "Audio", systemImage: "speaker.wave.2")
                    NavigationLink(value: SettingsRoute.display) {
                        Label("Pantalla", systemImage: "rectangle.on.rectangle")
                    }
                }
                Section("Biblioteca y partidas") {
                    NavigationLink(value: SettingsRoute.library) {
                        Label("Biblioteca", systemImage: "folder")
                    }
                    NavigationLink(value: SettingsRoute.saves) {
                        Label("Partidas", systemImage: "externaldrive")
                    }
                    PendingRow(title: "Almacenamiento", systemImage: "internaldrive")
                }
                Section {
                    NavigationLink(value: SettingsRoute.appearance) {
                        Label("Apariencia", systemImage: "circle.lefthalf.filled")
                    }
                    NavigationLink(value: SettingsRoute.about) {
                        Label("Acerca de", systemImage: "info.circle")
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(PocketColor.backgroundBase.ignoresSafeArea())
            .navigationTitle("Ajustes")
            .navigationDestination(for: SettingsRoute.self) { route in
                switch route {
                case .appearance: AppearanceSettingsView()
                case .about: AboutView()
                case .licenses: LicensesView()
                case .saves: SavesSettingsView()
                case .library: LibrarySettingsView()
                case .controls: ControlsSettingsView()
                case .display: DisplaySettingsView()
                case .backups(let fingerprint): SaveBackupsView(fingerprint: fingerprint)
                }
            }
        }
    }
}

/// Fila de una sección que aún no existe: se ve y se lee, pero no navega.
private struct PendingRow: View {
    let title: String
    let systemImage: String

    var body: some View {
        LabeledContent {
            Text("Próximamente")
        } label: {
            Label(title, systemImage: systemImage)
        }
        .foregroundStyle(.secondary)
        .accessibilityElement(children: .combine)
    }
}

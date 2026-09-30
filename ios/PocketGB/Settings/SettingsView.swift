import SwiftUI

/// Tab Ajustes: lista nativa agrupada (SPEC §4, `settings-main`).
struct SettingsView: View {
    @Environment(AppState.self) private var state

    var body: some View {
        @Bindable var state = state
        NavigationStack(path: $state.settingsPath) {
            Form {
                Section("Juego") {
                    NavigationLink(value: SettingsRoute.emulation) {
                        Label("Emulación", systemImage: "cpu")
                    }
                    NavigationLink(value: SettingsRoute.controls) {
                        Label("Controles", systemImage: "gamecontroller")
                    }
                    NavigationLink(value: SettingsRoute.audio) {
                        Label("Audio", systemImage: "speaker.wave.2")
                    }
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
                    NavigationLink(value: SettingsRoute.storage) {
                        Label("Almacenamiento", systemImage: "internaldrive")
                    }
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
                case .emulation: EmulationSettingsView()
                case .audio: AudioSettingsView()
                case .storage: StorageSettingsView()
                case .backups(let fingerprint): SaveBackupsView(fingerprint: fingerprint)
                }
            }
        }
    }
}

import SwiftUI

/// Uso de disco de PocketGB por tipo (D6). Todo está en `Application Support`; los ROMs
/// están en la carpeta del usuario y nunca se cuentan ni se borran desde aquí.
struct StorageUsage: Equatable, Sendable {
    var saves: Int64 = 0
    var states: Int64 = 0
    var artwork: Int64 = 0

    /// Tamaño de todos los archivos bajo `url` (0 si no existe).
    static func size(of url: URL) -> Int64 {
        guard let items = FileManager.default.enumerator(at: url, includingPropertiesForKeys: [.fileSizeKey]) else {
            return 0
        }
        var total: Int64 = 0
        for case let file as URL in items {
            total += Int64((try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        }
        return total
    }

    static func measure(saves: URL?, states: URL?, artwork: URL?) -> StorageUsage {
        StorageUsage(saves: saves.map(size) ?? 0, states: states.map(size) ?? 0, artwork: artwork.map(size) ?? 0)
    }
}

/// Ajustes › Almacenamiento (SPEC §9, `settings-storage`): partidas, estados y portadas por
/// separado. Solo se ofrece borrar las portadas (se regeneran al jugar). Sin opción de
/// borrar ROMs.
struct StorageSettingsView: View {
    @Environment(AppState.self) private var state
    @State private var usage: StorageUsage?
    @State private var confirmClear = false

    var body: some View {
        Form {
            Section {
                row("Partidas y copias", "externaldrive", usage?.saves)
                row("Estados guardados", "square.stack", usage?.states)
                row("Portadas", "photo", usage?.artwork)
            } header: {
                Text("En este iPhone")
            } footer: {
                Text("Los juegos (ROMs) están en tu carpeta de iCloud Drive o Archivos y PocketGB no los copia, así que no ocupan espacio aquí. La copia de cada partida junto al ROM tampoco se cuenta.")
            }
            Section {
                Button("Borrar portadas", systemImage: "trash", role: .destructive) { confirmClear = true }
                    .disabled((usage?.artwork ?? 0) == 0)
            } footer: {
                Text("Las portadas son capturas del último momento jugado; se vuelven a crear al jugar. Borrarlas no toca partidas ni estados.")
            }
        }
        .scrollContentBackground(.hidden)
        .background(PocketColor.backgroundBase.ignoresSafeArea())
        .navigationTitle("Almacenamiento")
        .task { await refresh() }
        .alert("¿Borrar las portadas?", isPresented: $confirmClear) {
            Button("Borrar portadas", role: .destructive) {
                state.artwork.removeAll()
                Task { await refresh() }
            }
            Button("Cancelar", role: .cancel) {}
        } message: {
            Text("Las partidas, las copias y los estados se conservan.")
        }
    }

    private func row(_ title: String, _ symbol: String, _ bytes: Int64?) -> some View {
        LabeledContent {
            if let bytes {
                Text(ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)).monospacedDigit()
            } else {
                ProgressView()
            }
        } label: {
            Label(title, systemImage: symbol)
        }
    }

    private func refresh() async {
        let dirs = state.storageDirectories
        usage = await Task.detached(priority: .utility) {
            StorageUsage.measure(saves: dirs.saves, states: dirs.states, artwork: dirs.artwork)
        }.value
    }
}

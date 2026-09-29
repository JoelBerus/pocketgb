import SwiftUI

/// Ajustes › Biblioteca (SPEC §9, `settings-library`): carpeta, vista, orden, escaneo y
/// juegos ocultos. Sin portadas por red.
struct LibrarySettingsView: View {
    @Environment(AppState.self) private var state

    private var prefs: LibraryPreferences { state.libraryPrefs }

    var body: some View {
        let hidden = state.library.entries.filter { prefs.isHidden($0) }
        Form {
            Section {
                LabeledContent("Carpeta") {
                    Text(folderName ?? "Sin elegir")
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
                Button("Cambiar carpeta", systemImage: "folder") { state.chooseFolder() }
                if case .ready = state.library.phase {
                    Button("Volver a escanear", systemImage: "arrow.clockwise") { state.library.refresh() }
                        .disabled(state.library.isScanning)
                }
            } header: {
                Text("Carpeta de juegos")
            } footer: {
                Text("PocketGB lee los ROMs de esta carpeta sin copiarlos ni modificarlos.")
            }
            Section("Presentación") {
                Picker("Vista", selection: Binding(get: { prefs.data.layout }, set: { prefs.setLayout($0) })) {
                    ForEach(LibraryLayout.allCases, id: \.self) { Text($0.title).tag($0) }
                }
                .pickerStyle(.segmented)
                Picker("Ordenar por", selection: Binding(get: { prefs.data.sort }, set: { prefs.setSort($0) })) {
                    ForEach(LibrarySort.allCases, id: \.self) { Text($0.title).tag($0) }
                }
            }
            Section {
                if hidden.isEmpty {
                    Text("Ninguno")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(hidden) { entry in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(entry.title)
                                    .lineLimit(2)
                                Text(entry.fileName)
                                    .font(.footnote)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                            }
                            Spacer()
                            Button("Mostrar") { prefs.unhide(entry) }
                                .buttonStyle(.borderless)
                                .accessibilityLabel("Mostrar \(entry.title)")
                        }
                    }
                }
            } header: {
                Text("Juegos ocultos")
            } footer: {
                Text("Ocultar un juego no borra el ROM, ni la partida, ni sus copias.")
            }
        }
        .scrollContentBackground(.hidden)
        .background(PocketColor.backgroundBase.ignoresSafeArea())
        .navigationTitle("Biblioteca")
    }

    private var folderName: String? {
        switch state.library.phase {
        case .noFolder: nil
        case .unavailable(let name): name
        case .ready(let name): name
        }
    }
}

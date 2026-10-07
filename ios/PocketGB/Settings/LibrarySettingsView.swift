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
                if state.library.isHashing {
                    // N1a: reconocer cada juego por su contenido (huella) en segundo plano.
                    HStack(spacing: PocketSpacing.sm) {
                        ProgressView()
                        Text("Reconociendo juegos nuevos o movidos…")
                            .foregroundStyle(.secondary)
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("library-hashing")
                }
                if state.library.limitReached {
                    Label("La carpeta es demasiado grande y no se leyó entera: más de \(LibraryScanner.maxEntries.formatted()) juegos o \(LibraryScanner.maxVisitedItems.formatted()) archivos y carpetas. Aparta lo que no uses en carpetas que empiecen por “_”.",
                          systemImage: "exclamationmark.triangle")
                        .foregroundStyle(PocketColor.danger)
                        .accessibilityIdentifier("library-limit-reached")
                }
            } header: {
                Text("Carpeta de juegos")
            } footer: {
                Text("PocketGB lee los ROMs de esta carpeta y de sus subcarpetas (hasta 5 niveles) sin copiarlos ni modificarlos. Las carpetas que empiezan por “_” o “.” y la carpeta “PocketGB” no se leen.")
            }
            // N4: estanterías del inicio (orden, fijadas, ocultas) y fila de Favoritos, por dispositivo.
            Section {
                NavigationLink(value: SettingsRoute.libraryHome) {
                    LabeledContent {
                        Text(homeSummary)
                    } label: {
                        Label("Inicio", systemImage: "rectangle.stack")
                    }
                }
                .accessibilityIdentifier("settings-library-home")
            } footer: {
                Text("Qué categorías salen en el inicio de la biblioteca y en qué orden. Solo en este iPhone.")
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
                                Text(entry.locationText)
                                    .font(.footnote)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(2)
                                    .truncationMode(.middle)
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

    private var homeSummary: String {
        let home = prefs.data.home
        let hidden = LibraryHome.arrangement(state.library.entries, prefs: prefs.data).filter(\.hidden).count
        if home.isDefault { return "Todas" }
        return hidden == 0 ? "Personalizado" : (hidden == 1 ? "1 oculta" : "\(hidden) ocultas")
    }

    private var folderName: String? {
        switch state.library.phase {
        case .noFolder: nil
        case .unavailable(let name): name
        case .ready(let name): name
        }
    }
}

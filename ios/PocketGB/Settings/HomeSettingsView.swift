import SwiftUI

/// N4 · Ajustes › Biblioteca › Inicio (por dispositivo, ND12): la fila de Favoritos y, por categoría
/// de primer nivel, fijarla arriba, subirla o bajarla (dentro de las fijadas o de las demás) y
/// mostrarla u ocultarla en el inicio. Una categoría oculta sigue en «Todos los juegos» y en su pantalla.
struct HomeSettingsView: View {
    @Environment(AppState.self) private var state
    @Environment(\.dynamicTypeSize) private var typeSize

    private var prefs: LibraryPreferences { state.libraryPrefs }

    var body: some View {
        let entries = state.library.entries
        let rows = LibraryHome.arrangement(entries, prefs: prefs.data)
        let keys = LibraryHome.keys(entries, prefs: prefs.data)
        Form {
            Section {
                Toggle("Fila de Favoritos", isOn: Binding(get: { prefs.data.home.showFavorites },
                                                          set: { v in prefs.updateHome { $0.showFavorites = v } }))
                    .accessibilityIdentifier("home-settings-favorites")
            } footer: {
                Text("Tus favoritos en una fila, entre «Continuar jugando» y las categorías.")
            }
            Section {
                if rows.isEmpty {
                    Text("Tu carpeta aún no tiene categorías.")
                        .foregroundStyle(.secondary)
                } else if !LibraryCategory.hasFolders(rows.map { CategoryOption(category: $0.category, count: $0.count) }) {
                    Text("Tu carpeta no tiene subcarpetas: el inicio no muestra estanterías (repetirían «Todos los juegos»).")
                        .foregroundStyle(.secondary)
                }
                ForEach(rows) { row in
                    HomeCategorySettingsRow(row: row, rows: rows, keys: keys)
                }
            } header: {
                Text("Categorías en el inicio")
            } footer: {
                Text("En el orden en que salen. Las fijadas van siempre primero; las flechas mueven una categoría dentro de su grupo. Una carpeta nueva aparece al final.")
            }
            if !prefs.data.home.isDefault {
                Section {
                    Button("Restablecer el inicio", systemImage: "arrow.counterclockwise") {
                        prefs.updateHome { $0 = HomeSettings() }
                    }
                    .accessibilityIdentifier("home-settings-reset")
                } footer: {
                    Text("Orden alfabético, con «Sin categoría» al final, todas a la vista y la fila de Favoritos.")
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(PocketColor.backgroundBase.ignoresSafeArea())
        .navigationTitle("Inicio")
        .accessibilityIdentifier("home-settings")
    }
}

/// Una categoría: nombre y número de juegos; fijar, subir, bajar y «En el inicio». Con texto grande
/// los controles van bajo el nombre.
private struct HomeCategorySettingsRow: View {
    @Environment(AppState.self) private var state
    let row: HomeCategoryRow
    let rows: [HomeCategoryRow]
    let keys: [String]

    private var prefs: LibraryPreferences { state.libraryPrefs }

    /// Puede subir o bajar dentro de su grupo (fijadas o no).
    private func canMove(_ offset: Int) -> Bool {
        let group = rows.filter { $0.pinned == row.pinned }
        guard let index = group.firstIndex(where: { $0.key == row.key }) else { return false }
        return group.indices.contains(index + offset)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: PocketSpacing.xxs) {
            label
            ViewThatFits(in: .horizontal) {
                HStack(spacing: PocketSpacing.xs) {
                    arrangeButtons
                    Spacer(minLength: PocketSpacing.xs)
                    shownToggle.fixedSize()
                }
                VStack(alignment: .leading, spacing: PocketSpacing.xs) {
                    HStack(spacing: PocketSpacing.xs) { arrangeButtons }
                    shownToggle
                }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("home-settings-row-\(row.key)")
    }

    private var label: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: PocketSpacing.xxs) {
                Image(systemName: row.category.systemImage)
                    .foregroundStyle(PocketColor.accent)
                    .accessibilityHidden(true)
                Text(row.category.title)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Text(subtitle)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .opacity(row.hidden ? 0.6 : 1)
        .accessibilityElement(children: .combine)
    }

    private var subtitle: String {
        var parts = [row.count == 1 ? "1 juego" : "\(row.count) juegos"]
        if row.pinned { parts.append("Fijada arriba") }
        if row.hidden { parts.append("Oculta") }
        return parts.joined(separator: " · ")
    }

    @ViewBuilder private var arrangeButtons: some View {
        Button {
            prefs.updateHome { $0 = $0.pinning(row.key, !row.pinned) }
        } label: {
            Image(systemName: row.pinned ? "pin.slash" : "pin")
                .frame(minWidth: PocketSpacing.minTouch, minHeight: PocketSpacing.minTouch)
        }
        .buttonStyle(.borderless)
        .accessibilityLabel(row.pinned ? "Soltar \(row.category.title)" : "Fijar \(row.category.title) arriba")
        .accessibilityIdentifier("home-settings-pin-\(row.key)")
        Button {
            prefs.updateHome { $0 = $0.moving(row.key, by: -1, keys: keys) }
        } label: {
            Image(systemName: "chevron.up")
                .frame(minWidth: PocketSpacing.minTouch, minHeight: PocketSpacing.minTouch)
        }
        .buttonStyle(.borderless)
        .disabled(!canMove(-1))
        .accessibilityLabel("Subir \(row.category.title)")
        .accessibilityIdentifier("home-settings-up-\(row.key)")
        Button {
            prefs.updateHome { $0 = $0.moving(row.key, by: 1, keys: keys) }
        } label: {
            Image(systemName: "chevron.down")
                .frame(minWidth: PocketSpacing.minTouch, minHeight: PocketSpacing.minTouch)
        }
        .buttonStyle(.borderless)
        .disabled(!canMove(1))
        .accessibilityLabel("Bajar \(row.category.title)")
        .accessibilityIdentifier("home-settings-down-\(row.key)")
    }

    private var shownToggle: some View {
        Toggle(isOn: Binding(get: { !row.hidden },
                             set: { v in prefs.updateHome { $0 = $0.hiding(row.key, !v) } })) {
            Text("En el inicio")
                .font(.subheadline)
        }
        .accessibilityLabel("\(row.category.title) en el inicio")
        .accessibilityIdentifier("home-settings-shown-\(row.key)")
    }
}

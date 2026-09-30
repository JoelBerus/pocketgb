import SwiftUI

/// Ajustes › Partidas: juegos con partida local (docs/04 §Restaurar).
struct SavesSettingsView: View {
    @Environment(AppState.self) private var state
    @State private var games: [(fingerprint: String, record: SavesIndex.Record?)] = []
    @State private var loaded = false

    var body: some View {
        Form {
            Section {
                if loaded && games.isEmpty {
                    Text("Todavía no hay partidas. Se guardan solas cuando el juego guarda.")
                        .foregroundStyle(.secondary)
                }
                ForEach(games, id: \.fingerprint) { game in
                    NavigationLink(value: SettingsRoute.backups(fingerprint: game.fingerprint)) {
                        VStack(alignment: .leading, spacing: PocketSpacing.xxs) {
                            Text(game.record?.title ?? "Juego sin nombre")
                            Text(game.record?.fileName ?? game.fingerprint)
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                    }
                }
            } footer: {
                Text("La partida de cada juego se guarda en este iPhone y se copia junto al ROM. PocketGB conserva las 5 versiones anteriores.")
            }
        }
        .scrollContentBackground(.hidden)
        .background(PocketColor.backgroundBase.ignoresSafeArea())
        .navigationTitle("Partidas")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            if let dir = state.storageDirectories.saves {
                games = SavesIndex(directory: dir).savedGames()
            }
            loaded = true
        }
    }
}

/// Backups de un juego, con fecha, y restauración que respalda antes la partida actual.
struct SaveBackupsView: View {
    let fingerprint: String
    @State private var backups: [(index: Int, date: Date?)] = []
    @State private var currentDate: Date?
    @State private var title = ""
    @State private var pending: Int?
    @State private var message: String?

    var body: some View {
        Form {
            Section("Partida actual") {
                LabeledContent("Guardada", value: currentDate.map(Self.format) ?? "—")
            }
            Section {
                if backups.isEmpty {
                    Text("Sin copias anteriores todavía.")
                        .foregroundStyle(.secondary)
                }
                ForEach(backups, id: \.index) { backup in
                    HStack {
                        VStack(alignment: .leading, spacing: PocketSpacing.xxs) {
                            Text(backup.index == 1 ? "Copia más reciente" : "Copia \(backup.index)")
                            Text(backup.date.map(Self.format) ?? "Fecha desconocida")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button("Restaurar") { pending = backup.index }
                            .pocketGlassButton()
                    }
                }
            } header: {
                Text("Copias anteriores")
            } footer: {
                Text("Restaurar no borra nada: la partida actual se guarda antes como copia.")
            }
        }
        .scrollContentBackground(.hidden)
        .background(PocketColor.backgroundBase.ignoresSafeArea())
        .navigationTitle(title.isEmpty ? "Copias" : title)
        .navigationBarTitleDisplayMode(.inline)
        .confirmationDialog("¿Restaurar esta copia?", isPresented: Binding(get: { pending != nil },
                                                                             set: { if !$0 { pending = nil } }),
                            titleVisibility: .visible) {
            Button("Restaurar copia") {
                if let n = pending { restore(n) }
            }
            Button("Cancelar", role: .cancel) {}
        } message: {
            Text("La partida actual se guardará como copia antes de restaurar, así que podrás volver a ella.")
        }
        .alert("Partidas", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(message ?? "")
        }
        .task { reload() }
    }

    private static func format(_ date: Date) -> String {
        date.formatted(date: .abbreviated, time: .shortened)
    }

    private func store() -> SaveStore? {
        (try? SaveStore.defaultDirectory()).map { SaveStore(directory: $0, fingerprint: fingerprint) }
    }

    private func reload() {
        guard let store = store() else { return }
        backups = store.backups()
        currentDate = store.modificationDate
        title = SavesIndex(directory: store.directory).load()[fingerprint]?.title ?? ""
    }

    private func restore(_ n: Int) {
        guard let store = store() else { return }
        do {
            try store.restore(backup: n)
            message = "Copia restaurada. La partida anterior quedó como copia más reciente."
        } catch {
            message = "No se pudo restaurar: \(error.localizedDescription). No se ha cambiado nada."
        }
        reload()
    }
}

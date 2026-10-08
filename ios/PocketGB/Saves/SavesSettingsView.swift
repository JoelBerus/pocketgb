import SwiftUI

/// Ajustes › Partidas: juegos con partida local (docs/04 §Restaurar).
struct SavesSettingsView: View {
    @Environment(AppState.self) private var state
    @State private var games: [(fingerprint: String, record: SavesIndex.Record?)] = []
    @State private var loaded = false
    /// N7a · copias en conflicto del proveedor junto a cada `.sav` (solo se listan).
    @State private var conflicts: [(title: String, url: URL)] = []

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
            if !conflicts.isEmpty {
                Section {
                    ForEach(conflicts, id: \.url) { copy in
                        VStack(alignment: .leading, spacing: PocketSpacing.xxs) {
                            Text(copy.url.lastPathComponent)
                            Text(copy.title)
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                        .accessibilityElement(children: .combine)
                        .accessibilityIdentifier("saves-conflict-copy")
                    }
                } header: {
                    Text("Copias en conflicto")
                } footer: {
                    Text("iCloud, Drive u otras apps crearon estas copias del .sav junto al juego. PocketGB nunca las borra. Para usar una, ábrela con PocketGB desde Archivos o impórtala desde el detalle del juego.")
                }
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
            let roms = state.library.entries.map { (state.libraryPrefs.displayTitle($0), $0.url) }
            conflicts = await Task.detached(priority: .utility) {
                roms.flatMap { title, url in ConflictCopies.scan(romURL: url).map { (title: title, url: $0) } }
            }.value
            #if DEBUG
            if let demo = DebugScreenRouter.demoConflictCopies { conflicts = demo }
            #endif
        }
    }
}

/// Backups de un juego, con fecha, y restauración que respalda antes la partida actual.
struct SaveBackupsView: View {
    @Environment(AppState.self) private var state
    let fingerprint: String
    @State private var backups: [(index: Int, date: Date?)] = []
    /// Partidas apartadas al abrir el juego (no rotan; auditoría N1, H1).
    @State private var kept: [SaveStore.KeptCopy] = []
    @State private var currentDate: Date?
    @State private var title = ""
    @State private var pending: Int?
    @State private var pendingKept: SaveStore.KeptCopy?
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
            if !kept.isEmpty {
                Section {
                    ForEach(kept, id: \.url) { copy in
                        HStack {
                            VStack(alignment: .leading, spacing: PocketSpacing.xxs) {
                                Text("Apartada al abrir el juego")
                                Text(copy.date.map(Self.format) ?? "Fecha desconocida")
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Button("Restaurar") { pendingKept = copy }
                                .pocketGlassButton()
                        }
                        .accessibilityIdentifier("save-kept-copy")
                    }
                } header: {
                    Text("Copias apartadas")
                } footer: {
                    Text("Partidas que perdieron frente a otra al abrir el juego, por ejemplo el .sav de otra copia del mismo juego en otra carpeta. No se borran ni se sustituyen con el tiempo.")
                }
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
        .confirmationDialog("¿Restaurar la copia apartada?",
                            isPresented: Binding(get: { pendingKept != nil }, set: { if !$0 { pendingKept = nil } }),
                            titleVisibility: .visible) {
            Button("Restaurar copia") {
                if let copy = pendingKept { restore(copy) }
            }
            Button("Cancelar", role: .cancel) {}
        } message: {
            Text("La partida actual se guardará como copia antes de restaurar y la copia apartada se conserva.")
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
        state.storageDirectories.saves.map { SaveStore(directory: $0, fingerprint: fingerprint) }
    }

    private func reload() {
        guard let store = store() else { return }
        backups = store.backups()
        kept = store.keptCopies()
        currentDate = store.modificationDate
        title = SavesIndex(directory: store.directory).load()[fingerprint]?.title ?? ""
    }

    private func restore(_ n: Int) {
        guard let store = store() else { return }
        do {
            try store.restore(backup: n)
            state.didRestoreSave(fingerprint: fingerprint)
            message = "Copia restaurada. La partida anterior quedó como copia más reciente."
        } catch {
            message = "No se pudo restaurar: \(error.localizedDescription). No se ha cambiado nada."
        }
        reload()
    }

    private func restore(_ copy: SaveStore.KeptCopy) {
        guard let store = store() else { return }
        do {
            try store.restore(kept: copy)
            state.didRestoreSave(fingerprint: fingerprint)
            message = "Copia restaurada. La partida anterior quedó como copia más reciente."
        } catch {
            message = "No se pudo restaurar: \(error.localizedDescription). No se ha cambiado nada."
        }
        reload()
    }
}

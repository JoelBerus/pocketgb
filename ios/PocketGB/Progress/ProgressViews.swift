import SwiftUI

/// Textos cortos del progreso.
enum ProgressSummary {
    /// «12 h 05 min · 4 sesiones · 3/9 hitos» (o «Sin jugar todavía»).
    static func line(_ p: GameProgress) -> String {
        var parts: [String] = []
        if p.sessions > 0 || p.playTime > 0 {
            parts.append(PlayTimeFormat.text(p.playTime))
            parts.append(p.sessions == 1 ? "1 sesión" : "\(p.sessions) sesiones")
        }
        if !p.milestones.isEmpty {
            parts.append("\(p.milestones.filter(\.done).count)/\(p.milestones.count) hitos")
        }
        return parts.isEmpty ? "Sin jugar todavía" : parts.joined(separator: " · ")
    }

    static func date(_ date: Date?) -> String {
        guard let date else { return "—" }
        return GameStatus.stateDate(date)
    }
}

/// Lee el lector Pokémon fuera del hilo principal: la cabecera guardada al abrir el juego y la partida local.
enum PokemonReader {
    static func read(fingerprint: String, header: Data?, savesDirectory: URL?) async -> PokemonProgress? {
        guard let header, PokemonProgress.isSupported(header: header), let savesDirectory else { return nil }
        return await Task.detached(priority: .utility) {
            let store = SaveStore(directory: savesDirectory, fingerprint: fingerprint)
            guard let sram = try? store.load() else { return nil }
            return PokemonProgress.read(header: header, sram: sram)
        }.value
    }
}

/// Panel «Leído de la partida» (ND5): jugador, medallas, Pokédex, tiempo y dinero. Solo informativo.
struct PokemonPanel: View {
    let pokemon: PokemonProgress

    var body: some View {
        VStack(alignment: .leading, spacing: PocketSpacing.xxs) {
            Label("Leído de la partida", systemImage: "sparkle.magnifyingglass")
                .font(.subheadline.weight(.semibold))
            row("Jugador", pokemon.playerName)
            row("Medallas", "\(pokemon.badgesCount) de \(pokemon.badgesTotal)")
            row("Pokédex", "\(pokemon.pokedexOwned) capturados · \(pokemon.pokedexSeen) vistos")
            row("Tiempo en el juego", "\(pokemon.hours) h \(String(format: "%02d", pokemon.minutes)) min")
            row("Dinero", "₽ " + pokemon.money.formatted(.number.grouping(.automatic).locale(Locale(identifier: "es_ES"))))
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("pokemon-panel")
    }

    private func row(_ title: String, _ value: String) -> some View {
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .firstTextBaseline) {
                Text(title).foregroundStyle(.secondary)
                Spacer()
                Text(value).multilineTextAlignment(.trailing)
            }
            VStack(alignment: .leading, spacing: 0) {
                Text(title).foregroundStyle(.secondary)
                Text(value)
            }
        }
        .font(.subheadline)
    }
}

/// N6 · bloque «Progreso» del detalle del juego: tiempo, sesiones, primera y última vez, hitos (y porcentaje si se
/// activó) y el panel del lector Pokémon. «Editar» abre el progreso en el centro de ajustes.
struct GameProgressSection: View {
    @Environment(AppState.self) private var state
    let entry: RomEntry
    let fingerprint: String
    @State private var pokemon: PokemonProgress?

    var body: some View {
        let p = state.progress.progress(fingerprint)
        VStack(alignment: .leading, spacing: PocketSpacing.xs) {
            HStack {
                Label("Progreso", systemImage: "flag.checkered").font(.headline)
                Spacer()
                if let percent = p.visiblePercent {
                    Text("\(percent) %").font(.headline.monospacedDigit()).foregroundStyle(PocketColor.accent)
                        .accessibilityLabel("\(percent) por ciento")
                }
            }
            ViewThatFits(in: .horizontal) {
                Grid(alignment: .leading, horizontalSpacing: PocketSpacing.md, verticalSpacing: PocketSpacing.xs) {
                    GridRow { stat("Tiempo", PlayTimeFormat.text(p.playTime)); stat("Sesiones", "\(p.sessions)") }
                    GridRow { stat("Primera vez", ProgressSummary.date(p.firstPlayed)); stat("Última vez", ProgressSummary.date(p.lastPlayed)) }
                }
                .fixedSize(horizontal: true, vertical: false)
                VStack(alignment: .leading, spacing: PocketSpacing.xs) { stats(p) }
            }
            if !p.milestones.isEmpty {
                Text("\(p.milestones.filter(\.done).count) de \(p.milestones.count) hitos")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            if let pokemon { PokemonPanel(pokemon: pokemon).padding(.top, PocketSpacing.xxs) }
            HStack(spacing: PocketSpacing.xs) {
                Button("Momentos", systemImage: "bookmark") { state.showGameCenter(entry, at: .moments(fingerprint: fingerprint)) }
                    .accessibilityIdentifier("game-details-moments")
                Button("Hitos", systemImage: "checklist") { state.showGameCenter(entry, at: .progress(fingerprint: fingerprint)) }
                    .accessibilityIdentifier("game-details-progress")
            }
            .labelStyle(.titleAndIcon)
            .font(.subheadline)
            .pocketGlassButton()
        }
        .padding(PocketSpacing.md)
        .background(PocketColor.backgroundElevated, in: RoundedRectangle(cornerRadius: PocketRadius.group, style: .continuous))
        .task(id: "\(fingerprint)-\(state.momentsRevision)-\(p.romHeader?.count ?? 0)") {
            pokemon = await PokemonReader.read(fingerprint: fingerprint, header: p.romHeader,
                                               savesDirectory: state.storageDirectories.saves)
        }
        .accessibilityIdentifier("game-details-progress-section")
    }

    @ViewBuilder private func stats(_ p: GameProgress) -> some View {
        stat("Tiempo", PlayTimeFormat.text(p.playTime))
        stat("Sesiones", "\(p.sessions)")
        stat("Primera vez", ProgressSummary.date(p.firstPlayed))
        stat("Última vez", ProgressSummary.date(p.lastPlayed))
    }

    private func stat(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            Text(value).font(.subheadline.weight(.medium)).fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
    }
}

/// N6 · centro de ajustes › Progreso: plantilla de hitos, casillas, añadir y borrar, «Mostrar porcentaje» (desactivado
/// de serie) y, en los Pokémon, la propuesta de marcar las medallas leídas de la partida (el usuario decide).
struct ProgressEditorView: View {
    @Environment(AppState.self) private var state
    let entryID: String
    let fingerprint: String
    @State private var newTitle = ""
    @State private var pokemon: PokemonProgress?

    var body: some View {
        let p = state.progress.progress(fingerprint)
        let library = state.progress
        Form {
            Section {
                LabeledContent("Tiempo de juego", value: PlayTimeFormat.text(p.playTime))
                LabeledContent("Sesiones", value: "\(p.sessions)")
                LabeledContent("Primera vez", value: ProgressSummary.date(p.firstPlayed))
                LabeledContent("Última vez", value: ProgressSummary.date(p.lastPlayed))
            } footer: {
                Text("El tiempo solo cuenta con el juego en marcha: ni en pausa ni en segundo plano.")
            }
            if let pokemon {
                Section {
                    PokemonPanel(pokemon: pokemon)
                    let suggested = BadgeSuggestion.ids(p, pokemon: pokemon)
                    if !suggested.isEmpty {
                        Button("Marcar \(suggested.count == 1 ? "1 medalla" : "\(suggested.count) medallas")",
                               systemImage: "checkmark.seal") {
                            library.change(fingerprint) { try $0.markDone(suggested, in: fingerprint) }
                        }
                        .accessibilityIdentifier("progress-mark-badges")
                    }
                } footer: {
                    Text(p.template == .pokemon
                         ? "Solo se lee la partida guardada; si no cuadra, no se muestra nada. Tú decides si marcas las medallas."
                         : "Solo se lee la partida guardada. Con la plantilla «Pokémon» te propone marcar las medallas.")
                }
            }
            Section {
                Picker("Plantilla", selection: Binding(get: { p.template ?? .free }, set: { template in
                    library.change(fingerprint) { try $0.apply(template, to: fingerprint) }
                })) {
                    ForEach(MilestoneTemplate.allCases, id: \.self) { Text($0.title).tag($0) }
                }
                .accessibilityIdentifier("progress-template")
                Toggle("Mostrar porcentaje", isOn: Binding(get: { p.showPercent }, set: { show in
                    library.change(fingerprint) { try $0.update(fingerprint) { $0.showPercent = show } }
                }))
                .accessibilityIdentifier("progress-show-percent")
            } header: {
                Text("Hitos")
            } footer: {
                Text("Elegir una plantilla añade sus hitos y conserva los tuyos. El porcentaje se ve en la tarjeta y en el detalle.")
            }
            Section {
                ForEach(p.milestones) { m in
                    Button {
                        library.change(fingerprint) { try $0.setDone(m.id, !m.done, in: fingerprint) }
                    } label: {
                        Label {
                            Text(m.title).foregroundStyle(.primary)
                        } icon: {
                            Image(systemName: m.done ? "checkmark.square.fill" : "square")
                                .foregroundStyle(m.done ? PocketColor.accent : .secondary)
                        }
                    }
                    .accessibilityAddTraits(m.done ? [.isSelected] : [])
                    .swipeActions {
                        Button("Borrar", systemImage: "trash", role: .destructive) {
                            library.change(fingerprint) { try $0.removeMilestone(m.id, from: fingerprint) }
                        }
                    }
                }
                HStack {
                    TextField("Nuevo hito", text: $newTitle)
                        .submitLabel(.done)
                        .onSubmit(add)
                        .accessibilityIdentifier("progress-new-milestone")
                    Button("Añadir", action: add)
                        .disabled(newTitle.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            } footer: {
                if let percent = p.percent {
                    Text("Hecho: \(percent) %. Desliza un hito para borrarlo.")
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(PocketColor.backgroundBase.ignoresSafeArea())
        .navigationTitle("Progreso")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: p.romHeader?.count ?? 0) {
            pokemon = await PokemonReader.read(fingerprint: fingerprint, header: p.romHeader,
                                               savesDirectory: state.storageDirectories.saves)
        }
        .accessibilityIdentifier("progress-editor")
    }

    private func add() {
        let title = newTitle
        newTitle = ""
        state.progress.change(fingerprint) { try $0.addMilestone(title, to: fingerprint) }
    }
}

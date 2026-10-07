import SwiftUI

/// Detalle del juego (SPEC §9, `game-details`): captura o placeholder, metadatos y la
/// acción dominante para jugar. Estados y ajustes por juego llegan en D5/D6.
struct GameDetailsView: View {
    @Environment(AppState.self) private var state
    let entryID: String

    var body: some View {
        if let entry = state.library.entries.first(where: { $0.id == entryID }),
           !state.libraryPrefs.isHidden(entry) {
            content(entry)
        } else {
            ContentUnavailableView("Juego no disponible", systemImage: "questionmark.folder",
                                   description: Text("Ya no está en la carpeta o se ocultó."))
        }
    }

    private func content(_ entry: RomEntry) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: PocketSpacing.md) {
                GameArtworkView(entry: entry)
                    .frame(maxWidth: .infinity)
                    .accessibilityIdentifier("game-details-artwork")
                VStack(alignment: .leading, spacing: PocketSpacing.xs) {
                    // Carpetas y archivo (N1b): «Pokémon › 2ª generación · archivo.gb». Si no cabe en
                    // una línea junto a las insignias, va debajo y entera (nunca recortada).
                    ViewThatFits(in: .horizontal) {
                        HStack(spacing: PocketSpacing.xs) {
                            badges(entry)
                            location(entry).lineLimit(1).fixedSize()
                        }
                        VStack(alignment: .leading, spacing: PocketSpacing.xxs) {
                            HStack(spacing: PocketSpacing.xs) { badges(entry) }
                            location(entry).fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    Text(state.libraryPrefs.displayTitle(entry))
                        .font(.title2.bold())
                        .fixedSize(horizontal: false, vertical: true)
                    if entry.isDuplicate { alsoIn(entry) }
                }
                stats(entry)
                primaryAction(entry)
                linkAction(entry)
                secondaryActions(entry)
                Button(role: .destructive) {
                    state.hideCandidate = entry
                } label: {
                    Label("Ocultar de PocketGB", systemImage: "eye.slash")
                        .frame(maxWidth: .infinity, minHeight: PocketSpacing.minTouch)
                }
                .pocketGlassButton()
                Text("Ocultar no borra el ROM ni la partida.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity)
            }
            .padding(.horizontal, PocketSpacing.md)
            .padding(.bottom, PocketSpacing.xl)
        }
        .scrollEdgeEffectStyle(.soft, for: .top)
        .background(PocketColor.backgroundBase.ignoresSafeArea())
        .navigationTitle(state.libraryPrefs.displayTitle(entry))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                let favorite = state.libraryPrefs.isFavorite(entry)
                Menu {
                    Button(favorite ? "Quitar de favoritos" : "Añadir a favoritos",
                           systemImage: favorite ? "star.slash" : "star") {
                        state.libraryPrefs.toggleFavorite(entry)
                    }
                    Button("Renombrar", systemImage: "pencil") { state.renamingEntry = entry }
                } label: {
                    Label("Más opciones", systemImage: "ellipsis")
                }
            }
        }
    }

    @ViewBuilder private func badges(_ entry: RomEntry) -> some View {
        ConsoleChip(badge: entry.badge)
        if entry.isDuplicate { DuplicateBadge() }
    }

    private func location(_ entry: RomEntry) -> some View {
        Text(entry.locationText)
            .font(.subheadline)
            .foregroundStyle(.secondary)
            .accessibilityLabel("Ubicación: \(entry.locationText)")
            .accessibilityIdentifier("game-details-location")
    }

    /// Duplicados (N1a): las otras rutas con el mismo ROM. Comparten partida, estados y ajustes.
    private func alsoIn(_ entry: RomEntry) -> some View {
        let paths = entry.duplicatePaths.map(RomEntry.displayPath)
        return Label {
            Text("También en: \(paths.joined(separator: "; "))")
                .fixedSize(horizontal: false, vertical: true)
        } icon: {
            Image(systemName: "doc.on.doc")
        }
        .font(.footnote)
        .foregroundStyle(.secondary)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("game-details-also-in")
    }

    /// Última vez jugado, partida junto al ROM y tamaño, en tres columnas.
    private func stats(_ entry: RomEntry) -> some View {
        let lastPlayed = state.libraryPrefs.lastPlayed(entry)
        return HStack(alignment: .top, spacing: 0) {
            stat("Jugado", lastPlayed.map(GameStatus.relative) ?? "Nunca")
            Divider()
            stat("Partida", entry.mirrorSaveDate.map(GameStatus.relative) ?? "—")
            Divider()
            stat("Tamaño", ByteCountFormatter.string(fromByteCount: Int64(entry.sizeBytes), countStyle: .file))
        }
        .padding(.vertical, PocketSpacing.sm)
        .overlay(alignment: .top) { Divider() }
        .overlay(alignment: .bottom) { Divider() }
    }

    private func stat(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(value)
                .font(.subheadline.weight(.semibold))
                .lineLimit(2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, PocketSpacing.xs)
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder private func primaryAction(_ entry: RomEntry) -> some View {
        if entry.problem != nil {
            Label(entry.problem?.message ?? "", systemImage: "exclamationmark.triangle.fill")
                .foregroundStyle(PocketColor.danger)
        } else {
            switch entry.cloud {
            case .current:
                let resumable = state.canResume(entry)
                Button {
                    state.open(entry: entry, mode: resumable ? .resumeAutomatic : .fresh)
                } label: {
                    Label(resumable ? "Continuar" : "Jugar", systemImage: "play.fill")
                        .font(.headline)
                        .frame(maxWidth: .infinity, minHeight: PocketSpacing.minTouch)
                }
                .pocketGlassButton(prominent: true)
                .accessibilityIdentifier("game-details-play")
                if resumable {
                    // Arranca con la SRAM vigente sin cargar el estado (SPEC §4).
                    Button {
                        state.open(entry: entry, mode: .fresh)
                    } label: {
                        Label("Jugar desde el inicio", systemImage: "arrow.counterclockwise")
                            .font(.subheadline)
                            .frame(maxWidth: .infinity, minHeight: PocketSpacing.minTouch)
                    }
                    .pocketGlassButton()
                    .accessibilityIdentifier("game-details-play-from-start")
                }
            case .notDownloaded:
                Button {
                    state.library.download(entry)
                } label: {
                    Label("Descargar de iCloud", systemImage: "icloud.and.arrow.down")
                        .frame(maxWidth: .infinity, minHeight: PocketSpacing.minTouch)
                }
                .pocketGlassButton(prominent: true)
            case .downloading:
                HStack(spacing: PocketSpacing.sm) {
                    ProgressView()
                    Text("Descargando de iCloud. Podrás jugar cuando termine.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    /// Cable link (M9): solo juegos de Game Boy y Game Boy Color que se pueden jugar. Este juego
    /// será el primer lado del cable.
    @ViewBuilder private func linkAction(_ entry: RomEntry) -> some View {
        if entry.isPlayable && entry.console == .gameBoy {
            Button {
                state.linkPartnerSource = entry
            } label: {
                Label("Conectar con otro juego…", systemImage: "cable.connector")
                    .frame(maxWidth: .infinity, minHeight: PocketSpacing.minTouch)
            }
            .pocketGlassButton()
            .accessibilityIdentifier("game-details-link")
        }
    }

    /// Favorito, estados y ajustes: estos dos últimos llegan en D5/D6.
    private func secondaryActions(_ entry: RomEntry) -> some View {
        GlassEffectContainer(spacing: PocketSpacing.xs) {
            HStack(spacing: PocketSpacing.xs) {
                let favorite = state.libraryPrefs.isFavorite(entry)
                Button {
                    state.libraryPrefs.toggleFavorite(entry)
                } label: {
                    Label("Favorito", systemImage: favorite ? "star.fill" : "star")
                        .frame(maxWidth: .infinity, minHeight: PocketSpacing.minTouch)
                }
                .accessibilityLabel(favorite ? "Quitar de favoritos" : "Añadir a favoritos")
                Button {} label: {
                    Label("Estados", systemImage: "square.stack")
                        .frame(maxWidth: .infinity, minHeight: PocketSpacing.minTouch)
                }
                .disabled(true)
                .accessibilityHint("Próximamente")
                Button {
                    state.gameSettingsEntry = entry
                } label: {
                    Label("Ajustes", systemImage: "slider.horizontal.3")
                        .frame(maxWidth: .infinity, minHeight: PocketSpacing.minTouch)
                }
                .accessibilityLabel("Ajustes del juego")
            }
            .labelStyle(.titleAndIcon)
            .font(.subheadline)
            .pocketGlassButton()
        }
    }
}

/// Acciones del long press (SPEC §9, `game-context-menu`). Nunca ofrece borrar el ROM.
struct GameContextMenu: View {
    @Environment(AppState.self) private var state
    let entry: RomEntry
    let tab: AppTab

    var body: some View {
        if entry.isPlayable {
            let resumable = state.canResume(entry)
            Button(resumable ? "Continuar" : "Jugar", systemImage: "play.fill") {
                state.open(entry: entry, mode: resumable ? .resumeAutomatic : .fresh)
            }
            if resumable {
                Button("Jugar desde el inicio", systemImage: "arrow.counterclockwise") {
                    state.open(entry: entry, mode: .fresh)
                }
            }
        }
        if entry.isPlayable && entry.console == .gameBoy {
            Button("Conectar con…", systemImage: "cable.connector") { state.linkPartnerSource = entry }
        }
        Button("Ver detalle", systemImage: "info.circle") { state.showDetails(entry, in: tab) }
        let favorite = state.libraryPrefs.isFavorite(entry)
        Button(favorite ? "Quitar de favoritos" : "Añadir a favoritos",
               systemImage: favorite ? "star.slash" : "star") {
            state.libraryPrefs.toggleFavorite(entry)
        }
        Button("Renombrar", systemImage: "pencil") { state.renamingEntry = entry }
        Button("Estados (próximamente)", systemImage: "square.stack") {}
            .disabled(true)
        Button("Ajustes del juego", systemImage: "slider.horizontal.3") { state.gameSettingsEntry = entry }
        Divider()
        Button("Ocultar de PocketGB", systemImage: "eye.slash", role: .destructive) {
            state.hideCandidate = entry
        }
    }
}

/// Confirmación de ocultar (SPEC §9, `remove-game-confirm`): dice qué se conserva.
struct HideGameAlert: ViewModifier {
    @Environment(AppState.self) private var state

    func body(content: Content) -> some View {
        let candidate = state.hideCandidate
        content.alert(candidate.map { "¿Ocultar “\(state.libraryPrefs.displayTitle($0))”?" } ?? "",
                      isPresented: Binding(get: { state.hideCandidate != nil },
                                           set: { if !$0 { state.hideCandidate = nil } })) {
            Button("Ocultar", role: .destructive) {
                if let candidate { state.hide(candidate) }
            }
            Button("Cancelar", role: .cancel) {}
        } message: {
            Text("El ROM sigue en tu carpeta y no se modifica. La partida y sus copias se conservan en este iPhone y junto al ROM. Puedes volver a mostrarlo en Ajustes › Biblioteca.")
        }
    }
}

/// Alias visual local. Vacío vuelve al título del cartucho; no cambia archivos ni saves.
struct RenameGameView: View {
    @Environment(AppState.self) private var state
    @Environment(\.dismiss) private var dismiss
    let entry: RomEntry
    @State private var name = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Nombre", text: $name)
                        .textInputAutocapitalization(.words)
                        .onChange(of: name) { _, value in
                            if value.count > 80 { name = String(value.prefix(80)) }
                        }
                } footer: {
                    Text("Vacía el campo para volver a “\(entry.title)”. El archivo y las partidas no cambian.")
                }
            }
            .navigationTitle("Renombrar")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Guardar") {
                        state.libraryPrefs.setAlias(name, for: entry)
                        dismiss()
                    }
                }
            }
        }
        .task {
            let current = state.libraryPrefs.displayTitle(entry)
            name = current == entry.title ? "" : current
        }
        .presentationDetents([.medium])
    }
}

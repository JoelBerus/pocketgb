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
                    HStack(spacing: PocketSpacing.xs) {
                        ConsoleChip(isColor: entry.isColor)
                        Text(entry.subfolder.isEmpty ? entry.fileName : "\(entry.subfolder) · \(entry.fileName)")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }
                    Text(entry.title)
                        .font(.title2.bold())
                        .fixedSize(horizontal: false, vertical: true)
                }
                stats(entry)
                primaryAction(entry)
                secondaryActions(entry)
                Button(role: .destructive) {
                    state.hideCandidate = entry
                } label: {
                    Label("Ocultar de PocketGB", systemImage: "eye.slash")
                        .frame(maxWidth: .infinity, minHeight: PocketSpacing.minTouch)
                }
                .buttonStyle(.glass)
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
        .navigationTitle(entry.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                let favorite = state.libraryPrefs.isFavorite(entry)
                Button {
                    state.libraryPrefs.toggleFavorite(entry)
                } label: {
                    Label(favorite ? "Quitar de favoritos" : "Añadir a favoritos",
                          systemImage: favorite ? "star.fill" : "star")
                }
            }
        }
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
                let played = state.libraryPrefs.lastPlayed(entry) != nil || entry.mirrorSaveDate != nil
                Button {
                    state.open(entry: entry)
                } label: {
                    Label(played ? "Continuar" : "Jugar", systemImage: "play.fill")
                        .font(.headline)
                        .frame(maxWidth: .infinity, minHeight: PocketSpacing.minTouch)
                }
                .buttonStyle(.glassProminent)
                .accessibilityIdentifier("game-details-play")
            case .notDownloaded:
                Button {
                    state.library.download(entry)
                } label: {
                    Label("Descargar de iCloud", systemImage: "icloud.and.arrow.down")
                        .frame(maxWidth: .infinity, minHeight: PocketSpacing.minTouch)
                }
                .buttonStyle(.glassProminent)
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
            .buttonStyle(.glass)
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
            Button("Jugar", systemImage: "play.fill") { state.open(entry: entry) }
        }
        Button("Ver detalle", systemImage: "info.circle") { state.showDetails(entry, in: tab) }
        let favorite = state.libraryPrefs.isFavorite(entry)
        Button(favorite ? "Quitar de favoritos" : "Añadir a favoritos",
               systemImage: favorite ? "star.slash" : "star") {
            state.libraryPrefs.toggleFavorite(entry)
        }
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
        content.alert(candidate.map { "¿Ocultar “\($0.title)”?" } ?? "",
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

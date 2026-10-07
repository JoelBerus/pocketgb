import SwiftUI

/// Tab Favoritos (SPEC §9, `favorites`): carril de jugados recientemente y cuadrícula
/// de favoritos. Usa el mismo modelo que la biblioteca (ocultos fuera).
struct FavoritesView: View {
    @Environment(AppState.self) private var state
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @Namespace private var zoom

    var body: some View {
        @Bindable var state = state
        let entries = state.library.entries
        let recent = state.libraryPrefs.recent(entries)
        let favorites = state.libraryPrefs.visible(entries, filter: .favorites, query: "")
        NavigationStack(path: $state.favoritesPath) {
            ScrollView {
                if recent.isEmpty && favorites.isEmpty {
                    EmptyStateView(
                        title: "Sin favoritos todavía",
                        systemImage: "star",
                        message: "Mantén pulsado un juego de la biblioteca y elige “Añadir a favoritos”. Aquí verás también los que jugaste hace poco.")
                        .frame(maxWidth: .infinity)
                        .padding(.horizontal, PocketSpacing.md)
                        .padding(.top, PocketSpacing.xxl)
                } else {
                    VStack(alignment: .leading, spacing: PocketSpacing.xl) {
                        if !recent.isEmpty { recentRow(recent) }
                        favoritesSection(favorites)
                    }
                    .padding(.horizontal, PocketSpacing.md)
                    .padding(.bottom, PocketSpacing.xl)
                }
            }
            .scrollBounceBehavior(.basedOnSize)
            // N3a: en horizontal, título en línea y borde duro (legible sobre una captura blanca).
            .scrollEdgeEffectStyle(verticalSizeClass == .compact ? .hard : .soft, for: .top)
            .background(PocketColor.backgroundBase.ignoresSafeArea())
            .navigationTitle("Favoritos")
            .navigationBarTitleDisplayMode(verticalSizeClass == .compact ? .inline : .automatic)
            .navigationDestination(for: LibraryRoute.self) { route in
                switch route {
                case .details(let id, let source):
                    GameDetailsView(entryID: id)
                        .modifier(ZoomNavigation(sourceID: source, namespace: zoom))
                }
            }
        }
    }

    private func recentRow(_ recent: [RomEntry]) -> some View {
        VStack(alignment: .leading, spacing: PocketSpacing.sm) {
            Text("Jugados recientemente")
                .font(.headline)
            ScrollView(.horizontal) {
                HStack(alignment: .top, spacing: PocketSpacing.sm) {
                    ForEach(recent) { entry in
                        Button { state.select(entry, in: .favorites, source: "recent-\(entry.id)") } label: {
                            VStack(alignment: .leading, spacing: PocketSpacing.xxs) {
                                GameArtworkView(entry: entry, cornerRadius: PocketRadius.thumbnail, compact: true)
                                    .matchedTransitionSource(id: "recent-\(entry.id)", in: zoom)
                                Text(state.libraryPrefs.lastPlayed(entry).map(GameStatus.relative) ?? "")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                            }
                            .frame(width: 96)
                            .accessibilityElement(children: .ignore)
                            .accessibilityLabel(GameAccessibility.label(entry, prefs: state.libraryPrefs))
                        }
                        .buttonStyle(.plain)
                        .contextMenu { GameContextMenu(entry: entry, tab: .favorites) }
                    }
                }
            }
            .scrollIndicators(.hidden)
            .scrollClipDisabled()
        }
    }

    @ViewBuilder private func favoritesSection(_ favorites: [RomEntry]) -> some View {
        VStack(alignment: .leading, spacing: PocketSpacing.sm) {
            Text("Favoritos")
                .font(.headline)
            if favorites.isEmpty {
                Text("Mantén pulsado un juego y elige “Añadir a favoritos”.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            } else {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: typeSize.isAccessibilitySize ? 280 : 150),
                                             spacing: PocketSpacing.sm, alignment: .top)],
                          alignment: .leading, spacing: PocketSpacing.lg) {
                    ForEach(favorites) { entry in
                        Button { state.select(entry, in: .favorites) } label: {
                            GameCard(entry: entry, zoom: zoom)
                        }
                        .buttonStyle(.plain)
                        .contextMenu { GameContextMenu(entry: entry, tab: .favorites) }
                    }
                }
                Text("Mantén pulsado un juego para quitarlo de favoritos.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity)
            }
        }
    }
}

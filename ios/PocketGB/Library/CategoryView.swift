import SwiftUI

/// N4 · pantalla de una categoría (en la pila de la pestaña Biblioteca, restaurable): migas
/// («Biblioteca › Pokémon › 2ª generación»), subcategorías con su número de juegos y los juegos de la
/// carpeta **y de todas sus subcarpetas** (contando las categorías virtuales, ND3), en cuadrícula o
/// lista según la vista que recuerda cada categoría.
struct CategoryView: View {
    @Environment(AppState.self) private var state
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    /// `[]` = «Sin categoría» (los juegos de la raíz).
    let path: [String]
    let zoom: Namespace.ID

    private var prefs: LibraryPreferences { state.libraryPrefs }
    private var title: String { path.last ?? "Sin categoría" }

    var body: some View {
        let shown = LibraryQuery.visible(state.library.entries, prefs: prefs.data, filter: .all, query: "")
        let games = LibraryTree.games(shown, in: path, prefs: prefs.data)
        let subcategories = LibraryTree.subcategories(shown, of: path, prefs: prefs.data)
        let layout = prefs.data.layout(forCategory: path)
        let compact = verticalSizeClass == .compact
        ScrollView {
            VStack(alignment: .leading, spacing: PocketSpacing.lg) {
                CategoryBreadcrumbs(path: path)
                if games.isEmpty {
                    EmptyStateView(
                        title: "Sin juegos en «\(title)»",
                        systemImage: "folder",
                        message: "Puede que sus juegos se hayan movido, ocultado o que la carpeta ya no exista.",
                        primaryTitle: "Volver a la biblioteca",
                        primaryAction: { state.showCrumb(nil) })
                        .frame(maxWidth: .infinity)
                        .padding(.top, PocketSpacing.xl)
                } else {
                    if !subcategories.isEmpty { subcategoriesSection(subcategories) }
                    gamesSection(games, layout: layout)
                }
            }
            .padding(.horizontal, PocketSpacing.md)
            .padding(.bottom, PocketSpacing.xl)
        }
        .scrollEdgeEffectStyle(compact ? .hard : .soft, for: .top)
        .background(PocketColor.backgroundBase.ignoresSafeArea())
        .navigationTitle(title)
        .modifier(InlineTitleInCompactHeight(compact: compact))
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                let next: LibraryLayout = layout == .grid ? .list : .grid
                Button(next == .list ? "Ver como lista" : "Ver como cuadrícula", systemImage: next.systemImage) {
                    prefs.setCategoryLayout(next, for: path)
                }
                .accessibilityValue(layout.title)
                .accessibilityIdentifier("category-layout")
            }
        }
        .accessibilityIdentifier("category-screen")
    }

    // MARK: Subcategorías

    private func subcategoriesSection(_ items: [LibraryTree.Subcategory]) -> some View {
        VStack(alignment: .leading, spacing: PocketSpacing.sm) {
            Text("Subcategorías")
                .font(.headline)
                .accessibilityAddTraits(.isHeader)
            FlowLayout(spacing: PocketSpacing.xs, lineSpacing: PocketSpacing.xs) {
                ForEach(items) { item in
                    Button { state.openCategory(item.path) } label: {
                        HStack(spacing: PocketSpacing.xs) {
                            Image(systemName: "folder")
                                .foregroundStyle(PocketColor.accent)
                            Text(item.name)
                                .fixedSize(horizontal: false, vertical: true)
                            Text("\(item.count)")
                                .monospacedDigit()
                                .foregroundStyle(.secondary)
                        }
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.primary)
                        .padding(.horizontal, PocketSpacing.sm)
                        .frame(minHeight: PocketSpacing.minTouch)
                        .background(.quaternary, in: Capsule())
                        .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Subcategoría \(item.name), \(item.count == 1 ? "1 juego" : "\(item.count) juegos")")
                    .accessibilityIdentifier("category-sub-\(item.id)")
                }
            }
        }
    }

    // MARK: Juegos

    private func gamesSection(_ games: [RomEntry], layout: LibraryLayout) -> some View {
        VStack(alignment: .leading, spacing: PocketSpacing.sm) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Juegos · \(games.count)")
                    .font(.headline)
                    .accessibilityAddTraits(.isHeader)
                Text(path.isEmpty ? "En la carpeta principal" : "De esta categoría y sus subcategorías")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("category-games-title")
            if layout == .grid {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: typeSize.isAccessibilitySize ? 280 : 150),
                                             spacing: PocketSpacing.sm, alignment: .top)],
                          alignment: .leading, spacing: PocketSpacing.lg) {
                    ForEach(games) { entry in
                        Button { state.select(entry, in: .library, source: "category-\(entry.id)") } label: {
                            GameCard(entry: entry, zoom: zoom, sourceID: "category-\(entry.id)")
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("category-card-\(entry.id)")
                        .contextMenu { GameContextMenu(entry: entry, tab: .library) }
                    }
                }
            } else {
                LazyVStack(alignment: .leading, spacing: 0) {
                    ForEach(games) { entry in
                        Button { state.select(entry, in: .library, source: "category-\(entry.id)") } label: {
                            GameListItem(entry: entry, zoom: zoom, sourceID: "category-\(entry.id)")
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("category-row-\(entry.id)")
                        .contextMenu { GameContextMenu(entry: entry, tab: .library) }
                        if entry.id != games.last?.id {
                            Divider().padding(.leading, 56 + PocketSpacing.sm)
                        }
                    }
                }
            }
        }
    }
}

/// Migas de la categoría: «Biblioteca › Pokémon › 2ª generación». Cada nivel anterior se toca para
/// volver a él; el actual no es un botón. VoiceOver no lee los separadores (auditoría N4 Android, H5).
struct CategoryBreadcrumbs: View {
    @Environment(AppState.self) private var state
    let path: [String]

    var body: some View {
        let crumbs = LibraryTree.breadcrumbs(path)
        FlowLayout(spacing: PocketSpacing.xxs, lineSpacing: 0) {
            crumb("Biblioteca", target: nil, current: false)
            if path.isEmpty {
                separator
                crumb("Sin categoría", target: [], current: true)
            }
            ForEach(Array(crumbs.enumerated()), id: \.offset) { index, level in
                separator
                crumb(level.last ?? "", target: level, current: index == crumbs.count - 1)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Estás en: Biblioteca › \(CategoryPaths.display(path))")
        .accessibilityIdentifier("category-breadcrumbs")
    }

    private var separator: some View {
        Image(systemName: "chevron.right")
            .font(.caption.weight(.semibold))
            .foregroundStyle(.tertiary)
            .frame(minHeight: PocketSpacing.minTouch)
            .accessibilityHidden(true)
    }

    @ViewBuilder private func crumb(_ title: String, target: [String]?, current: Bool) -> some View {
        if current {
            Text(title)
                .font(.subheadline.weight(.semibold))
                .fixedSize(horizontal: false, vertical: true)
                .frame(minHeight: PocketSpacing.minTouch)
                .accessibilityLabel("Estás en \(title)")
                .accessibilityIdentifier("category-crumb-current")
        } else {
            Button { state.showCrumb(target) } label: {
                Text(title)
                    .font(.subheadline)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(minHeight: PocketSpacing.minTouch)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .accessibilityLabel("Ir a \(title)")
            .accessibilityIdentifier("category-crumb-\(target.map(CategoryPaths.key) ?? "library")")
        }
    }
}

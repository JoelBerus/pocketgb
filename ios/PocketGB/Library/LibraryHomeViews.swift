import SwiftUI

/// N4 · el inicio de la biblioteca entre «Continuar jugando» y «Todos los juegos»: la fila de
/// Favoritos («Ver todo» abre la pestaña Favoritos, N4A-4) y una estantería por categoría de primer
/// nivel («Ver todo» abre su pantalla). Contenido L1: sin vidrio detrás de las portadas.
struct LibraryHomeRows: View {
    @Environment(AppState.self) private var state
    let sections: HomeSections
    /// Ancho de una tarjeta: el de una columna de la cuadrícula (como el carril de N3).
    let itemWidth: CGFloat?
    let zoom: Namespace.ID

    var body: some View {
        if !sections.favorites.isEmpty {
            HomeShelfView(key: "favorites", title: "Favoritos", systemImage: "star.fill",
                          entries: sections.favorites, total: sections.favoritesTotal,
                          seeAllLabel: "Ver todos los favoritos", itemWidth: itemWidth, zoom: zoom) {
                state.selectedTab = .favorites
            }
        }
        ForEach(sections.shelves) { shelf in
            HomeShelfView(key: shelf.category.key, title: shelf.category.title,
                          systemImage: shelf.pinned ? "pin.fill" : nil,
                          entries: shelf.games, total: shelf.total,
                          seeAllLabel: "Ver todo \(shelf.category.title)", itemWidth: itemWidth, zoom: zoom) {
                state.openCategory(shelf.category.path)
            }
        }
    }
}

/// Una fila del inicio: título con su número de juegos, «Ver todo» y hasta 10 tarjetas que se
/// desplazan en horizontal y se ajustan al soltar.
struct HomeShelfView: View {
    @Environment(AppState.self) private var state
    @Environment(\.dynamicTypeSize) private var typeSize
    let key: String
    let title: String
    let systemImage: String?
    let entries: [RomEntry]
    let total: Int
    let seeAllLabel: String
    let itemWidth: CGFloat?
    let zoom: Namespace.ID
    let seeAll: () -> Void

    private var countText: String { total == 1 ? "1 juego" : "\(total) juegos" }

    var body: some View {
        VStack(alignment: .leading, spacing: PocketSpacing.sm) {
            // Con texto grande, «Ver todo» baja bajo el título: nunca se recorta.
            ViewThatFits(in: .horizontal) {
                HStack(alignment: .firstTextBaseline, spacing: PocketSpacing.xs) {
                    heading
                    Spacer(minLength: PocketSpacing.xs)
                    seeAllButton
                }
                VStack(alignment: .leading, spacing: PocketSpacing.xxs) {
                    heading
                    seeAllButton
                }
            }
            ScrollView(.horizontal) {
                LazyHStack(alignment: .top, spacing: PocketSpacing.sm) {
                    ForEach(entries) { entry in card(entry) }
                }
                .scrollTargetLayout()
            }
            .contentMargins(.horizontal, 0, for: .scrollContent)
            .scrollTargetBehavior(.viewAligned)
            .scrollIndicators(.hidden)
            .scrollClipDisabled()
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("home-shelf-\(key)")
    }

    private var heading: some View {
        // Con tamaños de accesibilidad, el número bajo el título: el nombre no se parte.
        let layout = typeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 2))
            : AnyLayout(HStackLayout(alignment: .firstTextBaseline, spacing: PocketSpacing.xs))
        return layout {
            HStack(alignment: .firstTextBaseline, spacing: PocketSpacing.xs) {
                if let systemImage {
                    Image(systemName: systemImage)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Text(title)
                    .font(.headline)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Text(countText)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .monospacedDigit()
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(title), \(countText)")
        .accessibilityAddTraits(.isHeader)
    }

    private var seeAllButton: some View {
        Button(action: seeAll) {
            Text("Ver todo")
                .font(.subheadline.weight(.semibold))
                .frame(minHeight: PocketSpacing.minTouch)
                .contentShape(Rectangle())
        }
        .buttonStyle(.borderless)
        .accessibilityLabel("\(seeAllLabel), \(countText)")
        .accessibilityIdentifier("home-see-all-\(key)")
    }

    private func card(_ entry: RomEntry) -> some View {
        let source = "shelf-\(key)-\(entry.id)"
        return Button { state.select(entry, in: .library, source: source) } label: {
            GameCard(entry: entry, zoom: zoom, sourceID: source)
        }
        .buttonStyle(.plain)
        .frame(width: cardWidth)
        .accessibilityIdentifier("shelf-card-\(key)-\(entry.id)")
        .contextMenu {
            GameContextMenu(entry: entry, tab: .library)
        } preview: {
            GameArtworkView(entry: entry, style: .console)
                .frame(width: 300)
                .environment(state)
        }
    }

    /// El ancho de una columna; sin medir todavía, uno razonable.
    private var cardWidth: CGFloat {
        if let itemWidth, itemWidth > 0 { return itemWidth }
        return typeSize.isAccessibilitySize ? 280 : 160
    }
}

/// N4 · insignia «Movido en la app» (ND3): contorno fino y el símbolo de mover. En tarjetas, solo el
/// símbolo (el texto va en la etiqueta de VoiceOver de la tarjeta); en el detalle y el centro, con texto.
struct MovedBadge: View {
    static let systemImage = "arrow.turn.up.right"
    var compact = false

    var body: some View {
        HStack(spacing: 3) {
            Image(systemName: Self.systemImage)
                .imageScale(.small)
            if !compact { Text("Movido en la app") }
        }
        .font(.caption2.weight(.semibold))
        .foregroundStyle(.secondary)
        .padding(.horizontal, PocketSpacing.xxs + 2)
        .padding(.vertical, 1)
        .overlay(Capsule().strokeBorder(.secondary.opacity(0.4), lineWidth: 1))
        .fixedSize()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Movido en la app")
    }
}

/// N4 · en vertical, la etiqueta elegida bajo los filtros, con «Quitar».
struct ActiveTagRow: View {
    @Environment(AppState.self) private var state
    let tag: String

    var body: some View {
        HStack(spacing: PocketSpacing.xs) {
            Label("Etiqueta «\(tag)»", systemImage: "tag.fill")
                .font(.subheadline.weight(.semibold))
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: PocketSpacing.xs)
            Button("Quitar", systemImage: "xmark.circle.fill") { state.libraryTag = nil }
                .labelStyle(.titleAndIcon)
                .font(.subheadline)
                .buttonStyle(.borderless)
                .frame(minHeight: PocketSpacing.minTouch)
                .accessibilityLabel("Quitar la etiqueta \(tag)")
                .accessibilityIdentifier("library-tag-clear")
        }
        .accessibilityIdentifier("library-active-tag")
    }
}

/// Coloca sus vistas de izquierda a derecha y salta de fila cuando no caben (migas, subcategorías,
/// etiquetas). Con texto enorme, una vista que no cabe ocupa la fila y crece en alto.
struct FlowLayout: Layout {
    var spacing: CGFloat = PocketSpacing.xs
    var lineSpacing: CGFloat = PocketSpacing.xs

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? 360
        let rows = arrange(width: width, subviews: subviews)
        let height = rows.last.map { $0.y + $0.height } ?? 0
        let used = rows.map { row in row.items.last.map { $0.x + $0.size.width } ?? 0 }.max() ?? 0
        return CGSize(width: proposal.width ?? used, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        for row in arrange(width: bounds.width, subviews: subviews) {
            for item in row.items {
                // Alineadas por el centro de la fila.
                let y = bounds.minY + row.y + (row.height - item.size.height) / 2
                subviews[item.index].place(at: CGPoint(x: bounds.minX + item.x, y: y), proposal: ProposedViewSize(item.size))
            }
        }
    }

    private struct Item {
        let index: Int
        let x: CGFloat
        let size: CGSize
    }

    private struct Row {
        var y: CGFloat
        var height: CGFloat = 0
        var items: [Item] = []
    }

    private func arrange(width: CGFloat, subviews: Subviews) -> [Row] {
        var rows: [Row] = []
        var row = Row(y: 0)
        var x: CGFloat = 0
        for index in subviews.indices {
            var size = subviews[index].sizeThatFits(.unspecified)
            if x > 0 && x + size.width > width {
                rows.append(row)
                row = Row(y: row.y + row.height + lineSpacing)
                x = 0
            }
            if size.width > width {
                size = subviews[index].sizeThatFits(ProposedViewSize(width: width, height: nil))
                size.width = min(size.width, width)
            }
            row.items.append(Item(index: index, x: x, size: size))
            row.height = max(row.height, size.height)
            x += size.width + spacing
        }
        if !row.items.isEmpty { rows.append(row) }
        return rows
    }
}

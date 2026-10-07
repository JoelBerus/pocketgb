import SwiftUI

/// Lo que la biblioteca comparte con la raíz para colocar el grupo flotante (N3b). El grupo vive
/// encima del `TabView` (en coordenadas de la ventana): dentro de la pestaña no recibía toques a
/// la altura de la barra de pestañas, donde el área de la pestaña ya terminó.
@MainActor @Observable
final class LibraryToolsState {
    /// Barra de pestañas encogida (deducido del desplazamiento, `LibraryToolsPosition.minimized`).
    var tabBarMinimized = false
    /// Borde inferior de la barra de navegación de la biblioteca (coordenadas globales).
    var visibleTop: CGFloat = 0
    /// Texto del título de sección (coordenadas globales).
    var sectionTitleFrame: CGRect = .zero
    /// La biblioteca muestra juegos (no un estado vacío ni una búsqueda).
    var showsGames = false
    /// Cada toque en «Buscar» del grupo; la biblioteca abre la búsqueda.
    var searchRequests = 0
}

/// N3b · Biblioteca en horizontal: grupo flotante de vidrio a la derecha, a la altura de la barra
/// de pestañas (que al desplazarse queda como una burbuja a la izquierda), con Buscar, Filtros,
/// Categorías y Vista/Orden. Cada botón abre su panel **hacia arriba**, anclado a él, con un alto
/// máximo que no tapa el título de sección fijado arriba (`panelMaxHeight`).
struct LibraryToolsGroup: View {
    @Environment(AppState.self) private var state
    /// Alto disponible para el contenido de un panel sin tapar el título de sección.
    let panelMaxHeight: CGFloat
    let onSearch: () -> Void
    @State private var openPanel: Panel?

    /// Ancho del grupo: cuatro botones de 48 pt separados 8 pt.
    static let width: CGFloat = 4 * 48 + 3 * PocketSpacing.xs

    enum Panel: String, Identifiable {
        case filters, categories, view
        var id: String { rawValue }
    }

    private var prefs: LibraryPreferences { state.libraryPrefs }

    var body: some View {
        GlassEffectContainer(spacing: PocketSpacing.xs) {
            HStack(spacing: PocketSpacing.xs) {
                ToolButton(title: "Buscar", systemImage: "magnifyingglass", value: nil,
                           identifier: "library-tool-search", action: onSearch)
                panelButton(.filters, title: "Filtros",
                            systemImage: state.libraryFilter == .all
                                ? "line.3.horizontal.decrease" : "line.3.horizontal.decrease.circle.fill",
                            value: state.libraryFilter.title)
                panelButton(.categories, title: "Categorías",
                            systemImage: state.libraryCategory == .all ? "folder" : "folder.fill",
                            value: state.libraryCategory.title)
                panelButton(.view, title: "Vista y orden", systemImage: prefs.data.layout.systemImage,
                            value: "\(prefs.data.layout.title), \(prefs.data.sort.title)")
            }
        }
    }

    private func panelButton(_ panel: Panel, title: String, systemImage: String, value: String) -> some View {
        ToolButton(title: title, systemImage: systemImage, value: value,
                   identifier: "library-tool-\(panel.rawValue)") {
            openPanel = panel
        }
        .popover(isPresented: Binding(get: { openPanel == panel }, set: { if !$0 { openPanel = nil } }),
                 attachmentAnchor: .rect(.bounds), arrowEdge: .bottom) {
            ToolPanel(maxHeight: panelMaxHeight) {
                switch panel {
                case .filters: filtersPanel
                case .categories: categoriesPanel
                case .view: viewPanel
                }
            }
            .presentationCompactAdaptation(.popover)
            .environment(state)
        }
    }

    // MARK: Paneles
    // Anchos y bajos: en horizontal sobra ancho y falta alto. Las opciones son cápsulas que
    // fluyen en filas (con texto grande, en más filas); la elegida lleva marca y relleno.

    @ViewBuilder private var filtersPanel: some View {
        PanelTitle("Mostrar")
        ChipFlow {
            ForEach(LibraryFilter.allCases) { filter in
                Chip(title: filter == .all ? "Todos" : filter.title, systemImage: nil,
                     selected: state.libraryFilter == filter) {
                    state.libraryFilter = filter
                    openPanel = nil
                }
                .accessibilityIdentifier("library-filter-\(filter.rawValue)")
            }
        }
    }

    @ViewBuilder private var categoriesPanel: some View {
        let entries = state.library.entries
        PanelTitle("Categorías · carpetas")
        ChipFlow {
            ForEach([LibraryCategory.all] + prefs.categories(entries)) { category in
                let count = prefs.visible(entries, filter: .all, query: "", category: category).count
                Chip(title: category.title, systemImage: category == .all ? nil : category.systemImage,
                     selected: state.libraryCategory == category, detail: "\(count)") {
                    state.libraryCategory = category
                    openPanel = nil
                }
                .accessibilityIdentifier("library-category-\(category.id)")
            }
        }
    }

    @ViewBuilder private var viewPanel: some View {
        PanelTitle("Vista y orden")
        ChipFlow {
            ForEach(LibraryLayout.allCases, id: \.self) { layout in
                Chip(title: layout.title, systemImage: layout.systemImage, selected: prefs.data.layout == layout) {
                    prefs.setLayout(layout)
                }
                .accessibilityIdentifier("library-layout-\(layout.rawValue)")
            }
            ForEach(LibrarySort.allCases, id: \.self) { sort in
                Chip(title: sort.chipTitle, systemImage: sort.systemImage, selected: prefs.data.sort == sort) {
                    prefs.setSort(sort)
                }
                .accessibilityIdentifier("library-sort-\(sort.rawValue)")
            }
            Chip(title: "Volver a escanear", systemImage: "arrow.clockwise", selected: nil) {
                openPanel = nil
                state.library.refresh()
            }
            Chip(title: "Cambiar carpeta", systemImage: "folder.badge.gearshape", selected: nil) {
                openPanel = nil
                state.chooseFolder()
            }
        }
    }
}

/// Botón redondo de vidrio de 48 pt (área táctil ≥ 44 pt). El estado va en el símbolo y en la
/// etiqueta accesible, nunca solo en el color.
private struct ToolButton: View {
    let title: String
    let systemImage: String
    let value: String?
    let identifier: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            // Tamaño fijo: el botón no crece con el texto (con AX5 el símbolo se salía del círculo);
            // la etiqueta accesible da el nombre completo.
            Image(systemName: systemImage)
                .font(.system(size: 19, weight: .semibold))
                .frame(width: 48, height: 48)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(.primary)
        .pocketGlass(in: Circle(), interactive: true)
        .accessibilityLabel(title)
        .accessibilityValue(value ?? "")
        .accessibilityIdentifier(identifier)
    }
}

/// Contenido de un panel: se ajusta a su alto y, si no cabe en `maxHeight`, se desplaza.
private struct ToolPanel<Content: View>: View {
    let maxHeight: CGFloat
    @ViewBuilder let content: Content
    @State private var contentHeight: CGFloat = 0

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) { content }
                .padding(.horizontal, PocketSpacing.md)
                .padding(.vertical, PocketSpacing.sm)
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { contentHeight = $0 }
        }
        .scrollBounceBehavior(.basedOnSize)
        .frame(width: ToolPanelMetrics.width, height: max(min(contentHeight, maxHeight), PocketSpacing.minTouch))
    }
}

private struct PanelTitle: View {
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text)
            .font(.footnote.weight(.semibold))
            .foregroundStyle(.secondary)
            .textCase(.uppercase)
            .padding(.top, PocketSpacing.xs)
            .padding(.bottom, PocketSpacing.xxs)
            .accessibilityAddTraits(.isHeader)
    }
}

enum ToolPanelMetrics {
    /// Ancho de los paneles: cabe una fila de filtros y deja ver el título de sección a la izquierda.
    static let width: CGFloat = 400
}

/// Opción de un panel: cápsula sólida (sin vidrio: ya está sobre el vidrio del popover). La
/// elegida lleva relleno de acento y una marca, nunca solo color. `selected == nil`: es una acción.
private struct Chip: View {
    let title: String
    let systemImage: String?
    let selected: Bool?
    var detail: String?
    let action: () -> Void

    var body: some View {
        let isOn = selected == true
        Button(action: action) {
            HStack(spacing: PocketSpacing.xxs + 2) {
                if isOn {
                    Image(systemName: "checkmark").font(.footnote.weight(.bold))
                } else if let systemImage {
                    Image(systemName: systemImage).font(.footnote)
                }
                Text(title).fixedSize(horizontal: false, vertical: true)
                if let detail {
                    Text(detail).monospacedDigit().foregroundStyle(isOn ? .white.opacity(0.85) : .secondary)
                }
            }
            .font(.subheadline.weight(isOn ? .semibold : .regular))
            .foregroundStyle(isOn ? .white : .primary)
            .padding(.horizontal, PocketSpacing.sm)
            .frame(minHeight: PocketSpacing.minTouch)
            .background(isOn ? AnyShapeStyle(PocketColor.accent) : AnyShapeStyle(.quaternary), in: Capsule())
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isOn ? .isSelected : [])
        .accessibilityLabel(detail.map { "\(title), \($0) juegos" } ?? title)
    }
}

/// Coloca las opciones de izquierda a derecha y salta de fila cuando no caben.
private struct ChipFlow: Layout {
    var spacing: CGFloat = PocketSpacing.xs

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? ToolPanelMetrics.width
        let rows = arrange(width: width, subviews: subviews)
        let height = rows.last.map { $0.y + $0.height } ?? 0
        return CGSize(width: width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        for row in arrange(width: bounds.width, subviews: subviews) {
            for (index, x, size) in row.items {
                subviews[index].place(at: CGPoint(x: bounds.minX + x, y: bounds.minY + row.y),
                                      proposal: ProposedViewSize(size))
            }
        }
    }

    private struct Row {
        var y: CGFloat
        var height: CGFloat = 0
        var items: [(Int, CGFloat, CGSize)] = []
    }

    private func arrange(width: CGFloat, subviews: Subviews) -> [Row] {
        var rows: [Row] = []
        var row = Row(y: 0)
        var x: CGFloat = 0
        for index in subviews.indices {
            var size = subviews[index].sizeThatFits(.unspecified)
            size.width = min(size.width, width)
            if x > 0 && x + size.width > width {
                rows.append(row)
                row = Row(y: row.y + row.height + spacing)
                x = 0
            }
            if size.width >= width {   // texto enorme (AX5): ocupa la fila y crece en alto
                size = subviews[index].sizeThatFits(ProposedViewSize(width: width, height: nil))
            }
            row.items.append((index, x, size))
            row.height = max(row.height, size.height)
            x += size.width + spacing
        }
        if !row.items.isEmpty { rows.append(row) }
        return rows
    }
}

extension LibraryFilter {
    var systemImage: String {
        switch self {
        case .all: "square.grid.2x2"
        case .gb, .gbc, .gba: "gamecontroller"
        case .favorites: "star"
        }
    }
}

extension LibrarySort {
    var systemImage: String { self == .title ? "textformat" : "clock" }
    var chipTitle: String { self == .title ? "Por nombre" : "Recientes primero" }
}

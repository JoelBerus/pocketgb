import SwiftUI

/// Lo que la biblioteca comparte con la raíz para colocar el grupo flotante (N3b). El grupo vive
/// encima del `TabView` (en coordenadas de la ventana): dentro de la pestaña no recibía toques a
/// la altura de la barra de pestañas, donde el área de la pestaña ya terminó.
@MainActor @Observable
final class LibraryToolsState {
    /// Barra de pestañas encogida (deducido del desplazamiento, `LibraryToolsPosition.minimized`).
    /// Se reinicia (`reset`) al cambiar de pestaña, de ruta, de orientación o de carpeta y al cerrar
    /// la búsqueda: en esos casos la lista se recrea arriba y la barra vuelve a estar desplegada.
    private(set) var tabBarMinimized = false
    /// Borde inferior de la barra de navegación de la biblioteca (coordenadas globales).
    var visibleTop: CGFloat = 0
    /// Cabecera de sección entera (título y carpeta), en coordenadas globales.
    var sectionHeaderFrame: CGRect = .zero
    /// Cada toque en «Buscar» del grupo; la biblioteca abre la búsqueda.
    var searchRequests = 0

    /// Tras un desplazamiento de la lista (`old`/`new`: desplazamiento vertical).
    func scrolled(old: CGFloat, new: CGFloat) {
        let next = LibraryToolsPosition.minimized(tabBarMinimized, old: old, new: new)
        if next != tabBarMinimized { tabBarMinimized = next }
    }

    func reset() {
        if tabBarMinimized { tabBarMinimized = false }
    }
}

/// Dónde están Buscar, Filtros, Categorías y Vista (decisión del orquestador para N3, igual en
/// iOS y Android): en vertical, solo la lupa en la barra; en horizontal en reposo (barra de
/// pestañas desplegada), los cuatro en la barra de navegación con sus paneles hacia abajo; al
/// desplazar (barra encogida en burbuja), el grupo flotante a la derecha, en la fila de la burbuja,
/// con sus paneles hacia arriba. Así el grupo nunca coincide con la barra desplegada.
enum LibraryToolsPlacement: Equatable {
    case portraitSearchButton
    case navigationBar
    case floatingGroup

    static func placement(compactHeight: Bool, tabBarMinimized: Bool) -> LibraryToolsPlacement {
        guard compactHeight else { return .portraitSearchButton }
        return tabBarMinimized ? .floatingGroup : .navigationBar
    }
}

/// Paneles de las herramientas: Filtros, Categorías y Vista/Orden.
enum LibraryToolPanel: String, Identifiable, CaseIterable {
    case filters, categories, view
    var id: String { rawValue }

    var title: String {
        switch self {
        case .filters: "Filtros"
        case .categories: "Categorías"
        case .view: "Vista y orden"
        }
    }
}

/// Símbolo y valor (VoiceOver) de cada herramienta según lo elegido: el estado va en el símbolo
/// (relleno con un filtro o una categoría puestos) y en el texto, nunca solo en el color.
@MainActor
enum LibraryToolAppearance {
    static func systemImage(_ panel: LibraryToolPanel, state: AppState) -> String {
        switch panel {
        case .filters:
            state.libraryFilter == .all && state.libraryTag == nil
                ? "line.3.horizontal.decrease" : "line.3.horizontal.decrease.circle.fill"
        case .categories:
            "folder"
        case .view:
            state.libraryPrefs.data.layout.systemImage
        }
    }

    static func value(_ panel: LibraryToolPanel, state: AppState) -> String {
        switch panel {
        case .filters: state.libraryTag.map { "\(state.libraryFilter.title), etiqueta \($0)" } ?? state.libraryFilter.title
        case .categories: "Abre una categoría"
        case .view: "\(state.libraryPrefs.data.layout.title), \(state.libraryPrefs.data.sort.title)"
        }
    }
}

/// N3b · Grupo flotante de vidrio a la derecha, en la fila de la burbuja de la barra de pestañas
/// encogida, con Buscar, Filtros, Categorías y Vista/Orden. Cada botón abre su panel **hacia
/// arriba**, anclado a él, con un alto máximo que no tapa la cabecera de sección fijada.
struct LibraryToolsGroup: View {
    @Environment(AppState.self) private var state
    /// Alto disponible para el contenido de un panel sin tapar la cabecera de sección.
    let panelMaxHeight: CGFloat
    let onSearch: () -> Void

    /// Ancho del grupo: cuatro botones de 48 pt separados 8 pt.
    static let width: CGFloat = 4 * 48 + 3 * PocketSpacing.xs

    var body: some View {
        GlassEffectContainer(spacing: PocketSpacing.xs) {
            HStack(spacing: PocketSpacing.xs) {
                ToolButton(title: "Buscar", systemImage: "magnifyingglass", value: nil,
                           identifier: "library-tool-search", action: onSearch)
                ForEach(LibraryToolPanel.allCases) { panel in
                    LibraryToolPanelButton(panel: panel, maxHeight: panelMaxHeight, arrowEdge: .bottom) { open in
                        ToolButton(title: panel.title, systemImage: LibraryToolAppearance.systemImage(panel, state: state),
                                   value: LibraryToolAppearance.value(panel, state: state),
                                   identifier: "library-tool-\(panel.rawValue)", action: open)
                    }
                }
            }
        }
        // VoiceOver: un contenedor con nombre; dentro, cada botón con su etiqueta y su valor.
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Herramientas de la biblioteca")
    }
}

/// Botón de herramienta de la barra de navegación (horizontal en reposo): abre su panel **hacia
/// abajo**, anclado al botón.
struct LibraryToolBarButton: View {
    @Environment(AppState.self) private var state
    let panel: LibraryToolPanel
    let maxHeight: CGFloat

    var body: some View {
        LibraryToolPanelButton(panel: panel, maxHeight: maxHeight, arrowEdge: .top) { open in
            Button(panel.title, systemImage: LibraryToolAppearance.systemImage(panel, state: state), action: open)
                .accessibilityValue(LibraryToolAppearance.value(panel, state: state))
                .accessibilityIdentifier("library-bar-\(panel.rawValue)")
        }
    }
}

/// Un botón (el que se le pase) y su panel en popover anclado a él. `arrowEdge: .bottom`: la
/// flecha abajo y el panel encima (se abre hacia arriba); `.top`: se abre hacia abajo.
struct LibraryToolPanelButton<Label: View>: View {
    @Environment(AppState.self) private var state
    let panel: LibraryToolPanel
    let maxHeight: CGFloat
    let arrowEdge: Edge
    @ViewBuilder let label: (_ open: @escaping () -> Void) -> Label
    @State private var isOpen = false

    var body: some View {
        label { isOpen = true }
            .popover(isPresented: $isOpen, attachmentAnchor: .rect(.bounds), arrowEdge: arrowEdge) {
                ToolPanel(maxHeight: maxHeight) {
                    LibraryToolPanelContent(panel: panel) { isOpen = false }
                }
                .presentationCompactAdaptation(.popover)
                .environment(state)
                #if DEBUG
                .modifier(DebugDynamicType())   // el popover no hereda el tipo accesible forzado (captura AX5)
                #endif
            }
    }
}

/// Contenido de un panel. Anchos y bajos: en horizontal sobra ancho y falta alto. Las opciones
/// son cápsulas que fluyen en filas (con texto grande, en más filas); la elegida lleva marca.
struct LibraryToolPanelContent: View {
    @Environment(AppState.self) private var state
    let panel: LibraryToolPanel
    let close: () -> Void

    private var prefs: LibraryPreferences { state.libraryPrefs }

    var body: some View {
        switch panel {
        case .filters: filtersPanel
        case .categories: categoriesPanel
        case .view: viewPanel
        }
    }

    @ViewBuilder private var filtersPanel: some View {
        PanelTitle("Mostrar")
        ChipFlow {
            ForEach(LibraryFilter.allCases) { filter in
                Chip(title: filter == .all ? "Todos" : filter.title, systemImage: nil,
                     selected: state.libraryFilter == filter) {
                    state.libraryFilter = filter
                    close()
                }
                .accessibilityIdentifier("library-filter-\(filter.rawValue)")
            }
        }
        // N4 · filtro por etiqueta, combinable con el de consola.
        let tags = LibraryQuery.tagOptions(state.library.entries, prefs: prefs.data)
        if !tags.isEmpty {
            PanelTitle("Etiquetas")
            ChipFlow {
                Chip(title: "Todas", systemImage: nil, selected: state.libraryTag == nil) {
                    state.libraryTag = nil
                    close()
                }
                .accessibilityIdentifier("library-tag-all")
                ForEach(tags) { option in
                    Chip(title: option.tag, systemImage: "tag",
                         selected: state.libraryTag.map { Tags.same($0, option.tag) } ?? false,
                         detail: "\(option.count)") {
                        state.libraryTag = option.tag
                        close()
                    }
                    .accessibilityIdentifier("library-tag-\(option.tag)")
                }
            }
        }
    }

    /// N4 (N4A-1): cada categoría abre su pantalla (con sus subcategorías), ya no filtra en el sitio.
    @ViewBuilder private var categoriesPanel: some View {
        let options = LibraryCategory.options(state.library.entries, prefs: prefs.data)
        PanelTitle("Categorías · carpetas")
        ChipFlow {
            ForEach(options) { option in
                Chip(title: option.category.title, systemImage: option.category.systemImage,
                     selected: nil, detail: "\(option.count)") {
                    close()
                    // Tras cerrar el panel: el popover y la navegación no se animan a la vez.
                    Task { @MainActor in
                        try? await Task.sleep(for: .milliseconds(250))
                        state.openCategory(option.category.path)
                    }
                }
                .accessibilityIdentifier("library-category-\(option.category.id)")
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
                close()
                state.library.refresh()
            }
            Chip(title: "Cambiar carpeta", systemImage: "folder.badge.gearshape", selected: nil) {
                close()
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
        // Tamaño fijo con texto grande: mantener pulsado muestra el visor de contenido grande.
        .accessibilityShowsLargeContentViewer {
            Label(title, systemImage: systemImage)
        }
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

import SwiftUI

/// Tab Biblioteca (D2 + D3): la carpeta elegida con sus juegos en cuadrícula o lista,
/// búsqueda en vivo, filtros Todos/GB/GBC/Favoritos, "Continuar jugando" con la última
/// captura real y el detalle con zoom desde la portada.
struct LibraryView: View {
    @Environment(AppState.self) private var state
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Namespace private var zoom
    @State private var scrollOffset: CGFloat = 0

    private var library: LibraryStore { state.library }
    private var prefs: LibraryPreferences { state.libraryPrefs }

    var body: some View {
        @Bindable var state = state
        NavigationStack(path: $state.libraryPath) {
            content
                .background(PocketColor.backgroundBase.ignoresSafeArea())
                .navigationTitle("Biblioteca")
                .toolbar {
                    if case .ready = library.phase {
                        ToolbarItem(placement: .topBarTrailing) { optionsMenu }
                    }
                }
                .overlay(alignment: .bottom) {
                    if let summary = library.summary {
                        ToastView(text: summary, systemImage: "sparkles")
                            .padding(.bottom, PocketSpacing.md)
                            .transition(.opacity)
                    }
                }
                .navigationDestination(for: LibraryRoute.self) { route in
                    switch route {
                    case .details(let id, let source):
                        GameDetailsView(entryID: id)
                            .modifier(ZoomNavigation(sourceID: source, namespace: zoom))
                    }
                }
        }
    }

    // MARK: Estados de la carpeta

    @ViewBuilder private var content: some View {
        switch library.phase {
        case .noFolder:
            centered {
                EmptyStateView(
                    title: "Elige tu carpeta de juegos",
                    systemImage: "folder.badge.plus",
                    message: "PocketGB lee los juegos .gb y .gbc de una carpeta de iCloud Drive o de Archivos. No copia ni modifica tus ROMs.",
                    primaryTitle: "Elegir carpeta",
                    primaryAction: { state.chooseFolder() })
            }
        case .unavailable(let folderName):
            centered {
                EmptyStateView(
                    title: "No se puede abrir la carpeta",
                    systemImage: "folder.badge.questionmark",
                    message: "PocketGB ya no tiene acceso a \(folderName.map { "“\($0)”" } ?? "la carpeta de juegos"). Puede que se haya movido o renombrado. Tus partidas siguen guardadas en este iPhone.",
                    primaryTitle: "Elegir de nuevo",
                    primaryAction: { state.chooseFolder() },
                    secondaryTitle: "Reintentar",
                    secondaryAction: { library.restore() })
            }
        case .ready(let folderName):
            if library.entries.isEmpty && !library.isScanning {
                centered {
                    EmptyStateView(
                        title: "No hay juegos en esta carpeta",
                        systemImage: "folder",
                        message: "“\(folderName)” no tiene archivos .gb ni .gbc. Añádelos desde Archivos y vuelve a escanear, o elige otra carpeta.",
                        primaryTitle: "Volver a escanear",
                        primaryAction: { library.refresh() },
                        secondaryTitle: "Cambiar carpeta",
                        secondaryAction: { state.chooseFolder() })
                }
            } else {
                games(folderName: folderName)
            }
        }
    }

    private func centered(@ViewBuilder _ inner: () -> some View) -> some View {
        ScrollView {
            inner()
                .frame(maxWidth: .infinity)
                .padding(.horizontal, PocketSpacing.md)
                .padding(.top, PocketSpacing.xxl)
        }
        .scrollBounceBehavior(.basedOnSize)
    }

    private var optionsMenu: some View {
        Menu {
            Picker("Vista", selection: Binding(get: { prefs.data.layout }, set: { prefs.setLayout($0) })) {
                ForEach(LibraryLayout.allCases, id: \.self) { layout in
                    Label(layout.title, systemImage: layout.systemImage).tag(layout)
                }
            }
            Picker("Ordenar por", selection: Binding(get: { prefs.data.sort }, set: { prefs.setSort($0) })) {
                ForEach(LibrarySort.allCases, id: \.self) { sort in
                    Text(sort.title).tag(sort)
                }
            }
            Divider()
            Button("Volver a escanear", systemImage: "arrow.clockwise") { library.refresh() }
            Button("Cambiar carpeta", systemImage: "folder") { state.chooseFolder() }
        } label: {
            Label("Más opciones", systemImage: "ellipsis")
        }
    }

    // MARK: Juegos

    private func games(folderName: String) -> some View {
        @Bindable var state = state
        let query = state.librarySearch
        let searching = !query.trimmingCharacters(in: .whitespaces).isEmpty
        let shown = prefs.visible(library.entries, filter: state.libraryFilter, query: query)
        return ScrollView {
            VStack(alignment: .leading, spacing: PocketSpacing.lg) {
                minimized(filterPicker)
                if searching {
                    searchResults(shown, query: query)
                } else {
                    if state.libraryFilter == .all, let continueEntries = continueCandidates, !continueEntries.isEmpty {
                        minimized(ContinuePlayingRow(entries: continueEntries, zoom: zoom))
                    }
                    allGames(shown, folderName: folderName)
                }
            }
            .padding(.horizontal, PocketSpacing.md)
            .padding(.bottom, PocketSpacing.xl)
        }
        .scrollEdgeEffectStyle(.soft, for: .top)
        .onScrollGeometryChange(for: CGFloat.self) { geometry in
            max(0, geometry.contentOffset.y + geometry.contentInsets.top)
        } action: { _, value in
            scrollOffset = value
        }
        .refreshable { library.refresh() }
        // Campo siempre visible bajo el título: la búsqueda es la acción principal de la
        // biblioteca. (Con `.searchToolbarBehavior(.minimize)` quedaba en un botón que no se
        // podía expandir desde las capturas del catálogo.)
        .searchable(text: $state.librarySearch, isPresented: $state.librarySearchPresented,
                    placement: .navigationBarDrawer(displayMode: .automatic), prompt: "Juegos")
        .searchToolbarBehavior(.minimize)
    }

    private func minimized<Content: View>(_ content: Content) -> some View {
        let progress = min(max(scrollOffset / 120, 0), 1)
        let reduced = PocketMotion.reducesMotion(system: reduceMotion)
        return content
            .scaleEffect(reduced ? 1 : 1 - 0.055 * progress, anchor: .top)
            .opacity(1 - (reduced ? 0.28 : 0.14) * progress)
            .animation(reduced ? nil : .easeOut(duration: 0.18), value: progress)
    }

    /// Juegos jugados con captura local: "Continuar" nunca muestra una portada inventada.
    private var continueCandidates: [RomEntry]? {
        let recent = prefs.recent(library.entries, limit: 5).filter {
            $0.isPlayable && state.canResume($0)
                && state.artwork.image(for: prefs.fingerprint(of: $0)) != nil
        }
        return recent.isEmpty ? nil : recent
    }

    @ViewBuilder private var filterPicker: some View {
        @Bindable var state = state
        let picker = Picker("Filtro", selection: $state.libraryFilter) {
            ForEach(LibraryFilter.allCases) { filter in
                Text(filter.title).tag(filter)
            }
        }
        // Con tamaños de accesibilidad, el segmentado truncaría: menú en su lugar.
        if typeSize.isAccessibilitySize {
            picker.pickerStyle(.menu)
        } else {
            picker.pickerStyle(.segmented)
        }
    }

    @ViewBuilder private func allGames(_ shown: [RomEntry], folderName: String) -> some View {
        VStack(alignment: .leading, spacing: PocketSpacing.sm) {
            HStack(alignment: .firstTextBaseline) {
                Text(state.libraryFilter == .all ? "Todos los juegos" : state.libraryFilter.title)
                    .font(.headline)
                Spacer()
                Label(folderName, systemImage: "folder")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            .accessibilityElement(children: .combine)
            if let progress = library.scanProgress {
                ScanProgressRow(progress: progress)
            }
            if shown.isEmpty && !library.isScanning {
                EmptyStateView(
                    title: state.libraryFilter == .favorites ? "Sin favoritos" : "Sin juegos \(state.libraryFilter.title)",
                    systemImage: state.libraryFilter == .favorites ? "star" : "square.grid.2x2",
                    message: state.libraryFilter == .favorites
                        ? "Mantén pulsado un juego y elige “Añadir a favoritos”."
                        : "No hay juegos de este sistema en la carpeta.",
                    primaryTitle: "Ver todos",
                    primaryAction: { state.libraryFilter = .all })
            } else if prefs.data.layout == .grid {
                grid(shown)
            } else {
                list(shown)
            }
            if let note = cloudNote {
                Text(note)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var cloudNote: String? {
        if library.entries.contains(where: { $0.cloud == .downloading }) {
            return "Descargando de iCloud. Podrás jugar cuando termine la descarga."
        }
        if library.entries.contains(where: { $0.cloud == .notDownloaded }) {
            return "Los juegos con una nube solo están en iCloud: tócalos para descargarlos."
        }
        return nil
    }

    /// Menos columnas antes que truncar (SPEC §13, Dynamic Type).
    private var columns: [GridItem] {
        let minimum: CGFloat = typeSize.isAccessibilitySize ? 280 : 150
        return [GridItem(.adaptive(minimum: minimum), spacing: PocketSpacing.sm, alignment: .top)]
    }

    private func grid(_ shown: [RomEntry]) -> some View {
        LazyVGrid(columns: columns, alignment: .leading, spacing: PocketSpacing.lg) {
            ForEach(shown) { entry in
                Button { state.select(entry, in: .library) } label: {
                    GameCard(entry: entry, zoom: zoom)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("game-card-\(entry.id)")
                .contextMenu {
                    GameContextMenu(entry: entry, tab: .library)
                } preview: {
                    GameArtworkView(entry: entry)
                        .frame(width: 300)
                        .environment(state)
                }
            }
        }
    }

    private func list(_ shown: [RomEntry]) -> some View {
        LazyVStack(alignment: .leading, spacing: 0) {
            ForEach(shown) { entry in
                Button { state.select(entry, in: .library) } label: {
                    GameListItem(entry: entry, zoom: zoom)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("game-row-\(entry.id)")
                .contextMenu {
                    GameContextMenu(entry: entry, tab: .library)
                }
                if entry.id != shown.last?.id {
                    Divider().padding(.leading, 56 + PocketSpacing.sm)
                }
            }
        }
    }

    // MARK: Búsqueda

    @ViewBuilder private func searchResults(_ shown: [RomEntry], query: String) -> some View {
        if shown.isEmpty {
            let filter = state.libraryFilter
            Group {
                if filter == .all {
                    EmptyStateView(
                        title: "Sin resultados para “\(query)”",
                        systemImage: "magnifyingglass",
                        message: "Ningún juego de la carpeta se llama así. Revisa la ortografía o prueba con parte del nombre.")
                } else {
                    EmptyStateView(
                        title: "Sin resultados para “\(query)”",
                        systemImage: "magnifyingglass",
                        message: "No hay coincidencias en \(filter.title). Puede que el juego esté en otro filtro.",
                        primaryTitle: "Buscar en todos",
                        primaryAction: { state.libraryFilter = .all })
                }
            }
            .padding(.top, PocketSpacing.xl)
        } else {
            VStack(alignment: .leading, spacing: PocketSpacing.xs) {
                Text(shown.count == 1 ? "1 resultado" : "\(shown.count) resultados")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .textCase(.uppercase)
                list(shown)
            }
        }
    }
}

/// Rutas de la pila de Biblioteca y Favoritos.
enum LibraryRoute: Hashable {
    case details(id: String, source: String)

    var entryID: String {
        switch self {
        case .details(let id, _): id
        }
    }
}

/// Portada → detalle con zoom; con Reduce Motion, transición automática (SPEC §13).
struct ZoomNavigation: ViewModifier {
    let sourceID: String
    let namespace: Namespace.ID
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        if PocketMotion.reducesMotion(system: reduceMotion) {
            content.navigationTransition(.automatic)
        } else {
            content.navigationTransition(.zoom(sourceID: sourceID, in: namespace))
        }
    }
}

/// Carril "Continuar jugando" (SPEC §8, `ContinuePlayingView`): el último frame real y
/// el botón de vidrio flotando sobre la captura (L1 + L2).
struct ContinuePlayingRow: View {
    @Environment(AppState.self) private var state
    @Environment(\.dynamicTypeSize) private var typeSize
    let entries: [RomEntry]
    let zoom: Namespace.ID

    var body: some View {
        VStack(alignment: .leading, spacing: PocketSpacing.sm) {
            Text("Continuar jugando")
                .font(.headline)
            if typeSize.isAccessibilitySize {
                // Tamaños de accesibilidad: en columna y a todo el ancho (SPEC §13).
                VStack(alignment: .leading, spacing: PocketSpacing.md) {
                    ForEach(entries) { item($0, width: nil) }
                }
            } else {
                ScrollView(.horizontal) {
                    LazyHStack(alignment: .top, spacing: PocketSpacing.sm) {
                        ForEach(entries) { entry in
                            item(entry, width: nil)
                                .containerRelativeFrame(.horizontal) { length, _ in
                                    min(max(length * 0.68, 196), 240)
                                }
                        }
                    }
                    .scrollTargetLayout()
                }
                .contentMargins(.horizontal, 0, for: .scrollContent)
                .scrollTargetBehavior(.viewAligned)
                .scrollIndicators(.hidden)
                .scrollClipDisabled()
            }
        }
        .accessibilityIdentifier("library-continue")
    }

    private func item(_ entry: RomEntry, width: CGFloat?) -> some View {
        VStack(alignment: .leading, spacing: PocketSpacing.xxs) {
            Button { state.showDetails(entry, in: .library, source: "continue-\(entry.id)") } label: {
                GameArtworkView(entry: entry)
            }
            .buttonStyle(.plain)
            .matchedTransitionSource(id: "continue-\(entry.id)", in: zoom)
            .overlay(alignment: .bottomLeading) {
                Button { state.open(entry: entry, mode: .resumeAutomatic) } label: {
                    HStack(spacing: PocketSpacing.xs) {
                        Image(systemName: "play.fill")
                        Text("Continuar")
                    }
                        .foregroundStyle(.white)
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                        .fixedSize()
                        .frame(minHeight: 32)
                }
                .pocketGlassButton(prominent: true)
                .padding(PocketSpacing.xs)
                .accessibilityLabel("Continuar \(state.libraryPrefs.displayTitle(entry))")
            }
            Text(state.libraryPrefs.displayTitle(entry))
                .font(.subheadline.weight(.semibold))
                .lineLimit(typeSize.isAccessibilitySize ? nil : 1)
            Text(state.libraryPrefs.lastPlayed(entry).map { "Jugado \(GameStatus.relative($0))" } ?? "")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .frame(width: width)
        .frame(maxWidth: width == nil ? .infinity : nil, alignment: .leading)
    }
}

/// Progreso discreto del escaneo: la biblioteca sigue utilizable (SPEC §9, `library-scan-progress`).
struct ScanProgressRow: View {
    let progress: LibraryStore.ScanProgress

    var body: some View {
        HStack(spacing: PocketSpacing.sm) {
            ProgressView()
            Text(progress.total > 0 ? "Buscando juegos… \(progress.done) de \(progress.total)" : "Buscando juegos…")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .frame(minHeight: PocketSpacing.minTouch)
        .accessibilityElement(children: .combine)
    }
}

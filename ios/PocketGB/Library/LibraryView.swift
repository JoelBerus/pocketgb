import SwiftUI

/// Tab Biblioteca (D2 + D3): la carpeta elegida con sus juegos en cuadrícula o lista,
/// búsqueda en vivo, filtros Todos/GB/GBC/Favoritos, "Continuar jugando" con la última
/// captura real y el detalle con zoom desde la portada.
///
/// N3 · En altura compacta (horizontal): título en línea con borde de scroll duro (se lee sobre
/// una captura blanca), sin filtro segmentado ni buscador arriba, título de sección fijado y, a la
/// derecha, el grupo flotante `LibraryToolsGroup` (Buscar, Filtros, Categorías, Vista/Orden). La
/// búsqueda se abre con la lupa (en la barra o en el grupo) y desaparece al cancelarla.
struct LibraryView: View {
    @Environment(AppState.self) private var state
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @Namespace private var zoom
    @State private var scrollOffset: CGFloat = 0
    /// Horizontal: la búsqueda solo existe mientras se usa (la lupa la abre).
    @State private var landscapeSearch = false
    @State private var contentWidth: CGFloat = 0

    /// Horizontal en iPhone: altura compacta.
    private var compactHeight: Bool { verticalSizeClass == .compact }

    private var library: LibraryStore { state.library }
    private var prefs: LibraryPreferences { state.libraryPrefs }

    var body: some View {
        @Bindable var state = state
        NavigationStack(path: $state.libraryPath) {
            content
                .background(PocketColor.backgroundBase.ignoresSafeArea())
                .navigationTitle("Biblioteca")
                // N3a: en horizontal, título en línea (el grande no cabe y se perdía sobre la captura).
                .modifier(InlineTitleInCompactHeight(compact: compactHeight))
                .toolbar {
                    if case .ready = library.phase {
                        // N3b: la búsqueda también como botón de la barra, «como una opción más».
                        ToolbarItem(placement: .topBarTrailing) {
                            Button("Buscar", systemImage: "magnifyingglass") { startSearch() }
                                .accessibilityIdentifier("library-search-button")
                        }
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
                    message: "PocketGB lee los juegos .gb, .gbc y .gba de una carpeta de iCloud Drive o de Archivos. No copia ni modifica tus ROMs.",
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
                        message: "“\(folderName)” no tiene archivos .gb, .gbc ni .gba. Añádelos desde Archivos y vuelve a escanear, o elige otra carpeta.",
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
            // N3b: la categoría elegida en horizontal se ve y se cambia también en vertical.
            Picker("Categoría", systemImage: "folder", selection: Binding(get: { state.libraryCategory },
                                                                          set: { state.libraryCategory = $0 })) {
                ForEach([LibraryCategory.all] + prefs.categories(library.entries)) { category in
                    Text(category.title).tag(category)
                }
            }
            .pickerStyle(.menu)
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
        let shown = prefs.visible(library.entries, filter: state.libraryFilter, query: query,
                                  category: state.libraryCategory)
        let compact = compactHeight
        return ScrollView {
            // Horizontal: perezosa, con el título de sección fijado. Vertical: la de siempre.
            Group {
                if compact {
                    LazyVStack(alignment: .leading, spacing: PocketSpacing.lg,
                               pinnedViews: searching ? [] : [.sectionHeaders]) {
                        gamesContent(shown, query: query, searching: searching, folderName: folderName, compact: true)
                    }
                } else {
                    VStack(alignment: .leading, spacing: PocketSpacing.lg) {
                        gamesContent(shown, query: query, searching: searching, folderName: folderName, compact: false)
                    }
                }
            }
            .padding(.horizontal, PocketSpacing.md)
            // Horizontal: aire al final para que el grupo flotante no tape la última fila.
            .padding(.bottom, compact ? 72 : PocketSpacing.xl)
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { contentWidth = $0 }
        }
        // N3a: en horizontal, borde duro: el título en línea se lee aunque pase una captura blanca.
        .scrollEdgeEffectStyle(compact ? .hard : .soft, for: .top)
        .onScrollGeometryChange(for: CGFloat.self) { geometry in
            max(0, geometry.contentOffset.y + geometry.contentInsets.top)
        } action: { old, value in
            scrollOffset = value
            let tools = state.libraryTools
            let minimized = LibraryToolsPosition.minimized(tools.tabBarMinimized, old: old, new: value)
            if minimized != tools.tabBarMinimized { tools.tabBarMinimized = minimized }
        }
        // Borde inferior de la barra de navegación: el de arriba del área segura (una capa vacía
        // que no recibe toques), para el alto de los paneles del grupo flotante.
        .overlay {
            Color.clear
                .allowsHitTesting(false)
                .onGeometryChange(for: CGFloat.self) { $0.frame(in: .global).minY } action: {
                    state.libraryTools.visibleTop = $0
                }
        }
        .refreshable { library.refresh() }
        .modifier(LibrarySearchModifier(enabled: !compact || landscapeSearch || searching,
                                        text: $state.librarySearch, isPresented: $state.librarySearchPresented))
        .onChange(of: state.librarySearchPresented) { _, presented in
            if !presented && state.librarySearch.isEmpty { landscapeSearch = false }
        }
        .onChange(of: state.libraryTools.searchRequests) { startSearch() }
        .onAppear { state.libraryTools.showsGames = true }
        .onDisappear { state.libraryTools.showsGames = false }
    }


    @ViewBuilder private func gamesContent(_ shown: [RomEntry], query: String, searching: Bool,
                                           folderName: String, compact: Bool) -> some View {
        if !compact { minimized(filterPicker) }
        if searching {
            searchResults(shown, query: query)
        } else {
            if state.libraryFilter == .all, state.libraryCategory == .all,
               let continueEntries = continueCandidates, !continueEntries.isEmpty {
                minimized(ContinuePlayingRow(entries: continueEntries, zoom: zoom,
                                             itemWidth: compact ? gridColumnWidth : nil))
            }
            Section {
                allGames(shown)
            } header: {
                sectionHeader(folderName: folderName, count: shown.count, pinned: compact)
            }
        }
    }

    /// Ancho de una columna de la cuadrícula: en horizontal, el carril «Continuar» usa el mismo
    /// (como Android en N3a), así las tarjetas no ocupan toda la altura.
    private var gridColumnWidth: CGFloat? {
        guard contentWidth > 0 else { return nil }
        return GridMetrics.columnWidth(containerWidth: contentWidth, minimum: gridMinimum,
                                       spacing: PocketSpacing.sm)
    }

    /// Lupa de la barra o del grupo: abre la búsqueda. En horizontal la búsqueda no existe hasta
    /// entonces (no ocupa la parte de arriba), así que primero se añade y luego se enfoca.
    private func startSearch() {
        if !compactHeight || landscapeSearch {
            state.librarySearchPresented = true
            return
        }
        landscapeSearch = true
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(250))
            state.librarySearchPresented = true
        }
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

    /// Título de la sección: «Todos los juegos», el filtro o la categoría elegidos y la carpeta.
    /// En horizontal queda fijado arriba (fondo opaco del contenido, sin vidrio).
    private func sectionHeader(folderName: String, count: Int, pinned: Bool) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(sectionTitle)
                .font(.headline)
                .accessibilityAddTraits(.isHeader)
                .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { state.libraryTools.sectionTitleFrame = $0 }
            Spacer()
            Label(folderName, systemImage: "folder")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("library-section-title")
        .padding(.vertical, pinned ? PocketSpacing.xs : 0)
        .frame(minHeight: pinned ? PocketSpacing.minTouch : nil)
        .background {
            // Fijado: fondo opaco del contenido (sin vidrio) que también tapa la franja entre la
            // barra de navegación y el título, por donde asomaban las tarjetas.
            if pinned {
                PocketColor.backgroundBase
                    .padding(.horizontal, -PocketSpacing.md)
                    .padding(.top, -PocketSpacing.sm)
            }
        }
    }

    /// «Todos los juegos», «GBA», «Pokémon» o «Pokémon · GBA».
    private var sectionTitle: String {
        let filter = state.libraryFilter
        switch (state.libraryCategory, filter) {
        case (.all, .all): return "Todos los juegos"
        case (.all, _): return filter.title
        case (let category, .all): return category.title
        case (let category, _): return "\(category.title) · \(filter.title)"
        }
    }

    @ViewBuilder private func allGames(_ shown: [RomEntry]) -> some View {
        VStack(alignment: .leading, spacing: PocketSpacing.sm) {
            if let progress = library.scanProgress {
                ScanProgressRow(progress: progress)
            }
            if shown.isEmpty && !library.isScanning {
                if state.libraryCategory != .all {
                    EmptyStateView(
                        title: "Sin juegos en “\(state.libraryCategory.title)”",
                        systemImage: "folder",
                        message: "No hay juegos de este filtro en esta categoría.",
                        primaryTitle: "Ver todas las categorías",
                        primaryAction: { state.libraryCategory = .all })
                } else {
                    EmptyStateView(
                        title: state.libraryFilter == .favorites ? "Sin favoritos" : "Sin juegos \(state.libraryFilter.title)",
                        systemImage: state.libraryFilter == .favorites ? "star" : "square.grid.2x2",
                        message: state.libraryFilter == .favorites
                            ? "Mantén pulsado un juego y elige “Añadir a favoritos”."
                            : "No hay juegos de este sistema en la carpeta.",
                        primaryTitle: "Ver todos",
                        primaryAction: { state.libraryFilter = .all })
                }
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
    private var gridMinimum: CGFloat { typeSize.isAccessibilitySize ? 280 : 150 }

    private var columns: [GridItem] {
        [GridItem(.adaptive(minimum: gridMinimum), spacing: PocketSpacing.sm, alignment: .top)]
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
                    GameArtworkView(entry: entry, style: .console)
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
    /// Horizontal (N3): el ancho de una columna de la cuadrícula; nil = el de siempre.
    var itemWidth: CGFloat?

    var body: some View {
        VStack(alignment: .leading, spacing: PocketSpacing.sm) {
            Text("Continuar jugando")
                .font(.headline)
            if typeSize.isAccessibilitySize {
                // Tamaños de accesibilidad: en columna y a todo el ancho (SPEC §13).
                // En horizontal, del ancho de una columna: a todo el ancho no cabría en la pantalla.
                VStack(alignment: .leading, spacing: PocketSpacing.md) {
                    ForEach(entries) { item($0, width: itemWidth) }
                }
            } else {
                ScrollView(.horizontal) {
                    LazyHStack(alignment: .top, spacing: PocketSpacing.sm) {
                        ForEach(entries) { entry in
                            if let itemWidth {
                                item(entry, width: itemWidth)
                            } else {
                                item(entry, width: nil)
                                    .containerRelativeFrame(.horizontal) { length, _ in
                                        min(max(length * 0.68, 196), 240)
                                    }
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
                    // N3a: velo inferior bajo «Continuar»: el botón se lee sobre una captura blanca.
                    .overlay(alignment: .bottom) {
                        LinearGradient(colors: [.clear, PocketColor.controlScrim.opacity(0.45)],
                                       startPoint: .top, endPoint: .bottom)
                            .frame(height: 64)
                            .clipShape(UnevenRoundedRectangle(bottomLeadingRadius: PocketRadius.cover,
                                                              bottomTrailingRadius: PocketRadius.cover,
                                                              style: .continuous))
                            .allowsHitTesting(false)
                            .accessibilityHidden(true)
                    }
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

/// `.searchable` bajo el título con `.searchToolbarBehavior(.minimize)` (iOS 26), solo cuando está
/// activado: en horizontal no hay barra de búsqueda arriba hasta que se toca la lupa (N3b).
struct LibrarySearchModifier: ViewModifier {
    let enabled: Bool
    @Binding var text: String
    @Binding var isPresented: Bool

    func body(content: Content) -> some View {
        if enabled {
            content
                .searchable(text: $text, isPresented: $isPresented,
                            placement: .navigationBarDrawer(displayMode: .automatic), prompt: "Juegos")
                .searchToolbarBehavior(.minimize)
        } else {
            content
        }
    }
}

/// Columnas de una cuadrícula `.adaptive(minimum:)` (las mismas cuentas que SwiftUI).
enum GridMetrics {
    static func columns(containerWidth: CGFloat, minimum: CGFloat, spacing: CGFloat) -> Int {
        guard containerWidth > 0, minimum > 0 else { return 1 }
        return max(1, Int((containerWidth + spacing) / (minimum + spacing)))
    }

    static func columnWidth(containerWidth: CGFloat, minimum: CGFloat, spacing: CGFloat) -> CGFloat {
        let n = CGFloat(columns(containerWidth: containerWidth, minimum: minimum, spacing: spacing))
        return max((containerWidth - (n - 1) * spacing) / n, 0)
    }
}

/// Posición vertical del grupo flotante de la biblioteca en horizontal (N3b). La barra de
/// pestañas de iOS 26 no publica si está encogida, así que se deduce como ella misma decide
/// (`UITabBarController.MinimizeBehavior.onScrollDown`: se encoge al bajar y se despliega al
/// subir o arriba del todo). Las distancias al borde inferior de la pantalla se midieron en los
/// simuladores de iOS 26.5 (iPhone SE 3.ª gen y 17 Pro): la burbuja encogida tiene el centro a
/// 43 pt del borde y la barra desplegada empieza a 65 pt.
enum LibraryToolsPosition {
    /// Encogida: el grupo (48 pt) centrado con la burbuja de la izquierda.
    static let minimizedBottomGap: CGFloat = 43 - 24
    /// Desplegada: el grupo, 8 pt por encima de la barra.
    static let expandedBottomGap: CGFloat = 65 + 8

    /// ¿Está encogida la barra tras este desplazamiento? `old`/`new`: desplazamiento vertical.
    static func minimized(_ current: Bool, old: CGFloat, new: CGFloat) -> Bool {
        if new <= 8 { return false }          // arriba del todo: desplegada
        if new - old > 0.5 { return true }    // bajando: se encoge
        // Subiendo: la barra puede desplegarse (no siempre lo hace con poco recorrido): por si
        // acaso el grupo sube, para no pisarla nunca.
        if old - new > 0.5 { return false }
        return current
    }

    static let groupHeight: CGFloat = 48

    /// Alto máximo del contenido de un panel que se abre hacia arriba desde el grupo: llega hasta
    /// la barra de navegación, salvo que el título de sección quede debajo del panel (se solapan
    /// en horizontal): entonces se queda por debajo del título. 28 pt para la flecha y el aire.
    /// Nunca menos de dos filas (el panel se desplaza por dentro).
    static func panelMaxHeight(groupTop: CGFloat, visibleTop: CGFloat, titleFrame: CGRect,
                               panelMinX: CGFloat) -> CGFloat {
        guard groupTop > 0 else { return 220 }
        let titleUnder = !titleFrame.isEmpty && titleFrame.maxX + PocketSpacing.sm > panelMinX
            && titleFrame.maxY < groupTop
        let limit = titleUnder ? max(titleFrame.maxY, visibleTop) : visibleTop
        return max(groupTop - limit - 28, 2 * PocketSpacing.minTouch)
    }

    /// Distancia del borde inferior de la ventana al borde inferior del grupo.
    static func bottomGap(minimized: Bool) -> CGFloat {
        minimized ? minimizedBottomGap : expandedBottomGap
    }
}

/// Título en línea solo en altura compacta (N3a). En vertical no se toca el modo del título: con
/// `.automatic` explícito, el buscador bajo el título grande no aparecía al abrir.
struct InlineTitleInCompactHeight: ViewModifier {
    let compact: Bool

    func body(content: Content) -> some View {
        if compact {
            content.navigationBarTitleDisplayMode(.inline)
        } else {
            content
        }
    }
}

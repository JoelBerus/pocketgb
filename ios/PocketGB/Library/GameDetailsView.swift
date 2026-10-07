import SwiftUI

/// Detalle del juego (SPEC §9, `game-details`): captura o placeholder, metadatos y la
/// acción dominante para jugar. N3a: se adapta al espacio disponible (`DetailLayout`): en
/// horizontal, o con ≥ 600 pt de ancho, la imagen entera a la izquierda y la información con su
/// propio scroll a la derecha («Jugar»/«Continuar» visible sin desplazar); en vertical, la imagen
/// ocupa como máximo el 45 % del alto. Incluye la «Información técnica» plegable (paridad con Android).
struct GameDetailsView: View {
    @Environment(AppState.self) private var state
    @Environment(\.dynamicTypeSize) private var typeSize
    let entryID: String
    @State private var technical: TechnicalLoad = .idle
    @State private var technicalExpanded = GameDetailsView.expandsTechnicalInfoByDefault
    @State private var copiedSHA = false

    /// Estado de la «Información técnica»: se lee al desplegarla (no lee el ROM si no se mira).
    enum TechnicalLoad: Equatable {
        case idle, loading
        case loaded(GameTechnicalInfo)
        case failed(String)
    }

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
        GeometryReader { proxy in
            let layout = DetailLayout.make(available: proxy.size,
                                           aspectRatio: ArtworkStyle.console.frameAspectRatio(for: entry.console))
            switch layout.kind {
            case .twoColumns: twoColumns(entry, layout)
            case .singleColumn: singleColumn(entry, layout)
            }
        }
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
        .task(id: TechnicalKey(id: entry.id, cloud: entry.cloud, expanded: technicalExpanded)) {
            await loadTechnicalInfo(entry)
        }
    }

    /// Vertical: una columna con la imagen limitada al 45 % del alto, centrada.
    private func singleColumn(_ entry: RomEntry, _ layout: DetailLayout) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: PocketSpacing.md) {
                artwork(entry, layout)
                    .frame(maxWidth: .infinity)
                header(entry)
                stats(entry)
                primaryAction(entry)
                linkAction(entry)
                secondaryActions(entry)
                technicalSection(entry)
                hideAction()
            }
            .padding(.horizontal, DetailLayout.margin)
            .padding(.bottom, PocketSpacing.xl)
        }
        .scrollEdgeEffectStyle(.soft, for: .top)
        .accessibilityIdentifier("game-details-single-column")
    }

    /// Horizontal o ancho: la imagen entera a la izquierda (no se desplaza) y la información a la
    /// derecha con su propio scroll; la acción principal va justo bajo el título.
    private func twoColumns(_ entry: RomEntry, _ layout: DetailLayout) -> some View {
        HStack(alignment: .top, spacing: DetailLayout.columnSpacing) {
            artwork(entry, layout)
                .padding(.top, PocketSpacing.xs)
            ScrollView {
                VStack(alignment: .leading, spacing: PocketSpacing.md) {
                    header(entry)
                    primaryAction(entry)
                    stats(entry)
                    linkAction(entry)
                    secondaryActions(entry)
                    technicalSection(entry)
                    hideAction()
                }
                .padding(.top, PocketSpacing.xs)
                .padding(.bottom, PocketSpacing.xl)
                .padding(.trailing, PocketSpacing.xs)   // el indicador de scroll no pisa los valores
            }
            // Suave: el duro dibujaba una banda opaca rectangular solo sobre esta columna (H10).
            .scrollEdgeEffectStyle(.soft, for: .top)
            .scrollIndicators(.automatic)
        }
        .padding(.horizontal, DetailLayout.margin)
        .accessibilityIdentifier("game-details-two-columns")
    }

    private func artwork(_ entry: RomEntry, _ layout: DetailLayout) -> some View {
        GameArtworkView(entry: entry, style: .console)
            .frame(width: layout.artwork.width, height: layout.artwork.height)
            .accessibilityIdentifier("game-details-artwork")
    }

    private func header(_ entry: RomEntry) -> some View {
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
            organization(entry)
        }
    }

    /// N4: dónde se ve si se movió en la app (además de la ruta real de arriba) y sus etiquetas
    /// (en el detalle y el centro, no en las tarjetas: N4A-8).
    @ViewBuilder private func organization(_ entry: RomEntry) -> some View {
        let prefs = state.libraryPrefs
        if prefs.isMovedInApp(entry) {
            Label {
                Text("Se ve en «\(CategoryPaths.display(prefs.categoryPath(entry)))»")
                    .fixedSize(horizontal: false, vertical: true)
            } icon: {
                Image(systemName: MovedBadge.systemImage)
            }
            .font(.footnote)
            .foregroundStyle(.secondary)
            .accessibilityElement(children: .combine)
            .accessibilityLabel("Movido en la app. Se ve en \(CategoryPaths.display(prefs.categoryPath(entry)))")
            .accessibilityIdentifier("game-details-shown-in")
        }
        let tags = prefs.tags(entry)
        if !tags.isEmpty {
            Label {
                Text(tags.joined(separator: " · "))
                    .fixedSize(horizontal: false, vertical: true)
            } icon: {
                Image(systemName: "tag")
            }
            .font(.footnote)
            .foregroundStyle(.secondary)
            .accessibilityElement(children: .combine)
            .accessibilityLabel("Etiquetas: \(tags.joined(separator: ", "))")
            .accessibilityIdentifier("game-details-tags")
        }
    }

    private func hideAction() -> some View {
        VStack(spacing: PocketSpacing.md) {
            Button(role: .destructive) {
                if let entry = state.library.entries.first(where: { $0.id == entryID }) {
                    state.hideCandidate = entry
                }
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
    }

    // MARK: Información técnica

    private struct TechnicalKey: Hashable {
        let id: String
        let cloud: RomEntry.CloudState
        let expanded: Bool
    }

    static var expandsTechnicalInfoByDefault: Bool {
        #if DEBUG
        return DebugArguments.arguments.contains("-expandTechnicalInfo")
        #else
        return false
        #endif
    }

    /// Lee la cabecera con el núcleo fuera del hilo principal, solo con la sección desplegada y
    /// una vez por juego (no vuelve a leer al plegar y desplegar).
    private func loadTechnicalInfo(_ entry: RomEntry) async {
        guard technicalExpanded else { return }
        if case .loaded = technical { return }
        if let problem = entry.problem {
            technical = .failed(problem.message)
            return
        }
        guard entry.cloud == .current else {
            technical = .failed(GameTechnicalInfoLoader.Failure.notDownloaded.message)
            return
        }
        technical = .loading
        let url = Self.technicalURL(entry)
        let console = entry.console
        // Plegar (o salir) cancela esta tarea y, con ella, la lectura en segundo plano: no quedan
        // lecturas de hasta 32 MiB en paralelo ni la sección en «Leyendo…» (auditoría N3, H5).
        let result = await GameTechnicalInfoLoader.loadCancellable(url: url, console: console)
        guard !Task.isCancelled, result != .failure(.cancelled) else {
            if technical == .loading { technical = .idle }
            return
        }
        switch result {
        case .success(let info): technical = .loaded(info)
        case .failure(let failure): technical = .failed(failure.message)
        }
    }

    /// El ROM del juego. En DEBUG, `-demoROMDir <carpeta>` hace que la biblioteca de
    /// demostración lea los ROMs de prueba libres de esa carpeta (capturas con datos reales).
    static func technicalURL(_ entry: RomEntry) -> URL {
        #if DEBUG
        if let dir = DebugArguments.value("-demoROMDir"), entry.url.path.hasPrefix("/demo/") {
            return URL(fileURLWithPath: dir).appendingPathComponent(entry.fileName)
        }
        #endif
        return entry.url
    }

    private func technicalSection(_ entry: RomEntry) -> some View {
        DisclosureGroup(isExpanded: $technicalExpanded) {
            VStack(alignment: .leading, spacing: 0) {
                switch technical {
                case .idle, .loading:
                    HStack(spacing: PocketSpacing.sm) {
                        ProgressView()
                        Text("Leyendo la cabecera…")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                    .frame(minHeight: PocketSpacing.minTouch)
                case .failed(let message):
                    Label(message, systemImage: "info.circle")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.vertical, PocketSpacing.xs)
                case .loaded(let info):
                    ForEach(info.rows) { row in
                        technicalRow(row)
                        Divider()
                    }
                    shaRow(info.sha256)
                }
            }
            .padding(.top, PocketSpacing.xs)
        } label: {
            Label("Información técnica", systemImage: "cpu")
                .font(.headline)
                .foregroundStyle(.primary)
                .frame(minHeight: PocketSpacing.minTouch)
        }
        .accessibilityIdentifier("game-details-technical")
    }

    /// Etiqueta y valor en una línea; si no caben (AX5, columna estrecha), uno debajo de otro.
    private func technicalRow(_ row: GameTechnicalInfo.Row) -> some View {
        let value = HStack(spacing: PocketSpacing.xxs) {
            if row.warning {
                Image(systemName: "exclamationmark.triangle.fill")
                    .foregroundStyle(PocketColor.danger)
                    .accessibilityHidden(true)
            }
            Text(row.value)
        }
        return ViewThatFits(in: .horizontal) {
            HStack(alignment: .firstTextBaseline, spacing: PocketSpacing.md) {
                Text(row.label).foregroundStyle(.secondary)
                Spacer(minLength: PocketSpacing.xs)
                value.multilineTextAlignment(.trailing)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(row.label).foregroundStyle(.secondary)
                value.fixedSize(horizontal: false, vertical: true)
            }
        }
        .font(.subheadline)
        .frame(maxWidth: .infinity, minHeight: PocketSpacing.minTouch, alignment: .leading)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(row.label): \(row.warning ? "aviso, " : "")\(row.value)")
    }

    /// SHA-256 completo, seleccionable y con un botón para copiarlo.
    private func shaRow(_ sha: String) -> some View {
        VStack(alignment: .leading, spacing: PocketSpacing.xs) {
            Text("Huella SHA-256")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Text(sha)
                .font(.footnote.monospaced())
                .textSelection(.enabled)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("game-details-sha256")
            Button {
                UIPasteboard.general.string = sha
                copiedSHA = true
                Task {
                    try? await Task.sleep(for: .seconds(2))
                    copiedSHA = false
                }
            } label: {
                Label(copiedSHA ? "Copiada" : "Copiar huella", systemImage: copiedSHA ? "checkmark" : "doc.on.doc")
                    .font(.subheadline)
                    .frame(minHeight: PocketSpacing.minTouch)
            }
            .pocketGlassButton()
            .accessibilityIdentifier("game-details-copy-sha256")
        }
        .padding(.vertical, PocketSpacing.sm)
    }

    @ViewBuilder private func badges(_ entry: RomEntry) -> some View {
        ConsoleChip(badge: entry.badge)
        if entry.isDuplicate { DuplicateBadge() }
        if state.libraryPrefs.isMovedInApp(entry) { MovedBadge() }
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

    /// Última vez jugado, partida junto al ROM y tamaño, en tres columnas (en una sola columna con
    /// tamaños de accesibilidad, como Android con fuente grande: N3a).
    private func stats(_ entry: RomEntry) -> some View {
        let lastPlayed = state.libraryPrefs.lastPlayed(entry)
        let layout = typeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: PocketSpacing.xs))
            : AnyLayout(HStackLayout(alignment: .top, spacing: 0))
        return layout {
            stat("Jugado", lastPlayed.map(GameStatus.relative) ?? "Nunca")
            if !typeSize.isAccessibilitySize { Divider() }
            stat("Partida", entry.mirrorSaveDate.map(GameStatus.relative) ?? "—")
            if !typeSize.isAccessibilitySize { Divider() }
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

    /// Favorito, estados y ajustes. Si no caben en una fila (AX5 o la columna estrecha del
    /// horizontal), van en columna: nunca se recortan.
    private func secondaryActions(_ entry: RomEntry) -> some View {
        GlassEffectContainer(spacing: PocketSpacing.xs) {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: PocketSpacing.xs) { secondaryButtons(entry) }
                VStack(spacing: PocketSpacing.xs) { secondaryButtons(entry) }
            }
            .labelStyle(.titleAndIcon)
            .font(.subheadline)
            .pocketGlassButton()
        }
    }

    @ViewBuilder private func secondaryButtons(_ entry: RomEntry) -> some View {
        let favorite = state.libraryPrefs.isFavorite(entry)
        Button {
            state.libraryPrefs.toggleFavorite(entry)
        } label: {
            Label("Favorito", systemImage: favorite ? "star.fill" : "star")
                .lineLimit(1)
                .frame(maxWidth: .infinity, minHeight: PocketSpacing.minTouch)
        }
        .accessibilityLabel(favorite ? "Quitar de favoritos" : "Añadir a favoritos")
        Button {} label: {
            Label("Estados", systemImage: "square.stack")
                .lineLimit(1)
                .frame(maxWidth: .infinity, minHeight: PocketSpacing.minTouch)
        }
        .disabled(true)
        .accessibilityHint("Próximamente")
        Button {
            state.gameSettingsEntry = entry
        } label: {
            Label("Ajustes", systemImage: "slider.horizontal.3")
                .lineLimit(1)
                .frame(maxWidth: .infinity, minHeight: PocketSpacing.minTouch)
        }
        .accessibilityLabel("Ajustes del juego")
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

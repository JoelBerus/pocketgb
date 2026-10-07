import SwiftUI

/// Pantallas dentro del centro de ajustes del juego.
enum GameCenterRoute: Hashable {
    case rename
    case category
    case tags
    /// Ajustes › Partidas de este juego (copias y apartadas), por su huella.
    case saves(fingerprint: String)
    /// N5 · Portada (por la huella confirmada).
    case cover(fingerprint: String)
    /// N6 · Momentos del juego (sin abrirlo).
    case moments(fingerprint: String)
    /// N6 · Progreso: hitos, plantilla, porcentaje y lector Pokémon.
    case progress(fingerprint: String)
}

/// N4 · centro de ajustes del juego (sustituye a la hoja de ajustes por juego): todo lo que la app
/// recuerda de un juego, sin tocar sus archivos. Desde el detalle («Ajustes») y el menú contextual
/// («Ajustes del juego»); la pausa sigue sin él (decisión de N4 Android).
/// - Nombre (alias), Categoría (virtual, ND3: «Cambiar» y «Volver a su carpeta»), Etiquetas.
/// - Portada (N5): qué se elige y qué se ve, con «Cambiar». Progreso y momentos (N6).
/// - Partida: las copias de este juego (lo mismo que Ajustes › Partidas).
/// - Color y paleta (GB) o tipo de partida, reloj y BIOS (GBA), como antes.
/// - Ocultar, con confirmación.
/// Categoría y etiquetas solo se escriben con la huella confirmada: si falta, se lee el juego
/// («Leyendo el juego…»; nunca se descarga de iCloud para esto).
struct GameCenterView: View {
    @Environment(AppState.self) private var state
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var typeSize
    /// El juego tal como estaba al abrir la hoja; `entry` lo relee con su huella actual.
    private let opened: RomEntry
    @State private var path: [GameCenterRoute]
    @State private var confirmation: Confirmation = .checking
    @State private var confirmingHide = false

    enum Confirmation: Equatable {
        case checking
        case reading
        case confirmed
        case unavailable
    }

    init(entry: RomEntry, initialPath: [GameCenterRoute] = []) {
        opened = entry
        _path = State(initialValue: initialPath)
    }

    private var entry: RomEntry {
        state.library.entries.first { $0.id == opened.id } ?? opened
    }

    /// Pantalla con la que se abre: la raíz del centro (en DEBUG, `-centerRoute category|tags|rename`
    /// para las capturas).
    static func initialPath(for entry: RomEntry) -> [GameCenterRoute] {
        #if DEBUG
        switch DebugArguments.value("-centerRoute") {
        case "category": return [.category]
        case "tags": return [.tags]
        case "rename": return [.rename]
        case "cover":
            if let fingerprint = entry.fingerprint { return [.cover(fingerprint: fingerprint)] }
            return []
        default: return []
        }
        #else
        return []
        #endif
    }

    private var prefs: LibraryPreferences { state.libraryPrefs }

    var body: some View {
        let entry = self.entry
        NavigationStack(path: $path) {
            Form {
                identitySection(entry)
                organizationSection(entry)
                coverSection(entry)
                progressSection(entry)
                saveSection(entry)
                GameEmulationSections(entry: entry)
                Section {
                    Button("Ocultar de PocketGB", systemImage: "eye.slash", role: .destructive) {
                        confirmingHide = true
                    }
                    .accessibilityIdentifier("game-center-hide")
                } footer: {
                    Text("Ocultar no borra el ROM ni la partida. Se recupera en Ajustes › Biblioteca.")
                }
            }
            .scrollContentBackground(.hidden)
            .background(PocketColor.backgroundBase.ignoresSafeArea())
            .navigationTitle("Ajustes del juego")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Listo") { dismiss() }
                        .accessibilityIdentifier("game-center-done")
                }
            }
            .navigationDestination(for: GameCenterRoute.self) { route in
                switch route {
                case .rename: RenameGamePage(entry: entry)
                case .category: CategoryPickerView(entryID: entry.id)
                case .tags: TagEditorView(entryID: entry.id)
                case .saves(let fingerprint): SaveBackupsView(fingerprint: fingerprint)
                case .cover(let fingerprint): CoverCenterView(entryID: entry.id, fingerprint: fingerprint)
                case .moments(let fingerprint): MomentsView(context: .detail(entryID: entry.id, fingerprint: fingerprint))
                case .progress(let fingerprint): ProgressEditorView(entryID: entry.id, fingerprint: fingerprint)
                }
            }
            .alert("¿Ocultar “\(prefs.displayTitle(entry))”?", isPresented: $confirmingHide) {
                Button("Ocultar", role: .destructive) {
                    let target = entry
                    dismiss()
                    state.hide(target)
                }
                Button("Cancelar", role: .cancel) {}
            } message: {
                Text("El ROM sigue en tu carpeta y no se modifica. La partida y sus copias se conservan en este iPhone y junto al ROM. Puedes volver a mostrarlo en Ajustes › Biblioteca.")
            }
        }
        .task(id: entry.id) { await confirmFingerprint() }
        .accessibilityIdentifier("game-center")
    }

    // MARK: Huella

    /// Categoría y etiquetas necesitan la huella confirmada; si no la hay, se lee el juego (local o ya
    /// descargado). Sin poder leerlo, esas filas se quedan deshabilitadas con su motivo.
    private func confirmFingerprint() async {
        if prefs.confirmedFingerprint(of: entry) != nil {
            confirmation = .confirmed
            return
        }
        guard entry.isPlayable else {
            confirmation = .unavailable
            return
        }
        confirmation = .reading
        switch await state.library.confirmFingerprint(for: entry.id) {
        case .confirmed: confirmation = .confirmed
        case .unavailable: confirmation = .unavailable
        }
    }

    private var canOrganize: Bool {
        // Basta con la huella confirmada (también si el cálculo en segundo plano llega después).
        prefs.confirmedFingerprint(of: entry) != nil
    }

    // MARK: Secciones

    private func identitySection(_ entry: RomEntry) -> some View {
        Section {
            // Con tamaños de accesibilidad, la miniatura encima: el texto tiene todo el ancho.
            let layout = typeSize.isAccessibilitySize
                ? AnyLayout(VStackLayout(alignment: .leading, spacing: PocketSpacing.sm))
                : AnyLayout(HStackLayout(spacing: PocketSpacing.sm))
            layout {
                GameArtworkView(entry: entry, cornerRadius: PocketRadius.thumbnail, compact: true)
                    .frame(width: 64)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: PocketSpacing.xxs) {
                    Text(prefs.displayTitle(entry))
                        .font(.headline)
                        .fixedSize(horizontal: false, vertical: true)
                    HStack(spacing: PocketSpacing.xs) {
                        ConsoleChip(badge: entry.badge)
                        if entry.isDuplicate { DuplicateBadge() }
                    }
                    Text(entry.locationText)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityLabel("Archivo: \(entry.locationText)")
                }
            }
            .accessibilityElement(children: .combine)
            NavigationLink(value: GameCenterRoute.rename) {
                LabeledContent("Nombre") {
                    Text(prefs.displayTitle(entry))
                        .lineLimit(2)
                }
            }
            .accessibilityIdentifier("game-center-name")
        }
    }

    @ViewBuilder private func organizationSection(_ entry: RomEntry) -> some View {
        let moved = prefs.isMovedInApp(entry)
        let category = prefs.categoryPath(entry)
        let tags = prefs.tags(entry)
        Section {
            if !canOrganize {
                confirmationRow
            }
            VStack(alignment: .leading, spacing: PocketSpacing.xs) {
                ViewThatFits(in: .horizontal) {
                    HStack(alignment: .firstTextBaseline) {
                        Text("Categoría")
                        Spacer(minLength: PocketSpacing.sm)
                        categoryValue(category, moved: moved)
                    }
                    VStack(alignment: .leading, spacing: PocketSpacing.xxs) {
                        Text("Categoría")
                        categoryValue(category, moved: moved)
                    }
                }
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("game-center-category")
                if moved {
                    Text("Su carpeta: \(CategoryPaths.display(entry.folderPath))")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("game-center-own-folder")
                }
                // «Volver a su carpeta» junto a «Cambiar» (auditoría N4 Android, H2).
                GlassEffectContainer(spacing: PocketSpacing.xs) {
                    // En una fila si caben sin partir palabras; si no (texto grande), uno bajo otro.
                    ViewThatFits(in: .horizontal) {
                        HStack(spacing: PocketSpacing.xs) { categoryButtons(entry, moved: moved, oneLine: true) }
                        VStack(alignment: .leading, spacing: PocketSpacing.xs) {
                            categoryButtons(entry, moved: moved, oneLine: false)
                        }
                    }
                }
                .padding(.top, PocketSpacing.xxs)
            }
            .disabled(!canOrganize)
            NavigationLink(value: GameCenterRoute.tags) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Etiquetas")
                    Text(tags.isEmpty ? "Ninguna" : tags.joined(separator: " · "))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel("Etiquetas: \(tags.isEmpty ? "ninguna" : tags.joined(separator: ", "))")
            }
            .disabled(!canOrganize)
            .accessibilityIdentifier("game-center-tags")
        } header: {
            Text("Organización")
        } footer: {
            Text("Solo en PocketGB: el archivo no se mueve ni se renombra. Para que se vea también en otros emuladores, mueve la carpeta de verdad en Archivos.")
        }
    }

    private func categoryValue(_ category: [String], moved: Bool) -> some View {
        HStack(spacing: PocketSpacing.xs) {
            Text(CategoryPaths.display(category))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            if moved { MovedBadge() }
        }
    }

    @ViewBuilder private func categoryButtons(_ entry: RomEntry, moved: Bool, oneLine: Bool) -> some View {
        Button { path.append(.category) } label: {
            Label("Cambiar", systemImage: "folder")
                .lineLimit(oneLine ? 1 : nil)
                .fixedSize(horizontal: oneLine, vertical: !oneLine)
                .labelStyle(.titleAndIcon)
        }
        .font(.subheadline)
        .pocketGlassButton()
        .accessibilityLabel("Cambiar categoría")
        .accessibilityIdentifier("game-center-change-category")
        if moved {
            Button { prefs.returnToFolder(entry) } label: {
                Label("Volver a su carpeta", systemImage: "arrow.uturn.backward")
                    .lineLimit(oneLine ? 1 : nil)
                    .fixedSize(horizontal: oneLine, vertical: !oneLine)
                    .labelStyle(.titleAndIcon)
            }
            .font(.subheadline)
            .pocketGlassButton()
            .accessibilityIdentifier("game-center-return-to-folder")
        }
    }

    @ViewBuilder private var confirmationRow: some View {
        switch confirmation {
        case .checking, .reading:
            HStack(spacing: PocketSpacing.sm) {
                ProgressView()
                Text("Leyendo el juego…")
                    .foregroundStyle(.secondary)
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("game-center-reading")
        case .unavailable:
            Label(entry.cloud == .current
                  ? "No se pudo leer el juego: la categoría y las etiquetas no se pueden cambiar ahora."
                  : "Descarga el juego de iCloud para cambiar su categoría y sus etiquetas.",
                  systemImage: "exclamationmark.circle")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("game-center-unavailable")
        case .confirmed:
            EmptyView()
        }
    }

    /// N5 · «Portada · Automática · se ve: Imagen de la carpeta» con «Cambiar».
    @ViewBuilder private func coverSection(_ entry: RomEntry) -> some View {
        let fingerprint = prefs.confirmedFingerprint(of: entry)
        let covers = state.covers
        Section {
            if let fingerprint {
                NavigationLink(value: GameCenterRoute.cover(fingerprint: fingerprint)) {
                    VStack(alignment: .leading, spacing: 2) {
                        Label("Portada", systemImage: "photo")
                        Text("\(covers.choice(for: fingerprint).title) · se ve: \(covers.resolve(entry, fingerprint: fingerprint).title)")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityHint("Cambiar")
                }
                .accessibilityIdentifier("game-center-cover")
            } else {
                LabeledContent {
                    Text(confirmation == .unavailable ? "No disponible" : "Leyendo el juego…")
                } label: {
                    Label("Portada", systemImage: "photo")
                }
                .foregroundStyle(.secondary)
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("game-center-cover")
            }
        } footer: {
            Text("Una imagen tuya, una captura del juego o la generada. Solo en este iPhone.")
        }
    }

    /// N6 · Momentos y Progreso (por la huella confirmada).
    @ViewBuilder private func progressSection(_ entry: RomEntry) -> some View {
        let fingerprint = prefs.confirmedFingerprint(of: entry)
        Section {
            if let fingerprint {
                NavigationLink(value: GameCenterRoute.moments(fingerprint: fingerprint)) {
                    Label("Momentos", systemImage: "bookmark")
                }
                .accessibilityIdentifier("game-center-moments")
                NavigationLink(value: GameCenterRoute.progress(fingerprint: fingerprint)) {
                    VStack(alignment: .leading, spacing: 2) {
                        Label("Progreso", systemImage: "flag.checkered")
                        Text(ProgressSummary.line(state.progress.progress(fingerprint)))
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .accessibilityElement(children: .combine)
                }
                .accessibilityIdentifier("game-center-progress")
            } else {
                ForEach([("Momentos", "bookmark"), ("Progreso", "flag.checkered")], id: \.0) { title, icon in
                    LabeledContent {
                        Text(confirmation == .unavailable ? "No disponible" : "Leyendo el juego…")
                    } label: {
                        Label(title, systemImage: icon)
                    }
                    .foregroundStyle(.secondary)
                    .accessibilityElement(children: .combine)
                }
            }
        } header: {
            Text("Momentos y progreso")
        } footer: {
            Text("Los momentos guardan un instante exacto del juego; el progreso, tu tiempo de juego y los hitos que marques. Solo en este iPhone.")
        }
    }

    @ViewBuilder private func saveSection(_ entry: RomEntry) -> some View {
        // La partida va por la huella del núcleo: solo con la confirmada (nunca la de otro juego).
        let fingerprint = prefs.confirmedFingerprint(of: entry)
        Section {
            if let fingerprint {
                NavigationLink(value: GameCenterRoute.saves(fingerprint: fingerprint)) {
                    Label("Copias de la partida", systemImage: "externaldrive")
                }
                .accessibilityIdentifier("game-center-saves")
            } else {
                Label("Copias de la partida", systemImage: "externaldrive")
                    .foregroundStyle(.secondary)
            }
        } header: {
            Text("Partida")
        } footer: {
            Text("Las copias de seguridad y las partidas apartadas de este juego: lo mismo que Ajustes › Partidas, solo con él.")
        }
    }
}

// MARK: - Nombre

/// Alias visual dentro del centro (como `RenameGameView`): vacío vuelve al título del cartucho.
struct RenameGamePage: View {
    @Environment(AppState.self) private var state
    @Environment(\.dismiss) private var dismiss
    let entry: RomEntry
    @State private var name = ""

    var body: some View {
        Form {
            Section {
                TextField("Nombre", text: $name)
                    .textInputAutocapitalization(.words)
                    .submitLabel(.done)
                    .onSubmit(save)
                    .onChange(of: name) { _, value in
                        if value.count > 80 { name = String(value.prefix(80)) }
                    }
                    .accessibilityIdentifier("game-center-name-field")
            } footer: {
                Text("Vacía el campo para volver a “\(entry.title)”. El archivo y las partidas no cambian.")
            }
        }
        .scrollContentBackground(.hidden)
        .background(PocketColor.backgroundBase.ignoresSafeArea())
        .navigationTitle("Nombre")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Guardar", action: save)
                    .accessibilityIdentifier("game-center-name-save")
            }
        }
        .task {
            let current = state.libraryPrefs.displayTitle(entry)
            name = current == entry.title ? "" : current
        }
    }

    private func save() {
        state.libraryPrefs.setAlias(name, for: entry)
        dismiss()
    }
}

// MARK: - «Mostrar en categoría…»

/// N4 · categoría virtual (ND3): elige una categoría que ya existe (carpetas con juegos, sus carpetas
/// padre y otras virtuales) o escribe una nueva. El archivo no se mueve.
struct CategoryPickerView: View {
    @Environment(AppState.self) private var state
    @Environment(\.dismiss) private var dismiss
    let entryID: String
    @State private var typed = ""
    @State private var failure: String?
    @FocusState private var fieldFocused: Bool

    private var prefs: LibraryPreferences { state.libraryPrefs }

    var body: some View {
        if let entry = state.library.entries.first(where: { $0.id == entryID }) {
            content(entry)
        } else {
            ContentUnavailableView("Juego no disponible", systemImage: "questionmark.folder")
        }
    }

    private func content(_ entry: RomEntry) -> some View {
        let known = LibraryTree.knownPaths(state.library.entries, prefs: prefs.data)
        let current = prefs.categoryPath(entry)
        return Form {
            Section {
                ForEach([[]] + known, id: \.self) { option in
                    Button { pick(option, entry) } label: {
                        HStack(spacing: PocketSpacing.sm) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(option.isEmpty ? "Sin categoría" : (option.last ?? ""))
                                    .foregroundStyle(Color.primary)
                                    .fixedSize(horizontal: false, vertical: true)
                                let notes = Self.notes(option: option, entry: entry, current: current)
                                if !notes.isEmpty {
                                    Text(notes)
                                        .font(.footnote)
                                        .foregroundStyle(Color.secondary)
                                        .fixedSize(horizontal: false, vertical: true)
                                }
                            }
                            .padding(.leading, CGFloat(max(option.count - 1, 0)) * PocketSpacing.md)
                            Spacer(minLength: PocketSpacing.xs)
                            if option == current {
                                Image(systemName: "checkmark")
                                    .foregroundStyle(PocketColor.accent)
                                    .accessibilityHidden(true)
                            }
                        }
                        .contentShape(Rectangle())
                    }
                    .accessibilityLabel(CategoryPaths.display(option))
                    .accessibilityValue(Self.notes(option: option, entry: entry, current: current))
                    .accessibilityAddTraits(option == current ? .isSelected : [])
                    .accessibilityIdentifier("category-option-\(CategoryPaths.key(option))")
                }
            } header: {
                Text("Categorías que ya existen")
            }
            Section {
                TextField("Pokémon/Para jugar", text: $typed)
                    .textInputAutocapitalization(.sentences)
                    .autocorrectionDisabled()
                    .submitLabel(.done)
                    .focused($fieldFocused)
                    .onSubmit { submit(entry, known: known) }
                    .accessibilityLabel("Nueva categoría")
                    .accessibilityIdentifier("category-new-field")
                if let notice = matchNotice(known: known) {
                    Label(notice, systemImage: "info.circle")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityElement(children: .combine)
                        .accessibilityIdentifier("category-new-notice")
                }
                if let failure {
                    Label(failure, systemImage: "exclamationmark.triangle.fill")
                        .font(.footnote)
                        .foregroundStyle(PocketColor.danger)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityElement(children: .combine)
                        .accessibilityIdentifier("category-new-error")
                }
                Button("Mostrar aquí", systemImage: "folder.badge.plus") { submit(entry, known: known) }
                    .disabled(typed.trimmingCharacters(in: .whitespaces).isEmpty)
                    .accessibilityIdentifier("category-new-apply")
            } header: {
                Text("Nueva categoría")
            } footer: {
                Text("Usa «/» para meterla dentro de otra. Ningún nivel puede empezar por «.» ni por «_», y como mucho \(LibraryScanner.maxFolderDepth) niveles. Si ya existe con otras mayúsculas o acentos, se usa la existente.")
            }
        }
        .scrollContentBackground(.hidden)
        .background(PocketColor.backgroundBase.ignoresSafeArea())
        .navigationTitle("Mostrar en categoría")
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: typed) { failure = nil }
    }

    /// «su carpeta», «ahora» o los dos.
    static func notes(option: [String], entry: RomEntry, current: [String]) -> String {
        var notes: [String] = []
        if option == entry.folderPath { notes.append("Su carpeta") }
        if option == current { notes.append("Ahora") }
        if option.count > 1 { notes.insert(CategoryPaths.display(option), at: 0) }
        return notes.joined(separator: " · ")
    }

    /// Aviso en vivo: la categoría escrita coincide con una existente (sin mayúsculas ni acentos).
    private func matchNotice(known: [[String]]) -> String? {
        guard case .success(let path) = CategoryPaths.parse(typed) else { return nil }
        let matched = CategoryPaths.matchExisting(path, known: known)
        guard let last = matched.indices.last(where: { matched[$0] != path[$0] }) else { return nil }
        return "Se usará la que ya existe: \(CategoryPaths.display(Array(matched.prefix(last + 1))))"
    }

    private func submit(_ entry: RomEntry, known: [[String]]) {
        switch CategoryPaths.parse(typed) {
        case .failure(let problem):
            failure = problem.message
        case .success(let path):
            pick(CategoryPaths.matchExisting(path, known: known), entry)
        }
    }

    private func pick(_ path: [String], _ entry: RomEntry) {
        if prefs.moveToCategory(path, entry: entry) {
            fieldFocused = false
            dismiss()
        } else {
            failure = "PocketGB aún no ha reconocido este juego. Vuelve a intentarlo en un momento."
        }
    }
}

// MARK: - Etiquetas

/// N4 · editor de etiquetas: añadir (Intro o «+»), quitar y las que ya se usan en otros juegos.
struct TagEditorView: View {
    @Environment(AppState.self) private var state
    let entryID: String
    @State private var typed = ""
    @State private var message: String?
    @FocusState private var fieldFocused: Bool

    private var prefs: LibraryPreferences { state.libraryPrefs }

    var body: some View {
        if let entry = state.library.entries.first(where: { $0.id == entryID }) {
            content(entry)
        } else {
            ContentUnavailableView("Juego no disponible", systemImage: "questionmark.folder")
        }
    }

    private func content(_ entry: RomEntry) -> some View {
        let tags = prefs.tags(entry)
        let suggestions = LibraryQuery.tagOptions(state.library.entries, prefs: prefs.data)
            .filter { option in !tags.contains { Tags.same($0, option.tag) } }
        return Form {
            Section {
                HStack(spacing: PocketSpacing.sm) {
                    TextField("Nueva etiqueta", text: $typed)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .submitLabel(.done)
                        .focused($fieldFocused)
                        .onSubmit { add(typed, entry) }
                        .onChange(of: typed) { _, value in
                            if value.count > Tags.maxLength + 1 { typed = String(value.prefix(Tags.maxLength + 1)) }
                            message = nil
                        }
                        .accessibilityIdentifier("tag-new-field")
                    Button { add(typed, entry) } label: {
                        Image(systemName: "plus.circle.fill")
                            .font(.title2)
                            .frame(minWidth: PocketSpacing.minTouch, minHeight: PocketSpacing.minTouch)
                    }
                    .buttonStyle(.borderless)
                    .disabled(Tags.normalize(typed) == nil)
                    .accessibilityLabel("Añadir la etiqueta")
                    .accessibilityIdentifier("tag-add")
                }
                if let message {
                    Label(message, systemImage: "exclamationmark.circle")
                        .font(.footnote)
                        .foregroundStyle(PocketColor.danger)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityElement(children: .combine)
                        .accessibilityIdentifier("tag-message")
                }
            } header: {
                Text("Añadir")
            } footer: {
                Text("Hasta \(Tags.maxPerGame) por juego y \(Tags.maxLength) caracteres cada una. «RPG» y «rpg» son la misma.")
            }
            Section {
                if tags.isEmpty {
                    Text("Ninguna todavía")
                        .foregroundStyle(.secondary)
                }
                ForEach(tags, id: \.self) { tag in
                    HStack {
                        Label(tag, systemImage: "tag")
                        Spacer()
                        Button(role: .destructive) { prefs.removeTag(tag, from: entry) } label: {
                            Image(systemName: "minus.circle.fill")
                                .font(.title3)
                                .frame(minWidth: PocketSpacing.minTouch, minHeight: PocketSpacing.minTouch)
                        }
                        .buttonStyle(.borderless)
                        .accessibilityLabel("Quitar la etiqueta \(tag)")
                        .accessibilityIdentifier("tag-remove-\(tag)")
                    }
                }
            } header: {
                Text("Etiquetas de este juego")
            }
            if !suggestions.isEmpty {
                Section {
                    FlowLayout {
                        ForEach(suggestions) { option in
                            Button { add(option.tag, entry) } label: {
                                HStack(spacing: PocketSpacing.xxs) {
                                    Image(systemName: "plus")
                                        .foregroundStyle(PocketColor.accent)
                                    Text(option.tag)
                                        .foregroundStyle(Color.primary)
                                        .fixedSize(horizontal: false, vertical: true)
                                }
                                .font(.subheadline)
                                .padding(.horizontal, PocketSpacing.sm)
                                .frame(minHeight: PocketSpacing.minTouch)
                                .background(.quaternary, in: Capsule())
                                .contentShape(Capsule())
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("Añadir \(option.tag)")
                            .accessibilityIdentifier("tag-suggestion-\(option.tag)")
                        }
                    }
                    .padding(.vertical, PocketSpacing.xxs)
                } header: {
                    Text("Etiquetas que ya usas")
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(PocketColor.backgroundBase.ignoresSafeArea())
        .navigationTitle("Etiquetas")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func add(_ raw: String, _ entry: RomEntry) {
        let result = prefs.addTag(raw, to: entry)
        if result == .added {
            if raw == typed { typed = "" }
            message = nil
        } else {
            message = result.message
        }
    }
}

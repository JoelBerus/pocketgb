import SwiftUI
import UIKit

/// Dónde se abre la pantalla de Momentos.
enum MomentsContext: Equatable {
    /// En la pausa, con el juego abierto: crear, cargar y recuperar la posición exacta.
    case pause
    /// En el detalle, sin abrir el juego: cargar abre el juego; «Recuperar su partida» instala la partida del momento.
    case detail(entryID: String, fingerprint: String)
}

/// N6 · pantalla «Momentos» de un juego (§3.3): momentos con miniatura, nombre, fecha, tiempo jugado, etiquetas y nota,
/// agrupados por colección y filtrables por etiqueta; el anillo «Antes de cargar» con «Recuperar» en un toque.
/// Cargar pide confirmación y explica que cambia también la partida del juego.
struct MomentsView: View {
    @Environment(AppState.self) private var state
    let context: MomentsContext

    @State private var snapshot = MomentStore.Snapshot()
    @State private var thumbnails: [String: UIImage] = [:]
    @State private var damaged = false
    @State private var filterTag: String?
    @State private var creating = false
    @State private var newName = ""
    @State private var editing: MomentStore.Moment?
    @State private var actions: Target?
    @State private var confirmLoad: Target?
    @State private var confirmDelete: Target?
    @State private var confirmInstall: Target?

    /// Un momento o una entrada del anillo.
    struct Target: Identifiable, Equatable {
        let kind: MomentStore.Kind
        let moment: MomentStore.Moment
        var id: String { kind.rawValue + moment.id }
    }

    private var fingerprint: String? {
        switch context {
        case .pause: state.session?.info.fingerprint
        case .detail(_, let fingerprint): fingerprint
        }
    }

    private var store: MomentStore? {
        switch context {
        case .pause: state.momentStore
        case .detail(_, let fingerprint): state.momentStoreFor(fingerprint)
        }
    }

    private var console: Console {
        switch context {
        case .pause: state.session?.info.console ?? .gameBoy
        case .detail(let id, _): state.library.entries.first { $0.id == id }?.console ?? .gameBoy
        }
    }

    private var currentConfig: [String: String] {
        switch context {
        case .pause: return state.currentMomentConfig
        case .detail(let id, let fingerprint):
            guard let entry = state.library.entries.first(where: { $0.id == id }) else { return [:] }
            return state.momentConfig(for: entry, fingerprint: fingerprint)
        }
    }

    var body: some View {
        List {
            guideSection
            if context == .pause {
                Section {
                    Button("Nuevo momento", systemImage: "plus.circle.fill") {
                        newName = state.suggestedMomentName()
                        creating = true
                    }
                    .accessibilityIdentifier("moments-new")
                }
            }
            if damaged {
                Section {
                    Label("No se pudo leer la lista de momentos. No se ha borrado nada; se reconstruirá al guardar el siguiente.",
                          systemImage: "exclamationmark.triangle")
                        .foregroundStyle(PocketColor.danger)
                }
            }
            tagFilter
            momentSections
            beforeLoadSection
        }
        .scrollContentBackground(.hidden)
        .background(PocketColor.backgroundBase.ignoresSafeArea())
        .navigationTitle("Momentos")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: state.momentsRevision) { reload() }
        #if DEBUG
        .task { await debugOpen() }
        #endif
        .alert("Nuevo momento", isPresented: $creating) {
            TextField("Nombre", text: $newName)
            Button("Crear") { state.createMoment(name: newName) }
            Button("Cancelar", role: .cancel) {}
        } message: {
            Text("Guarda la posición exacta, la partida de este instante y una miniatura. No cambia tu partida.")
        }
        .confirmationDialog(actions.map { $0.moment.name } ?? "", isPresented: binding($actions),
                            titleVisibility: .visible, presenting: actions) { target in
            actionButtons(target)
        }
        .alert(confirmLoad.map { $0.kind == .moment ? "¿Cargar «\($0.moment.name)»?" : "¿Recuperar lo de antes?" } ?? "",
               isPresented: binding($confirmLoad), presenting: confirmLoad) { target in
            Button(target.kind == .moment ? "Cargar" : "Recuperar", role: .destructive) { load(target) }
                .accessibilityIdentifier("moments-confirm-load")
            Button("Cancelar", role: .cancel) {}
        } message: { target in
            Text(loadMessage(target))
        }
        .alert("¿Recuperar la partida de «\(confirmInstall?.moment.name ?? "")»?", isPresented: binding($confirmInstall),
               presenting: confirmInstall) { target in
            Button("Recuperar partida", role: .destructive) { install(target) }
            Button("Cancelar", role: .cancel) {}
        } message: { _ in
            Text("Se instala la partida del cartucho guardada en este momento. La de ahora queda en «Antes de cargar» y en las copias de seguridad. Úsalo si el momento ya no carga.")
        }
        .alert("¿Borrar «\(confirmDelete?.moment.name ?? "")»?", isPresented: binding($confirmDelete),
               presenting: confirmDelete) { target in
            Button("Borrar", role: .destructive) {
                if let store { state.deleteMoment(store, target.kind, target.moment.id) }
            }
            Button("Cancelar", role: .cancel) {}
        } message: { _ in
            Text("Se borra este momento. Tu partida no cambia.")
        }
        .alert(state.momentNotice?.title ?? "", isPresented: Binding(get: { state.momentNotice != nil },
                                                                     set: { if !$0 { state.momentNotice = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(state.momentNotice?.message ?? "")
        }
        .sheet(item: $editing) { moment in
            if let store {
                MomentEditor(moment: moment, collections: collections) { name, tags, collection, note in
                    state.updateMoment(store, moment.id, name: name, tags: tags, collection: collection, note: note)
                }
            }
        }
        .accessibilityIdentifier("moments")
    }

    // MARK: Secciones

    private var guideSection: some View {
        Section {
            Label {
                Text("Cargar un momento cambia también la partida del juego. Antes se guarda lo de ahora en «Antes de cargar», y «Recuperar» lo devuelve en un toque.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            } icon: {
                Image(systemName: "info.circle").foregroundStyle(PocketColor.accent)
            }
        }
    }

    private var allTags: [String] {
        var seen = Set<String>()
        return snapshot.moments.flatMap(\.tags).filter { seen.insert($0.lowercased()).inserted }.sorted()
    }

    @ViewBuilder private var tagFilter: some View {
        let tags = allTags
        if !tags.isEmpty {
            Section {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: PocketSpacing.xs) {
                        chip("Todas", selected: filterTag == nil) { filterTag = nil }
                        ForEach(tags, id: \.self) { tag in
                            chip(tag, selected: filterTag?.lowercased() == tag.lowercased()) { filterTag = tag }
                        }
                    }
                    .padding(.vertical, PocketSpacing.xxs)
                }
            } header: {
                Text("Filtrar por etiqueta")
            }
        }
    }

    private func chip(_ title: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.subheadline.weight(selected ? .semibold : .regular))
                .padding(.horizontal, PocketSpacing.sm)
                .frame(minHeight: PocketSpacing.minTouch)
                .background(selected ? PocketColor.accent.opacity(0.22) : PocketColor.backgroundElevated, in: Capsule())
                .overlay(Capsule().strokeBorder(selected ? PocketColor.accent : .clear, lineWidth: 1.5))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }

    private var collections: [String] {
        var seen = Set<String>()
        let used = snapshot.moments.compactMap(\.collection).filter { seen.insert($0.lowercased()).inserted }
        return (["Principal", "Experimentos"] + used).reduce(into: [String]()) { list, name in
            if !list.contains(where: { $0.lowercased() == name.lowercased() }) { list.append(name) }
        }
    }

    @ViewBuilder private var momentSections: some View {
        let visible = snapshot.moments.filter { m in
            filterTag.map { tag in m.tags.contains { $0.lowercased() == tag.lowercased() } } ?? true
        }
        if visible.isEmpty {
            Section {
                ContentUnavailableView {
                    Label(snapshot.moments.isEmpty ? "Sin momentos" : "Ninguno con esa etiqueta", systemImage: "bookmark")
                } description: {
                    Text(snapshot.moments.isEmpty
                         ? (context == .pause ? "Toca «Nuevo momento» para guardar este instante."
                                              : "Crea momentos desde la pausa del juego.")
                         : "Elige otra etiqueta o «Todas».")
                }
            }
        } else {
            // Por colección: las que tienen nombre, en orden alfabético, y al final las que no tienen.
            let groups = Dictionary(grouping: visible) { $0.collection ?? "" }
            let names = groups.keys.sorted { a, b in
                a.isEmpty != b.isEmpty ? !a.isEmpty : a.localizedStandardCompare(b) == .orderedAscending
            }
            ForEach(names, id: \.self) { name in
                Section(name.isEmpty ? (names.count == 1 ? "Momentos" : "Sin colección") : name) {
                    ForEach(groups[name] ?? []) { moment in
                        Button { actions = Target(kind: .moment, moment: moment) } label: {
                            MomentRow(moment: moment, thumbnail: thumbnails[MomentStore.Kind.moment.rawValue + moment.id],
                                      console: console)
                        }
                        .buttonStyle(.plain)
                        .accessibilityHint("Opciones del momento")
                        .accessibilityIdentifier("moment-\(moment.id)")
                    }
                }
            }
        }
    }

    @ViewBuilder private var beforeLoadSection: some View {
        Section {
            if snapshot.beforeLoad.isEmpty {
                Text("Vacío. Al cargar un momento, lo de antes se guarda aquí.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            ForEach(snapshot.beforeLoad) { entry in
                VStack(alignment: .leading, spacing: PocketSpacing.xs) {
                    MomentRow(moment: entry, thumbnail: thumbnails[MomentStore.Kind.beforeLoad.rawValue + entry.id],
                              console: console)
                    recoverButton(entry)
                }
                .swipeActions {
                    Button("Borrar", systemImage: "trash", role: .destructive) {
                        confirmDelete = Target(kind: .beforeLoad, moment: entry)
                    }
                }
            }
        } header: {
            Text("Antes de cargar")
        } footer: {
            Text(context == .pause
                 ? "Las 3 últimas posiciones de antes de cargar, aparte de las copias de seguridad. «Recuperar» vuelve a esa posición y a su partida; lo de ahora también se guarda aquí."
                 : "Las 3 últimas posiciones de antes de cargar. Desde aquí se recupera la partida; la posición exacta, desde la pausa del juego.")
        }
    }

    @ViewBuilder private func recoverButton(_ entry: MomentStore.Moment) -> some View {
        let target = Target(kind: .beforeLoad, moment: entry)
        switch context {
        case .pause:
            Button("Recuperar", systemImage: "arrow.uturn.backward") { confirmLoad = target }
                .pocketGlassButton()
                .disabled(!entry.hasState)
                .accessibilityHint(entry.hasState ? "" : "Solo tiene la partida: recupérala desde el detalle del juego")
                .accessibilityIdentifier("before-load-recover-\(entry.id)")
        case .detail:
            Button("Recuperar partida", systemImage: "arrow.uturn.backward") { confirmInstall = target }
                .pocketGlassButton()
                .disabled(!entry.hasSRAM)
                .accessibilityIdentifier("before-load-recover-\(entry.id)")
        }
    }

    @ViewBuilder private func actionButtons(_ target: Target) -> some View {
        let moment = target.moment
        if moment.hasState {
            Button(context == .pause ? "Cargar" : "Abrir el juego y cargar") { confirmLoad = target }
        }
        if case .detail = context, moment.hasSRAM {
            Button("Recuperar su partida") { confirmInstall = target }
        }
        Button("Editar") { editing = moment }
        Button("Borrar", role: .destructive) { confirmDelete = target }
        Button("Cancelar", role: .cancel) {}
    }

    private func loadMessage(_ target: Target) -> String {
        var text: String
        if target.kind == .moment {
            text = "Cargar un momento cambia también la partida del juego: vuelve a la del instante en que lo creaste. Lo de ahora se guarda antes en «Antes de cargar» (las 3 últimas) y la partida anterior queda en las copias de seguridad. «Continuar» no cambia."
            if case .detail = context { text += " Se abrirá el juego y se cargará en pausa." }
        } else {
            text = "Vuelves a la posición y a la partida de antes de cargar. Lo de ahora también se guarda en «Antes de cargar», así que puedes ir y volver."
        }
        let diffs = MomentConfig.differences(target.moment.config, currentConfig)
        if !diffs.isEmpty {
            text += " Ojo: se creó con otra configuración (\(diffs.map(MomentConfig.describe).joined(separator: ", "))). Si no carga, vuelve a ponerla en los ajustes del juego."
        }
        return text
    }

    // MARK: Acciones

    private func load(_ target: Target) {
        switch context {
        case .pause:
            state.loadMoment(target.kind, target.moment)
        case .detail(let id, let fingerprint):
            guard let entry = state.library.entries.first(where: { $0.id == id }) else { return }
            state.openAndLoadMoment(entry, fingerprint: fingerprint, momentID: target.moment.id)
        }
    }

    private func install(_ target: Target) {
        guard let fingerprint else { return }
        state.installMomentSave(fingerprint: fingerprint, target.kind, target.moment)
    }

    private func reload() {
        guard let store else { snapshot = .init(); return }
        if case .detail = context { try? store.recoverOrphans() }
        do {
            snapshot = try store.snapshot()
            damaged = false
        } catch {
            snapshot = .init()
            damaged = true
        }
        var images: [String: UIImage] = [:]
        for (kind, list) in [(MomentStore.Kind.moment, snapshot.moments), (.beforeLoad, snapshot.beforeLoad)] {
            for m in list where m.hasThumbnail {
                if let data = store.thumbnail(kind, m.id), let image = UIImage(data: data) { images[kind.rawValue + m.id] = image }
            }
        }
        thumbnails = images
    }

    #if DEBUG
    /// Capturas: `-momentsOpen` abre una confirmación o el editor del primer momento.
    private func debugOpen() async {
        guard let what = DebugScreenRouter.momentsOpen else { return }
        try? await Task.sleep(for: .seconds(1))
        guard let first = snapshot.moments.first else { return }
        switch what {
        case "load": confirmLoad = Target(kind: .moment, moment: snapshot.moments.first { $0.id == "m3" } ?? first)
        case "new": newName = state.suggestedMomentName(); creating = true
        case "edit": editing = first
        case "install": confirmInstall = Target(kind: .moment, moment: first)
        case "filter": filterTag = "experimento"
        case "recover": if let b = snapshot.beforeLoad.first { confirmLoad = Target(kind: .beforeLoad, moment: b) }
        default: break
        }
    }
    #endif

    private func binding<T>(_ value: Binding<T?>) -> Binding<Bool> {
        Binding(get: { value.wrappedValue != nil }, set: { if !$0 { value.wrappedValue = nil } })
    }
}

/// Fila de un momento: miniatura con la proporción de la consola, nombre, fecha, tiempo, etiquetas y nota.
/// Con letra muy grande, la miniatura va encima del texto (nunca se recorta).
struct MomentRow: View {
    @Environment(\.dynamicTypeSize) private var typeSize
    let moment: MomentStore.Moment
    let thumbnail: UIImage?
    let console: Console

    var body: some View {
        let layout = typeSize.isAccessibilitySize ? AnyLayout(VStackLayout(alignment: .leading, spacing: PocketSpacing.xs))
                                                  : AnyLayout(HStackLayout(alignment: .top, spacing: PocketSpacing.sm))
        layout {
            preview
                .frame(width: typeSize.isAccessibilitySize ? 160 : 96)
            VStack(alignment: .leading, spacing: 2) {
                Text(moment.name).font(.headline).fixedSize(horizontal: false, vertical: true)
                Text(details).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                if !moment.tags.isEmpty {
                    Text(moment.tags.map { "#\($0)" }.joined(separator: " "))
                        .font(.caption.weight(.medium))
                        .foregroundStyle(PocketColor.accent)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if !moment.note.isEmpty {
                    Text(moment.note).font(.footnote).lineLimit(3).fixedSize(horizontal: false, vertical: true)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, PocketSpacing.xxs)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }

    private var details: String {
        var parts = [GameStatus.stateDate(moment.created)]
        if let t = moment.playTime { parts.append("jugado \(PlayTimeFormat.text(t))") }
        if !moment.hasState { parts.append("solo la partida") }
        if moment.origin != nil { parts.append("de una ranura antigua") }
        return parts.joined(separator: " · ")
    }

    private var preview: some View {
        Color.clear
            .aspectRatio(ArtworkStyle.aspectRatio(of: thumbnail?.size, fallback: console), contentMode: .fit)
            .overlay {
                if let thumbnail {
                    Image(uiImage: thumbnail).resizable().interpolation(.none).aspectRatio(contentMode: .fill)
                } else {
                    ZStack {
                        PocketColor.backgroundElevated
                        Image(systemName: moment.hasState ? "bookmark" : "externaldrive").foregroundStyle(.secondary)
                    }
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: PocketRadius.thumbnail, style: .continuous))
            .accessibilityHidden(true)
    }
}

/// Editar nombre, etiquetas, colección y nota de un momento.
struct MomentEditor: View {
    @Environment(\.dismiss) private var dismiss
    let moment: MomentStore.Moment
    let collections: [String]
    let onSave: (String, [String], String?, String) -> Void
    @State private var name = ""
    @State private var tags = ""
    @State private var collection = ""
    @State private var note = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("Nombre") {
                    TextField("Nombre", text: $name)
                        .accessibilityIdentifier("moment-edit-name")
                }
                Section {
                    TextField("Etiquetas", text: $tags)
                        .textInputAutocapitalization(.never)
                        .accessibilityIdentifier("moment-edit-tags")
                } header: {
                    Text("Etiquetas")
                } footer: {
                    Text("Separadas por comas, por ejemplo: jefe, legendario.")
                }
                Section {
                    TextField("Sin colección", text: $collection)
                        .accessibilityIdentifier("moment-edit-collection")
                    ForEach(collections, id: \.self) { name in
                        Button {
                            collection = name
                        } label: {
                            HStack {
                                Text(name)
                                Spacer()
                                if collection.lowercased() == name.lowercased() {
                                    Image(systemName: "checkmark").foregroundStyle(PocketColor.accent)
                                }
                            }
                        }
                    }
                } header: {
                    Text("Colección")
                } footer: {
                    Text("Opcional. Agrupa los momentos en la lista. Vacía el campo para dejarlo sin colección.")
                }
                Section("Nota") {
                    TextField("Nota", text: $note, axis: .vertical)
                        .lineLimit(3...8)
                        .accessibilityIdentifier("moment-edit-note")
                }
            }
            .navigationTitle("Editar momento")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Guardar") {
                        onSave(name, MomentStore.parseTags(tags), collection, note)
                        dismiss()
                    }
                    .accessibilityIdentifier("moment-edit-save")
                }
            }
        }
        .task {
            name = moment.name
            tags = moment.tags.joined(separator: ", ")
            collection = moment.collection ?? ""
            note = moment.note
        }
    }
}

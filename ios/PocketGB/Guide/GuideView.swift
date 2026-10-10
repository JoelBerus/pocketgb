import SwiftUI

/// N9 · Ajustes › Guía: índice de secciones con búsqueda. Todo sale del bundle; nada es remoto.
struct GuideView: View {
    @State private var query = ""
    private let library = GuideLibrary.shared

    var body: some View {
        List {
            if query.trimmingCharacters(in: .whitespaces).isEmpty {
                Section {
                    ForEach(GuideLibrary.sections) { section in
                        NavigationLink(value: SettingsRoute.guideSection(id: section.id, focus: nil)) {
                            GuideIndexRow(section: section)
                        }
                        .accessibilityIdentifier("guide-section-\(section.id)")
                    }
                } footer: {
                    Text("La guía va dentro de la app y funciona sin conexión.")
                }
            } else {
                results
            }
        }
        .scrollContentBackground(.hidden)
        .background(PocketColor.backgroundBase.ignoresSafeArea())
        .navigationTitle("Guía")
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Buscar en la guía")
        .accessibilityIdentifier("guide")
        #if DEBUG
        .task { if let preset = DebugArguments.value("-guideSearch") { query = preset } }
        #endif
    }

    @ViewBuilder private var results: some View {
        let hits = library.search(query)
        if hits.isEmpty {
            ContentUnavailableView.search(text: query)
                .listRowBackground(Color.clear)
        } else {
            let grouped = Dictionary(grouping: hits, by: \.sectionID)
            let order = hits.map(\.sectionID).reduce(into: [String]()) { if !$0.contains($1) { $0.append($1) } }
            ForEach(order.compactMap(GuideLibrary.section)) { section in
                Section(section.title) {
                    ForEach(grouped[section.id] ?? []) { hit in
                        NavigationLink(value: SettingsRoute.guideSection(id: section.id, focus: hit.block)) {
                            VStack(alignment: .leading, spacing: 2) {
                                if let heading = hit.heading {
                                    Text(heading).font(.subheadline.weight(.semibold))
                                }
                                if !hit.snippet.isEmpty {
                                    Text(hit.snippet)
                                        .font(.subheadline)
                                        .foregroundStyle(.secondary)
                                        .fixedSize(horizontal: false, vertical: true)
                                }
                            }
                            .accessibilityElement(children: .combine)
                        }
                    }
                }
            }
        }
    }
}

/// Fila del índice: icono, título y resumen. Con texto muy grande el icono va encima, para que el título
/// tenga todo el ancho y no se parta.
private struct GuideIndexRow: View {
    @Environment(\.dynamicTypeSize) private var typeSize
    let section: GuideSection

    var body: some View {
        let text = VStack(alignment: .leading, spacing: 2) {
            Text(section.title)
            Text(section.summary)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        let icon = Image(systemName: section.systemImage).foregroundStyle(PocketColor.accent).accessibilityHidden(true)
        if typeSize.isAccessibilitySize {
            VStack(alignment: .leading, spacing: PocketSpacing.xxs) { icon; text }
                .accessibilityElement(children: .combine)
        } else {
            Label { text } icon: { icon }
                .accessibilityElement(children: .combine)
        }
    }
}

/// Una sección de la guía dibujada con vistas nativas: títulos marcados como encabezados (rotor de
/// VoiceOver), listas, tablas como fichas apiladas (se leen igual con AX5) y el ejemplo de carpetas en
/// un bloque desplazable. Los enlaces `guia:<id>` abren otra sección dentro de Ajustes.
struct GuideSectionView: View {
    @Environment(AppState.self) private var state
    let section: GuideSection
    let focus: Int?
    private let blocks: [GuideBlock]

    init(section: GuideSection, focus: Int?, library: GuideLibrary = .shared) {
        self.section = section
        self.focus = focus
        blocks = library.blocks(section.id)
    }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: PocketSpacing.md) {
                    ForEach(Array(blocks.enumerated()), id: \.offset) { index, block in
                        GuideBlockView(block: block)
                            .padding(index == focus ? PocketSpacing.xs : 0)
                            .background(index == focus ? PocketColor.accent.opacity(0.12) : .clear,
                                        in: RoundedRectangle(cornerRadius: PocketRadius.group / 2, style: .continuous))
                            .id(index)
                    }
                }
                .frame(maxWidth: 720, alignment: .leading)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(PocketSpacing.md)
            }
            .task {
                guard let focus else { return }
                try? await Task.sleep(for: .milliseconds(250))
                proxy.scrollTo(focus, anchor: .top)
            }
        }
        .background(PocketColor.backgroundBase.ignoresSafeArea())
        .navigationTitle(section.title)
        .navigationBarTitleDisplayMode(.inline)
        .environment(\.openURL, OpenURLAction { url in
            guard url.scheme == "guia" else { return .discarded }   // la guía nunca abre nada de fuera
            let id = url.absoluteString.replacingOccurrences(of: "guia:", with: "")
            guard GuideLibrary.section(id) != nil else { return .discarded }
            state.settingsPath.append(.guideSection(id: id, focus: nil))
            return .handled
        })
        .accessibilityIdentifier("guide-section")
    }
}

private struct GuideBlockView: View {
    let block: GuideBlock

    var body: some View {
        switch block {
        case .heading(let level, let text):
            Text(Self.inline(text))
                .font(level <= 2 ? .title3.bold() : .headline)
                .padding(.top, level <= 2 ? PocketSpacing.xs : 0)
                .accessibilityAddTraits(.isHeader)
                .fixedSize(horizontal: false, vertical: true)
        case .paragraph(let text):
            Text(Self.inline(text)).fixedSize(horizontal: false, vertical: true)
        case .note(let text):
            Label {
                Text(Self.inline(text)).fixedSize(horizontal: false, vertical: true)
            } icon: {
                Image(systemName: "info.circle").foregroundStyle(PocketColor.accent)
            }
            .font(.subheadline)
        case .list(_, let items):
            VStack(alignment: .leading, spacing: PocketSpacing.xs) {
                ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                    HStack(alignment: .firstTextBaseline, spacing: PocketSpacing.xs) {
                        Text(item.marker)
                            .foregroundStyle(.secondary)
                            .accessibilityHidden(item.marker == "•")
                        Text(Self.inline(item.text)).fixedSize(horizontal: false, vertical: true)
                    }
                    .padding(.leading, CGFloat(item.level) * PocketSpacing.md)
                    .guideCombined(unlessLinksIn: item.text)
                }
            }
        case .table(let header, let rows):
            VStack(alignment: .leading, spacing: PocketSpacing.xs) {
                ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                    GuideTableRow(header: header, row: row)
                }
            }
        case .code(let text):
            ScrollView(.horizontal) {
                Text(text)
                    .font(.system(.footnote, design: .monospaced))
                    .fixedSize()
                    .textSelection(.enabled)
                    .padding(PocketSpacing.sm)
            }
            .background(PocketColor.backgroundElevated, in: RoundedRectangle(cornerRadius: PocketRadius.group / 2, style: .continuous))
            .accessibilityElement(children: .combine)
            .accessibilityLabel("Ejemplo")
            .accessibilityValue(text)
        }
    }

    /// Negrita, cursiva, `código` y enlaces; si el Markdown no se entiende, el texto tal cual.
    static func inline(_ text: String) -> AttributedString {
        (try? AttributedString(markdown: text, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace)))
            ?? AttributedString(text)
    }
}

/// Una fila de tabla como ficha: la primera celda de título y las demás debajo, con su cabecera si
/// la tabla tiene más de dos columnas. Con VoiceOver se lee de una vez.
private struct GuideTableRow: View {
    let header: [String]
    let row: [String]

    var body: some View {
        VStack(alignment: .leading, spacing: PocketSpacing.xxs) {
            if let first = row.first {
                Text(GuideBlockView.inline(first)).font(.body.weight(.semibold)).fixedSize(horizontal: false, vertical: true)
            }
            ForEach(Array(row.dropFirst().enumerated()), id: \.offset) { index, cell in
                VStack(alignment: .leading, spacing: 0) {
                    if header.count > 2, index + 1 < header.count {
                        Text(GuideBlockView.inline(header[index + 1]))
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.secondary)
                    }
                    Text(GuideBlockView.inline(cell)).fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(PocketSpacing.sm)
        .background(PocketColor.backgroundElevated, in: RoundedRectangle(cornerRadius: PocketRadius.group / 2, style: .continuous))
        .guideCombined(unlessLinksIn: row.joined())
    }
}

private extension View {
    /// Junta la fila en un solo elemento de VoiceOver salvo si lleva un enlace: así el enlace sigue
    /// siendo un elemento que se activa.
    @ViewBuilder func guideCombined(unlessLinksIn text: String) -> some View {
        if text.contains("](guia:") { self.accessibilityElement(children: .contain) } else { self.accessibilityElement(children: .combine) }
    }
}

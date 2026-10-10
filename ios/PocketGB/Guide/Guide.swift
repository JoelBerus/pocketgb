import Foundation

/// N9 · Ajustes › Guía: las secciones de `docs/guia` que aplican al iPhone, empaquetadas en la app
/// (`Resources/Guide/guia-<id>.md`, copiadas con `tools/ios-guide-sync.py`). Sin red: se leen del bundle
/// y se dibujan con vistas nativas (`GuideSectionView`).
struct GuideSection: Identifiable, Hashable, Sendable {
    let id: String
    let title: String
    let summary: String
    let systemImage: String
}

/// Un bloque de Markdown de la guía. El texto en línea (negrita, `código`, enlaces `guia:<id>`) se
/// interpreta al dibujarlo con `AttributedString(markdown:)`.
enum GuideBlock: Hashable, Sendable {
    case heading(level: Int, text: String)
    case paragraph(String)
    case list(ordered: Bool, items: [GuideListItem])
    case table(header: [String], rows: [[String]])
    case code(String)
    case note(String)
}

struct GuideListItem: Hashable, Sendable {
    let level: Int
    let marker: String
    let text: String
}

/// Lector del subconjunto de Markdown que usa la guía: títulos, párrafos, listas (con sangría),
/// tablas, bloques de código y citas. Lo demás se trata como párrafo; nunca falla.
enum GuideParser {
    static func parse(_ markdown: String) -> [GuideBlock] {
        var blocks: [GuideBlock] = []
        var paragraph: [String] = []
        var items: [GuideListItem] = []
        var ordered = false
        var table: [[String]] = []
        var code: [String]?

        func flushParagraph() {
            if !paragraph.isEmpty { blocks.append(.paragraph(paragraph.joined(separator: " "))) }
            paragraph = []
        }
        func flushList() {
            if !items.isEmpty { blocks.append(.list(ordered: ordered, items: items)) }
            items = []
        }
        func flushTable() {
            guard !table.isEmpty else { return }
            let header = table[0]
            let rows = table.dropFirst().filter { row in !row.allSatisfy { $0.allSatisfy { "-: ".contains($0) } } }
            blocks.append(.table(header: header, rows: Array(rows)))
            table = []
        }
        func flushAll() { flushParagraph(); flushList(); flushTable() }

        for rawLine in markdown.components(separatedBy: "\n") {
            let line = rawLine.replacingOccurrences(of: "\t", with: "    ")
            if var lines = code {
                if line.hasPrefix("```") {
                    blocks.append(.code(lines.joined(separator: "\n")))
                    code = nil
                } else {
                    lines.append(line)
                    code = lines
                }
                continue
            }
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.hasPrefix("<!--") { continue }
            if trimmed.hasPrefix("```") { flushAll(); code = []; continue }
            if trimmed.isEmpty { flushAll(); continue }
            if let heading = heading(trimmed) { flushAll(); blocks.append(heading); continue }
            if trimmed.hasPrefix("|") {
                flushParagraph(); flushList()
                table.append(cells(trimmed))
                continue
            }
            flushTable()
            if trimmed.hasPrefix("> ") || trimmed == ">" {
                flushAll()
                blocks.append(.note(String(trimmed.dropFirst()).trimmingCharacters(in: .whitespaces)))
                continue
            }
            let indent = line.prefix { $0 == " " }.count
            if let item = listItem(trimmed, level: indent / 2) {
                flushParagraph()
                if !items.isEmpty, item.ordered != ordered, item.item.level == 0 { flushList() }
                if items.isEmpty { ordered = item.ordered }
                items.append(item.item)
                continue
            }
            if !items.isEmpty, indent > 0, let last = items.popLast() {
                // Continuación de un elemento de lista en la línea siguiente.
                items.append(GuideListItem(level: last.level, marker: last.marker, text: last.text + " " + trimmed))
                continue
            }
            flushList()
            paragraph.append(trimmed)
        }
        if let lines = code { blocks.append(.code(lines.joined(separator: "\n"))) }
        flushAll()
        return blocks
    }

    private static func heading(_ line: String) -> GuideBlock? {
        let hashes = line.prefix { $0 == "#" }.count
        guard (1...6).contains(hashes), line.dropFirst(hashes).first == " " else { return nil }
        return .heading(level: hashes, text: line.dropFirst(hashes + 1).trimmingCharacters(in: .whitespaces))
    }

    private static func cells(_ line: String) -> [String] {
        var parts = line.split(separator: "|", omittingEmptySubsequences: false).map {
            $0.trimmingCharacters(in: .whitespaces)
        }
        if parts.first?.isEmpty == true { parts.removeFirst() }
        if parts.last?.isEmpty == true { parts.removeLast() }
        return parts
    }

    private static func listItem(_ line: String, level: Int) -> (ordered: Bool, item: GuideListItem)? {
        if line.hasPrefix("- ") || line.hasPrefix("* ") {
            return (false, GuideListItem(level: level, marker: "•", text: String(line.dropFirst(2))))
        }
        let digits = line.prefix { $0.isNumber }
        if !digits.isEmpty, digits.count <= 3, line.dropFirst(digits.count).hasPrefix(". ") {
            return (true, GuideListItem(level: level, marker: "\(digits).",
                                        text: String(line.dropFirst(digits.count + 2))))
        }
        return nil
    }
}

/// Texto plano de un fragmento en línea (para buscar y para los fragmentos de resultados).
enum GuideText {
    static func plain(_ markdown: String) -> String {
        var s = markdown
        // [texto](destino) → texto
        while let open = s.range(of: "["), let close = s.range(of: "](", range: open.upperBound..<s.endIndex),
              let end = s.range(of: ")", range: close.upperBound..<s.endIndex) {
            let label = s[open.upperBound..<close.lowerBound]
            s.replaceSubrange(open.lowerBound..<end.upperBound, with: label)
        }
        for marker in ["**", "`", "*"] { s = s.replacingOccurrences(of: marker, with: "") }
        return s
    }

    static func plain(_ block: GuideBlock) -> String {
        switch block {
        case .heading(_, let text), .paragraph(let text), .note(let text): plain(text)
        case .code(let text): text
        case .list(_, let items): items.map { plain($0.text) }.joined(separator: " ")
        case .table(let header, let rows): ([header] + rows).map { $0.map(plain).joined(separator: " ") }.joined(separator: " ")
        }
    }

    /// Sin mayúsculas ni acentos, para que «categoria» encuentre «Categoría».
    static func fold(_ text: String) -> String {
        text.folding(options: [.caseInsensitive, .diacriticInsensitive, .widthInsensitive], locale: Locale(identifier: "es"))
    }
}

/// Un resultado de búsqueda: la sección, el bloque donde aparece (para desplazarse a él), el título
/// de su apartado y un fragmento del texto.
struct GuideHit: Identifiable, Hashable, Sendable {
    let sectionID: String
    let block: Int?
    let heading: String?
    let snippet: String
    var id: String { "\(sectionID)-\(block ?? -1)" }
}

/// Las secciones de la guía del iPhone y su contenido.
struct GuideLibrary: Sendable {
    /// Mismo orden y mismos id que `SECTIONS` en `tools/ios-guide-sync.py`.
    static let sections: [GuideSection] = [
        GuideSection(id: "carpetas", title: "Carpetas",
                     summary: "Tus carpetas son las categorías. Qué lee PocketGB y dónde va la partida.",
                     systemImage: "folder"),
        GuideSection(id: "biblioteca", title: "Biblioteca y detalle",
                     summary: "Buscar, filtrar, vertical y horizontal, información técnica.",
                     systemImage: "square.grid.2x2"),
        GuideSection(id: "categorias", title: "Categorías y etiquetas",
                     summary: "El inicio, subcategorías, mover de categoría sin tocar archivos y etiquetas.",
                     systemImage: "tag"),
        GuideSection(id: "portadas", title: "Portadas",
                     summary: "Tu imagen, la de la carpeta, una captura o la generada.",
                     systemImage: "photo"),
        GuideSection(id: "continuar", title: "Jugar y continuar",
                     summary: "La partida y el estado automático, la pausa, renombrar y el cable link.",
                     systemImage: "play.circle"),
        GuideSection(id: "momentos", title: "Momentos y progreso",
                     summary: "Guardar un instante, cargarlo sin miedo, «Recuperar», tiempo e hitos.",
                     systemImage: "bookmark"),
        GuideSection(id: "viajar", title: "Partidas que viajan",
                     summary: "Enviar la partida a otro dispositivo, recibirla y exportar el .sav.",
                     systemImage: "paperplane"),
        GuideSection(id: "controles", title: "Controles",
                     summary: "Cruceta, flechas separadas, diagonales, tamaño y ubicación.",
                     systemImage: "gamecontroller"),
        GuideSection(id: "gba", title: "Game Boy Advance",
                     summary: "La BIOS opcional, L y R, y cómo guardan los juegos de GBA.",
                     systemImage: "rectangle.landscape.rotate"),
    ]

    static let shared = GuideLibrary(bundle: .main)

    private let documents: [String: [GuideBlock]]

    init(documents: [String: [GuideBlock]]) { self.documents = documents }

    init(bundle: Bundle) {
        var documents: [String: [GuideBlock]] = [:]
        for section in Self.sections {
            if let url = bundle.url(forResource: "guia-\(section.id)", withExtension: "md"),
               let text = try? String(contentsOf: url, encoding: .utf8) {
                documents[section.id] = GuideParser.parse(text)
            }
        }
        self.documents = documents
    }

    static func section(_ id: String) -> GuideSection? { sections.first { $0.id == id } }

    func blocks(_ id: String) -> [GuideBlock] { documents[id] ?? [] }

    /// Busca todas las palabras (sin mayúsculas ni acentos) en el título de cada sección y en cada bloque.
    func search(_ query: String) -> [GuideHit] {
        let words = GuideText.fold(query).split(whereSeparator: \.isWhitespace).map(String.init)
        guard !words.isEmpty else { return [] }
        func matches(_ text: String) -> Bool {
            let folded = GuideText.fold(text)
            return words.allSatisfy { folded.contains($0) }
        }
        var hits: [GuideHit] = []
        // Primero las secciones cuyo título o resumen coincide: suelen ser lo que se busca.
        let titled = Self.sections.filter { matches($0.title + " " + $0.summary) }
        let ordered = titled + Self.sections.filter { section in !titled.contains(section) }
        for section in ordered {
            if titled.contains(section) {
                hits.append(GuideHit(sectionID: section.id, block: nil, heading: nil, snippet: section.summary))
            }
            var heading: String?
            var found: [GuideHit] = []
            for (index, block) in blocks(section.id).enumerated() {
                var isHeading = false
                if case .heading(_, let text) = block { heading = GuideText.plain(text); isHeading = true }
                let plain = GuideText.plain(block)
                guard matches(plain) else { continue }
                // Un título que coincide sale solo (sin fragmento); si algo de su apartado también coincide, basta eso.
                found.append(GuideHit(sectionID: section.id, block: index, heading: heading,
                                      snippet: isHeading ? "" : Self.snippet(plain, around: words[0])))
            }
            hits += found.filter { hit in
                !hit.snippet.isEmpty || !found.contains { $0.heading == hit.heading && !$0.snippet.isEmpty }
            }
        }
        return hits
    }

    /// Unas 140 letras alrededor de la primera palabra buscada.
    static func snippet(_ text: String, around word: String, length: Int = 140) -> String {
        let collapsed = text.split(whereSeparator: \.isNewline).joined(separator: " ")
        guard collapsed.count > length else { return collapsed }
        let folded = GuideText.fold(collapsed)
        let characters = Array(collapsed)
        // `fold` conserva la cuenta de caracteres del español (solo quita marcas y mayúsculas).
        let offset = folded.range(of: word).map { folded.distance(from: folded.startIndex, to: $0.lowerBound) } ?? 0
        var start = max(0, min(offset - length / 3, characters.count - length))
        var end = min(characters.count, start + length)
        // Sin cortar palabras por la mitad.
        if start > 0, let space = characters[start..<min(offset, end)].firstIndex(of: " ") { start = space + 1 }
        if end < characters.count, let space = characters[max(start, offset)..<end].lastIndex(of: " ") { end = space }
        var result = String(characters[start..<end]).trimmingCharacters(in: .whitespaces)
        if start > 0 { result = "…" + result }
        if end < characters.count { result += "…" }
        return result
    }
}

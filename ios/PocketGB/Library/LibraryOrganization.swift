import Foundation

// N4 · categorías anidadas, categoría virtual (ND3), etiquetas e inicio con estanterías. Funciones
// puras sobre `RomEntry` y `LibraryPreferencesData`: se prueban sin disco ni actor. Mismo criterio
// que Android (`library/LibraryOrganization.kt`, rama N4 Android): ver docs/11-biblioteca-carpetas.md.

/// Comparación de textos que escribe Joel: sin mayúsculas, sin acentos y sin distinguir anchos.
enum TextKey {
    static func fold(_ text: String) -> String {
        text.trimmingCharacters(in: .whitespacesAndNewlines)
            .folding(options: [.caseInsensitive, .diacriticInsensitive, .widthInsensitive], locale: nil)
    }

    /// Espacios seguidos (y saltos de línea) como uno, sin espacios en los extremos.
    static func collapsingWhitespace(_ text: String) -> String {
        text.split(whereSeparator: { $0.isWhitespace || $0.isNewline }).joined(separator: " ")
    }

    /// Orden natural («Juego 2» antes que «Juego 10»), como el Finder.
    static func naturalLess(_ a: String, _ b: String) -> Bool {
        a.localizedStandardCompare(b) == .orderedAscending
    }
}

/// N4 · etiquetas libres por juego (por huella). «RPG» y «rpg», o «acción» y «accion», son la misma.
enum Tags {
    /// Caracteres visibles como mucho de una etiqueta.
    static let maxLength = 30
    /// Etiquetas como mucho por juego.
    static let maxPerGame = 20

    /// Recortada, sin `#` delante, con los espacios seguidos como uno y limitada a `maxLength`; nil si
    /// queda vacía.
    static func normalize(_ raw: String) -> String? {
        var flat = TextKey.collapsingWhitespace(raw)
        while flat.hasPrefix("#") { flat.removeFirst() }
        flat = flat.trimmingCharacters(in: .whitespaces)
        guard !flat.isEmpty else { return nil }
        return String(flat.prefix(maxLength)).trimmingCharacters(in: .whitespaces)
    }

    static func same(_ a: String, _ b: String) -> Bool { TextKey.fold(a) == TextKey.fold(b) }

    static func sorted(_ tags: [String]) -> [String] { tags.sorted(by: TextKey.naturalLess) }

    /// Lista guardada saneada: normalizadas, sin repetidas, en orden natural y como mucho `maxPerGame`.
    static func sanitized(_ raw: [String]) -> [String] {
        var result: [String] = []
        for tag in raw.compactMap(normalize) where !result.contains(where: { same($0, tag) }) {
            result.append(tag)
        }
        return Array(sorted(result).prefix(maxPerGame))
    }

    enum AddOutcome: Equatable, Sendable {
        case added([String])
        /// No queda nada tras normalizar.
        case empty
        /// El juego ya la lleva (sin mayúsculas ni acentos).
        case duplicate
        /// Ya tiene `maxPerGame`.
        case full
    }

    static func adding(_ raw: String, to current: [String]) -> AddOutcome {
        guard let tag = normalize(raw) else { return .empty }
        if current.contains(where: { same($0, tag) }) { return .duplicate }
        if current.count >= maxPerGame { return .full }
        return .added(sorted(current + [tag]))
    }

    static func removing(_ tag: String, from current: [String]) -> [String] {
        current.filter { !same($0, tag) }
    }
}

/// N4 · rutas de categoría: una carpeta real o una categoría virtual (ND3), nivel a nivel desde la raíz.
/// `[]` es la raíz («Sin categoría»).
enum CategoryPaths {
    /// Caracteres visibles como mucho de cada nivel escrito en la app (N4A-7).
    static let maxSegmentLength = 60
    /// Separador de migas y rutas visibles (también se acepta al escribir, como `/`).
    static let separator = " › "
    /// Clave de «Sin categoría»: ningún nombre de carpeta leída empieza por «.» (ND11).
    static let rootKey = "."

    enum Problem: Error, Equatable, Sendable {
        /// No hay ningún nombre.
        case empty
        /// Un nivel empieza por `.` o por `_` (carpetas que PocketGB nunca lee, ND11).
        case reserved
        /// Más de `LibraryScanner.maxFolderDepth` niveles.
        case tooDeep

        var message: String {
            switch self {
            case .empty: "Escribe el nombre de la categoría."
            case .reserved: "Ningún nivel puede empezar por «.» ni por «_»: son carpetas que PocketGB no lee."
            case .tooDeep: "Como mucho \(LibraryScanner.maxFolderDepth) niveles, como las carpetas."
            }
        }
    }

    /// «Pokémon / Para jugar» o «Pokémon › Para jugar» → `["Pokémon", "Para jugar"]`, validada.
    static func parse(_ text: String) -> Result<[String], Problem> {
        let segments = text.split(whereSeparator: { $0 == "/" || $0 == "›" }).compactMap { normalizeSegment(String($0)) }
        if segments.isEmpty { return .failure(.empty) }
        if segments.contains(where: { $0.hasPrefix(".") || $0.hasPrefix("_") }) { return .failure(.reserved) }
        if segments.count > LibraryScanner.maxFolderDepth { return .failure(.tooDeep) }
        return .success(segments)
    }

    /// Una ruta guardada es válida con las mismas reglas (lo que no lo sea no se usa).
    static func isValid(_ path: [String]) -> Bool {
        path.count <= LibraryScanner.maxFolderDepth
            && path.allSatisfy { !$0.isEmpty && !$0.hasPrefix(".") && !$0.hasPrefix("_") && !$0.contains("/") }
    }

    static func normalizeSegment(_ raw: String) -> String? {
        let flat = TextKey.collapsingWhitespace(raw)
        guard !flat.isEmpty else { return nil }
        return String(flat.prefix(maxSegmentLength)).trimmingCharacters(in: .whitespaces)
    }

    /// `typed` con la ortografía de una categoría existente si coincide nivel a nivel sin mayúsculas ni
    /// acentos («pokemon/para jugar» → «Pokémon/para jugar»): así no se crea otra casi igual.
    static func matchExisting(_ typed: [String], known: [[String]]) -> [String] {
        var result: [String] = []
        for segment in typed {
            let existing = known.first { path in
                path.count == result.count + 1 && Array(path.prefix(result.count)) == result
                    && TextKey.fold(path[result.count]) == TextKey.fold(segment)
            }
            result.append(existing?.last ?? segment)
        }
        return result
    }

    /// «Pokémon › 2ª generación», o «Sin categoría» para la raíz.
    static func display(_ path: [String]) -> String {
        path.isEmpty ? "Sin categoría" : path.joined(separator: separator)
    }

    /// Clave estable para las preferencias (orden del inicio, vista por categoría): la ruta con `/`
    /// (ningún nombre de carpeta ni de categoría escrita la contiene) o «.» para la raíz.
    static func key(_ path: [String]) -> String {
        path.isEmpty ? rootKey : path.joined(separator: "/")
    }

    /// `prefix` es la propia ruta o una carpeta que la contiene (por niveles, no por texto).
    static func hasPrefix(_ path: [String], _ prefix: [String]) -> Bool {
        path.count >= prefix.count && Array(path.prefix(prefix.count)) == prefix
    }

    /// Orden natural nivel a nivel; una carpeta va antes que sus subcarpetas.
    static func less(_ a: [String], _ b: [String]) -> Bool {
        for (x, y) in zip(a, b) where x != y {
            let order = x.localizedStandardCompare(y)
            if order != .orderedSame { return order == .orderedAscending }
        }
        return a.count < b.count
    }
}

// MARK: - Lo que se ve de cada juego

extension LibraryPreferencesData {
    /// Categoría virtual del juego (ND3), si se movió en la app. Se **ve** con cualquier huella conocida
    /// (también la pista de su ruta, como el resto de metadatos), pero solo se **escribe** con la huella
    /// confirmada (`LibraryPreferences.confirmedFingerprint`).
    func virtualFolder(_ entry: RomEntry) -> [String]? {
        metadata(entry).virtualFolder
    }

    /// La categoría en la que se ve el juego: la virtual si la tiene o la de su carpeta.
    func categoryPath(_ entry: RomEntry) -> [String] {
        virtualFolder(entry) ?? entry.folderPath
    }

    /// «Movido en la app»: la categoría virtual es distinta de su carpeta. Una copia que ya está en
    /// esa carpeta de verdad no lo está (auditoría N4 Android, H10).
    func isMovedInApp(_ entry: RomEntry) -> Bool {
        guard let virtual = virtualFolder(entry) else { return false }
        return virtual != entry.folderPath
    }

    func tags(_ entry: RomEntry) -> [String] {
        metadata(entry).tags
    }

    /// Vista de la pantalla de una categoría: la suya o, si no tiene, la de la biblioteca.
    func layout(forCategory path: [String]) -> LibraryLayout {
        categoryLayouts[CategoryPaths.key(path)] ?? layout
    }
}

// MARK: - Categorías de primer nivel (estanterías, panel y menú)

/// Una categoría elegible con sus juegos visibles (todo su subárbol).
struct CategoryOption: Equatable, Hashable, Sendable, Identifiable {
    let category: LibraryCategory
    let count: Int
    var id: String { category.id }
}

extension LibraryCategory {
    /// Ruta de la categoría: la carpeta de primer nivel, o `[]` para «Sin categoría» (y «Todas»).
    var path: [String] {
        if case .folder(let name) = self { return [name] }
        return []
    }

    /// Clave del inicio (`HomeSettings`): la de su ruta.
    var key: String { CategoryPaths.key(path) }

    /// La categoría de primer nivel de una ruta: «Sin categoría» para la raíz.
    static func topLevel(of path: [String]) -> LibraryCategory {
        path.first.map(LibraryCategory.folder) ?? .uncategorized
    }

    /// Categorías de primer nivel con juegos visibles (sin ocultos) y cuántos tiene cada una, contando
    /// la categoría **en la que se ve** cada juego (la virtual si está movido): las carpetas en orden
    /// natural y «Sin categoría» al final si hay juegos en la raíz.
    static func options(_ entries: [RomEntry], prefs: LibraryPreferencesData) -> [CategoryOption] {
        var counts: [String: Int] = [:]
        var root = 0
        for entry in entries where !prefs.isHidden(entry) {
            if let first = prefs.categoryPath(entry).first {
                counts[first, default: 0] += 1
            } else {
                root += 1
            }
        }
        let folders = counts.keys.sorted(by: TextKey.naturalLess)
            .map { CategoryOption(category: .folder($0), count: counts[$0] ?? 0) }
        return root > 0 ? folders + [CategoryOption(category: .uncategorized, count: root)] : folders
    }

    /// Hay al menos una carpeta: sin carpetas, las estanterías repetirían «Todos los juegos» (N4A-2).
    static func hasFolders(_ options: [CategoryOption]) -> Bool {
        options.contains { if case .folder = $0.category { true } else { false } }
    }
}

// MARK: - Árbol de categorías

/// N4 · el árbol de categorías tal como se ve (carpetas reales y categorías virtuales).
enum LibraryTree {
    struct Subcategory: Equatable, Hashable, Sendable, Identifiable {
        let name: String
        let path: [String]
        /// Juegos de todo su subárbol.
        let count: Int
        var id: String { CategoryPaths.key(path) }
    }

    /// Juegos de la pantalla de una categoría (de `shown`, ya filtrados y ordenados): los de esa
    /// carpeta **y de todas sus subcarpetas**. «Sin categoría» (`[]`): solo los de la raíz.
    static func games(_ shown: [RomEntry], in path: [String], prefs: LibraryPreferencesData) -> [RomEntry] {
        shown.filter { entry in
            let category = prefs.categoryPath(entry)
            return path.isEmpty ? category.isEmpty : CategoryPaths.hasPrefix(category, path)
        }
    }

    /// Subcategorías directas de `parent`, con los juegos de su subárbol, en orden natural. La raíz
    /// («Sin categoría») no tiene subcategorías: las carpetas de primer nivel son categorías.
    static func subcategories(_ shown: [RomEntry], of parent: [String], prefs: LibraryPreferencesData) -> [Subcategory] {
        guard !parent.isEmpty else { return [] }
        var counts: [String: Int] = [:]
        for entry in shown {
            let category = prefs.categoryPath(entry)
            guard category.count > parent.count, CategoryPaths.hasPrefix(category, parent) else { continue }
            counts[category[parent.count], default: 0] += 1
        }
        return counts.keys.sorted(by: TextKey.naturalLess)
            .map { Subcategory(name: $0, path: parent + [$0], count: counts[$0] ?? 0) }
    }

    /// Migas de una ruta: cada nivel desde el primero hasta el actual.
    static func breadcrumbs(_ path: [String]) -> [[String]] {
        path.indices.map { Array(path.prefix($0 + 1)) }
    }

    /// Todas las categorías que existen (carpetas con juegos, sus carpetas padre y las categorías
    /// virtuales), sin la raíz y sin juegos ocultos, en orden natural en profundidad: lo que ofrece
    /// «Mostrar en categoría…».
    static func knownPaths(_ entries: [RomEntry], prefs: LibraryPreferencesData) -> [[String]] {
        var paths = Set<[String]>()
        for entry in entries where !prefs.isHidden(entry) {
            for path in [entry.folderPath, prefs.categoryPath(entry)] where !path.isEmpty {
                for depth in 1...path.count { paths.insert(Array(path.prefix(depth))) }
            }
        }
        return paths.sorted(by: CategoryPaths.less)
    }
}

// MARK: - Inicio

/// N4 · ajustes del inicio (por dispositivo, ND12): orden de las estanterías, las fijadas arriba, las
/// ocultas y la fila de Favoritos. Claves de `CategoryPaths.key`. Una categoría que desaparece un rato
/// conserva su sitio; una nueva va detrás de las ordenadas (y antes de «Sin categoría» si esta sigue
/// la última).
struct HomeSettings: Codable, Equatable, Sendable {
    /// Claves en el orden elegido (puede incluir categorías que ahora no están).
    var order: [String] = []
    /// Fijadas arriba: van antes que las demás, en el orden de `order` (N4A-9).
    var pinned: [String] = []
    /// Fuera del inicio: sus juegos siguen en «Todos los juegos» y en su pantalla de categoría.
    var hidden: [String] = []
    var showFavorites = true

    /// Claves recordadas como mucho en cada lista.
    static let maxKeys = 500

    init() {}

    /// Cada campo se lee por separado: uno dañado vuelve a su valor por defecto.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        order = Array(((try? c.decodeIfPresent([String].self, forKey: .order)) ?? []).prefix(Self.maxKeys))
        pinned = Array(((try? c.decodeIfPresent([String].self, forKey: .pinned)) ?? []).prefix(Self.maxKeys))
        hidden = Array(((try? c.decodeIfPresent([String].self, forKey: .hidden)) ?? []).prefix(Self.maxKeys))
        showFavorites = (try? c.decodeIfPresent(Bool.self, forKey: .showFavorites)) ?? true
    }

    var isDefault: Bool { self == HomeSettings() }

    func isPinned(_ key: String) -> Bool { pinned.contains(key) }
    func isHidden(_ key: String) -> Bool { hidden.contains(key) }

    /// `keys` (las presentes, en su orden natural con «Sin categoría» al final) en el orden del inicio:
    /// primero las fijadas, después las demás; dentro de cada grupo, las ordenadas por Joel y luego las
    /// nuevas.
    func arrange(_ keys: [String]) -> [String] {
        let ordered = ordered(keys)
        return ordered.filter(isPinned) + ordered.filter { !isPinned($0) }
    }

    /// Sube (`offset` < 0) o baja la categoría `key` dentro de su grupo (fijadas o no).
    func moving(_ key: String, by offset: Int, keys: [String]) -> HomeSettings {
        // Se trabaja sobre el orden relativo (sin poner las fijadas delante): al soltar una fijada
        // vuelve a su sitio (auditoría N4 Android, H11).
        var ordered = ordered(keys)
        guard ordered.contains(key), offset != 0 else { return self }
        let group = arrange(keys).filter { isPinned($0) == isPinned(key) }
        guard let index = group.firstIndex(of: key) else { return self }
        let target = index + offset
        guard group.indices.contains(target) else { return self }
        ordered.removeAll { $0 == key }
        guard let anchor = ordered.firstIndex(of: group[target]) else { return self }
        ordered.insert(key, at: offset > 0 ? anchor + 1 : anchor)
        var copy = self
        copy.order = remembered(ordered)
        return copy
    }

    /// Fija o suelta. El orden no cambia: al soltarla vuelve a su sitio entre las demás.
    func pinning(_ key: String, _ value: Bool) -> HomeSettings {
        var copy = self
        copy.pinned.removeAll { $0 == key }
        if value { copy.pinned.append(key) }
        return copy
    }

    func hiding(_ key: String, _ value: Bool) -> HomeSettings {
        var copy = self
        copy.hidden.removeAll { $0 == key }
        if value { copy.hidden.append(key) }
        return copy
    }

    /// Las presentes en el orden elegido y, detrás, las nuevas (en el orden de `keys`). «Sin categoría»
    /// sin sitio guardado sigue la última aunque aparezcan carpetas nuevas.
    private func ordered(_ keys: [String]) -> [String] {
        let known = order.filter(keys.contains)
        let fresh = keys.filter { !order.contains($0) && $0 != CategoryPaths.rootKey }
        let root = keys.contains(CategoryPaths.rootKey) && !order.contains(CategoryPaths.rootKey)
            ? [CategoryPaths.rootKey] : []
        return known + fresh + root
    }

    /// El orden relativo y, detrás, las que ahora no están (conservan su sitio relativo). Si «Sin
    /// categoría» queda la última no se guarda: así sigue al final cuando aparece una carpeta nueva.
    private func remembered(_ ordered: [String]) -> [String] {
        let saved = ordered.last == CategoryPaths.rootKey ? Array(ordered.dropLast()) : ordered
        return Array((saved + order.filter { !ordered.contains($0) }).prefix(Self.maxKeys))
    }
}

/// Una estantería del inicio: una categoría de primer nivel con hasta `LibraryHome.shelfLimit` juegos.
struct HomeShelf: Equatable, Sendable, Identifiable {
    let category: LibraryCategory
    let games: [RomEntry]
    /// Juegos visibles de la categoría (todo su subárbol), aunque la fila muestre menos.
    let total: Int
    let pinned: Bool
    var id: String { category.key }
}

/// Lo que muestra el inicio entre «Continuar jugando» y «Todos los juegos». `favoritesTotal` cuenta
/// todos los favoritos aunque la fila muestre como mucho `shelfLimit` (auditoría N4 Android, H1).
struct HomeSections: Equatable, Sendable {
    var favorites: [RomEntry] = []
    var favoritesTotal = 0
    var shelves: [HomeShelf] = []

    var isEmpty: Bool { favorites.isEmpty && shelves.isEmpty }
}

/// Una fila de Ajustes › Biblioteca › Inicio.
struct HomeCategoryRow: Equatable, Sendable, Identifiable {
    let category: LibraryCategory
    let count: Int
    let pinned: Bool
    let hidden: Bool
    var id: String { category.key }
    var key: String { category.key }
}

/// N4 · el inicio de la biblioteca: fila de Favoritos y una estantería por categoría de primer nivel.
enum LibraryHome {
    /// Juegos como mucho en cada estantería y en la fila de Favoritos; el resto, en «Ver todo» (N4A-3).
    static let shelfLimit = 10

    /// Favoritos (si se muestran; cada juego una vez aunque tenga copias) y una estantería por
    /// categoría de primer nivel (con «Sin categoría» para la raíz) en el orden del inicio, sin las
    /// ocultas. Sin carpetas no hay estanterías (N4A-2). Los juegos van en el orden de la biblioteca;
    /// las estanterías muestran archivos (como la cuadrícula).
    static func sections(_ entries: [RomEntry], prefs: LibraryPreferencesData, limit: Int = shelfLimit) -> HomeSections {
        let shown = LibraryQuery.visible(entries, prefs: prefs, filter: .all, query: "")
        var sections = HomeSections()
        if prefs.home.showFavorites {
            var seen = Set<String>()
            let favorites = shown.filter { entry in
                guard prefs.isFavorite(entry) else { return false }
                let key = prefs.fingerprint(of: entry).map { "fp:\($0)" } ?? "path:\(entry.id)"
                return seen.insert(key).inserted
            }
            sections.favorites = Array(favorites.prefix(limit))
            sections.favoritesTotal = favorites.count
        }
        let options = LibraryCategory.options(entries, prefs: prefs)
        guard LibraryCategory.hasFolders(options) else { return sections }
        let byKey = Dictionary(options.map { ($0.category.key, $0) }, uniquingKeysWith: { a, _ in a })
        sections.shelves = prefs.home.arrange(options.map(\.category.key))
            .filter { !prefs.home.isHidden($0) }
            .compactMap { key in
                guard let option = byKey[key] else { return nil }
                let games = LibraryTree.games(shown, in: option.category.path, prefs: prefs)
                return HomeShelf(category: option.category, games: Array(games.prefix(limit)), total: games.count,
                                 pinned: prefs.home.isPinned(key))
            }
        return sections
    }

    /// Todas las categorías de primer nivel en el orden del inicio, también las ocultas.
    static func arrangement(_ entries: [RomEntry], prefs: LibraryPreferencesData) -> [HomeCategoryRow] {
        let options = LibraryCategory.options(entries, prefs: prefs)
        let byKey = Dictionary(options.map { ($0.category.key, $0) }, uniquingKeysWith: { a, _ in a })
        return prefs.home.arrange(options.map(\.category.key)).compactMap { key in
            byKey[key].map {
                HomeCategoryRow(category: $0.category, count: $0.count, pinned: prefs.home.isPinned(key),
                                hidden: prefs.home.isHidden(key))
            }
        }
    }

    /// Claves de las categorías presentes en su orden natural (lo que esperan `HomeSettings.moving`…).
    static func keys(_ entries: [RomEntry], prefs: LibraryPreferencesData) -> [String] {
        LibraryCategory.options(entries, prefs: prefs).map(\.category.key)
    }
}

// MARK: - Búsqueda y filtro por etiqueta

/// Una etiqueta de los juegos visibles y cuántos la llevan (filtro por etiqueta).
struct TagOption: Equatable, Hashable, Sendable, Identifiable {
    let tag: String
    let count: Int
    var id: String { TextKey.fold(tag) }
}

extension LibraryQuery {
    /// Búsqueda (N4): nombre visible, archivo, cada nivel de la categoría **en la que se ve** el juego
    /// (N4A-6) y sus etiquetas, sin mayúsculas ni acentos.
    static func matches(_ entry: RomEntry, prefs: LibraryPreferencesData, query: String) -> Bool {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return true }
        let options: String.CompareOptions = [.caseInsensitive, .diacriticInsensitive]
        func has(_ text: String) -> Bool { text.range(of: q, options: options) != nil }
        if has(prefs.displayTitle(entry)) || has(entry.fileName) { return true }
        if prefs.categoryPath(entry).contains(where: has) { return true }
        return prefs.tags(entry).contains(where: has)
    }

    /// Como `visible(_:prefs:filter:query:)`, solo con los juegos que llevan la etiqueta `tag` (sin
    /// mayúsculas ni acentos). nil = sin filtro de etiqueta.
    static func visible(_ entries: [RomEntry], prefs: LibraryPreferencesData, filter: LibraryFilter,
                        query: String, tag: String?) -> [RomEntry] {
        let shown = visible(entries, prefs: prefs, filter: filter, query: query)
        guard let tag else { return shown }
        return shown.filter { entry in prefs.tags(entry).contains { Tags.same($0, tag) } }
    }

    /// Etiquetas de los juegos visibles (sin ocultos) con cuántos juegos la llevan, en orden natural. Dos
    /// escrituras de la misma etiqueta son una sola opción (con la del primer juego que la lleva).
    static func tagOptions(_ entries: [RomEntry], prefs: LibraryPreferencesData) -> [TagOption] {
        var counts: [String: (tag: String, count: Int)] = [:]
        for entry in entries where !prefs.isHidden(entry) {
            for tag in prefs.tags(entry) {
                let key = TextKey.fold(tag)
                let current = counts[key]
                counts[key] = (current?.tag ?? tag, (current?.count ?? 0) + 1)
            }
        }
        return counts.values.map { TagOption(tag: $0.tag, count: $0.count) }
            .sorted { TextKey.naturalLess($0.tag, $1.tag) }
    }
}

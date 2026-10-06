import Foundation
import Observation

/// Presentación de la biblioteca (SPEC §8, `LibraryView` grid/list).
enum LibraryLayout: String, Codable, CaseIterable, Sendable {
    case grid, list

    var title: String { self == .grid ? "Cuadrícula" : "Lista" }
    var systemImage: String { self == .grid ? "square.grid.2x2" : "list.bullet" }
}

/// Orden de "Todos los juegos".
enum LibrarySort: String, Codable, CaseIterable, Sendable {
    case title, recent

    var title: String { self == .title ? "Nombre" : "Jugados recientemente" }
}

/// Filtros de la biblioteca (SPEC §4): solo Game Boy y Game Boy Color.
enum LibraryFilter: String, CaseIterable, Identifiable, Sendable {
    case all, gb, gbc, gba, favorites

    var id: Self { self }
    var title: String {
        switch self {
        case .all: "Todos"
        case .gb: "GB"
        case .gbc: "GBC"
        case .gba: "GBA"
        case .favorites: "Favoritos"
        }
    }
}

/// Lo que PocketGB recuerda de cada juego de la carpeta. Solo metadatos de la app:
/// ocultar un juego no toca el ROM, ni la partida, ni sus copias (SPEC §12).
struct LibraryPreferencesData: Codable, Equatable, Sendable {
    /// Ids (ruta relativa) de los favoritos.
    var favorites: Set<String> = []
    /// Última vez que se abrió cada juego, por id.
    var lastPlayed: [String: Date] = [:]
    /// Huella SHA-256 del ROM, por id; se conoce al abrirlo por primera vez.
    var fingerprints: [String: String] = [:]
    /// Juegos ocultos por su huella (sobrevive a mover o renombrar el ROM).
    var hiddenFingerprints: Set<String> = []
    /// Juegos ocultos que nunca se abrieron (sin huella todavía), por id.
    var hiddenPaths: Set<String> = []
    /// Nombre visible estable cuando ya se conoce el contenido del ROM.
    var aliasesByFingerprint: [String: String] = [:]
    /// Nombre visible provisional antes de la primera apertura del ROM.
    var aliasesByPath: [String: String] = [:]
    var layout: LibraryLayout = .grid
    var sort: LibrarySort = .title

    init() {}

    // Claves opcionales: un archivo de una versión anterior se sigue leyendo.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        favorites = try c.decodeIfPresent(Set<String>.self, forKey: .favorites) ?? []
        lastPlayed = try c.decodeIfPresent([String: Date].self, forKey: .lastPlayed) ?? [:]
        fingerprints = try c.decodeIfPresent([String: String].self, forKey: .fingerprints) ?? [:]
        hiddenFingerprints = try c.decodeIfPresent(Set<String>.self, forKey: .hiddenFingerprints) ?? []
        hiddenPaths = try c.decodeIfPresent(Set<String>.self, forKey: .hiddenPaths) ?? []
        aliasesByFingerprint = try c.decodeIfPresent([String: String].self, forKey: .aliasesByFingerprint) ?? [:]
        aliasesByPath = try c.decodeIfPresent([String: String].self, forKey: .aliasesByPath) ?? [:]
        layout = (try? c.decodeIfPresent(LibraryLayout.self, forKey: .layout)) ?? .grid
        sort = (try? c.decodeIfPresent(LibrarySort.self, forKey: .sort)) ?? .title
    }

    func isFavorite(_ entry: RomEntry) -> Bool { favorites.contains(entry.id) }
    func lastPlayedDate(_ entry: RomEntry) -> Date? { lastPlayed[entry.id] }

    func isHidden(_ entry: RomEntry) -> Bool {
        if hiddenPaths.contains(entry.id) { return true }
        guard let fp = fingerprints[entry.id] else { return false }
        return hiddenFingerprints.contains(fp)
    }

    func displayTitle(_ entry: RomEntry) -> String {
        if let fingerprint = fingerprints[entry.id], let alias = aliasesByFingerprint[fingerprint] {
            return alias
        }
        return aliasesByPath[entry.id] ?? entry.title
    }
}

/// Favoritos, recientes, ocultos, vista y orden (D3). Se guardan en
/// `Application Support/Library/preferences.json`; con `fileURL == nil` (tests y
/// capturas DEBUG) solo viven en memoria.
@MainActor @Observable
final class LibraryPreferences {
    private(set) var data: LibraryPreferencesData
    private let fileURL: URL?
    private let queue = DispatchQueue(label: "PocketGB.library-preferences", qos: .utility)

    init(fileURL: URL?) {
        self.fileURL = fileURL
        if let fileURL, let raw = try? Data(contentsOf: fileURL),
           let decoded = try? JSONDecoder().decode(LibraryPreferencesData.self, from: raw) {
            data = decoded
        } else {
            data = LibraryPreferencesData()
        }
    }

    static func defaultFileURL() -> URL? {
        guard let base = try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                                      appropriateFor: nil, create: true) else { return nil }
        return base.appendingPathComponent("Library", isDirectory: true)
            .appendingPathComponent("preferences.json")
    }

    func fingerprint(of entry: RomEntry) -> String? { data.fingerprints[entry.id] }
    func isFavorite(_ entry: RomEntry) -> Bool { data.isFavorite(entry) }
    func lastPlayed(_ entry: RomEntry) -> Date? { data.lastPlayedDate(entry) }
    func isHidden(_ entry: RomEntry) -> Bool { data.isHidden(entry) }
    func displayTitle(_ entry: RomEntry) -> String { data.displayTitle(entry) }

    func toggleFavorite(_ entry: RomEntry) {
        if data.favorites.contains(entry.id) {
            data.favorites.remove(entry.id)
        } else {
            data.favorites.insert(entry.id)
        }
        persist()
    }

    /// Al abrir un juego: fecha y huella del ROM.
    func recordPlayed(id: String, fingerprint: String, at date: Date) {
        data.lastPlayed[id] = date
        data.fingerprints[id] = fingerprint
        if let alias = data.aliasesByPath.removeValue(forKey: id) {
            data.aliasesByFingerprint[fingerprint] = alias
        }
        persist()
    }

    /// El alias solo cambia la presentación; nunca renombra el ROM ni sus partidas.
    func setAlias(_ value: String, for entry: RomEntry) {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        let alias = String(trimmed.prefix(80))
        if let fingerprint = data.fingerprints[entry.id] {
            if alias.isEmpty { data.aliasesByFingerprint.removeValue(forKey: fingerprint) }
            else { data.aliasesByFingerprint[fingerprint] = alias }
            data.aliasesByPath.removeValue(forKey: entry.id)
        } else if alias.isEmpty {
            data.aliasesByPath.removeValue(forKey: entry.id)
        } else {
            data.aliasesByPath[entry.id] = alias
        }
        persist()
    }

    /// Oculta el juego de la biblioteca. Solo cambia este archivo de preferencias.
    func hide(_ entry: RomEntry) {
        if let fp = data.fingerprints[entry.id] {
            data.hiddenFingerprints.insert(fp)
        } else {
            data.hiddenPaths.insert(entry.id)
        }
        persist()
    }

    func unhide(_ entry: RomEntry) {
        data.hiddenPaths.remove(entry.id)
        if let fp = data.fingerprints[entry.id] { data.hiddenFingerprints.remove(fp) }
        persist()
    }

    func setLayout(_ layout: LibraryLayout) {
        guard data.layout != layout else { return }
        data.layout = layout
        persist()
    }

    func setSort(_ sort: LibrarySort) {
        guard data.sort != sort else { return }
        data.sort = sort
        persist()
    }

    /// Escritura en una cola serie: el orden de los cambios se conserva.
    private func persist() {
        guard let fileURL else { return }
        guard let encoded = try? JSONEncoder().encode(data) else { return }
        queue.async {
            try? FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(),
                                                     withIntermediateDirectories: true)
            try? encoded.write(to: fileURL, options: .atomic)
        }
    }

    /// Espera a que terminen las escrituras pendientes (tests).
    func waitForPendingWrites() {
        queue.sync {}
    }

    #if DEBUG
    /// Estado de demostración para capturas (sin disco).
    func applyDemo(_ demo: LibraryPreferencesData) {
        data = demo
    }
    #endif
}

/// Qué juegos se ven y en qué orden. Cuadrícula y lista usan la misma función
/// (D-README §5: "Grid/list comparten el mismo modelo").
enum LibraryQuery {
    static func matches(_ entry: RomEntry, filter: LibraryFilter, isFavorite: Bool) -> Bool {
        switch filter {
        case .all: true
        case .gb: entry.badge == .gb
        case .gbc: entry.badge == .gbc
        case .gba: entry.badge == .gba
        case .favorites: isFavorite
        }
    }

    static func matches(_ entry: RomEntry, displayTitle: String, query: String) -> Bool {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return true }
        let options: String.CompareOptions = [.caseInsensitive, .diacriticInsensitive]
        return displayTitle.range(of: q, options: options) != nil
            || entry.fileName.range(of: q, options: options) != nil
    }

    static func visible(_ entries: [RomEntry], prefs: LibraryPreferencesData, filter: LibraryFilter,
                        query: String) -> [RomEntry] {
        let shown = entries.filter {
            !prefs.isHidden($0) && matches($0, filter: filter, isFavorite: prefs.isFavorite($0))
                && matches($0, displayTitle: prefs.displayTitle($0), query: query)
        }
        switch prefs.sort {
        case .title:
            return shown.sorted {
                prefs.displayTitle($0).localizedStandardCompare(prefs.displayTitle($1)) == .orderedAscending
            }
        case .recent:
            return shown.sorted { a, b in
                let da = prefs.lastPlayedDate(a) ?? .distantPast
                let db = prefs.lastPlayedDate(b) ?? .distantPast
                if da != db { return da > db }
                return prefs.displayTitle(a).localizedStandardCompare(prefs.displayTitle(b)) == .orderedAscending
            }
        }
    }

    /// Jugados recientemente (no ocultos), del más reciente al más antiguo.
    static func recent(_ entries: [RomEntry], prefs: LibraryPreferencesData, limit: Int = 10) -> [RomEntry] {
        let played = entries.filter { !prefs.isHidden($0) && prefs.lastPlayedDate($0) != nil }
        return Array(played.sorted { (prefs.lastPlayedDate($0) ?? .distantPast) > (prefs.lastPlayedDate($1) ?? .distantPast) }
            .prefix(limit))
    }
}

extension LibraryPreferences {
    /// Juegos visibles de la biblioteca con el filtro, la búsqueda y el orden actuales.
    func visible(_ entries: [RomEntry], filter: LibraryFilter, query: String) -> [RomEntry] {
        LibraryQuery.visible(entries, prefs: data, filter: filter, query: query)
    }

    func recent(_ entries: [RomEntry], limit: Int = 10) -> [RomEntry] {
        LibraryQuery.recent(entries, prefs: data, limit: limit)
    }
}

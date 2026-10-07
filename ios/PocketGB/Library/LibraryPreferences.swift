import Foundation
import Observation
import os

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

/// Lo que PocketGB recuerda de un juego (N1a, plan §3.1). Solo metadatos de la app: ocultar
/// un juego no toca el ROM, ni la partida, ni sus copias (SPEC §12).
struct GameMetadata: Codable, Equatable, Sendable {
    var favorite = false
    var lastPlayed: Date?
    /// Ruta desde la que se abrió la última vez: con duplicados, «Continuar» muestra esa copia.
    var lastPlayedPath: String?
    var hidden = false
    /// Nombre visible (≤ 80 caracteres); nil = el título de la cabecera.
    var alias: String?
    /// Ajustes del juego (color, paleta, partida GBA…); vacío = los globales.
    var overrides = GameOverrides()

    var isEmpty: Bool {
        !favorite && lastPlayed == nil && !hidden && alias == nil && overrides.isEmpty
    }

    init() {}

    /// Cada campo es opcional y se valida por separado: un campo dañado vuelve a su valor por
    /// defecto sin descartar el resto del juego.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        favorite = (try? c.decodeIfPresent(Bool.self, forKey: .favorite)) ?? false
        lastPlayed = (try? c.decodeIfPresent(Date.self, forKey: .lastPlayed)) ?? nil
        lastPlayedPath = (try? c.decodeIfPresent(String.self, forKey: .lastPlayedPath)) ?? nil
        hidden = (try? c.decodeIfPresent(Bool.self, forKey: .hidden)) ?? false
        let rawAlias = (try? c.decodeIfPresent(String.self, forKey: .alias)) ?? nil
        alias = rawAlias.map { String($0.prefix(80)) }
        overrides = (try? c.decodeIfPresent(GameOverrides.self, forKey: .overrides)) ?? GameOverrides()
    }

    /// Une lo provisional (por ruta) con lo de la huella: lo marcado en cualquiera de los dos se
    /// conserva y, en un valor con los dos lados puestos, gana el de la huella.
    func merging(_ other: GameMetadata) -> GameMetadata {
        var merged = self
        merged.favorite = favorite || other.favorite
        merged.hidden = hidden || other.hidden
        if (other.lastPlayed ?? .distantPast) > (lastPlayed ?? .distantPast) {
            merged.lastPlayed = other.lastPlayed
            merged.lastPlayedPath = other.lastPlayedPath
        }
        merged.alias = alias ?? other.alias
        merged.overrides = overrides.filling(other.overrides)
        return merged
    }
}

extension GameOverrides {
    /// Los valores puestos aquí ganan; los demás se toman de `other`.
    func filling(_ other: GameOverrides) -> GameOverrides {
        GameOverrides(colorForGameBoy: colorForGameBoy ?? other.colorForGameBoy,
                      compatPalette: compatPalette ?? other.compatPalette,
                      gbaSaveType: gbaSaveType ?? other.gbaSaveType,
                      gbaRTC: gbaRTC ?? other.gbaRTC,
                      gbaUseBIOS: gbaUseBIOS ?? other.gbaUseBIOS)
    }
}

/// Preferencias de la biblioteca, formato 2 (N1a): todo por **huella** y, mientras un juego
/// no tiene huella conocida, por su ruta relativa como clave provisional. Al conocer la huella
/// (caché, cálculo en segundo plano o apertura), lo provisional se une a la huella.
///
/// El formato 1 (hasta N0: favoritos, recientes y alias por ruta, ocultos por huella o ruta)
/// se migra al leerlo, sin perder nada (`migrating(_:)`).
struct LibraryPreferencesData: Codable, Equatable, Sendable {
    static let currentVersion = 2

    var version = currentVersion
    /// Metadatos por huella: la fuente de verdad.
    var games: [String: GameMetadata] = [:]
    /// Metadatos provisionales de juegos cuya huella aún no se conoce, por ruta relativa.
    var pendingByPath: [String: GameMetadata] = [:]
    /// Última huella conocida de cada ruta (al abrir el juego). Pista para los juegos que no se
    /// pueden hashear ahora (iCloud sin descargar); la huella del escaneo tiene prioridad.
    var fingerprints: [String: String] = [:]
    var layout: LibraryLayout = .grid
    var sort: LibrarySort = .title
    /// Ya se importaron los ajustes por juego de `UserDefaults` (`gameplaySettings.perGame`,
    /// por ruta, hasta N0). Esa copia antigua no se borra: queda como respaldo.
    var importedLegacyGameSettings = false

    enum CodingKeys: String, CodingKey {
        case version, games, pendingByPath, fingerprints, layout, sort, importedLegacyGameSettings
    }

    init() {}

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        // Solo un archivo **sin** la clave `version` es del formato 1 (auditoría N1, H4): un
        // `null`, un texto o un número menor que 2 no se migran ni se leen como formato 2.
        guard c.contains(.version) else {
            self = Self.migrating(try LegacyV1(from: decoder))
            return
        }
        let stored = try c.decode(Int.self, forKey: .version)
        guard stored >= Self.currentVersion else {
            throw DecodingError.dataCorruptedError(forKey: .version, in: c,
                                                   debugDescription: "Versión \(stored) no válida")
        }
        version = stored
        // Las colecciones se leen estrictas: si una no se entiende, todo el archivo se aparta
        // (cuarentena) en lugar de perderla en silencio. Cada juego tolera campos dañados.
        games = try c.decodeIfPresent([String: GameMetadata].self, forKey: .games) ?? [:]
        pendingByPath = try c.decodeIfPresent([String: GameMetadata].self, forKey: .pendingByPath) ?? [:]
        fingerprints = try c.decodeIfPresent([String: String].self, forKey: .fingerprints) ?? [:]
        layout = (try? c.decodeIfPresent(LibraryLayout.self, forKey: .layout)) ?? .grid
        sort = (try? c.decodeIfPresent(LibrarySort.self, forKey: .sort)) ?? .title
        importedLegacyGameSettings = (try? c.decodeIfPresent(Bool.self, forKey: .importedLegacyGameSettings)) ?? false
    }

    /// Formato 1 tal como lo escribía la versión anterior (`LibraryPreferences.swift` hasta N0).
    struct LegacyV1: Decodable, Equatable, Sendable {
        var favorites: Set<String> = []
        var lastPlayed: [String: Date] = [:]
        var fingerprints: [String: String] = [:]
        var hiddenFingerprints: Set<String> = []
        var hiddenPaths: Set<String> = []
        var aliasesByFingerprint: [String: String] = [:]
        var aliasesByPath: [String: String] = [:]
        var layout: LibraryLayout = .grid
        var sort: LibrarySort = .title

        enum CodingKeys: String, CodingKey {
            case favorites, lastPlayed, fingerprints, hiddenFingerprints, hiddenPaths
            case aliasesByFingerprint, aliasesByPath, layout, sort
        }

        init() {}

        // Mismas reglas que la versión 1: claves opcionales y colecciones estrictas.
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
    }

    /// Formato 1 → 2. Lo que iba por una ruta con huella conocida pasa a la huella; el resto
    /// queda provisional por ruta. Como en la versión 1, el alias de la huella gana al de la ruta.
    static func migrating(_ v1: LegacyV1) -> LibraryPreferencesData {
        var d = LibraryPreferencesData()
        d.layout = v1.layout
        d.sort = v1.sort
        d.fingerprints = v1.fingerprints
        func edit(_ path: String, _ change: (inout GameMetadata) -> Void) {
            if let fp = v1.fingerprints[path] {
                change(&d.games[fp, default: GameMetadata()])
            } else {
                change(&d.pendingByPath[path, default: GameMetadata()])
            }
        }
        for path in v1.favorites.sorted() { edit(path) { $0.favorite = true } }
        for path in v1.lastPlayed.keys.sorted() {
            let date = v1.lastPlayed[path] ?? .distantPast
            edit(path) { m in
                if (m.lastPlayed ?? .distantPast) < date {
                    m.lastPlayed = date
                    m.lastPlayedPath = path
                }
            }
        }
        for fp in v1.hiddenFingerprints.sorted() { d.games[fp, default: GameMetadata()].hidden = true }
        for path in v1.hiddenPaths.sorted() { edit(path) { $0.hidden = true } }
        for fp in v1.aliasesByFingerprint.keys.sorted() {
            d.games[fp, default: GameMetadata()].alias = v1.aliasesByFingerprint[fp]
        }
        for path in v1.aliasesByPath.keys.sorted() {
            let alias = v1.aliasesByPath[path]
            edit(path) { if $0.alias == nil { $0.alias = alias } }
        }
        return d
    }

    /// Huella del juego: la del escaneo (caché o cálculo) y, si no hay, la última conocida.
    func fingerprint(of entry: RomEntry) -> String? {
        entry.fingerprint ?? fingerprints[entry.id]
    }

    /// Metadatos del juego: los de su huella, unidos a lo provisional de su ruta si aún queda.
    func metadata(_ entry: RomEntry) -> GameMetadata {
        metadata(fingerprint: fingerprint(of: entry), path: entry.id)
    }

    func metadata(fingerprint: String?, path: String) -> GameMetadata {
        let pending = pendingByPath[path]
        guard let fingerprint else { return pending ?? GameMetadata() }
        let known = games[fingerprint] ?? GameMetadata()
        return pending.map { known.merging($0) } ?? known
    }

    func isFavorite(_ entry: RomEntry) -> Bool { metadata(entry).favorite }
    func lastPlayedDate(_ entry: RomEntry) -> Date? { metadata(entry).lastPlayed }
    func isHidden(_ entry: RomEntry) -> Bool { metadata(entry).hidden }
    func displayTitle(_ entry: RomEntry) -> String { metadata(entry).alias ?? entry.title }
    func overrides(_ entry: RomEntry) -> GameOverrides { metadata(entry).overrides }
}

/// Favoritos, recientes, ocultos, alias, ajustes por juego, vista y orden (D3, N1a). Se guardan
/// en `Application Support/Library/preferences.json`; con `fileURL == nil` (tests y capturas
/// DEBUG) solo viven en memoria.
///
/// Robustez (N1a, como Android `LibraryPreferencesFile`):
/// - Un archivo que no se entiende se aparta como `preferences.corrupt-<fecha>.json` (sin pisar
///   uno previo), se empieza de cero y se avisa.
/// - Un error de E/S al leer bloquea las escrituras de la sesión: nunca se pisa un archivo que
///   no se pudo leer. Igual con un archivo de una versión más nueva.
/// - Las escrituras son atómicas (temporal + fsync + rename) y sus errores se avisan.
/// - Al migrar del formato 1, el archivo original se copia antes a `preferences.v1.json`.
@MainActor @Observable
final class LibraryPreferences {
    /// Problema con el archivo de preferencias, con su texto para Joel.
    enum Issue: Equatable, Sendable {
        case quarantined(fileName: String)
        case unreadable(String)
        case newerVersion(Int)
        case writeFailed(String)
        /// No se pudo copiar el archivo del formato 1 antes de migrarlo: no se toca (H10).
        case migrationBackupFailed(String)

        var title: String {
            switch self {
            case .quarantined: "Preferencias de la biblioteca dañadas"
            case .unreadable: "No se pudieron leer las preferencias"
            case .newerVersion: "Preferencias de una versión más nueva"
            case .writeFailed: "No se pudieron guardar las preferencias"
            case .migrationBackupFailed: "No se pudieron actualizar las preferencias"
            }
        }

        var message: String {
            switch self {
            case .quarantined(let name):
                "No se pudieron leer los favoritos, nombres y ajustes por juego. El archivo se apartó sin borrarlo como “\(name)” y PocketGB empieza con preferencias nuevas. Tus partidas, estados y ROMs no se tocan."
            case .unreadable(let detail):
                "PocketGB no pudo abrir el archivo de favoritos, nombres y ajustes por juego (\(detail)). No se modificará: los cambios de esta sesión no se guardarán. Tus partidas no se tocan."
            case .newerVersion:
                "Las guardó una versión más nueva de PocketGB. Se usan, pero no se modificarán: los cambios de esta sesión no se guardarán."
            case .writeFailed(let detail):
                "Los últimos cambios de favoritos, nombres o ajustes por juego no se guardaron (\(detail)). Tus partidas no se ven afectadas."
            case .migrationBackupFailed(let detail):
                "PocketGB no pudo guardar una copia del archivo de favoritos, nombres y ajustes antes de pasarlo al formato nuevo (\(detail)). Se usan, pero no se modificarán: los cambios de esta sesión no se guardarán. Tus partidas no se tocan."
            }
        }
    }

    private(set) var data: LibraryPreferencesData
    /// Último problema con el archivo (al leerlo o al escribirlo).
    private(set) var issue: Issue?
    /// Se llama al aparecer un problema nuevo de escritura (la app lo muestra como alerta).
    @ObservationIgnored var onIssue: ((Issue) -> Void)?
    @ObservationIgnored private let fileURL: URL?
    @ObservationIgnored private var writesBlocked = false
    @ObservationIgnored private let queue = DispatchQueue(label: "PocketGB.library-preferences", qos: .utility)
    @ObservationIgnored private let writeResult = WriteResultBox()
    @ObservationIgnored private let log = Logger(subsystem: "com.joelbermudez.pocketgb", category: "library-preferences")

    init(fileURL: URL?, now: Date = Date()) {
        self.fileURL = fileURL
        guard let fileURL else {
            data = LibraryPreferencesData()
            return
        }
        let loaded = Self.load(fileURL, now: now)
        data = loaded.data
        issue = loaded.issue
        writesBlocked = loaded.blocked
        if let issue = loaded.issue {
            log.error("Preferencias: \(issue.title, privacy: .public)")
        }
        if loaded.migrated { persist() }
    }

    nonisolated static func defaultFileURL() -> URL? {
        guard let base = try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                                      appropriateFor: nil, create: true) else { return nil }
        return base.appendingPathComponent("Library", isDirectory: true)
            .appendingPathComponent("preferences.json")
    }

    // MARK: Lectura

    private struct Loaded {
        var data = LibraryPreferencesData()
        var issue: Issue?
        var blocked = false
        var migrated = false
    }

    /// Formato del archivo, mirado **antes** de decodificarlo (auditoría N1, H2 y H4).
    enum FileFormat: Equatable, Sendable {
        /// No es un objeto JSON.
        case notAnObject
        /// Sin clave `version`: formato 1.
        case legacy
        case version(Int)
        /// `version` existe pero no es un entero (`null`, texto, decimal, booleano).
        case invalidVersion
    }

    nonisolated static func fileFormat(_ raw: Data) -> FileFormat {
        guard let object = try? JSONSerialization.jsonObject(with: raw) as? [String: Any] else { return .notAnObject }
        guard let value = object["version"] else { return .legacy }
        guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(),
              let version = value as? Int else { return .invalidVersion }
        return .version(version)
    }

    /// `version` del JSON (nil = formato 1 o no válida).
    nonisolated static func fileVersion(_ raw: Data) -> Int? {
        if case .version(let v) = fileFormat(raw) { return v }
        return nil
    }

    /// Copia exacta del archivo del formato 1 antes de migrarlo: `preferences.v1.json` o, si ya
    /// existe con otro contenido (p. ej. una copia a medias), `preferences.v1-<fecha>[-n].json`.
    /// Nunca pisa nada y comprueba lo escrito; si falla, lanza y no se migra en disco (H10).
    nonisolated static func backupLegacy(_ raw: Data, beside fileURL: URL, now: Date) throws -> URL {
        let fm = FileManager.default
        let dir = fileURL.deletingLastPathComponent()
        var target = dir.appendingPathComponent("preferences.v1.json")
        if fm.fileExists(atPath: target.path) {
            if (try? Data(contentsOf: target)) == raw { return target }
            let formatter = DateFormatter()
            formatter.locale = Locale(identifier: "en_US_POSIX")
            formatter.dateFormat = "yyyyMMdd-HHmmss"
            let stamp = formatter.string(from: now)
            target = dir.appendingPathComponent("preferences.v1-\(stamp).json")
            var n = 2
            while fm.fileExists(atPath: target.path) {
                target = dir.appendingPathComponent("preferences.v1-\(stamp)-\(n).json")
                n += 1
            }
        }
        let tmp = target.appendingPathExtension("tmp")
        try AtomicFile.writeSynced(raw, to: tmp)
        guard !fm.fileExists(atPath: target.path) else {
            try? fm.removeItem(at: tmp)
            throw CocoaError(.fileWriteFileExists)
        }
        try AtomicFile.rename(tmp, target)
        guard (try Data(contentsOf: target)) == raw else { throw CocoaError(.fileWriteUnknown) }
        return target
    }

    /// `preferences.corrupt-<fecha>.json` libre (con `-2`, `-3`… si ya existe): nunca se pisa.
    nonisolated static func quarantineURL(for fileURL: URL, now: Date) -> URL {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyyMMdd-HHmmss"
        let stamp = formatter.string(from: now)
        let dir = fileURL.deletingLastPathComponent()
        let base = fileURL.deletingPathExtension().lastPathComponent
        var candidate = dir.appendingPathComponent("\(base).corrupt-\(stamp).json")
        var n = 2
        while FileManager.default.fileExists(atPath: candidate.path) {
            candidate = dir.appendingPathComponent("\(base).corrupt-\(stamp)-\(n).json")
            n += 1
        }
        return candidate
    }

    private static func load(_ fileURL: URL, now: Date) -> Loaded {
        let fm = FileManager.default
        let decoder = JSONDecoder()
        guard fm.fileExists(atPath: fileURL.path) else {
            // Primera escritura cortada justo antes del rename: se recupera el temporal completo.
            let tmp = fileURL.appendingPathExtension("tmp")
            if let raw = try? Data(contentsOf: tmp),
               let decoded = try? decoder.decode(LibraryPreferencesData.self, from: raw),
               (try? AtomicFile.rename(tmp, fileURL)) != nil {
                return Loaded(data: decoded)
            }
            return Loaded()
        }
        let raw: Data
        do {
            raw = try Data(contentsOf: fileURL)
        } catch {
            // Error de E/S: no es motivo para apartar ni sobrescribir el archivo.
            return Loaded(issue: .unreadable(error.localizedDescription), blocked: true)
        }
        let format = fileFormat(raw)
        // Versión más nueva: se lee lo que se entienda, sin apartar el archivo ni escribirlo,
        // aunque esta versión no sepa decodificarlo (auditoría N1, H2).
        if case .version(let version) = format, version > LibraryPreferencesData.currentVersion {
            let decoded = (try? decoder.decode(LibraryPreferencesData.self, from: raw)) ?? LibraryPreferencesData()
            return Loaded(data: decoded, issue: .newerVersion(version), blocked: true)
        }
        do {
            let decoded = try decoder.decode(LibraryPreferencesData.self, from: raw)
            guard format == .legacy else { return Loaded(data: decoded) }
            // Formato 1: copia exacta del original antes de escribir el 2; si no se puede, no
            // se migra en disco (se usa en memoria, sin escribir) y se avisa (H10).
            do {
                _ = try backupLegacy(raw, beside: fileURL, now: now)
            } catch {
                return Loaded(data: decoded, issue: .migrationBackupFailed(error.localizedDescription), blocked: true)
            }
            return Loaded(data: decoded, migrated: true)
        } catch {
            let target = quarantineURL(for: fileURL, now: now)
            do {
                try fm.moveItem(at: fileURL, to: target)
                return Loaded(issue: .quarantined(fileName: target.lastPathComponent))
            } catch {
                return Loaded(issue: .unreadable(error.localizedDescription), blocked: true)
            }
        }
    }

    // MARK: Consultas

    func fingerprint(of entry: RomEntry) -> String? { data.fingerprint(of: entry) }
    func isFavorite(_ entry: RomEntry) -> Bool { data.isFavorite(entry) }
    func lastPlayed(_ entry: RomEntry) -> Date? { data.lastPlayedDate(entry) }
    func isHidden(_ entry: RomEntry) -> Bool { data.isHidden(entry) }
    func displayTitle(_ entry: RomEntry) -> String { data.displayTitle(entry) }
    func overrides(for entry: RomEntry) -> GameOverrides { data.overrides(entry) }

    /// Ajustes del juego que se va a abrir: por la huella calculada de los bytes leídos (vale
    /// aunque el archivo se acabe de mover) o, si no hay, por la última conocida de su ruta.
    func overrides(fingerprint: String?, path: String) -> GameOverrides {
        data.metadata(fingerprint: fingerprint ?? data.fingerprints[path], path: path).overrides
    }

    // MARK: Cambios

    /// Cambia los metadatos del juego: en su huella si se conoce (uniendo antes lo provisional
    /// de la ruta) o, si no, provisionalmente en su ruta.
    private func edit(_ entry: RomEntry, _ change: (inout GameMetadata) -> Void) {
        if let fp = data.fingerprint(of: entry) {
            var m = data.games[fp] ?? GameMetadata()
            if let pending = data.pendingByPath.removeValue(forKey: entry.id) { m = m.merging(pending) }
            change(&m)
            data.games[fp] = m.isEmpty ? nil : m
        } else {
            var m = data.pendingByPath[entry.id] ?? GameMetadata()
            change(&m)
            data.pendingByPath[entry.id] = m.isEmpty ? nil : m
        }
        persist()
    }

    func toggleFavorite(_ entry: RomEntry) {
        let favorite = isFavorite(entry)
        edit(entry) { $0.favorite = !favorite }
    }

    /// Al abrir un juego: fecha, ruta y huella del ROM (la del núcleo).
    func recordPlayed(id: String, fingerprint: String, at date: Date) {
        data.fingerprints[id] = fingerprint
        _ = adoptPending(path: id, fingerprint: fingerprint)
        var m = data.games[fingerprint] ?? GameMetadata()
        m.lastPlayed = date
        m.lastPlayedPath = id
        data.games[fingerprint] = m
        persist()
    }

    /// Huellas recién conocidas (escaneo, caché o cálculo en segundo plano): lo provisional de
    /// esas rutas pasa a su huella. Solo escribe si algo cambió.
    func adopt(_ resolved: [String: String]) {
        var changed = false
        for path in resolved.keys.sorted() {
            if let fingerprint = resolved[path], adoptPending(path: path, fingerprint: fingerprint) { changed = true }
        }
        if changed { persist() }
    }

    private func adoptPending(path: String, fingerprint: String) -> Bool {
        guard let pending = data.pendingByPath.removeValue(forKey: path) else { return false }
        let known = data.games[fingerprint] ?? GameMetadata()
        data.games[fingerprint] = known.merging(pending)
        return true
    }

    /// El alias solo cambia la presentación; nunca renombra el ROM ni sus partidas.
    func setAlias(_ value: String, for entry: RomEntry) {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        let alias = String(trimmed.prefix(80))
        edit(entry) { $0.alias = alias.isEmpty ? nil : alias }
    }

    /// Oculta el juego de la biblioteca. Solo cambia este archivo de preferencias.
    func hide(_ entry: RomEntry) {
        edit(entry) { $0.hidden = true }
    }

    func unhide(_ entry: RomEntry) {
        edit(entry) { $0.hidden = false }
    }

    /// Ajustes de un juego; todo en «Global» = sin ajustes propios.
    func setOverrides(_ overrides: GameOverrides, for entry: RomEntry) {
        edit(entry) { $0.overrides = overrides }
    }

    /// Importa una vez los ajustes por juego de `UserDefaults` (por ruta, hasta N0). Lo que ya
    /// tiene la huella gana; la copia antigua no se toca.
    func importLegacyGameSettings(_ legacy: [String: GameOverrides]) {
        guard !data.importedLegacyGameSettings else { return }
        for path in legacy.keys.sorted() {
            guard let overrides = legacy[path], !overrides.isEmpty else { continue }
            if let fp = data.fingerprints[path] {
                var m = data.games[fp] ?? GameMetadata()
                m.overrides = m.overrides.filling(overrides)
                data.games[fp] = m
            } else {
                var m = data.pendingByPath[path] ?? GameMetadata()
                m.overrides = m.overrides.filling(overrides)
                data.pendingByPath[path] = m
            }
        }
        data.importedLegacyGameSettings = true
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

    // MARK: Escritura

    /// Resultado de la última escritura, compartido con la cola de escritura.
    private final class WriteResultBox: Sendable {
        enum State: Sendable { case idle, ok, failed(String) }
        private let state = OSAllocatedUnfairLock(initialState: State.idle)

        func set(_ value: State) { state.withLock { $0 = value } }
        func take() -> State {
            state.withLock { current in
                let value = current
                current = .idle
                return value
            }
        }
    }

    /// Escritura atómica en una cola serie: el orden de los cambios se conserva.
    private func persist() {
        guard let fileURL, !writesBlocked else { return }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let encoded: Data
        do {
            encoded = try encoder.encode(data)
        } catch {
            report(.writeFailed(error.localizedDescription))
            return
        }
        let box = writeResult
        queue.async { [weak self] in
            do {
                let dir = fileURL.deletingLastPathComponent()
                try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                let tmp = fileURL.appendingPathExtension("tmp")
                try AtomicFile.writeSynced(encoded, to: tmp)
                try AtomicFile.rename(tmp, fileURL)
                try? AtomicFile.syncDirectory(dir)
                box.set(.ok)
            } catch {
                box.set(.failed(String(describing: error)))
            }
            Task { @MainActor [weak self] in self?.drainWriteResult() }
        }
    }

    private func drainWriteResult() {
        switch writeResult.take() {
        case .idle:
            break
        case .ok:
            if case .writeFailed = issue { issue = nil }
        case .failed(let detail):
            report(.writeFailed(detail))
        }
    }

    private func report(_ new: Issue) {
        log.error("Preferencias: \(new.title, privacy: .public)")
        // Un aviso por racha de fallos: no una alerta en cada cambio.
        if case .writeFailed = issue, case .writeFailed = new { return }
        issue = new
        onIssue?(new)
    }

    /// Espera a que terminen las escrituras pendientes (tests).
    func waitForPendingWrites() {
        queue.sync {}
        drainWriteResult()
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

    /// Jugados recientemente (no ocultos), del más reciente al más antiguo. Con duplicados
    /// (misma huella), una sola tarjeta: la copia que se abrió la última vez.
    static func recent(_ entries: [RomEntry], prefs: LibraryPreferencesData, limit: Int = 10) -> [RomEntry] {
        let played = entries.compactMap { entry -> (entry: RomEntry, meta: GameMetadata)? in
            let meta = prefs.metadata(entry)
            guard !meta.hidden, meta.lastPlayed != nil else { return nil }
            return (entry, meta)
        }
        let sorted = played.sorted { a, b in
            let da = a.meta.lastPlayed ?? .distantPast
            let db = b.meta.lastPlayed ?? .distantPast
            if da != db { return da > db }
            let aIsLast = a.meta.lastPlayedPath == a.entry.id
            let bIsLast = b.meta.lastPlayedPath == b.entry.id
            if aIsLast != bIsLast { return aIsLast }
            return a.entry.id.localizedStandardCompare(b.entry.id) == .orderedAscending
        }
        var seen = Set<String>()
        var result: [RomEntry] = []
        for item in sorted where result.count < limit {
            let key = prefs.fingerprint(of: item.entry).map { "fp:\($0)" } ?? "path:\(item.entry.id)"
            guard seen.insert(key).inserted else { continue }
            result.append(item.entry)
        }
        return result
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

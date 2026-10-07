import Foundation
import PocketGBCore

/// Un hito de progreso del usuario (casilla).
struct Milestone: Codable, Equatable, Identifiable, Sendable {
    var id: String
    var title: String
    var done = false
    var doneAt: Date?
}

/// Plantillas de hitos (N6): «Pokémon: 8 medallas + Liga» y «Libre» (sin hitos).
enum MilestoneTemplate: String, Codable, Sendable, CaseIterable {
    case free, pokemon

    var title: String {
        switch self {
        case .free: "Libre"
        case .pokemon: "Pokémon: 8 medallas + Liga"
        }
    }

    /// Las 8 medallas de la 1.ª generación (o de Johto) más la Liga. El lector propone marcar las medallas en orden.
    static let pokemonBadges = (1...8).map { "Medalla \($0)" }
    var titles: [String] {
        switch self {
        case .free: []
        case .pokemon: Self.pokemonBadges + ["Liga Pokémon"]
        }
    }
}

/// N6 · progreso de un juego, por dispositivo (ND12): tiempo de juego (solo con el juego corriendo), sesiones, primera
/// y última vez, hitos opcionales y si se muestra el porcentaje (desactivado por defecto). Guarda también la cabecera
/// del ROM (0x150 bytes) para que el lector Pokémon lea la partida desde el detalle sin abrir el ROM.
struct GameProgress: Codable, Equatable, Sendable {
    var version = 1
    var playTime: TimeInterval = 0
    var sessions = 0
    var firstPlayed: Date?
    var lastPlayed: Date?
    var template: MilestoneTemplate?
    var milestones: [Milestone] = []
    var showPercent = false
    var romHeader: Data?

    static let maxMilestones = 64
    static let maxTitle = 60

    /// Porcentaje de hitos hechos (0–100), o nil sin hitos.
    var percent: Int? {
        milestones.isEmpty ? nil : milestones.filter(\.done).count * 100 / milestones.count
    }

    /// El porcentaje solo si el usuario lo activó y hay hitos.
    var visiblePercent: Int? { showPercent ? percent : nil }

    init() {}

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        version = (try? c.decode(Int.self, forKey: .version)) ?? 1
        playTime = max(0, (try? c.decode(TimeInterval.self, forKey: .playTime)) ?? 0)
        sessions = max(0, (try? c.decode(Int.self, forKey: .sessions)) ?? 0)
        firstPlayed = try? c.decode(Date.self, forKey: .firstPlayed)
        lastPlayed = try? c.decode(Date.self, forKey: .lastPlayed)
        template = try? c.decode(MilestoneTemplate.self, forKey: .template)
        milestones = ((try? c.decode([Milestone].self, forKey: .milestones)) ?? []).prefix(Self.maxMilestones).map { $0 }
        showPercent = (try? c.decode(Bool.self, forKey: .showPercent)) ?? false
        romHeader = try? c.decode(Data.self, forKey: .romHeader)
    }
}

/// `Application Support/Progress/<huella>.json`, escrito con temporal + `fsync` + `rename`. Un archivo ilegible vale
/// como vacío y se aparta al escribir (nunca se pisa).
struct ProgressStore: Sendable {
    let directory: URL
    var now: @Sendable () -> Date = { Date() }

    static func defaultDirectory() throws -> URL {
        let base = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                               appropriateFor: nil, create: true)
        return base.appendingPathComponent("Progress", isDirectory: true)
    }

    func url(_ fingerprint: String) -> URL? {
        guard Self.isFingerprint(fingerprint) else { return nil }
        return directory.appendingPathComponent("\(fingerprint).json")
    }

    /// Huellas del núcleo (64 hex) y, en DEBUG, las de demostración (`demo-…`).
    static func isFingerprint(_ s: String) -> Bool {
        let hex = s.count == 64 && s.unicodeScalars.allSatisfy { ("0"..."9").contains($0) || ("a"..."f").contains($0) }
        #if DEBUG
        if s.hasPrefix("demo-") && !s.contains("/") && s.count < 64 { return true }
        #endif
        return hex
    }

    func load(_ fingerprint: String) -> GameProgress {
        guard let url = url(fingerprint) else { return GameProgress() }
        return read(url) ?? GameProgress()
    }

    /// Lee, transforma y escribe bajo el lock de esa huella. Devuelve el resultado.
    @discardableResult
    func update(_ fingerprint: String, _ transform: (inout GameProgress) -> Void) throws -> GameProgress {
        guard let url = url(fingerprint) else { throw CocoaError(.fileWriteInvalidFileName) }
        let lock = MomentLocks.lock(for: url)
        lock.lock(); defer { lock.unlock() }
        let fm = FileManager.default
        let current = read(url)
        if current == nil && fm.fileExists(atPath: url.path) {
            try AtomicFile.rename(url, directory.appendingPathComponent(
                "\(fingerprint).damaged-\(Int(now().timeIntervalSince1970)).json"))
        }
        var next = current ?? GameProgress()
        transform(&next)
        next.milestones = next.milestones.prefix(GameProgress.maxMilestones).map {
            var m = $0
            m.title = String(m.title.trimmingCharacters(in: .whitespacesAndNewlines).prefix(GameProgress.maxTitle))
            return m
        }
        if next != current {
            try fm.createDirectory(at: directory, withIntermediateDirectories: true)
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.sortedKeys]
            encoder.dateEncodingStrategy = .millisecondsSince1970
            let tmp = url.appendingPathExtension("tmp")
            try AtomicFile.writeSynced(try encoder.encode(next), to: tmp)
            try AtomicFile.rename(tmp, url)
            try AtomicFile.syncDirectory(directory)
        }
        return next
    }

    // MARK: Hitos

    /// Los hitos ya existentes se conservan; la plantilla añade los que falten por título.
    @discardableResult
    func apply(_ template: MilestoneTemplate, to fingerprint: String) throws -> GameProgress {
        try update(fingerprint) { p in
            let existing = Set(p.milestones.map { $0.title.lowercased() })
            p.template = template
            p.milestones += template.titles.filter { !existing.contains($0.lowercased()) }.map { Milestone(id: Self.newID(), title: $0) }
        }
    }

    @discardableResult
    func addMilestone(_ title: String, to fingerprint: String) throws -> GameProgress {
        try update(fingerprint) { p in
            let clean = title.trimmingCharacters(in: .whitespacesAndNewlines)
            if !clean.isEmpty { p.milestones.append(Milestone(id: Self.newID(), title: clean)) }
        }
    }

    @discardableResult
    func setDone(_ id: String, _ done: Bool, in fingerprint: String) throws -> GameProgress {
        let at = now()
        return try update(fingerprint) { p in
            for i in p.milestones.indices where p.milestones[i].id == id {
                p.milestones[i].done = done
                p.milestones[i].doneAt = done ? at : nil
            }
        }
    }

    @discardableResult
    func markDone(_ ids: [String], in fingerprint: String) throws -> GameProgress {
        let at = now()
        return try update(fingerprint) { p in
            for i in p.milestones.indices where ids.contains(p.milestones[i].id) && !p.milestones[i].done {
                p.milestones[i].done = true
                p.milestones[i].doneAt = at
            }
        }
    }

    @discardableResult
    func removeMilestone(_ id: String, from fingerprint: String) throws -> GameProgress {
        try update(fingerprint) { $0.milestones.removeAll { $0.id == id } }
    }

    func recordHeader(_ header: Data, for fingerprint: String) {
        guard load(fingerprint).romHeader != header else { return }
        _ = try? update(fingerprint) { $0.romHeader = header }
    }

    private func read(_ url: URL) -> GameProgress? {
        guard FileManager.default.fileExists(atPath: url.path) else { return GameProgress() }
        guard let data = try? Data(contentsOf: url), data.count <= 1 << 20 else { return nil }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .millisecondsSince1970
        return try? decoder.decode(GameProgress.self, from: data)
    }

    static func newID() -> String {
        String(UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(12))
    }
}

/// Medallas que el lector propone marcar: los primeros `badgesCount` hitos «Medalla n» aún sin marcar.
enum BadgeSuggestion {
    static func ids(_ progress: GameProgress, pokemon: PokemonProgress) -> [String] {
        guard progress.template == .pokemon else { return [] }
        let count = min(pokemon.badgesCount, 8)
        let wanted = Set(MilestoneTemplate.pokemonBadges.prefix(count).map { $0.lowercased() })
        return progress.milestones.filter { wanted.contains($0.title.lowercased()) && !$0.done }.map(\.id)
    }
}

/// Contabilidad del tiempo de juego (N6): cuenta solo mientras la sesión corre (`setRunning(true)`). Cada paso a «no
/// corriendo» (pausa, segundo plano, editor, salida) lo escribe, y `checkpoint` (cada 30 s) acota lo que se pierde con
/// un cierre forzado. La sesión se cuenta la primera vez que el juego corre.
final class PlayTimeTracker: @unchecked Sendable {
    private let store: ProgressStore
    private let fingerprint: String
    private let clock: @Sendable () -> TimeInterval
    private let lock = NSLock()
    private var runningSince: TimeInterval?
    private var counted = false

    static let checkpointSeconds: TimeInterval = 30

    init(store: ProgressStore, fingerprint: String,
         clock: @escaping @Sendable () -> TimeInterval = { ProcessInfo.processInfo.systemUptime }) {
        self.store = store
        self.fingerprint = fingerprint
        self.clock = clock
    }

    func setRunning(_ running: Bool) {
        lock.lock(); defer { lock.unlock() }
        if running {
            if runningSince == nil { runningSince = clock() }
            if !counted {
                counted = true
                let at = store.now()
                _ = try? store.update(fingerprint) { p in
                    p.sessions += 1
                    p.firstPlayed = p.firstPlayed ?? at
                    p.lastPlayed = at
                }
            }
        } else {
            flush(keepRunning: false)
        }
    }

    /// Escribe lo acumulado sin parar la cuenta.
    func checkpoint() {
        lock.lock(); defer { lock.unlock() }
        flush(keepRunning: true)
    }

    /// Acumulado aún sin escribir (pruebas y tiempo de un momento).
    var pending: TimeInterval {
        lock.lock(); defer { lock.unlock() }
        return runningSince.map { max(0, clock() - $0) } ?? 0
    }

    /// Tiempo total: lo escrito más lo pendiente.
    var total: TimeInterval { store.load(fingerprint).playTime + pending }

    private func flush(keepRunning: Bool) {
        guard let since = runningSince else { return }
        let t = clock()
        let elapsed = max(0, t - since)
        runningSince = keepRunning ? t : nil
        guard elapsed > 0 else { return }
        let at = store.now()
        _ = try? store.update(fingerprint) { p in
            p.playTime += elapsed
            p.lastPlayed = at
        }
    }
}

/// Lo que el lector Pokémon (`pgb_progress_read`, N6-C) saca de una partida.
struct PokemonProgress: Equatable, Sendable {
    enum Game: Sendable { case gen1, goldSilver, crystal }
    let game: Game
    let playerName: String
    let badgesMask: UInt16
    let badgesCount: Int
    let pokedexOwned: Int
    let pokedexSeen: Int
    let hours: Int
    let minutes: Int
    let seconds: Int
    let money: Int

    /// nil si el juego no es un Pokémon soportado o la partida no cuadra (checksum, rangos…).
    static func read(header: Data, sram: Data) -> PokemonProgress? {
        guard header.count >= Int(PGB_PROG_HEADER_MIN) else { return nil }
        var out = pgb_progress()
        let ok = header.withUnsafeBytes { h in
            sram.withUnsafeBytes { s in
                pgb_progress_read(h.bindMemory(to: UInt8.self).baseAddress, h.count,
                                  s.bindMemory(to: UInt8.self).baseAddress, s.count, &out)
            }
        }
        guard ok else { return nil }
        let game: Game
        switch out.game {
        case PGB_PROG_GEN1: game = .gen1
        case PGB_PROG_GEN2_GS: game = .goldSilver
        case PGB_PROG_GEN2_C: game = .crystal
        default: return nil
        }
        let name = withUnsafeBytes(of: out.player_name) { raw in
            String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self)
        }
        return PokemonProgress(game: game, playerName: name, badgesMask: out.badges_mask,
                               badgesCount: Int(out.badges_count), pokedexOwned: Int(out.pokedex_owned),
                               pokedexSeen: Int(out.pokedex_seen), hours: Int(out.play_hours),
                               minutes: Int(out.play_minutes), seconds: Int(out.play_seconds), money: Int(out.money))
    }

    /// ¿La cabecera es de un Pokémon soportado? (No mira la partida.)
    static func isSupported(header: Data) -> Bool {
        header.withUnsafeBytes { h in
            pgb_progress_identify(h.bindMemory(to: UInt8.self).baseAddress, h.count) != PGB_PROG_NONE
        }
    }

    var pokedexTotal: Int { game == .gen1 ? 151 : 251 }
    var badgesTotal: Int { game == .gen1 ? 8 : 16 }
}

/// «12 h 05 min», «45 min», «menos de 1 min».
enum PlayTimeFormat {
    static func text(_ t: TimeInterval) -> String {
        let minutes = Int(t / 60)
        if minutes < 1 { return "menos de 1 min" }
        let h = minutes / 60, m = minutes % 60
        return h > 0 ? "\(h) h \(String(format: "%02d", m)) min" : "\(m) min"
    }
}

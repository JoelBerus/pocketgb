import Foundation
import CryptoKit

/// N6 · momentos de un juego (§3.3, ND13) en `Application Support/Moments/<huella>/`, por dispositivo (ND12):
///
/// - **Momento** (`m-<id>.*`): estado del núcleo (`.state`), RAM del cartucho del instante (`.sav`, para recuperar
///   la partida aunque el estado ya no cargue), miniatura (`.png`) y, en `index.json`, nombre, etiquetas, colección,
///   nota, fecha, tiempo jugado y configuración (modelo y paleta; en GBA, tipo de partida, reloj y BIOS).
/// - **Anillo «Antes de cargar»** (`b-<id>.*`): las 3 últimas posiciones de antes de cargar un momento, **fuera** de
///   la rotación de 5 backups del `.sav`. La 4.ª empuja a la más antigua.
///
/// Regla dura 6: cada archivo se escribe con temporal + `fsync` + `rename` (`AtomicFile`) y el índice es el punto de
/// confirmación (se escribe el último al crear y el primero al borrar). Un archivo sin entrada en el índice es una
/// escritura que no llegó a confirmarse y `recoverOrphans` lo retira; un índice ilegible no borra nada: se aparta y se
/// reconstruye desde los archivos. Las ranuras 1–4 se migran sin pérdida con `migrateSlots`.
/// Misma disposición que `MomentStore.kt` de Android (paridad, no compatibilidad de archivos: son por dispositivo).
struct MomentStore: Sendable {
    enum Kind: String, Sendable, Codable {
        case moment = "m-"
        case beforeLoad = "b-"
    }

    /// Un momento o una entrada del anillo. Los textos ya vienen normalizados.
    struct Moment: Codable, Equatable, Identifiable, Sendable {
        var id: String
        var name: String
        var tags: [String] = []
        var collection: String?
        var note: String = ""
        var created: Date
        /// Tiempo de juego acumulado al crearlo, o nil si no se sabía.
        var playTime: TimeInterval?
        /// Configuración con la que se creó (claves de `MomentConfig`).
        var config: [String: String] = [:]
        var hasState = true
        var hasSRAM = false
        var hasThumbnail = false
        /// Migrado de una ranura antigua (`slot1`…`slot4`) y SHA-256 de su estado.
        var origin: String?
        var originSHA: String?
    }

    /// Lo que se guarda de un instante: estado, RAM del cartucho y miniatura (falta como mucho uno de los dos primeros).
    struct Capture: Sendable {
        let state: Data?
        let sram: Data?
        let thumbnail: Data?
    }

    struct Snapshot: Equatable, Sendable {
        var moments: [Moment] = []
        var beforeLoad: [Moment] = []

        func find(_ kind: Kind, _ id: String) -> Moment? {
            (kind == .moment ? moments : beforeLoad).first { $0.id == id }
        }
    }

    private struct Index: Codable, Equatable {
        var version = 1
        var moments: [Moment] = []
        var beforeLoad: [Moment] = []
    }

    enum StoreError: Error, LocalizedError, Equatable {
        case damagedIndex
        case missing
        case noSRAM
        case nothingToSave

        var errorDescription: String? {
            switch self {
            case .damagedIndex: "El índice de momentos está dañado."
            case .missing: "Ese momento ya no existe."
            case .noSRAM: "Este momento no guardó la partida del cartucho."
            case .nothingToSave: "No hay nada que guardar."
            }
        }
    }

    static let ringSize = 3
    static let maxName = 80
    static let maxNote = 2_000
    static let maxTags = 12
    static let maxTag = 40
    static let maxCollection = 40
    static let maxStateBytes = 4 << 20
    static let maxSRAMBytes = 1 << 20
    private static let maxIndexBytes = 4 << 20

    let directory: URL
    var now: @Sendable () -> Date = { Date() }
    var newID: @Sendable () -> String = {
        String(UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(16))
    }
    /// Solo pruebas: se llama en cada punto de escritura (`"files"`, `"index"`, `"evict"`) y puede lanzar para simular
    /// que el proceso muere ahí (kill-test).
    var crashPoint: (@Sendable (String) throws -> Void)?

    init(directory: URL) { self.directory = directory }

    init(root: URL, fingerprint: String) {
        directory = root.appendingPathComponent(fingerprint, isDirectory: true)
    }

    static func defaultRoot() throws -> URL {
        let base = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                               appropriateFor: nil, create: true)
        return base.appendingPathComponent("Moments", isDirectory: true)
    }

    private var indexURL: URL { directory.appendingPathComponent("index.json") }
    private var lock: NSLock { MomentLocks.lock(for: directory) }

    func stateURL(_ kind: Kind, _ id: String) -> URL { file(kind, id, "state") }
    func sramURL(_ kind: Kind, _ id: String) -> URL { file(kind, id, "sav") }
    func thumbnailURL(_ kind: Kind, _ id: String) -> URL { file(kind, id, "png") }

    private func file(_ kind: Kind, _ id: String, _ ext: String) -> URL {
        precondition(Self.isValidID(id), "Id de momento no válido")
        return directory.appendingPathComponent("\(kind.rawValue)\(id).\(ext)")
    }

    static func isValidID(_ id: String) -> Bool {
        !id.isEmpty && id.count <= 32 && id.unicodeScalars.allSatisfy { ("0"..."9").contains($0) || ("a"..."z").contains($0) }
    }

    static func sha256(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    /// Etiquetas sin espacios sobrantes, sin repetir (sin distinguir mayúsculas), acotadas.
    static func normalizeTags(_ tags: [String]) -> [String] {
        var seen = Set<String>()
        return Array(tags.map { String($0.trimmingCharacters(in: .whitespacesAndNewlines).prefix(maxTag)) }
            .filter { !$0.isEmpty && seen.insert($0.lowercased()).inserted }
            .prefix(maxTags))
    }

    /// «a, b ,c» → ["a", "b", "c"].
    static func parseTags(_ text: String) -> [String] {
        normalizeTags(text.split(separator: ",").map(String.init))
    }

    // MARK: Lectura

    /// Momentos y anillo, del más reciente al más antiguo. Lanza si el índice no se puede interpretar (no toca nada).
    func snapshot() throws -> Snapshot {
        lock.lock(); defer { lock.unlock() }
        guard let index = try readIndex() else { throw StoreError.damagedIndex }
        return Snapshot(moments: index.moments.sorted { $0.created > $1.created },
                        beforeLoad: index.beforeLoad.reversed())   // orden de inserción, la última primero
    }

    func loadState(_ kind: Kind, _ id: String) throws -> Data {
        try Self.read(stateURL(kind, id), limit: Self.maxStateBytes)
    }

    func loadSRAM(_ kind: Kind, _ id: String) throws -> Data {
        let url = sramURL(kind, id)
        guard FileManager.default.fileExists(atPath: url.path) else { throw StoreError.noSRAM }
        return try Self.read(url, limit: Self.maxSRAMBytes)
    }

    func thumbnail(_ kind: Kind, _ id: String) -> Data? {
        try? Self.read(thumbnailURL(kind, id), limit: 1 << 20)
    }

    // MARK: Escritura

    /// Crea un momento. Los archivos se confirman antes que el índice.
    @discardableResult
    func create(_ capture: Capture, name: String, config: [String: String] = [:], playTime: TimeInterval? = nil,
                tags: [String] = [], collection: String? = nil, note: String = "", created: Date? = nil) throws -> Moment {
        guard capture.state != nil || capture.sram != nil else { throw StoreError.nothingToSave }
        lock.lock(); defer { lock.unlock() }
        var index = try indexForWriting()
        let id = freshID(index)
        try writeFiles(.moment, id, capture)
        let moment = Self.normalized(Moment(id: id, name: name, tags: tags, collection: collection, note: note,
                                            created: created ?? now(), playTime: playTime, config: config,
                                            hasState: capture.state != nil, hasSRAM: capture.sram != nil,
                                            hasThumbnail: capture.thumbnail != nil))
        index.moments.append(moment)
        try writeIndex(index)
        return moment
    }

    /// Añade al anillo la posición actual y retira la más antigua si pasa de 3 (sus archivos se borran después de
    /// confirmar el índice). `label` = qué se iba a cargar, para el nombre en la lista.
    @discardableResult
    func pushBeforeLoad(_ capture: Capture, label: String, config: [String: String] = [:],
                        playTime: TimeInterval? = nil) throws -> Moment {
        guard capture.state != nil || capture.sram != nil else { throw StoreError.nothingToSave }
        lock.lock(); defer { lock.unlock() }
        var index = try indexForWriting()
        let id = freshID(index)
        try writeFiles(.beforeLoad, id, capture)
        let entry = Self.normalized(Moment(id: id, name: label, created: now(), playTime: playTime, config: config,
                                           hasState: capture.state != nil, hasSRAM: capture.sram != nil,
                                           hasThumbnail: capture.thumbnail != nil))
        // Por orden de inserción (no por fecha: el reloj puede retroceder): la recién escrita nunca se expulsa.
        let ring = index.beforeLoad + [entry]
        index.beforeLoad = Array(ring.suffix(Self.ringSize))
        try writeIndex(index)
        try crashPoint?("evict")
        for old in ring.dropLast(Self.ringSize) { deleteFiles(.beforeLoad, old.id) }
        return entry
    }

    /// Renombra o edita etiquetas, colección y nota.
    @discardableResult
    func update(_ id: String, name: String, tags: [String], collection: String?, note: String) throws -> Moment {
        lock.lock(); defer { lock.unlock() }
        var index = try indexForWriting()
        guard let i = index.moments.firstIndex(where: { $0.id == id }) else { throw StoreError.missing }
        var updated = index.moments[i]
        updated.name = name
        updated.tags = tags
        updated.collection = collection
        updated.note = note
        updated = Self.normalized(updated)
        index.moments[i] = updated
        try writeIndex(index)
        return updated
    }

    /// Borra un momento o una entrada del anillo: primero sale del índice (confirmado), después sus archivos.
    func delete(_ kind: Kind, _ id: String) throws {
        lock.lock(); defer { lock.unlock() }
        var index = try indexForWriting()
        let before = index
        switch kind {
        case .moment: index.moments.removeAll { $0.id == id }
        case .beforeLoad: index.beforeLoad.removeAll { $0.id == id }
        }
        if index != before { try writeIndex(index) }
        deleteFiles(kind, id)
    }

    /// Al abrir: borra temporales y archivos que nunca llegaron al índice. Con el índice ilegible no se borra nada:
    /// se aparta (`index.damaged-<fecha>.json`) y se reconstruye desde los archivos que haya.
    func recoverOrphans() throws {
        lock.lock(); defer { lock.unlock() }
        let fm = FileManager.default
        guard fm.fileExists(atPath: directory.path) else { return }
        for name in try fm.contentsOfDirectory(atPath: directory.path) where name.hasSuffix(".tmp") {
            try? fm.removeItem(at: directory.appendingPathComponent(name))
        }
        let index = try indexForWriting()
        let known = Set(index.moments.map { Kind.moment.rawValue + $0.id } + index.beforeLoad.map { Kind.beforeLoad.rawValue + $0.id })
        for name in try fm.contentsOfDirectory(atPath: directory.path) {
            guard let stem = Self.stem(of: name), !known.contains(stem) else { continue }
            try? fm.removeItem(at: directory.appendingPathComponent(name))
        }
    }

    /// Migra las ranuras 1–4 de `states` a momentos sin pérdida: se copia el estado (y su captura), se confirma el
    /// índice con su origen y su SHA-256 y solo entonces se borra la ranura. Si el proceso muere entre medias, la
    /// siguiente migración reconoce el SHA y solo borra la ranura. Devuelve cuántas migró. El automático no se toca.
    @discardableResult
    func migrateSlots(from states: StateStore) throws -> Int {
        lock.lock(); defer { lock.unlock() }
        let fm = FileManager.default
        var migrated = 0
        for slot in StateSlot.manual {
            let url = states.stateURL(slot)
            guard fm.fileExists(atPath: url.path) else { continue }
            let state = try Self.read(url, limit: Self.maxStateBytes)
            let sha = Self.sha256(state)
            var index = try indexForWriting()
            // Deduplicado por SHA del origen y, tras reconstruir un índice dañado (sin origen), por el propio estado.
            let known = index.moments.contains { m in
                m.originSHA == sha || (m.originSHA == nil && m.hasState
                    && (try? Self.read(stateURL(.moment, m.id), limit: Self.maxStateBytes)).map(Self.sha256) == sha)
            }
            if !known {
                let thumb = try? Self.read(states.thumbnailURL(slot), limit: 1 << 20)
                let id = freshID(index)
                try writeFiles(.moment, id, Capture(state: state, sram: nil, thumbnail: thumb))
                let date = SaveStore.modificationDate(url) ?? now()
                index.moments.append(Self.normalized(Moment(id: id, name: slot.title, created: date, hasState: true,
                                                            hasSRAM: false, hasThumbnail: thumb != nil,
                                                            origin: slot.fileStem, originSHA: sha)))
                try writeIndex(index)
            }
            try crashPoint?("migrate")
            try states.delete(slot)
            migrated += 1
        }
        if migrated > 0 { try? AtomicFile.syncDirectory(states.directory) }
        return migrated
    }

    // MARK: Interno

    private static func normalized(_ m: Moment) -> Moment {
        var m = m
        let name = String(m.name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(maxName))
        m.name = name.isEmpty ? "—" : name
        m.tags = normalizeTags(m.tags)
        let collection = m.collection.map { String($0.trimmingCharacters(in: .whitespacesAndNewlines).prefix(maxCollection)) }
        m.collection = collection?.isEmpty == false ? collection : nil
        m.note = String(m.note.trimmingCharacters(in: .whitespacesAndNewlines).prefix(maxNote))
        return m
    }

    private func freshID(_ index: Index) -> String {
        let used = Set((index.moments + index.beforeLoad).map(\.id))
        while true {
            let id = newID()
            precondition(Self.isValidID(id))
            let exists = [Kind.moment, .beforeLoad].contains {
                FileManager.default.fileExists(atPath: stateURL($0, id).path)
                    || FileManager.default.fileExists(atPath: sramURL($0, id).path)
            }
            if !used.contains(id) && !exists { return id }
        }
    }

    private static func stem(of name: String) -> String? {
        guard name.hasPrefix(Kind.moment.rawValue) || name.hasPrefix(Kind.beforeLoad.rawValue),
              let dot = name.lastIndex(of: ".") else { return nil }
        switch name[dot...] {
        case ".state", ".sav", ".png": return String(name[..<dot])
        default: return nil
        }
    }

    private func writeFiles(_ kind: Kind, _ id: String, _ capture: Capture) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        if let state = capture.state { try Self.writeAtomic(state, to: stateURL(kind, id)) }
        if let sram = capture.sram { try Self.writeAtomic(sram, to: sramURL(kind, id)) }
        if let thumb = capture.thumbnail { try? Self.writeAtomic(thumb, to: thumbnailURL(kind, id)) }
        try AtomicFile.syncDirectory(directory)
        try crashPoint?("files")
    }

    private func deleteFiles(_ kind: Kind, _ id: String) {
        for url in [stateURL(kind, id), sramURL(kind, id), thumbnailURL(kind, id)] {
            try? FileManager.default.removeItem(at: url)
        }
    }

    private static func writeAtomic(_ data: Data, to url: URL) throws {
        let tmp = url.appendingPathExtension("tmp")
        try AtomicFile.writeSynced(data, to: tmp)
        try AtomicFile.rename(tmp, url)
    }

    private func writeIndex(_ index: Index) throws {
        try crashPoint?("index")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        encoder.dateEncodingStrategy = .millisecondsSince1970
        try Self.writeAtomic(try encoder.encode(index), to: indexURL)
        try AtomicFile.syncDirectory(directory)
    }

    /// nil si el índice existe pero no se puede interpretar; vacío si no existe. Lanza si no se puede leer.
    private func readIndex() throws -> Index? {
        guard FileManager.default.fileExists(atPath: indexURL.path) else { return Index() }
        let data = try Self.read(indexURL, limit: Self.maxIndexBytes)
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .millisecondsSince1970
        guard var index = try? decoder.decode(Index.self, from: data) else { return nil }
        // Ids que no son nuestros (editados a mano) no se usan como rutas.
        index.moments.removeAll { !Self.isValidID($0.id) }
        index.beforeLoad.removeAll { !Self.isValidID($0.id) }
        return index
    }

    /// El índice para modificarlo; si está dañado, lo aparta y lo reconstruye desde los archivos (sin borrar ninguno).
    private func indexForWriting() throws -> Index {
        if let index = try readIndex() { return index }
        let aside = directory.appendingPathComponent(
            "index.damaged-\(Int(now().timeIntervalSince1970))-\(newID().prefix(6)).json")
        try AtomicFile.rename(indexURL, aside)
        let names = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
        let stems = Set(names.compactMap(Self.stem(of:))).sorted()
        func rebuilt(_ kind: Kind) -> [Moment] {
            stems.filter { $0.hasPrefix(kind.rawValue) }
                .map { String($0.dropFirst(kind.rawValue.count)) }
                .filter(Self.isValidID)
                .map { id in
                    let fm = FileManager.default
                    let date = SaveStore.modificationDate(stateURL(kind, id)) ?? SaveStore.modificationDate(sramURL(kind, id)) ?? now()
                    return Moment(id: id, name: "Recuperado \(id)", created: date,
                                  hasState: fm.fileExists(atPath: stateURL(kind, id).path),
                                  hasSRAM: fm.fileExists(atPath: sramURL(kind, id).path),
                                  hasThumbnail: fm.fileExists(atPath: thumbnailURL(kind, id).path))
                }
                .filter { $0.hasState || $0.hasSRAM }
        }
        // Anillo: las 3 más nuevas (por fecha de archivo, es lo único que queda); las demás no se borran, pasan a
        // momentos «Recuperado». Origen/SHA de migración se recalcula para las ranuras: ver `migrateSlots`.
        let ring = rebuilt(.beforeLoad).sorted { $0.created < $1.created }
        var extra: [Moment] = []
        for old in ring.dropLast(Self.ringSize) {
            let id = freshID(Index(moments: rebuilt(.moment) + extra, beforeLoad: ring))
            for ext in ["state", "sav", "png"] {
                let from = file(.beforeLoad, old.id, ext)
                if FileManager.default.fileExists(atPath: from.path) { try AtomicFile.rename(from, file(.moment, id, ext)) }
            }
            var m = old
            m.id = id
            extra.append(m)
        }
        let index = Index(moments: rebuilt(.moment) + extra, beforeLoad: Array(ring.suffix(Self.ringSize)))
        try writeIndex(index)
        return index
    }

    private static func read(_ url: URL, limit: Int) throws -> Data {
        let size = (try FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int) ?? 0
        guard size <= limit else { throw CocoaError(.fileReadTooLarge) }
        return try Data(contentsOf: url)
    }
}

/// Un lock por carpeta de momentos: dos `MomentStore` del mismo juego (pausa y detalle) no escriben el índice a la vez.
enum MomentLocks {
    private static let registry = NSLock()
    nonisolated(unsafe) private static var locks: [String: NSLock] = [:]

    static func lock(for directory: URL) -> NSLock {
        registry.lock(); defer { registry.unlock() }
        let key = directory.standardizedFileURL.path
        if let lock = locks[key] { return lock }
        let lock = NSLock()
        locks[key] = lock
        return lock
    }
}

/// Claves de la configuración de un momento y su texto para el aviso al cargar.
enum MomentConfig {
    static func make(_ options: EmulationOptions, console: Console) -> [String: String] {
        switch console {
        case .gameBoy:
            return ["console": "gb", "model": options.colorForGameBoy ? "cgb" : "dmg",
                    "palette": String(options.compatPalette)]
        case .gameBoyAdvance:
            return ["console": "gba", "gbaSaveType": String(options.gbaSaveType), "gbaRtc": String(options.gbaRTC),
                    "gbaBios": options.gbaUseBIOS ? "1" : "0"]
        }
    }

    /// Las claves que difieren (vacío si coinciden o si el momento no guardó configuración, p. ej. una ranura migrada).
    static func differences(_ saved: [String: String], _ current: [String: String]) -> [String] {
        guard !saved.isEmpty else { return [] }
        return saved.keys.sorted().filter { current[$0] != nil && saved[$0] != current[$0] }
    }

    static func describe(_ key: String) -> String {
        switch key {
        case "model": "el color de Game Boy"
        case "palette": "la paleta"
        case "gbaSaveType": "el tipo de partida"
        case "gbaRtc": "el reloj"
        case "gbaBios": "la BIOS"
        default: key
        }
    }
}

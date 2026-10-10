import Foundation

/// Ranura de save state (SPEC §12): cuatro manuales y una automática al salir.
enum StateSlot: Hashable, Sendable, CaseIterable, Identifiable {
    case auto, manual1, manual2, manual3, manual4

    var id: String { fileStem }
    var fileStem: String {
        switch self {
        case .auto: "auto"
        case .manual1: "slot1"
        case .manual2: "slot2"
        case .manual3: "slot3"
        case .manual4: "slot4"
        }
    }
    var title: String {
        switch self {
        case .auto: "Automático"
        case .manual1: "Ranura 1"
        case .manual2: "Ranura 2"
        case .manual3: "Ranura 3"
        case .manual4: "Ranura 4"
        }
    }
    static let manual: [StateSlot] = [.manual1, .manual2, .manual3, .manual4]
}

/// Save states de un juego en `Application Support/States/<huella>/`: `<ranura>.state`
/// escrito de forma atómica (tmp + fsync + rename) y su captura `<ranura>.png`.
/// Los estados son independientes de la partida (SRAM): nunca se presentan como lo mismo.
struct StateStore: Sendable {
    struct Entry: Equatable, Sendable {
        let slot: StateSlot
        let date: Date
        let thumbnail: Data?
        /// La firma no es la de un estado de PocketGB: se muestra como dañado.
        let corrupt: Bool
    }

    let directory: URL

    init(directory: URL) {
        self.directory = directory
    }

    init(root: URL, fingerprint: String) {
        directory = root.appendingPathComponent(fingerprint, isDirectory: true)
    }

    static func defaultRoot() throws -> URL {
        let base = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                               appropriateFor: nil, create: true)
        return base.appendingPathComponent("States", isDirectory: true)
    }

    func stateURL(_ slot: StateSlot) -> URL { directory.appendingPathComponent("\(slot.fileStem).state") }
    func thumbnailURL(_ slot: StateSlot) -> URL { directory.appendingPathComponent("\(slot.fileStem).png") }

    /// Firmas de los estados de los dos núcleos (`core/src/state.c`, `gba/src/gba_state.c`).
    static let signatures: Set<Data> = [Data("PGBS".utf8), Data("PGBA".utf8)]

    /// Ranuras ocupadas.
    func entries() -> [StateSlot: Entry] {
        var result: [StateSlot: Entry] = [:]
        for slot in StateSlot.allCases {
            if let entry = entry(slot, withThumbnail: true) { result[slot] = entry }
        }
        return result
    }

    /// Una ranura: fecha y cabecera de 4 bytes; la miniatura PNG solo si se pide.
    private func entry(_ slot: StateSlot, withThumbnail: Bool) -> Entry? {
        let url = stateURL(slot)
        guard let attrs = try? FileManager.default.attributesOfItem(atPath: url.path) else { return nil }
        let date = attrs[.modificationDate] as? Date ?? .distantPast
        let head = (try? FileHandle(forReadingFrom: url)).flatMap { h in
            defer { try? h.close() }
            return try? h.read(upToCount: 4)
        }
        // Firma del núcleo: "PGBS" (Game Boy) o "PGBA" (Game Boy Advance). Antes de N3 solo se
        // aceptaba la de Game Boy y los estados de GBA salían como «Dañado» (y sin «Continuar»).
        return Entry(slot: slot, date: date,
                     thumbnail: withThumbnail ? try? Data(contentsOf: thumbnailURL(slot)) : nil,
                     corrupt: !Self.signatures.contains(head ?? Data()))
    }

    /// Estado automático utilizable para “Continuar”. Una SRAM guardada después del
    /// estado lo invalida: restaurar un backup nunca debe quedar revertido al reanudar.
    /// Solo lee metadatos y la cabecera, nunca la miniatura (la biblioteca lo consulta
    /// por cada juego).
    func automaticEntry(newerThan saveDate: Date?) -> Entry? {
        guard let entry = entry(.auto, withThumbnail: false), !entry.corrupt else { return nil }
        if let saveDate, entry.date < saveDate { return nil }
        return entry
    }

    /// Escribe el estado (atómico) y después su captura. Si la captura falla, el estado vale igual.
    func save(_ state: Data, thumbnail: Data?, to slot: StateSlot) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try Self.replace(stateURL(slot), with: state)
        if let thumbnail {
            try? Self.replace(thumbnailURL(slot), with: thumbnail)
        } else {
            try? FileManager.default.removeItem(at: thumbnailURL(slot))
        }
    }

    func load(_ slot: StateSlot) throws -> Data {
        try Data(contentsOf: stateURL(slot))
    }

    func delete(_ slot: StateSlot) throws {
        let fm = FileManager.default
        if fm.fileExists(atPath: stateURL(slot).path) { try fm.removeItem(at: stateURL(slot)) }
        try? fm.removeItem(at: thumbnailURL(slot))
    }

    // MARK: AUTO apartado (auditoría N-final, H1/H10)

    static let setAsidePrefix = "auto.obsolete-"

    /// Aparta el estado automático en `auto.obsolete-<unix>-<id>.state` (mismo directorio, rename atómico + fsync) en
    /// vez de borrarlo: «Continuar» deja de ofrecerlo, pero el archivo se conserva (como Android
    /// `StateStore.setAsideAuto`). Ninguna ranura lo lee. Sin AUTO no hace nada y devuelve `nil`.
    @discardableResult
    func setAsideAuto(now: Date = Date()) throws -> URL? {
        let fm = FileManager.default
        guard fm.fileExists(atPath: stateURL(.auto).path) else { return nil }
        var target: URL
        repeat {
            target = directory.appendingPathComponent(
                "\(Self.setAsidePrefix)\(Int(now.timeIntervalSince1970))-\(UUID().uuidString.prefix(8)).state")
        } while fm.fileExists(atPath: target.path)
        try AtomicFile.rename(stateURL(.auto), target)
        try AtomicFile.syncDirectory(directory)
        try? fm.removeItem(at: thumbnailURL(.auto))
        return target
    }

    /// Estados automáticos apartados (`setAsideAuto`), sin orden.
    func setAsideAutos() -> [URL] {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
        return names.filter { $0.hasPrefix(Self.setAsidePrefix) && $0.hasSuffix(".state") }
            .map { directory.appendingPathComponent($0) }
    }

    /// Aparta el AUTO solo si ya no es vigente por fecha: es anterior a la partida (`saveDate`), así que «Continuar» no
    /// lo ofrece y la próxima salida del juego lo pisaría. Uno dañado o vigente se queda en su ranura.
    @discardableResult
    func setAsideAutoIfStale(saveDate: Date?) throws -> URL? {
        guard let saveDate, let entry = entry(.auto, withThumbnail: false), !entry.corrupt,
              entry.date < saveDate else { return nil }
        return try setAsideAuto()
    }

    /// Devuelve a su ranura un AUTO apartado por una operación que después falló (si la ranura sigue libre).
    func unsetAside(_ url: URL) {
        guard !FileManager.default.fileExists(atPath: stateURL(.auto).path) else { return }
        try? AtomicFile.rename(url, stateURL(.auto))
        try? AtomicFile.syncDirectory(directory)
    }

    /// tmp completo + fsync → rename → fsync del directorio (primitivas de `AtomicFile`).
    private static func replace(_ url: URL, with data: Data) throws {
        let tmp = url.appendingPathExtension("tmp")
        try AtomicFile.writeSynced(data, to: tmp)
        try AtomicFile.rename(tmp, url)
        try AtomicFile.syncDirectory(url.deletingLastPathComponent())
    }
}

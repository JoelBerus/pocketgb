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

    /// Ranuras ocupadas.
    func entries() -> [StateSlot: Entry] {
        var result: [StateSlot: Entry] = [:]
        for slot in StateSlot.allCases {
            let url = stateURL(slot)
            guard let attrs = try? FileManager.default.attributesOfItem(atPath: url.path) else { continue }
            let date = attrs[.modificationDate] as? Date ?? .distantPast
            let head = (try? FileHandle(forReadingFrom: url)).flatMap { h in
                defer { try? h.close() }
                return try? h.read(upToCount: 4)
            }
            result[slot] = Entry(slot: slot, date: date, thumbnail: try? Data(contentsOf: thumbnailURL(slot)),
                                 corrupt: head != Data("PGBS".utf8))
        }
        return result
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

    /// tmp completo + fsync → rename → fsync del directorio (primitivas de `AtomicFile`).
    private static func replace(_ url: URL, with data: Data) throws {
        let tmp = url.appendingPathExtension("tmp")
        try AtomicFile.writeSynced(data, to: tmp)
        try AtomicFile.rename(tmp, url)
        try AtomicFile.syncDirectory(url.deletingLastPathComponent())
    }
}

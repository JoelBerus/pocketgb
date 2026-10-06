import Foundation

/// Qué juego corresponde a cada huella de `Saves/` (`Saves/index.json`), para que
/// Ajustes › Partidas muestre títulos y no hashes. Solo metadatos: si se pierde,
/// las partidas siguen intactas y se listan por huella.
struct SavesIndex: Sendable {
    struct Record: Codable, Equatable, Sendable {
        var title: String
        var fileName: String
        /// Game Boy Advance: medio de guardado y reloj que detectó el núcleo al abrir el juego sin
        /// ajustes forzados (para mostrar "Detectado (…)" en los ajustes del juego).
        var gbaMedia: String?
        var gbaHasRTC: Bool?
    }

    let directory: URL
    private var url: URL { directory.appendingPathComponent("index.json") }

    func load() -> [String: Record] {
        guard let data = try? Data(contentsOf: url),
              let records = try? JSONDecoder().decode([String: Record].self, from: data) else { return [:] }
        return records
    }

    /// - Parameter detected: medio y reloj detectados (solo Game Boy Advance sin ajustes forzados);
    ///   `nil` conserva lo que ya hubiera.
    func record(fingerprint: String, title: String, fileName: String, detected: (media: String, hasRTC: Bool)? = nil) {
        var records = load()
        var new = Record(title: title, fileName: fileName,
                         gbaMedia: records[fingerprint]?.gbaMedia, gbaHasRTC: records[fingerprint]?.gbaHasRTC)
        if let detected { new.gbaMedia = detected.media; new.gbaHasRTC = detected.hasRTC }
        guard records[fingerprint] != new else { return }
        records[fingerprint] = new
        if let data = try? JSONEncoder().encode(records) {
            try? data.write(to: url, options: .atomic)
        }
    }

    /// Huellas con partida local (`<huella>.sav`), con su título si se conoce.
    func savedGames() -> [(fingerprint: String, record: Record?)] {
        let records = load()
        let files = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
        return files.filter { $0.hasSuffix(".sav") }
            .map { String($0.dropLast(4)) }
            .map { ($0, records[$0]) }
            .sorted { ($0.1?.title ?? $0.0) < ($1.1?.title ?? $1.0) }
    }
}

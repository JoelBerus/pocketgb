import Foundation
import os

/// Copia de la partida junto al ROM (`<rom>.sav`, en la carpeta de iCloud Drive o
/// de Archivos). Se lee y escribe con `NSFileCoordinator`. La copia local es la
/// autoritativa: un fallo del espejo nunca invalida el guardado (docs/04 §Saves).
struct SaveMirror: Sendable {
    let url: URL

    init(romURL: URL) {
        url = romURL.deletingPathExtension().appendingPathExtension("sav")
    }

    init(url: URL) {
        self.url = url
    }

    var exists: Bool { FileManager.default.fileExists(atPath: url.path) }
    var modificationDate: Date? { SaveStore.modificationDate(url) }

    func read() throws -> Data? {
        guard exists else { return nil }
        var result: Result<Data, Error> = .failure(CocoaError(.fileReadUnknown))
        var coordError: NSError?
        NSFileCoordinator(filePresenter: nil).coordinate(readingItemAt: url, options: [], error: &coordError) { u in
            result = Result { try Data(contentsOf: u) }
        }
        if let coordError { throw coordError }
        return try result.get()
    }

    func write(_ data: Data) throws {
        var result: Result<Void, Error> = .success(())
        var coordError: NSError?
        NSFileCoordinator(filePresenter: nil).coordinate(writingItemAt: url, options: .forReplacing,
                                                         error: &coordError) { u in
            result = Result { try data.write(to: u, options: .atomic) }
        }
        if let coordError { throw coordError }
        try result.get()
    }
}

/// Qué partida cargar al abrir un ROM (docs/04 §Saves, "Carga al abrir un ROM").
/// Es una decisión pura, sin E/S, para poder probar cada caso.
enum SaveResolution: Equatable {
    /// No hay partida: el juego empieza de cero.
    case none
    /// Cargar `data`.
    /// - `backupOther`: el espejo, cuando pierde frente a la local; se guarda como backup.
    /// - `installLocal`: la partida viene del espejo y se instala en local con
    ///   `AtomicFile`, que deja la local anterior en `.1` (así también queda respaldada).
    /// - `updateMirror`: el espejo falta o está desfasado y se reescribe con `data`.
    /// - `mirrorIgnored`: el espejo tiene un tamaño que el núcleo no acepta; no se
    ///   toca en toda la sesión y se avisa a Joel.
    case load(data: Data, backupOther: Data?, installLocal: Bool, updateMirror: Bool, mirrorIgnored: Bool)
    /// La única partida que hay tiene un tamaño incorrecto: no se carga ni se toca,
    /// y la sesión no guarda (para no sobrescribirla).
    case wrongSize(fromMirror: Bool)

    struct Candidate: Equatable {
        let data: Data
        let date: Date?
    }

    /// - Parameters:
    ///   - local: `Saves/<huella>.sav`.
    ///   - mirror: `<rom>.sav` junto al ROM (nil si no hay o no hay carpeta).
    ///   - isValidSize: tamaños que acepta `gb_sram_load`.
    static func resolve(local: Candidate?, mirror: Candidate?, isValidSize: (Int) -> Bool) -> SaveResolution {
        let localOK = local.map { isValidSize($0.data.count) } ?? false
        let mirrorOK = mirror.map { isValidSize($0.data.count) } ?? false
        switch (local, mirror) {
        case (nil, nil):
            return .none
        case let (local?, nil):
            guard localOK else { return .wrongSize(fromMirror: false) }
            return .load(data: local.data, backupOther: nil, installLocal: false,
                         updateMirror: true, mirrorIgnored: false)
        case let (nil, mirror?):
            guard mirrorOK else { return .wrongSize(fromMirror: true) }
            return .load(data: mirror.data, backupOther: nil, installLocal: true,
                         updateMirror: false, mirrorIgnored: false)
        case let (local?, mirror?):
            if !mirrorOK {
                // El espejo no se entiende: se usa la local (si vale) y el espejo no se toca.
                guard localOK else { return .wrongSize(fromMirror: false) }
                return .load(data: local.data, backupOther: nil, installLocal: false,
                             updateMirror: false, mirrorIgnored: true)
            }
            guard localOK else {
                // Local ilegible (no debería pasar): gana el espejo; la local queda en `.1`.
                return .load(data: mirror.data, backupOther: nil, installLocal: true,
                             updateMirror: false, mirrorIgnored: false)
            }
            if local.data == mirror.data {
                return .load(data: local.data, backupOther: nil, installLocal: false,
                             updateMirror: false, mirrorIgnored: false)
            }
            // Difieren: gana la más reciente; ante la duda (sin fecha o empate), la local.
            let mirrorNewer = (mirror.date ?? .distantPast) > (local.date ?? .distantPast)
            return mirrorNewer
                ? .load(data: mirror.data, backupOther: nil, installLocal: true,
                        updateMirror: false, mirrorIgnored: false)
                : .load(data: local.data, backupOther: mirror.data, installLocal: false,
                        updateMirror: true, mirrorIgnored: false)
        }
    }
}

/// Destino de los guardados de una sesión: la copia local (obligatoria) y el espejo
/// (opcional). Solo se usa desde la cola serie de guardado de `EmulatorSession`.
final class SaveTarget: @unchecked Sendable {
    let local: SaveStore
    let mirror: SaveMirror?
    /// El espejo falló o está desfasado: se reintenta en el próximo guardado.
    private(set) var mirrorPending = false
    private let log = Logger(subsystem: "com.joelbermudez.pocketgb", category: "saves")

    init(local: SaveStore, mirror: SaveMirror?, mirrorPending: Bool = false) {
        self.local = local
        self.mirror = mirror
        self.mirrorPending = mirrorPending && mirror != nil
    }

    /// Guarda en local (si falla, lanza: la partida no está a salvo) y después en el
    /// espejo (si falla, se registra y queda pendiente; no lanza).
    func persist(_ data: Data) throws {
        try local.save(data)
        mirrorPending = true
        syncMirror(data)
    }

    /// Reintenta el espejo con el contenido dado si quedó pendiente.
    func retryMirrorIfNeeded(_ data: Data) {
        if mirrorPending { syncMirror(data) }
    }

    private func syncMirror(_ data: Data) {
        guard let mirror else {
            mirrorPending = false
            return
        }
        do {
            try mirror.write(data)
            mirrorPending = false
        } catch {
            log.error("Espejo de la partida sin escribir (se reintentará): \(error.localizedDescription, privacy: .public)")
        }
    }
}

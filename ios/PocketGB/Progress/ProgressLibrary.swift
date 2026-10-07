import Foundation
import Observation

/// N6 · progreso de todos los juegos en memoria (tarjetas, detalle y centro de ajustes), leído una vez del disco y
/// escrito por `ProgressStore`. El tiempo de juego lo escribe `PlayTimeTracker`; al cerrar el juego se relee.
@MainActor @Observable
final class ProgressLibrary {
    static let headerBytes = 0x150

    @ObservationIgnored let store: ProgressStore?
    private(set) var games: [String: GameProgress] = [:]

    init(store: ProgressStore? = ProgressLibrary.defaultStore()) {
        self.store = store
        reload()
    }

    static func defaultStore() -> ProgressStore? {
        #if DEBUG
        if DebugScreenRouter.overridesLibrary {
            // Capturas: carpeta temporal por arranque (sin tocar el progreso real).
            let dir = FileManager.default.temporaryDirectory.appendingPathComponent("demo-progress-\(UUID().uuidString)")
            return ProgressStore(directory: dir)
        }
        #endif
        return (try? ProgressStore.defaultDirectory()).map { ProgressStore(directory: $0) }
    }

    func progress(_ fingerprint: String?) -> GameProgress {
        fingerprint.flatMap { games[$0] } ?? GameProgress()
    }

    /// Relee todos los archivos (son pequeños: uno por juego jugado).
    func reload() {
        guard let store else { return }
        let names = (try? FileManager.default.contentsOfDirectory(atPath: store.directory.path)) ?? []
        var all: [String: GameProgress] = [:]
        for name in names where name.hasSuffix(".json") && !name.contains(".damaged-") {
            let fingerprint = String(name.dropLast(5))
            if ProgressStore.isFingerprint(fingerprint) { all[fingerprint] = store.load(fingerprint) }
        }
        games = all
    }

    func recordHeader(_ header: Data, for fingerprint: String) {
        store?.recordHeader(header, for: fingerprint)
        if let store { games[fingerprint] = store.load(fingerprint) }
    }

    /// Aplica un cambio y actualiza la caché. Un fallo de escritura deja lo que había.
    func change(_ fingerprint: String, _ body: (ProgressStore) throws -> GameProgress) {
        guard let store, let next = try? body(store) else { return }
        games[fingerprint] = next
    }

    #if DEBUG
    /// Capturas: progreso de demostración escrito en la carpeta temporal.
    func seed(_ fingerprint: String, _ progress: GameProgress) {
        change(fingerprint) { store in try store.update(fingerprint) { $0 = progress } }
    }
    #endif
}

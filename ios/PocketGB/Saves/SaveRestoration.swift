import Foundation

/// Cambiar la partida (`.sav`) de un juego **sin sesión** (Ajustes › Partidas, detalle › Momentos), con dos garantías
/// de la regla dura 6 (auditoría N-final, H10 y H11):
///
/// 1. La huella va en exclusiva (`FingerprintOwnership.withExclusive`): con el juego abierto o aún guardando su espejo
///    se rechaza sin tocar nada, así nunca hay dos escritores de la misma partida.
/// 2. El estado automático deja de ser vigente en cuanto la partida cambia: se **aparta** (`auto.obsolete-*`) antes de
///    escribir, porque la próxima salida del juego (`closeGame`) lo pisaría sin copia. Si la escritura falla, vuelve a
///    su ranura. Si la partida nueva es idéntica a la actual, el AUTO sigue siendo vigente y no se toca.
enum SaveRestoration {
    /// Instala `data` con `write` (que hace la escritura real con sus backups). Llamar con la huella ya en exclusiva.
    static func install(_ data: Data, saves: SaveStore, states: StateStore?, write: () throws -> Void) throws {
        let changes = try saves.load() != data
        let aside = changes ? try states?.setAsideAuto() : nil
        do {
            try write()
        } catch {
            if let aside { states?.unsetAside(aside) }
            throw error
        }
    }

    /// Restaura el backup `n` (la actual pasa a ser el backup `.1`).
    static func restore(backup n: Int, saves: SaveStore, states: StateStore?, ownership: FingerprintOwnership) throws {
        try ownership.withExclusive(saves.fingerprint, owner: "ajustes") {
            let data = try Data(contentsOf: saves.backupURL(n))
            try install(data, saves: saves, states: states) { try saves.restore(backup: n) }
        }
    }

    /// Restaura una copia apartada (la actual pasa a ser el backup `.1`; la apartada se conserva).
    static func restore(kept copy: SaveStore.KeptCopy, saves: SaveStore, states: StateStore?,
                        ownership: FingerprintOwnership) throws {
        try ownership.withExclusive(saves.fingerprint, owner: "ajustes") {
            let data = try Data(contentsOf: copy.url)
            try install(data, saves: saves, states: states) { try saves.restore(kept: copy) }
        }
    }
}

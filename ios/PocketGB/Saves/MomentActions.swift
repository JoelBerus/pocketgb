import Foundation

/// N6 · lo que se hace con los momentos de un juego, separado de la interfaz para poder probarlo con una sesión real.
///
/// Regla dura 6 en cada camino:
/// - **Cargar** (desde la pausa): primero la posición actual (estado + RAM del cartucho + miniatura) entra en el anillo
///   «Antes de cargar» y solo después el núcleo carga el momento; la sesión guarda en el acto su partida por la ruta
///   normal (`AtomicFile`: la anterior queda como backup `.1`). El estado automático no se toca (AUTO unificado).
/// - **Instalar la partida** de un momento o del anillo (desde el detalle, sin sesión): con la huella en exclusiva, la
///   partida actual entra antes en el anillo y después se escribe con `SaveStore.save` (backup `.1` de la anterior).
@MainActor
struct MomentActions {
    let moments: MomentStore
    let saves: SaveStore

    /// Captura de la sesión en pausa: estado, RAM del cartucho (si guarda) y miniatura.
    static func capture(_ session: EmulatorSession) throws -> MomentStore.Capture {
        let saved = try session.saveState()
        return MomentStore.Capture(state: saved.state, sram: try session.cartridgeRAM(),
                                   thumbnail: AppState.thumbnail(saved.pixels))
    }

    @discardableResult
    func create(from session: EmulatorSession, name: String, config: [String: String],
                playTime: TimeInterval?) throws -> MomentStore.Moment {
        try moments.create(Self.capture(session), name: name, config: config, playTime: playTime)
    }

    /// Carga un momento (o una entrada del anillo) en la sesión en pausa. Los bytes se leen **antes** de añadir la
    /// posición actual al anillo: recuperar la entrada más antigua con el anillo lleno no la pierde al desplazarla.
    func load(_ kind: MomentStore.Kind, _ moment: MomentStore.Moment, into session: EmulatorSession,
              config: [String: String], playTime: TimeInterval?) throws {
        guard moment.hasState else { throw MomentStore.StoreError.missing }
        let data = try moments.loadState(kind, moment.id)
        let label = kind == .moment ? "Antes de cargar «\(moment.name)»" : "Antes de recuperar"
        let saved = try moments.appendBeforeLoad(Self.capture(session), label: label, config: config, playTime: playTime)
        try session.loadState(data)
        // Solo con la carga confirmada se recorta el anillo; la entrada recuperada no se expulsa (H1).
        try? moments.trimRing(keeping: [saved.id, moment.id])
    }

    /// Instala en el `.sav` la RAM del cartucho guardada en un momento o en el anillo, sin sesión (detalle). Se rechaza
    /// si la huella tiene dueño (juego abierto o aún guardando) o si el tamaño no coincide con el de la partida actual.
    func installSRAM(_ kind: MomentStore.Kind, _ moment: MomentStore.Moment, ownership: FingerprintOwnership) throws {
        try ownership.withExclusive(saves.fingerprint, owner: "detalle") {
            let data = try moments.loadSRAM(kind, moment.id)
            let current = try saves.load()
            if let current, current.count != data.count { throw InstallError.sizeMismatch }
            var keep: Set<String> = [moment.id]
            if let current {
                let label = kind == .moment ? "Antes de recuperar «\(moment.name)»" : "Antes de recuperar"
                keep.insert(try moments.appendBeforeLoad(.init(state: nil, sram: current, thumbnail: nil), label: label).id)
            }
            try saves.save(data)
            try? moments.trimRing(keeping: keep)
        }
    }

    enum InstallError: Error, LocalizedError, Equatable {
        case sizeMismatch
        var errorDescription: String? {
            "La partida del momento no tiene el tamaño de la de ahora (¿cambió el tipo de partida en los ajustes del juego?). No se ha cambiado nada."
        }
    }
}

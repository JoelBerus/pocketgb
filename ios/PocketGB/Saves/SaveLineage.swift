import CryptoKit
import Foundation

/// N7a · linaje de la partida (N-README §3.4). Decide, **sin fechas** siempre que hay historial, qué relación hay entre
/// la partida de este iPhone y otra (el `.sav` junto al ROM, o la de un paquete `.pgbm` que llega de otro equipo).
///
/// El historial es el de las escrituras propias del espejo (`<huella>.mirror-history.json`, acotado a los últimos 8
/// sha confirmados y 8 pendientes), más, para los paquetes, los sha de las copias de seguridad y apartadas. Las fechas
/// solo deciden la primera vez (sin historial), porque el reloj de otro equipo puede ir desfasado.
enum SaveLineage {
    static func sha256(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    /// Escrituras propias del espejo, de la más reciente a la más antigua.
    struct History: Equatable, Sendable {
        var written: [String] = []
        var pending: [String] = []

        var isEmpty: Bool { written.isEmpty && pending.isEmpty }
        /// La última partida que PocketGB mandó al espejo (en curso o confirmada).
        var lastOwnWrite: String? { pending.first ?? written.first }
        func contains(_ hash: String) -> Bool { written.contains(hash) || pending.contains(hash) }
    }

    /// Relación del espejo con la partida local (las dos existen y tienen un tamaño válido).
    enum MirrorRelation: Equatable, Sendable {
        /// Espejo = local: nada que hacer.
        case same
        /// El espejo es una escritura nuestra anterior: gana la local y se reescribe el espejo.
        case ownEarlier
        /// Espejo desconocido y la local no cambió desde nuestra última escritura: cambio externo, se instala.
        case external
        /// Espejo desconocido y la local también cambió: divergencia, se pregunta.
        case divergent
        /// Sin historial (primera vez): regla por fecha, con copia de la que pierde.
        case noHistory
    }

    static func relation(local: String, mirror: String, history: History) -> MirrorRelation {
        if local == mirror { return .same }
        if history.contains(mirror) { return .ownEarlier }
        if history.isEmpty { return .noHistory }
        if local == history.lastOwnWrite { return .external }
        return .divergent
    }

    /// Relación de un paquete (`sav_sha256` y `base_sav_sha256` de su `META`) con la partida local.
    enum PackageRelation: Equatable, Sendable {
        /// No hay partida local: se instala.
        case noLocal
        /// El paquete trae exactamente la partida que ya hay.
        case same
        /// El paquete parte de la partida local: es un avance, se instala (con copia).
        case advance
        /// El paquete trae una partida que este iPhone ya tuvo y que después avanzó: es más vieja. Se pregunta.
        case older
        /// Las dos avanzaron por separado, o el paquete parte de una partida que aquí aún no ha llegado (latencia del
        /// proveedor) o de una desconocida (`baseKnown == false`). Se pregunta; la otra queda como momento «Conflicto».
        case divergent(baseKnown: Bool)
    }

    /// - Parameter known: sha que este iPhone ha tenido (historial del espejo, copias de seguridad y apartadas).
    static func relation(packageSave: String, packageBase: String?, local: String?, known: Set<String>) -> PackageRelation {
        guard let local else { return .noLocal }
        let save = packageSave.lowercased()
        let base = packageBase?.lowercased()
        if save == local { return .same }
        if base == local { return .advance }
        if known.contains(save) { return .older }
        return .divergent(baseKnown: base.map(known.contains) ?? false)
    }
}

/// Qué hacer ante una divergencia, una vez que Joel eligió.
enum DivergenceChoice: Equatable, Sendable {
    /// Seguir con la de este iPhone; la otra queda como momento «Conflicto …» y apartada.
    case keepLocal
    /// Usar la otra; la de este iPhone queda como momento «Conflicto …», apartada y como copia `.1`.
    case useOther
}

/// Copias en conflicto que crean los proveedores junto al `.sav` (N-README §3.4): iCloud (`Juego 2.sav`), Drive y
/// Windows (`Juego (1).sav`), Syncthing (`Juego.sync-conflict-20261008-101010-ABCDEFG.sav`) y Dropbox
/// (`Juego (copia en conflicto de …).sav`, `Juego (… conflicted copy …).sav`). Se listan en Ajustes › Partidas como
/// candidatas; PocketGB **nunca** las borra ni las renombra.
enum ConflictCopies {
    /// ¿Es `name` una copia en conflicto de `<base>.sav`? Sin distinguir mayúsculas (iCloud y APFS no las distinguen).
    static func isConflictCopy(_ name: String, of base: String) -> Bool {
        let lower = name.lowercased()
        let stem = base.lowercased()
        guard lower.hasSuffix(".sav"), lower.hasPrefix(stem), lower != "\(stem).sav" else { return false }
        let rest = String(lower.dropFirst(stem.count).dropLast(4))   // lo que va entre el nombre y «.sav»
        if rest.hasPrefix(".sync-conflict-") { return true }
        if rest.hasPrefix(" "), let n = Int(rest.dropFirst()), n >= 2 { return true }            // «Juego 2»
        if rest.hasPrefix(" ("), rest.hasSuffix(")") {
            let inner = rest.dropFirst(2).dropLast()
            if let n = Int(inner), n >= 1 { return true }                                         // «Juego (1)»
            if inner.contains("conflicted copy") || inner.contains("copia en conflicto") { return true }
        }
        return false
    }

    /// Copias en conflicto del `.sav` de un ROM, en su carpeta. Solo lista: no lee ni toca nada.
    static func scan(romURL: URL) -> [URL] {
        let folder = romURL.deletingLastPathComponent()
        let base = romURL.deletingPathExtension().lastPathComponent
        let names = (try? FileManager.default.contentsOfDirectory(atPath: folder.path)) ?? []
        let stems = Set(names.compactMap { name -> String? in
            let url = URL(fileURLWithPath: name)
            return ["gb", "gbc", "gba"].contains(url.pathExtension.lowercased())
                ? url.deletingPathExtension().lastPathComponent.lowercased() : nil
        })
        // «Juego 2.sav» junto a «Juego 2.gb» es la partida de otro juego, no una copia en conflicto.
        return names.filter { isConflictCopy($0, of: base) && !stems.contains(String($0.lowercased().dropLast(4))) }
            .sorted().map { folder.appendingPathComponent($0) }
    }
}

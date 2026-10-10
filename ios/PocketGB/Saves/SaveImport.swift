import Foundation

/// N7b · importar una partida (`.pgbm` o `.sav` crudo) en un juego. Dos pasos, para poder probarlos:
/// 1. `evaluate` (pura, sin E/S): valida el paquete con el parser C y `META v1`, comprueba la huella y el tamaño contra
///    el cartucho y calcula el linaje. **Cualquier rechazo ocurre aquí, antes de tocar nada.**
/// 2. `apply` (con la huella en exclusiva, `FingerprintOwnership`): respalda lo actual y escribe con `SaveStore.save`
///    (escritura atómica + anillo de backups; regla 6).
enum SaveImport {
    /// Lo que hace falta saber del juego de destino (sale de `RomInfo` al leer su ROM).
    struct Cartridge: Equatable, Sendable {
        /// Clave interna de iOS: 32 hex (16 primeros bytes del SHA-256).
        let fingerprint: String
        /// SHA-256 completo (64 hex) si se conoce; con él se compara la huella entera.
        let romSHA256: String?
        let console: Console
        let hasBattery: Bool
        let validSizes: Set<Int>

        init(info: RomInfo, validSizes: Set<Int>) {
            fingerprint = info.fingerprint
            romSHA256 = info.sha256.isEmpty ? nil : info.sha256.lowercased()
            console = info.console
            hasBattery = info.hasBattery
            self.validSizes = validSizes
        }

        init(fingerprint: String, romSHA256: String?, console: Console, hasBattery: Bool, validSizes: Set<Int>) {
            self.fingerprint = fingerprint
            self.romSHA256 = romSHA256
            self.console = console
            self.hasBattery = hasBattery
            self.validSizes = validSizes
        }
    }

    enum Rejection: Error, Equatable, LocalizedError {
        case container(PGBMPackage.ContainerError)
        case meta(PackageMeta.Invalid)
        case missingMeta
        case otherGame
        case wrongConsole
        case saveMismatch
        case stateWithoutLink
        case tooLarge
        case rawWrongSize

        var needsNewerApp: Bool {
            switch self {
            case .container(let e): e.needsNewerApp
            case .meta(let e): e == .newerFormat
            default: false
            }
        }

        var errorDescription: String? {
            switch self {
            case .container(let e): e.errorDescription
            case .meta(let e): e.errorDescription
            case .missingMeta: "El paquete no trae sus datos (META). No se ha cambiado nada."
            case .otherGame: "El paquete es de otro juego. No se ha cambiado nada."
            case .wrongConsole: "El paquete es de otra consola. No se ha cambiado nada."
            case .saveMismatch: "La partida del paquete no coincide con sus datos: puede estar dañado. No se ha cambiado nada."
            case .stateWithoutLink: "El paquete trae un estado sin indicar de qué partida es. No se ha cambiado nada."
            case .tooLarge: "El archivo es demasiado grande para ser una partida."
            case .rawWrongSize: "El archivo .sav no tiene el tamaño de la partida de este juego. No se ha cambiado nada."
            }
        }
    }

    /// Plan de importación ya validado.
    struct Plan: Equatable, Sendable {
        let fingerprint: String
        /// La partida que llega (nil = no se puede instalar: vacía o de tamaño distinto en un juego con batería).
        let save: Data?
        /// La partida que llegó tal cual, aunque no se instale (se aparta si no está vacía).
        let incoming: Data
        let relation: SaveLineage.PackageRelation
        /// Estado que corresponde exactamente a la partida del paquete: «Continuar donde lo dejaste en <equipo>».
        let continuation: Data?
        let thumbnail: Data?
        let meta: PackageMeta?
        /// `.sav` crudo (no `.pgbm`): siempre se confirma antes.
        let raw: Bool
        /// Juego sin batería con `SAVE` vacía: no hay partida que instalar, solo el estado.
        let batteryless: Bool

        var needsChoice: Bool {
            switch relation {
            case .older, .divergent: true
            // Un `.sav` crudo siempre se confirma, también sin partida previa (auditoría N7 iOS, H3).
            default: raw && save != nil && relation != .same
            }
        }
        var origin: String? { meta?.deviceName }
    }

    static let maxFileBytes = 4 * 1024 * 1024

    /// Valida un `.pgbm` o un `.sav` crudo para `cartridge`. `local` es la partida actual; `known`, los sha que este
    /// iPhone ha tenido (`SaveStore.knownHashes`).
    static func evaluate(_ data: Data, cartridge: Cartridge, local: Data?, known: Set<String>) throws -> Plan {
        guard data.count <= maxFileBytes else { throw Rejection.tooLarge }
        let localHash = local.map(SaveLineage.sha256)
        guard PGBMPackage.looksLikePackage(data) else {
            // `.sav` crudo: el tamaño exacto de la partida del cartucho, o nada.
            guard cartridge.hasBattery, cartridge.validSizes.contains(data.count) else { throw Rejection.rawWrongSize }
            let hash = SaveLineage.sha256(data)
            let relation = SaveLineage.relation(packageSave: hash, packageBase: nil, local: localHash, known: known)
            return Plan(fingerprint: cartridge.fingerprint, save: data, incoming: data, relation: relation,
                        continuation: nil, thumbnail: nil, meta: nil, raw: true, batteryless: false)
        }
        let package: PGBMPackage
        do { package = try PGBMPackage.parse(data) } catch let e as PGBMPackage.ContainerError {
            throw Rejection.container(e)
        }
        guard let json = package.meta else { throw Rejection.missingMeta }
        let meta: PackageMeta
        do { meta = try PackageMeta.parse(json) } catch let e as PackageMeta.Invalid { throw Rejection.meta(e) }
        let romf = package.romFingerprint.map { String(format: "%02x", $0) }.joined()
        guard meta.romSHA256 == romf else { throw Rejection.meta(.field("rom_sha256")) }
        guard romf.hasPrefix(cartridge.fingerprint.lowercased()),
              cartridge.romSHA256.map({ $0 == romf }) ?? true else { throw Rejection.otherGame }
        guard meta.coreName == (cartridge.console == .gameBoyAdvance ? "gba" : "gb") else { throw Rejection.wrongConsole }
        guard SaveLineage.sha256(package.save) == meta.savSHA256 else { throw Rejection.saveMismatch }
        if package.state != nil, meta.stateOfSavSHA256 == nil { throw Rejection.stateWithoutLink }

        // Tamaño: una partida vacía o de otro tamaño nunca sustituye la de un juego con batería (regla 6).
        let installable = cartridge.hasBattery ? cartridge.validSizes.contains(package.save.count) : package.save.isEmpty
        let relation = installable && cartridge.hasBattery
            ? SaveLineage.relation(packageSave: meta.savSHA256, packageBase: meta.baseSavSHA256, local: localHash, known: known)
            : .same
        let exact = package.state != nil && meta.stateOfSavSHA256 == meta.savSHA256 && installable
        return Plan(fingerprint: cartridge.fingerprint, save: installable && cartridge.hasBattery ? package.save : nil,
                    incoming: package.save, relation: relation, continuation: exact ? package.state : nil,
                    thumbnail: package.thumbnail, meta: meta, raw: false,
                    batteryless: !cartridge.hasBattery && installable)
    }

    /// Resultado de aplicar un plan.
    enum Outcome: Equatable, Sendable {
        /// Se instaló la partida (y, si venía, el estado para continuar).
        case installed(continuation: Bool)
        /// La partida ya era esta; como mucho se instaló el estado para continuar.
        case alreadyCurrent(continuation: Bool)
        /// Divergencia o paquete más viejo: hay que preguntar. No se ha tocado nada.
        case needsChoice
        /// La partida ya es la misma, pero el paquete trae otro estado automático que el de este iPhone: se pregunta
        /// antes de sustituirlo (auditoría N7 iOS, H1). No se ha tocado nada.
        case needsStateChoice
        /// Joel eligió seguir con la suya: la del paquete quedó apartada y como momento «Conflicto».
        case keptLocal
        /// La partida del paquete no vale para este cartucho (vacía o de otro tamaño): el `.sav` no se tocó y lo
        /// actual quedó además como copia de seguridad.
        case saveNotTouched
    }

    /// Datos de procedencia (para «Partida: <equipo> · hace 2 h» y «Continuar donde lo dejaste en <equipo>»).
    struct Origin: Codable, Equatable, Sendable {
        var platform: String
        var deviceName: String
        var createdMs: Int
        var savSHA256: String
        var importedAt: Date
        var continuation: Bool

        static func url(_ store: SaveStore) -> URL {
            store.directory.appendingPathComponent("\(store.fingerprint).origin.json")
        }

        static func load(_ store: SaveStore) -> Origin? {
            guard let data = try? Data(contentsOf: url(store)) else { return nil }
            return try? JSONDecoder().decode(Origin.self, from: data)
        }

        /// Al guardar un estado automático propio: la continuación de otro equipo ya no es la que hay.
        static func endContinuation(_ store: SaveStore) {
            guard var origin = load(store), origin.continuation else { return }
            origin.continuation = false
            try? origin.save(to: store)
        }

        func save(to store: SaveStore) throws {
            let url = Self.url(store)
            let tmp = url.appendingPathExtension("tmp")
            try AtomicFile.writeSynced(try JSONEncoder().encode(self), to: tmp)
            try AtomicFile.rename(tmp, url)
        }
    }

    /// Aplica `plan` con la huella en exclusiva. Antes de escribir nada, lo actual entra en «Antes de cargar» (con el
    /// estado automático, si lo hay) y en una copia apartada que no rota; después `SaveStore.save` deja además la
    /// anterior como backup `.1`.
    static func apply(_ plan: Plan, choice: DivergenceChoice? = nil, store: SaveStore, states: StateStore?,
                      moments: MomentStore?, ownership: FingerprintOwnership, now: Date = Date()) throws -> Outcome {
        try ownership.withExclusive(plan.fingerprint, owner: "importar") {
            let current = try store.load()
            // El estado automático propio (si lo hay). Nunca se sustituye sin guardarlo antes (auditoría N7 iOS, H1).
            let ownAuto = try? states?.load(.auto)
            var ownAutoKept = false
            var ringIDs: Set<String> = []
            let label = plan.origin.map { "Antes de importar de \($0)" } ?? "Antes de importar"

            /// Lo actual (partida y estado automático) entra en «Antes de cargar»; la partida, además, en una copia
            /// apartada que no rota.
            func keepCurrent() throws {
                if let current { try store.keepMirrorLoser(current, now: now) }
                guard current != nil || ownAuto != nil, let moments else { return }
                let entry = try moments.appendBeforeLoad(.init(state: ownAuto, sram: current, thumbnail: nil), label: label)
                ringIDs.insert(entry.id)
                ownAutoKept = ownAuto != nil
            }
            func installContinuation() throws -> Bool {
                guard let state = plan.continuation, let states else { return false }
                if let ownAuto, ownAuto != state, !ownAutoKept {
                    // Regla 6: el estado propio se guarda antes de sustituirlo; si no se puede, no se sustituye.
                    guard let moments else { return false }
                    ringIDs.insert(try moments.appendBeforeLoad(.init(state: ownAuto, sram: current, thumbnail: nil),
                                                                label: label).id)
                    ownAutoKept = true
                }
                // Después del `.sav`: el estado es más nuevo que la partida y «Continuar» lo ofrece.
                try states.save(state, thumbnail: plan.thumbnail, to: .auto)
                return true
            }
            func recordOrigin(_ continuation: Bool) {
                guard let meta = plan.meta else { return }
                try? Origin(platform: meta.platform, deviceName: meta.deviceName, createdMs: meta.createdMs,
                            savSHA256: meta.savSHA256, importedAt: now, continuation: continuation).save(to: store)
            }
            /// N1: el anillo vuelve a su tamaño solo con todo escrito, sin expulsar las entradas de esta importación.
            func trimRing() { if !ringIDs.isEmpty { try? moments?.trimRing(keeping: ringIDs) } }

            if plan.batteryless || plan.relation == .same, plan.save != nil || plan.batteryless {
                // La partida ya es esta: solo puede cambiar el estado automático. Si el propio es otro, se pregunta.
                if let state = plan.continuation, let ownAuto, ownAuto != state {
                    switch choice {
                    case nil:
                        return .needsStateChoice
                    case .keepLocal?:
                        _ = try? moments?.create(.init(state: state, sram: current, thumbnail: plan.thumbnail),
                                                 name: SaveOpening.conflictName(now))
                        return .keptLocal
                    case .useOther?:
                        break
                    }
                }
                let c = try installContinuation()
                recordOrigin(c)
                trimRing()
                return .alreadyCurrent(continuation: c)
            }
            guard let incoming = plan.save else {
                // Partida vacía o de otro tamaño: el `.sav` no se toca; lo actual queda además como copia.
                if let current { try store.addBackup(current) }
                if !plan.incoming.isEmpty { try store.keepMirrorLoser(plan.incoming, now: now) }
                return .saveNotTouched
            }
            if plan.needsChoice {
                switch choice {
                case nil:
                    return .needsChoice
                case .keepLocal?:
                    try store.keepMirrorLoser(incoming, now: now)
                    _ = try? moments?.create(.init(state: plan.continuation, sram: incoming, thumbnail: plan.thumbnail),
                                             name: SaveOpening.conflictName(now))
                    return .keptLocal
                case .useOther?:
                    if let current, !plan.raw {
                        _ = try? moments?.create(.init(state: ownAuto, sram: current, thumbnail: nil),
                                                 name: SaveOpening.conflictName(now))
                    }
                }
            }
            try keepCurrent()
            try store.save(incoming)
            let c = try installContinuation()
            recordOrigin(c)
            trimRing()
            return .installed(continuation: c)
        }
    }
}

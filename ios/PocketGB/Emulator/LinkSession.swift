import Foundation
import Observation

/// Cable link virtual (M9) visto desde la interfaz: dos juegos de Game Boy unidos, uno activo
/// (el que se ve, se oye y recibe los botones) y otro en segundo plano. Es una fachada en el
/// hilo principal y **no tiene hilo propio**: el motor es `EmulatorSession` con un `LinkedPair`
/// como núcleo y una `SRAMPersistence` por juego (docs/hitos/M9-ios-plan.md §1).
@MainActor @Observable
final class LinkSession {
    /// Lado del cable: el primero es el juego desde cuyo detalle se conectó.
    enum Side: Int, Sendable {
        case first = 0, second = 1

        var other: Side { self == .first ? .second : .first }
    }

    /// Un juego del cable, ya leído fuera del hilo principal.
    struct Game: Sendable {
        var fileName: String
        /// Título visible (alias o título de la biblioteca); `nil` usa el de la cabecera.
        var title: String?
        var rom: Data
        var console: Console = .gameBoy
        var mirror: SaveMirror?
        var mirrorSnapshot: SaveMirror.Snapshot = .absent
        var emulation = EmulationOptions(colorForGameBoy: false, compatPalette: 0)
    }

    /// Motivos por los que no se abre el cable. Con ellos no se toca ninguna partida (salvo lo
    /// que ya haría abrir el primer juego solo) y se explica por qué.
    enum Refusal: Error, Equatable {
        case notGameBoy(title: String)
        /// El mismo ROM con batería dos veces: compartirían partida, copias y canal.
        case sameGame(title: String)
        /// Un juego con batería se quedaría sin guardar: un intercambio guardado en un solo lado
        /// perdería un Pokémon.
        case cannotSave(title: String, warning: SaveLoadWarning)
        /// La partida está en iCloud sin descargar y no hay copia local.
        case saveNotDownloaded(title: String)

        var title: String {
            switch self {
            case .notGameBoy: "No se puede conectar"
            case .sameGame: "Elige otro juego"
            case .cannotSave(let title, _): "No se puede guardar “\(title)”"
            case .saveNotDownloaded: "Partida de iCloud sin descargar"
            }
        }

        var message: String {
            switch self {
            case .notGameBoy(let title):
                "“\(title)” es de Game Boy Advance. El cable link solo une juegos de Game Boy y Game Boy Color."
            case .sameGame(let title):
                "“\(title)” tiene batería y las dos copias compartirían la misma partida, así que una pisaría a la otra. Conéctalo con otro juego."
            case .cannotSave(let title, let warning):
                "\(warning.message) Con el cable link, un intercambio que se guardara solo en uno de los dos juegos perdería datos, así que “\(title)” no se conecta."
            case .saveNotDownloaded(let title):
                "La partida de “\(title)” está en iCloud y no se pudo descargar. Para no empezar de cero ni pisarla, el cable no se abre. Vuelve a intentarlo con conexión."
            }
        }
    }

    /// Avisos de apertura de los dos juegos, juntos en una sola alerta.
    struct Notice: Equatable {
        let title: String
        let message: String
    }

    let session: EmulatorSession
    /// Cabeceras de los dos juegos, por lado.
    let infos: [RomInfo]
    /// Títulos visibles, por lado.
    let titles: [String]
    /// Títulos para `SavesIndex` (M9-H2): el de la cabecera, o el nombre de archivo si está vacío, como el
    /// juego suelto; nunca el alias del usuario (`titles` es solo para la interfaz).
    var indexTitles: [String] {
        zip(infos, fileNames).map { $0.title.isEmpty ? $1 : $0.title }
    }
    private let fileNames: [String]
    /// Frames del juego que no está activo (la miniatura).
    let peerFrames: FrameBuffers
    private let selector: LinkSideSelector
    private(set) var activeSide: Side = .first
    /// Avisos al abrir (`nil` si no hubo ninguno).
    let notice: Notice?

    var activeInfo: RomInfo { infos[activeSide.rawValue] }
    var peerInfo: RomInfo { infos[activeSide.other.rawValue] }
    var activeTitle: String { titles[activeSide.rawValue] }
    var peerTitle: String { titles[activeSide.other.rawValue] }

    /// Secuencia de apertura (§1.2), en el hilo principal y antes de arrancar el hilo de emulación:
    /// núcleos → ROMs → mismo juego → partidas → cable. No arranca el motor (`session.start()`).
    /// - Throws: `Refusal`, `SaveOpening.Refusal` no (se traduce) o `CoreError`.
    init(games: [Game], savesDirectory: URL, onAudioInterrupted: @escaping @MainActor @Sendable () -> Void) throws {
        precondition(games.count == 2, "el cable une exactamente dos juegos")
        for game in games where game.console == .gameBoyAdvance {
            throw Refusal.notGameBoy(title: game.title ?? game.fileName)
        }
        let now = Int64(Date().timeIntervalSince1970)
        var cores: [CoreBridge] = []
        var infos: [RomInfo] = []
        var titles: [String] = []
        for game in games {
            let core = try CoreBridge()
            let info = try core.loadROM(game.rom, unixTime: now, sampleRate: 48_000,
                                        colorForGameBoy: game.emulation.colorForGameBoy,
                                        compatPalette: game.emulation.compatPalette)
            cores.append(core)
            infos.append(info)
            let title = game.title ?? info.title
            titles.append(title.isEmpty ? game.fileName : title)
        }
        // Mismo ROM con batería: antes de abrir ninguna partida (carpeta intacta).
        if infos[0].fingerprint == infos[1].fingerprint, infos[0].hasBattery {
            throw Refusal.sameGame(title: titles[0])
        }

        var persisters: [SRAMPersistence] = []
        var notices: [String] = []
        for (i, game) in games.enumerated() {
            let opened: (persister: SRAMPersistence?, warning: SaveLoadWarning?)
            do {
                opened = try SRAMPersistence.open(core: cores[i], info: infos[i], savesDirectory: savesDirectory,
                                                  mirror: game.mirror, snapshot: game.mirrorSnapshot,
                                                  mirrorWriter: nil,
                                                  // N7a · con el cable no se pregunta: sigue la de este iPhone y la
                                                  // otra queda como momento «Conflicto» y apartada.
                                                  lineage: .init(divergence: .keepLocal,
                                                                 conflictMoments: (try? MomentStore.defaultRoot()).map {
                                                                     MomentStore(root: $0, fingerprint: infos[i].fingerprint)
                                                                 }))
            } catch SaveOpening.Refusal.mirrorNotDownloaded {
                throw Refusal.saveNotDownloaded(title: titles[i])
            }
            if let persister = opened.persister { persisters.append(persister) }
            if infos[i].hasBattery, cores[i].sramSaveSize > 0, opened.persister == nil {
                // Sin destino de guardado: no se conecta (`.localWrongSize`, `.mirrorWrongSizeOnly`, `.unreadable`).
                throw Refusal.cannotSave(title: titles[i], warning: opened.warning ?? .unreadable("No se pudo abrir la partida."))
            }
            if let warning = opened.warning {
                notices.append("“\(titles[i])”: \(warning.message)")
            }
            if !infos[i].headerChecksumOK {
                notices.append("“\(titles[i])”: la cabecera del ROM no coincide con su checksum. Puede ser un volcado dañado.")
            }
        }

        let selector = LinkSideSelector()
        let peerFrames = FrameBuffers(size: Console.gameBoy.screen)
        let pair = try LinkedPair(cores: cores, selector: selector, peerFrames: peerFrames)
        session = EmulatorSession(core: pair, info: infos[0], persisters: persisters, loadWarning: nil,
                                  onAudioInterrupted: onAudioInterrupted)
        self.infos = infos
        self.titles = titles
        self.fileNames = games.map(\.fileName)
        self.selector = selector
        self.peerFrames = peerFrames
        notice = switch notices.count {
        case 0: nil
        case 1: Notice(title: "Aviso al conectar", message: notices[0])
        default: Notice(title: "Avisos al conectar", message: notices.joined(separator: "\n\n"))
        }
    }

    /// Cambia el juego activo: el siguiente frame ya pinta, suena y recibe botones del otro lado.
    func switchSide() {
        activeSide = activeSide.other
        selector.side = activeSide.rawValue
    }
}

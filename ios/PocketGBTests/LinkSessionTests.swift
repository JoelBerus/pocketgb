import Foundation
import Testing
@testable import PocketGB

/// M9 lote 3: `LinkSession` (secuencia de apertura, rechazos y guardado por la ruta normal).
@MainActor
struct LinkSessionTests {
    let dir: URL
    static let keys: [UInt8] = [0x5A, 0xA5]

    init() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    private static func game(_ rom: Data, name: String = "juego.gb", title: String? = nil,
                             console: Console = .gameBoy, mirror: SaveMirror? = nil,
                             snapshot: SaveMirror.Snapshot = .absent) -> LinkSession.Game {
        LinkSession.Game(fileName: name, title: title, rom: rom, console: console, mirror: mirror,
                         mirrorSnapshot: snapshot)
    }

    private func open(_ a: LinkSession.Game, _ b: LinkSession.Game) throws -> LinkSession {
        try LinkSession(games: [a, b], savesDirectory: dir, onAudioInterrupted: {})
    }

    private func exchangePair() throws -> LinkSession {
        try open(Self.game(LinkTestROMs.exchange(title: "MAESTRO", key: Self.keys[0], sc: 0x81)),
                 Self.game(LinkTestROMs.exchange(title: "ESCLAVO", key: Self.keys[1], sc: 0x80)))
    }

    private func store(_ link: LinkSession, _ side: Int) -> SaveStore {
        SaveStore(directory: dir, fingerprint: link.infos[side].fingerprint)
    }

    private static func fingerprint(_ rom: Data) throws -> String {
        let core = try CoreBridge()
        return try core.loadROM(rom, unixTime: 0).fingerprint
    }

    private func listing() throws -> [String] {
        try FileManager.default.contentsOfDirectory(atPath: dir.path).sorted()
    }

    /// Reanuda y pausa hasta que las dos partidas en disco tengan el intercambio hecho ($A100 == 1).
    private func playUntilBothSaved(_ link: LinkSession) throws {
        let stores = [store(link, 0), store(link, 1)]
        for _ in 0..<10 {
            link.session.resume()
            Thread.sleep(forTimeInterval: 0.3)
            link.session.pause()
            let saved = try stores.map { try $0.load() }
            if saved.allSatisfy({ $0.map { $0.count > 0x100 && $0[0x100] == 1 } ?? false }) { return }
        }
        Issue.record("El intercambio no llegó a las dos partidas en 3 s")
    }

    @Test func exchangeIsSavedInBothSavFilesThroughTheNormalPath() throws {
        let link = try exchangePair()
        link.session.start()
        link.session.pause()
        try playUntilBothSaved(link)
        for side in 0..<2 {
            let sav = try #require(try store(link, side).load())
            for i in 0..<16 { #expect(sav[i] == UInt8(i) ^ Self.keys[1 - side], "lado \(side) byte \(i)") }
            #expect(sav[0x100] == 1)
        }
        link.session.stop()
    }

    @Test func pauseSavesBothSRAMs() throws {
        let link = try exchangePair()
        link.session.start()
        link.session.pause()
        // Un rato de juego y la pausa (flush síncrono) dejan la partida en disco en los dos lados.
        link.session.resume()
        Thread.sleep(forTimeInterval: 0.5)
        link.session.pause()
        for side in 0..<2 { #expect(try store(link, side).load() != nil, "lado \(side) sin partida tras la pausa") }
        link.session.stop()
    }

    @Test func sameGameWithBatteryIsRefusedAndTouchesNothing() throws {
        let rom = LinkTestROMs.exchange(title: "IGUAL", key: 0x11, sc: 0x81)
        #expect(throws: LinkSession.Refusal.sameGame(title: "IGUAL")) {
            _ = try open(Self.game(rom), Self.game(rom))
        }
        #expect(try listing().isEmpty)
    }

    /// M9-H2: el índice de partidas lleva el título de cabecera (o el nombre de archivo), no el alias.
    @Test func indexTitlesIgnoreTheAliasAndFallBackToTheFileName() throws {
        let link = try open(Self.game(LinkTestROMs.exchange(title: "MAESTRO", key: 0x5A, sc: 0x81), title: "Mi alias"),
                            Self.game(LinkTestROMs.romOnly(title: ""), name: "sin-titulo.gb", title: "Otro alias"))
        #expect(link.titles == ["Mi alias", "Otro alias"])
        #expect(link.indexTitles == [link.infos[0].title, "sin-titulo.gb"])
        #expect(link.infos[0].title == "MAESTRO")
        link.session.start()
        link.session.pause()
        link.session.stop()
    }

    /// Observación del auditor: la comprobación de `.sameGame` va antes de `open`. Con un `.sav` local y un
    /// espejo más nuevo, abrir instalaría el espejo (con backup) y cambiaría la carpeta.
    @Test func sameGameIsRefusedBeforeOpeningEvenWithANewerMirror() throws {
        let rom = LinkTestROMs.exchange(title: "IGUAL", key: 0x11, sc: 0x81)
        let sav = SaveStore(directory: dir, fingerprint: try Self.fingerprint(rom))
        try sav.save(Data(repeating: 0x11, count: 8_192))
        let before = try listing()
        let bytes = try Data(contentsOf: sav.saveURL)
        let mirror = SaveMirror(romURL: dir.appendingPathComponent("igual.gb"))
        let newer = SaveMirror.Snapshot.read(Data(repeating: 0x22, count: 8_192), Date().addingTimeInterval(3_600))
        #expect(throws: LinkSession.Refusal.sameGame(title: "IGUAL")) {
            _ = try open(Self.game(rom, mirror: mirror, snapshot: newer), Self.game(rom))
        }
        #expect(try Data(contentsOf: sav.saveURL) == bytes)
        #expect(try listing() == before)
    }

    @Test func sameGameWithoutBatteryOpens() throws {
        let rom = LinkTestROMs.romOnly(title: "TETRIS")
        let link = try open(Self.game(rom), Self.game(rom))
        #expect(link.infos[0].fingerprint == link.infos[1].fingerprint)
        link.session.start()
        link.session.pause()
        link.session.stop()
        #expect(try listing().isEmpty)
    }

    @Test func wrongSizeSavRefusesWithoutChangingAByte() throws {
        let rom = LinkTestROMs.exchange(title: "MAL", key: 0x22, sc: 0x81)
        let other = LinkTestROMs.exchange(title: "BIEN", key: 0x33, sc: 0x80)
        let sav = SaveStore(directory: dir, fingerprint: try Self.fingerprint(rom))
        try sav.save(Data([1, 2, 3, 4, 5]))
        let before = try listing()
        let bytes = try Data(contentsOf: sav.saveURL)
        do {
            _ = try open(Self.game(rom), Self.game(other))
            Issue.record("debió rechazar el cable")
        } catch let refusal as LinkSession.Refusal {
            guard case .cannotSave(let title, let warning) = refusal else {
                Issue.record("rechazo inesperado: \(refusal)"); return
            }
            #expect(title == "MAL" && warning == .localWrongSize)
            #expect(refusal.message.contains("MAL"))
        }
        #expect(try Data(contentsOf: sav.saveURL) == bytes)
        #expect(try listing() == before)
    }

    @Test func unavailableMirrorWithoutLocalRefuses() throws {
        let rom = LinkTestROMs.exchange(title: "ICLOUD", key: 0x44, sc: 0x81)
        let mirror = SaveMirror(romURL: dir.appendingPathComponent("icloud.gb"))
        #expect(throws: LinkSession.Refusal.saveNotDownloaded(title: "ICLOUD")) {
            _ = try open(Self.game(rom, mirror: mirror, snapshot: .unavailable),
                         Self.game(LinkTestROMs.romOnly(title: "OTRO")))
        }
    }

    @Test func unavailableMirrorWithLocalOpensWithATitledNotice() throws {
        let rom = LinkTestROMs.exchange(title: "ICLOUD", key: 0x44, sc: 0x81)
        let sav = SaveStore(directory: dir, fingerprint: try Self.fingerprint(rom))
        try sav.save(Data(count: 8_192))
        let mirror = SaveMirror(romURL: dir.appendingPathComponent("icloud.gb"))
        let link = try open(Self.game(rom, title: "Mi juego", mirror: mirror, snapshot: .unavailable),
                            Self.game(LinkTestROMs.romOnly(title: "OTRO")))
        let notice = try #require(link.notice)
        #expect(notice.message.contains("Mi juego"))
        #expect(notice.message.contains(SaveLoadWarning.mirrorUnavailable.message))
    }

    @Test func advanceRomIsRefused() throws {
        #expect(throws: LinkSession.Refusal.notGameBoy(title: "Pokémon GBA")) {
            _ = try open(Self.game(Data(), name: "a.gba", title: "Pokémon GBA", console: .gameBoyAdvance),
                         Self.game(LinkTestROMs.romOnly(title: "OTRO")))
        }
        #expect(try listing().isEmpty)
    }

    @Test func saveStateThrowsLinkUnsupported() throws {
        let link = try open(Self.game(LinkTestROMs.romOnly(title: "A")),
                            Self.game(LinkTestROMs.romOnly(title: "B")))
        link.session.start()
        link.session.pause()
        #expect(throws: CoreError.linkUnsupported) { try link.session.saveState() }
        link.session.stop()
    }

    @Test func switchSideChangesTheActiveGame() throws {
        let link = try open(Self.game(LinkTestROMs.romOnly(title: "PRIMERO"), title: "Uno"),
                            Self.game(LinkTestROMs.romOnly(title: "SEGUNDO")))
        #expect(link.activeSide == .first && link.activeInfo.title == "PRIMERO")
        #expect(link.activeTitle == "Uno" && link.peerTitle == "SEGUNDO")
        link.switchSide()
        #expect(link.activeSide == .second && link.activeInfo.title == "SEGUNDO")
        #expect(link.activeTitle == "SEGUNDO" && link.peerTitle == "Uno")
        link.switchSide()
        #expect(link.activeSide == .first)
    }
}

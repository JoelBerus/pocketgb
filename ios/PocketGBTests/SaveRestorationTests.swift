import Foundation
import Testing
@testable import PocketGB

/// Auditoría N-final (regla dura 6): el estado automático nunca se borra sin copia al dejar de ser vigente (H1, H10) y
/// restaurar una partida desde Ajustes va con la huella en exclusiva (H11).
@MainActor
struct SaveRestorationTests {
    let dir: URL
    let saves: SaveStore
    let states: StateStore
    let auto = Data("PGBS".utf8) + Data(repeating: 0xA7, count: 64)
    let x = Data(repeating: 1, count: 32), y = Data(repeating: 2, count: 32)

    init() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("restore-\(UUID().uuidString)", isDirectory: true)
        let savesDir = dir.appendingPathComponent("Saves", isDirectory: true)
        try FileManager.default.createDirectory(at: savesDir, withIntermediateDirectories: true)
        saves = SaveStore(directory: savesDir, fingerprint: "fp")
        states = StateStore(root: dir.appendingPathComponent("States"), fingerprint: "fp")
    }

    struct Crash: Error {}

    private func setAside() throws -> [Data] { try states.setAsideAutos().map { try Data(contentsOf: $0) } }

    // MARK: H1

    /// H1: un AUTO cuya RAM no es la de la partida se aparta (deja de ofrecerse) y sigue en disco; uno de otra
    /// configuración se queda en su ranura.
    @Test func refusedContinuationSetsAsideOrKeepsTheAutoNeverDeletes() throws {
        try states.save(auto, thumbnail: Data([1]), to: .auto)
        #expect(AppState.retireAutomaticState(after: .otherConfiguration, in: states) == false)
        #expect(try states.load(.auto) == auto)
        #expect(try setAside().isEmpty)

        #expect(AppState.retireAutomaticState(after: .notCurrent, in: states) == true)
        #expect((try? states.load(.auto)) == nil)
        #expect(states.automaticEntry(newerThan: nil) == nil)
        #expect(try setAside() == [auto])
        #expect(!FileManager.default.fileExists(atPath: states.thumbnailURL(.auto).path))
        // Las ranuras no ven el apartado.
        #expect(states.entries().isEmpty)
    }

    // MARK: H10

    /// H10: restaurar un backup aparta el AUTO (la próxima salida del juego lo pisaría) y la partida anterior queda
    /// como backup `.1`.
    @Test func restoringABackupSetsAsideTheAuto() throws {
        try saves.save(x)
        try saves.save(y)   // x pasa a ser el backup 1
        try states.save(auto, thumbnail: nil, to: .auto)
        try SaveRestoration.restore(backup: 1, saves: saves, states: states, ownership: FingerprintOwnership())
        #expect(try saves.load() == x)
        #expect(try Data(contentsOf: saves.backupURL(1)) == y)
        #expect((try? states.load(.auto)) == nil)
        #expect(try setAside() == [auto])
    }

    @Test func restoringAKeptCopySetsAsideTheAuto() throws {
        try saves.save(x)
        try saves.keepMirrorLoser(y)
        try states.save(auto, thumbnail: nil, to: .auto)
        let copy = try #require(saves.keptCopies().first)
        try SaveRestoration.restore(kept: copy, saves: saves, states: states, ownership: FingerprintOwnership())
        #expect(try saves.load() == y)
        #expect(try setAside() == [auto])
    }

    /// Instalar la misma partida no cambia nada: el AUTO sigue siendo vigente y se queda.
    @Test func installingTheSameSaveKeepsTheAuto() throws {
        try saves.save(x)
        try states.save(auto, thumbnail: nil, to: .auto)
        try SaveRestoration.install(x, saves: saves, states: states) { try saves.save(x) }
        #expect(try states.load(.auto) == auto)
        #expect(try setAside().isEmpty)
    }

    /// Si la escritura falla, el AUTO vuelve a su ranura y la partida no cambia.
    @Test func failedInstallPutsTheAutoBack() throws {
        try saves.save(x)
        try states.save(auto, thumbnail: nil, to: .auto)
        #expect(throws: Crash.self) { try SaveRestoration.install(y, saves: saves, states: states) { throw Crash() } }
        #expect(try saves.load() == x)
        #expect(try states.load(.auto) == auto)
        #expect(try setAside().isEmpty)
    }

    /// H10 (momentos): instalar la partida de un momento desde el detalle aparta el AUTO antes de escribir.
    @Test func installingAMomentSaveSetsAsideTheAuto() throws {
        let moments = MomentStore(root: dir.appendingPathComponent("Moments"), fingerprint: "fp")
        let moment = try moments.create(.init(state: auto, sram: y, thumbnail: nil), name: "m")
        try saves.save(x)
        try states.save(auto + Data([9]), thumbnail: nil, to: .auto)
        try MomentActions(moments: moments, saves: saves, states: states)
            .installSRAM(.moment, moment, ownership: FingerprintOwnership())
        #expect(try saves.load() == y)
        #expect((try? states.load(.auto)) == nil)
        #expect(try setAside() == [auto + Data([9])])
    }

    /// H10 (cable link, restaurar fuera de la app…): al abrir el juego sin «Continuar», un AUTO anterior a la partida
    /// se aparta; uno vigente o dañado se queda.
    @Test func staleAutoIsSetAsideOnlyWhenOlderThanTheSave() throws {
        try states.save(auto, thumbnail: nil, to: .auto)
        let autoDate = try #require(states.automaticEntry(newerThan: nil)).date
        #expect(try states.setAsideAutoIfStale(saveDate: autoDate.addingTimeInterval(-60)) == nil)
        #expect(try states.setAsideAutoIfStale(saveDate: nil) == nil)
        #expect(try states.load(.auto) == auto)
        #expect(try states.setAsideAutoIfStale(saveDate: autoDate.addingTimeInterval(60)) != nil)
        #expect(try setAside() == [auto])

        try states.save(Data("XXXX".utf8), thumbnail: nil, to: .auto)   // dañado: no se mueve
        #expect(try states.setAsideAutoIfStale(saveDate: .distantFuture) == nil)
        #expect(try states.load(.auto) == Data("XXXX".utf8))
    }

    // MARK: H11

    /// H11: con el juego abierto (o aún guardando su espejo) restaurar se rechaza sin tocar la partida ni el AUTO.
    @Test func restoreIsRefusedWhileTheGameIsOwned() throws {
        try saves.save(x)
        try saves.save(y)
        try saves.keepMirrorLoser(x)
        try states.save(auto, thumbnail: nil, to: .auto)
        let ownership = FingerprintOwnership()
        let lease = ownership.takeForSession("fp")
        #expect(throws: FingerprintOwnership.Busy.self) {
            try SaveRestoration.restore(backup: 1, saves: saves, states: states, ownership: ownership)
        }
        let copy = try #require(saves.keptCopies().first)
        #expect(throws: FingerprintOwnership.Busy.self) {
            try SaveRestoration.restore(kept: copy, saves: saves, states: states, ownership: ownership)
        }
        #expect(try saves.load() == y)
        #expect(try states.load(.auto) == auto)
        lease.release()
        try SaveRestoration.restore(backup: 1, saves: saves, states: states, ownership: ownership)
        #expect(try saves.load() == x)
        #expect(!ownership.isOwned("fp"))
    }
}

import Foundation
import Testing
@testable import PocketGB

/// N6 · momentos (§3.3, ND13): almacén, anillo «Antes de cargar», migración de ranuras, carga con sesión real,
/// recuperación en un toque, estado que ya no carga, exclusión por huella y kill-tests (regla dura 6).
@MainActor
struct MomentsTests {
    let dir: URL
    let saves: URL
    let moments: URL

    init() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        saves = dir.appendingPathComponent("Saves", isDirectory: true)
        moments = dir.appendingPathComponent("Moments", isDirectory: true)
        try FileManager.default.createDirectory(at: saves, withIntermediateDirectories: true)
    }

    struct Crash: Error {}

    private func store(_ fp: String = "fp") -> MomentStore { MomentStore(root: moments, fingerprint: fp) }

    private func capture(_ byte: UInt8, sram: Bool = true) -> MomentStore.Capture {
        .init(state: Data("PGBS".utf8) + Data(repeating: byte, count: 64),
              sram: sram ? Data(repeating: byte, count: 32) : nil, thumbnail: Data([byte]))
    }

    // MARK: Almacén

    @Test func createEditDeleteAndNormalize() throws {
        let s = store()
        let m = try s.create(capture(1), name: "  Jefe  ", config: ["model": "dmg"], playTime: 90,
                             tags: ["a", " A ", "b", ""], collection: "  ", note: " hola ")
        #expect(m.name == "Jefe" && m.tags == ["a", "b"] && m.collection == nil && m.note == "hola")
        #expect(try s.loadSRAM(.moment, m.id) == Data(repeating: 1, count: 32))
        let u = try s.update(m.id, name: "Otro", tags: MomentStore.parseTags("x, y"), collection: "Experimentos", note: "")
        #expect(u.collection == "Experimentos" && u.tags == ["x", "y"])
        #expect(try s.snapshot().moments == [u])
        try s.delete(.moment, m.id)
        #expect(try s.snapshot().moments.isEmpty)
        #expect(!FileManager.default.fileExists(atPath: s.stateURL(.moment, m.id).path))
    }

    @Test func ringKeepsThreeAndEvictsTheOldest() throws {
        var s = store()
        var t = 1_000.0
        for i in 1...4 {
            t += 10
            let now = t
            s.now = { Date(timeIntervalSince1970: now) }
            try s.pushBeforeLoad(capture(UInt8(i)), label: "b\(i)")
        }
        let ring = try s.snapshot().beforeLoad
        #expect(ring.map(\.name) == ["b4", "b3", "b2"])
        let files = try FileManager.default.contentsOfDirectory(atPath: s.directory.path).filter { $0.hasPrefix("b-") }
        #expect(files.count == 3 * 3)   // estado, partida y miniatura de cada una
        // Los momentos no cuentan para el anillo (ni al revés).
        try s.create(capture(9), name: "m")
        #expect(try s.snapshot().beforeLoad.count == 3)
    }

    @Test func damagedIndexIsSetAsideAndRebuiltWithoutDeletingFiles() throws {
        let s = store()
        let m = try s.create(capture(1), name: "uno")
        try Data("{roto".utf8).write(to: s.directory.appendingPathComponent("index.json"))
        #expect(throws: MomentStore.StoreError.damagedIndex) { _ = try s.snapshot() }
        try s.recoverOrphans()   // reconstruye, no borra
        let rebuilt = try s.snapshot().moments
        #expect(rebuilt.map(\.id) == [m.id] && rebuilt[0].hasSRAM && rebuilt[0].hasState)
        let names = try FileManager.default.contentsOfDirectory(atPath: s.directory.path)
        #expect(names.contains { $0.hasPrefix("index.damaged-") })
    }

    /// Kill-test: el proceso muere tras escribir los archivos y antes del índice → no hay momento a medias.
    @Test func crashBeforeIndexLeavesNoHalfMoment() throws {
        var s = store()
        try s.create(capture(1), name: "bueno")
        s.crashPoint = { if $0 == "index" { throw Crash() } }
        #expect(throws: Crash.self) { try s.create(capture(2), name: "a medias") }
        s.crashPoint = nil
        try s.recoverOrphans()
        #expect(try s.snapshot().moments.map(\.name) == ["bueno"])
        let names = try FileManager.default.contentsOfDirectory(atPath: s.directory.path).filter { $0.hasPrefix("m-") }
        #expect(names.count == 3)
    }

    /// Kill-test: muere tras confirmar el índice y antes de borrar la entrada expulsada → se limpia al abrir.
    @Test func crashBeforeEvictionIsCleanedOnOpen() throws {
        var s = store()
        for i in 1...3 { try s.pushBeforeLoad(capture(UInt8(i)), label: "b\(i)") }
        s.crashPoint = { if $0 == "evict" { throw Crash() } }
        #expect(throws: Crash.self) { try s.pushBeforeLoad(capture(4), label: "b4") }
        s.crashPoint = nil
        #expect(try s.snapshot().beforeLoad.count == 3)
        try s.recoverOrphans()
        let files = try FileManager.default.contentsOfDirectory(atPath: s.directory.path).filter { $0.hasPrefix("b-") }
        #expect(files.count == 9)
    }

    /// Auditoría N6 Android: con el reloj que retrocede, la entrada recién escrita nunca se expulsa.
    @Test func ringEvictsByInsertionEvenIfTheClockGoesBack() throws {
        var s = store()
        for (i, t) in [500.0, 400, 300, 100].enumerated() {
            s.now = { Date(timeIntervalSince1970: t) }
            try s.pushBeforeLoad(capture(UInt8(i)), label: "b\(i)")
        }
        #expect(try s.snapshot().beforeLoad.map(\.name) == ["b3", "b2", "b1"])
    }

    @Test func rebuiltIndexKeepsTheNewestThreeInTheRingAndLosesNothing() throws {
        var s = store()
        for i in 1...3 { try s.pushBeforeLoad(capture(UInt8(i)), label: "b\(i)") }
        // Un cuarto archivo de anillo (p. ej. de antes de un fallo) y el índice dañado.
        s.newID = { "extra" }
        try AtomicFile.writeSynced(Data("PGBS-old".utf8), to: s.stateURL(.beforeLoad, "extra"))
        try FileManager.default.setAttributes([.modificationDate: Date(timeIntervalSince1970: 10)],
                                              ofItemAtPath: s.stateURL(.beforeLoad, "extra").path)
        try Data("{".utf8).write(to: s.directory.appendingPathComponent("index.json"))
        s.newID = { String(UUID().uuidString.prefix(8)).lowercased().filter { $0 != "-" } }
        try s.recoverOrphans()
        let snap = try s.snapshot()
        #expect(snap.beforeLoad.count == 3)
        #expect(!snap.beforeLoad.contains { $0.id == "extra" })
        #expect(snap.moments.count == 1)   // la sobrante pasa a momento, no se borra
    }

    @Test func migrationAfterARebuiltIndexDoesNotDuplicate() throws {
        let states = StateStore(root: dir.appendingPathComponent("States"), fingerprint: "fp")
        try states.save(Data("PGBS-1".utf8), thumbnail: nil, to: .manual1)
        var s = store()
        s.crashPoint = { if $0 == "migrate" { throw Crash() } }
        #expect(throws: Crash.self) { try s.migrateSlots(from: states) }
        s.crashPoint = nil
        try Data("{".utf8).write(to: s.directory.appendingPathComponent("index.json"))
        try s.recoverOrphans()   // reconstruido sin origen
        #expect(try s.migrateSlots(from: states) == 1)
        #expect(try s.snapshot().moments.count == 1)
    }

    // MARK: Migración de las ranuras 1–4

    @Test func slotsMigrateWithoutLossAndSurviveACrashMidway() throws {
        let states = StateStore(root: dir.appendingPathComponent("States"), fingerprint: "fp")
        try states.save(Data("PGBS-auto".utf8), thumbnail: nil, to: .auto)
        try states.save(Data("PGBS-1".utf8), thumbnail: Data([1]), to: .manual1)
        try states.save(Data("PGBS-3".utf8), thumbnail: nil, to: .manual3)
        var s = store()
        // Muere tras confirmar el primer momento y antes de borrar su ranura.
        s.crashPoint = { if $0 == "migrate" { throw Crash() } }
        #expect(throws: Crash.self) { try s.migrateSlots(from: states) }
        #expect(try s.snapshot().moments.count == 1)
        s.crashPoint = nil
        #expect(try s.migrateSlots(from: states) == 2)
        let migrated = try s.snapshot().moments
        #expect(Set(migrated.map(\.name)) == ["Ranura 1", "Ranura 3"])   // sin duplicar la 1
        for m in migrated {
            let data = try s.loadState(.moment, m.id)
            #expect(data == Data("PGBS-\(m.origin == "slot1" ? "1" : "3")".utf8))
            #expect(!m.hasSRAM)
        }
        #expect(states.entries().keys.sorted { $0.fileStem < $1.fileStem } == [.auto])   // el automático no cambia
        #expect(try s.migrateSlots(from: states) == 0)
    }

    // MARK: Con una sesión real

    private func openSession() throws -> (EmulatorSession, SaveStore, MomentActions) {
        let session = try EmulatorSession(romData: StateSRAMTests.rom(title: "MOMENTOS", value: 0, counting: true),
                                          savesDirectory: saves, onAudioInterrupted: {})
        session.start()
        session.pause()
        let saveStore = SaveStore(directory: saves, fingerprint: session.info.fingerprint)
        let actions = MomentActions(moments: store(session.info.fingerprint), saves: saveStore)
        return (session, saveStore, actions)
    }

    /// Criterio: cargar un momento antiguo → «Recuperar» devuelve la partida más nueva en un toque; el `.sav` previo
    /// queda en backup y el AUTO no cambia.
    @Test func loadOldMomentThenRecoverInOneTap() throws {
        let (session, saveStore, actions) = try openSession()
        defer { session.stop() }
        let x = try #require(try saveStore.load())
        let moment = try actions.create(from: session, name: "viejo", config: [:], playTime: nil)
        #expect(moment.hasSRAM && moment.hasState)
        #expect(try actions.moments.loadSRAM(.moment, moment.id) == x)
        let y = try StateSRAMTests.playUntilSaveChanges(session, store: saveStore, from: x)

        let autoStore = StateStore(root: dir.appendingPathComponent("States"), fingerprint: session.info.fingerprint)
        try autoStore.save(Data("PGBS-auto".utf8), thumbnail: nil, to: .auto)

        try actions.load(.moment, moment, into: session, config: [:], playTime: nil)
        #expect(try saveStore.load() == x)
        #expect(try Data(contentsOf: saveStore.backupURL(1)) == y)
        #expect(try autoStore.load(.auto) == Data("PGBS-auto".utf8))   // cargar no pisa el AUTO
        let ring = try actions.moments.snapshot().beforeLoad
        #expect(ring.count == 1)
        #expect(try actions.moments.loadSRAM(.beforeLoad, ring[0].id) == y)

        // «Recuperar»: vuelve Y y lo de ahora (X) también entra en el anillo.
        try actions.load(.beforeLoad, ring[0], into: session, config: [:], playTime: nil)
        #expect(try saveStore.load() == y)
        #expect(try actions.moments.snapshot().beforeLoad.count == 2)
    }

    /// Recuperar la entrada más antigua con el anillo lleno no la pierde al desplazarla.
    @Test func recoveringTheOldestWithAFullRingWorks() throws {
        let (session, saveStore, actions) = try openSession()
        defer { session.stop() }
        let x = try #require(try saveStore.load())
        var store = actions.moments
        store.now = { Date(timeIntervalSince1970: 100) }
        try store.pushBeforeLoad(try MomentActions.capture(session), label: "más antigua")
        for t in [200.0, 300] {
            store.now = { Date(timeIntervalSince1970: t) }
            try store.pushBeforeLoad(.init(state: Data("PGBS-x".utf8), sram: nil, thumbnail: nil), label: "otra")
        }
        _ = try StateSRAMTests.playUntilSaveChanges(session, store: saveStore, from: x)
        let oldest = try #require(try store.snapshot().beforeLoad.last)
        try MomentActions(moments: store, saves: saveStore).load(.beforeLoad, oldest, into: session, config: [:], playTime: nil)
        #expect(try saveStore.load() == x)
    }

    /// Criterio: un estado que el núcleo ya no carga (simulado) deja recuperar la RAM del momento.
    @Test func stateThatNoLongerLoadsStillRecoversItsSave() throws {
        let (session, saveStore, actions) = try openSession()
        let x = try #require(try saveStore.load())
        let moment = try actions.create(from: session, name: "roto", config: [:], playTime: nil)
        let y = try StateSRAMTests.playUntilSaveChanges(session, store: saveStore, from: x)
        // Simula un estado de otra versión: el núcleo lo rechaza y nada cambia.
        var state = try actions.moments.loadState(.moment, moment.id)
        state[state.count / 2] ^= 0xFF
        try AtomicFile.writeSynced(state, to: actions.moments.stateURL(.moment, moment.id))
        #expect(throws: CoreError.self) { try actions.load(.moment, moment, into: session, config: [:], playTime: nil) }
        #expect(try saveStore.load() == y)
        session.stop()

        // Sin sesión (detalle): «Recuperar su partida» instala la RAM del momento.
        let ownership = FingerprintOwnership()
        try actions.installSRAM(.moment, moment, ownership: ownership)
        #expect(try saveStore.load() == x)
        #expect(try Data(contentsOf: saveStore.backupURL(1)) == y)
        let ring = try actions.moments.snapshot().beforeLoad
        #expect(try ring.contains { try actions.moments.loadSRAM(.beforeLoad, $0.id) == y })
        #expect(!ownership.isOwned(saveStore.fingerprint))
    }

    /// Criterio: exclusión por huella con la sesión aparcada, y kill-test a mitad de instalar.
    @Test func installIsRefusedWhileTheSessionOwnsTheGameAndSurvivesACrash() throws {
        let (session, saveStore, actions) = try openSession()
        let x = try #require(try saveStore.load())
        let moment = try actions.create(from: session, name: "m", config: [:], playTime: nil)
        let y = try StateSRAMTests.playUntilSaveChanges(session, store: saveStore, from: x)
        let ownership = FingerprintOwnership()
        let lease = ownership.takeForSession(saveStore.fingerprint)   // sesión abierta y en pausa (aparcada)
        #expect(throws: FingerprintOwnership.Busy.self) { try actions.installSRAM(.moment, moment, ownership: ownership) }
        #expect(try saveStore.load() == y)
        #expect(try actions.moments.snapshot().beforeLoad.isEmpty)
        session.stop()
        // Aún escribiendo el espejo: la huella sigue con dueño hasta que se suelta.
        #expect(ownership.isOwned(saveStore.fingerprint))
        lease.release()

        // Muere tras guardar lo actual en el anillo y antes de escribir el .sav: la partida no cambia.
        var crashing = actions.moments
        crashing.crashPoint = { if $0 == "index" { throw Crash() } }
        let killed = MomentActions(moments: crashing, saves: saveStore)
        #expect(throws: Crash.self) { try killed.installSRAM(.moment, moment, ownership: ownership) }
        #expect(try saveStore.load() == y)
        #expect(!ownership.isOwned(saveStore.fingerprint))
        // Al reintentar, termina bien.
        try actions.installSRAM(.moment, moment, ownership: ownership)
        #expect(try saveStore.load() == x)
    }

    /// Kill-test de cargar un momento: muere al escribir el anillo o justo después (antes de la partida).
    @Test func crashWhileLoadingAMomentNeverLosesTheSave() throws {
        let (session, saveStore, actions) = try openSession()
        defer { session.stop() }
        let x = try #require(try saveStore.load())
        let moment = try actions.create(from: session, name: "m", config: [:], playTime: nil)
        let y = try StateSRAMTests.playUntilSaveChanges(session, store: saveStore, from: x)
        for point in ["files", "between", "index"] {
            var crashing = actions.moments
            crashing.crashPoint = { if $0 == point { throw Crash() } }
            #expect(throws: Crash.self) {
                try MomentActions(moments: crashing, saves: saveStore).load(.moment, moment, into: session, config: [:], playTime: nil)
            }
            #expect(try saveStore.load() == y)
            try actions.moments.recoverOrphans()
        }
        try actions.load(.moment, moment, into: session, config: [:], playTime: nil)
        #expect(try saveStore.load() == x)
    }

    /// Auditoría H1: anillo lleno, recuperar la más antigua y que falle el núcleo o el guardado → la entrada sigue.
    @Test func recoveringTheOldestNeverEvictsItWhenTheOperationFails() throws {
        let (session, saveStore, actions) = try openSession()
        let x = try #require(try saveStore.load())
        let store = actions.moments
        try store.pushBeforeLoad(try MomentActions.capture(session), label: "más antigua")
        for _ in 0..<2 { try store.pushBeforeLoad(.init(state: Data("PGBS-x".utf8), sram: x, thumbnail: nil), label: "otra") }
        let oldest = try #require(try store.snapshot().beforeLoad.last)
        #expect(oldest.name == "más antigua")
        // 1) El núcleo rechaza el estado (dañado).
        var bad = try store.loadState(.beforeLoad, oldest.id)
        let good = bad
        bad[bad.count / 2] ^= 0xFF
        try AtomicFile.writeSynced(bad, to: store.stateURL(.beforeLoad, oldest.id))
        #expect(throws: CoreError.self) { try actions.load(.beforeLoad, oldest, into: session, config: [:], playTime: nil) }
        #expect(try store.snapshot().beforeLoad.contains { $0.id == oldest.id })
        #expect(FileManager.default.fileExists(atPath: store.sramURL(.beforeLoad, oldest.id).path))
        try AtomicFile.writeSynced(good, to: store.stateURL(.beforeLoad, oldest.id))
        // 2) El guardado de la partida falla (carpeta de solo lectura).
        _ = try StateSRAMTests.playUntilSaveChanges(session, store: saveStore, from: x)
        try FileManager.default.setAttributes([.posixPermissions: 0o555], ofItemAtPath: saves.path)
        #expect(throws: (any Error).self) { try actions.load(.beforeLoad, oldest, into: session, config: [:], playTime: nil) }
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: saves.path)
        #expect(try store.snapshot().beforeLoad.contains { $0.id == oldest.id })
        session.stop()
        // 3) Sin sesión: instalar con el guardado fallando tampoco la expulsa.
        try FileManager.default.setAttributes([.posixPermissions: 0o555], ofItemAtPath: saves.path)
        #expect(throws: (any Error).self) { try actions.installSRAM(.beforeLoad, oldest, ownership: FingerprintOwnership()) }
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: saves.path)
        #expect(try store.snapshot().beforeLoad.contains { $0.id == oldest.id })
        // Y al confirmar, sí se recorta a 3 conservándola.
        try actions.installSRAM(.beforeLoad, oldest, ownership: FingerprintOwnership())
        let ring = try store.snapshot().beforeLoad
        #expect(ring.count == 3 && ring.contains { $0.id == oldest.id })
        #expect(try saveStore.load() == x)
    }

    /// Auditoría H2/H3: un .tmp a medias y un m-<id>.state sin .sav ni índice (muerte entre los dos renames): el
    /// temporal se borra y el huérfano se aparta en orphans/, nunca se borra.
    @Test func halfWrittenMomentIsSetAsideNotDeleted() throws {
        var s = store()
        try s.create(capture(1), name: "bueno")
        s.newID = { "medias" }
        s.crashPoint = { if $0 == "between" { throw Crash() } }
        #expect(throws: Crash.self) { try s.create(capture(2), name: "a medias") }
        s.crashPoint = nil
        #expect(FileManager.default.fileExists(atPath: s.stateURL(.moment, "medias").path))
        #expect(!FileManager.default.fileExists(atPath: s.sramURL(.moment, "medias").path))
        try Data([1, 2]).write(to: s.directory.appendingPathComponent("m-medias.sav.tmp"))
        try s.recoverOrphans()
        let names = try FileManager.default.contentsOfDirectory(atPath: s.directory.path)
        #expect(!names.contains { $0.hasSuffix(".tmp") || $0.hasPrefix("m-medias") })
        let orphans = try FileManager.default.contentsOfDirectory(atPath: s.directory.appendingPathComponent("orphans").path)
        #expect(orphans.contains("m-medias.state"))
        #expect(try s.snapshot().moments.map(\.name) == ["bueno"])
    }

    @Test func installRefusesASaveOfAnotherSize() throws {
        let saveStore = SaveStore(directory: saves, fingerprint: "fp")
        try saveStore.save(Data(repeating: 1, count: 64))
        let s = store()
        let m = try s.create(capture(2), name: "m")
        let actions = MomentActions(moments: s, saves: saveStore)
        #expect(throws: MomentActions.InstallError.sizeMismatch) {
            try actions.installSRAM(.moment, m, ownership: FingerprintOwnership())
        }
        #expect(try saveStore.load() == Data(repeating: 1, count: 64))
    }

    @Test func configDifferencesAreReported() {
        let a = MomentConfig.make(EmulationOptions(colorForGameBoy: false, compatPalette: 0), console: .gameBoy)
        let b = MomentConfig.make(EmulationOptions(colorForGameBoy: true, compatPalette: 2), console: .gameBoy)
        #expect(MomentConfig.differences(a, b) == ["model", "palette"])
        #expect(MomentConfig.differences([:], b).isEmpty)
        #expect(MomentConfig.make(EmulationOptions(colorForGameBoy: false, compatPalette: 0), console: .gameBoyAdvance).keys.sorted()
                == ["console", "gbaBios", "gbaRtc", "gbaSaveType"])
    }
}

/// N6 · progreso: tiempo de juego, hitos y lector Pokémon (partidas sintéticas byte a byte).
@MainActor
struct ProgressTests {
    let store: ProgressStore
    let fp = String(repeating: "a", count: 64)

    init() {
        store = ProgressStore(directory: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString))
    }

    final class Clock: @unchecked Sendable {
        var t: TimeInterval = 0
    }

    /// Criterio: contabilidad con pausa, segundo plano y cierre forzado.
    @Test func playTimeCountsOnlyWhileRunning() {
        let clock = Clock()
        let tracker = PlayTimeTracker(store: store, fingerprint: fp, clock: { clock.t })
        tracker.setRunning(true)          // abre y corre: 1 sesión
        clock.t = 10
        tracker.setRunning(false)         // pausa: escribe 10 s
        clock.t = 100                     // 90 s en pausa no cuentan
        tracker.setRunning(true)
        clock.t = 130
        tracker.setRunning(false)         // segundo plano: +30 s
        #expect(store.load(fp).playTime == 40)
        #expect(store.load(fp).sessions == 1)
        tracker.setRunning(true)
        clock.t = 160
        tracker.checkpoint()              // cada 30 s
        clock.t = 175                     // cierre forzado: se pierde como mucho lo posterior al último checkpoint
        #expect(store.load(fp).playTime == 70)
        #expect(tracker.pending == 15)
        // Una sesión nueva al volver a abrir.
        PlayTimeTracker(store: store, fingerprint: fp, clock: { clock.t }).setRunning(true)
        #expect(store.load(fp).sessions == 2)
        #expect(store.load(fp).firstPlayed != nil)
    }

    @Test func templatesDoNotDuplicateAndPercentIsOffByDefault() throws {
        try store.addMilestone("Medalla 1", to: fp)
        var p = try store.apply(.pokemon, to: fp)
        #expect(p.milestones.count == 9)
        p = try store.apply(.pokemon, to: fp)
        #expect(p.milestones.count == 9)
        #expect(p.visiblePercent == nil && p.percent == 0)
        p = try store.setDone(p.milestones[0].id, true, in: fp)
        p = try store.update(fp) { $0.showPercent = true }
        #expect(p.visiblePercent == 11)
        p = try store.apply(.free, to: fp)
        #expect(p.milestones.count == 9)   // cambiar de plantilla no borra
    }

    @Test func damagedProgressIsSetAsideNotOverwritten() throws {
        try FileManager.default.createDirectory(at: store.directory, withIntermediateDirectories: true)
        try Data("nope".utf8).write(to: try #require(store.url(fp)))
        try store.addMilestone("x", to: fp)
        let names = try FileManager.default.contentsOfDirectory(atPath: store.directory.path)
        #expect(names.contains { $0.contains(".damaged-") })
        #expect(store.load(fp).milestones.count == 1)
    }

    @Test func pokemonReaderWithSyntheticSaveAndBadChecksum() throws {
        let header = DebugScreenRouter.syntheticGen1Header()
        var sav = DebugScreenRouter.syntheticGen1Save()
        let p = try #require(PokemonProgress.read(header: header, sram: sav))
        #expect(p.game == .gen1 && p.playerName == "PRUEBA" && p.badgesCount == 3)
        #expect(p.pokedexOwned == 24 && p.pokedexSeen == 40 && p.hours == 12 && p.minutes == 34 && p.money == 3456)
        var progress = try store.apply(.pokemon, to: fp)
        #expect(BadgeSuggestion.ids(progress, pokemon: p).count == 3)   // se propone, no se marca
        progress = store.load(fp)
        #expect(progress.milestones.allSatisfy { !$0.done })
        sav[0x3523] ^= 0x01
        #expect(PokemonProgress.read(header: header, sram: sav) == nil)
        var japanese = header
        japanese[0x14A] = 0
        #expect(!PokemonProgress.isSupported(header: japanese))
    }
}

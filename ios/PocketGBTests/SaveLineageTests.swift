import Foundation
import Testing
@testable import PocketGB

/// N7a · tabla de linaje completa (N-README §3.4), con latencia, copias en conflicto del proveedor y reloj desfasado.
struct SaveLineageTests {
    let dir: URL
    let d1 = Data([1, 1, 1, 1]), d2 = Data([2, 2, 2, 2]), d3 = Data([3, 3, 3, 3])
    let farPast = Date(timeIntervalSince1970: 1_000)          // reloj de otro equipo muy atrasado
    let farFuture = Date(timeIntervalSince1970: 4_000_000_000) // o muy adelantado

    init() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("lineage-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    private func setup(_ name: String) throws -> (SaveStore, SaveMirror) {
        let store = SaveStore(directory: dir, fingerprint: "\(name)-\(UUID().uuidString.prefix(6))")
        let mirror = SaveMirror(url: dir.appendingPathComponent("\(store.fingerprint)-rom.sav"))
        return (store, mirror)
    }

    /// Escritura normal de una sesión: local + espejo, esperando a que el espejo termine (queda en el historial).
    private func play(_ data: Data, _ store: SaveStore, _ mirror: SaveMirror) throws {
        let target = SaveTarget(local: store, mirror: mirror)
        try target.persistLocal(data)
        let idle = DispatchSemaphore(value: 0)
        target.whenMirrorIdle { idle.signal() }
        #expect(idle.wait(timeout: .now() + 2) == .success)
    }

    private func setDate(_ date: Date, _ url: URL) throws {
        try FileManager.default.setAttributes([.modificationDate: date], ofItemAtPath: url.path)
    }

    private func open(_ store: SaveStore, _ mirror: SaveMirror,
                      _ options: SaveOpening.LineageOptions = .init()) throws -> SaveOpening.Outcome {
        try SaveOpening.prepare(store: store, mirror: mirror, snapshot: mirror.snapshot(), validSizes: [4], lineage: options)
    }

    // MARK: Decisión pura

    @Test func mirrorRelationTable() {
        let h = SaveLineage.History(written: ["b", "a"], pending: [])
        #expect(SaveLineage.relation(local: "x", mirror: "x", history: h) == .same)
        #expect(SaveLineage.relation(local: "b", mirror: "a", history: h) == .ownEarlier)
        #expect(SaveLineage.relation(local: "b", mirror: "z", history: h) == .external)
        #expect(SaveLineage.relation(local: "c", mirror: "z", history: h) == .divergent)
        #expect(SaveLineage.relation(local: "c", mirror: "z", history: .init()) == .noHistory)
        // Escritura pendiente (el proceso murió a mitad): cuenta como la última propia.
        let pending = SaveLineage.History(written: ["a"], pending: ["c"])
        #expect(SaveLineage.relation(local: "c", mirror: "z", history: pending) == .external)
        #expect(SaveLineage.relation(local: "a", mirror: "c", history: pending) == .ownEarlier)
    }

    @Test func packageRelationTableWithLatency() {
        let known: Set<String> = ["s0", "s1"]
        #expect(SaveLineage.relation(packageSave: "s2", packageBase: "s1", local: nil, known: known) == .noLocal)
        #expect(SaveLineage.relation(packageSave: "s1", packageBase: "s0", local: "s1", known: known) == .same)
        #expect(SaveLineage.relation(packageSave: "s2", packageBase: "s1", local: "s1", known: known) == .advance)
        // Mayúsculas en el paquete: se comparan sin distinguirlas (META v1).
        #expect(SaveLineage.relation(packageSave: "S2", packageBase: "S1", local: "s1", known: known) == .advance)
        #expect(SaveLineage.relation(packageSave: "s0", packageBase: nil, local: "s1", known: known) == .older)
        // Las dos avanzaron desde s0.
        #expect(SaveLineage.relation(packageSave: "o1", packageBase: "s0", local: "s1", known: known) == .divergent(baseKnown: true))
        // Latencia: el paquete anuncia una base (s5) que aquí aún no llegó. Nunca se instala sin preguntar.
        #expect(SaveLineage.relation(packageSave: "s6", packageBase: "s5", local: "s1", known: known) == .divergent(baseKnown: false))
        #expect(SaveLineage.relation(packageSave: "s6", packageBase: nil, local: "s1", known: known) == .divergent(baseKnown: false))
    }

    // MARK: Tabla con E/S real

    @Test func mirrorEqualsLocalDoesNothing() throws {
        let (store, mirror) = try setup("same")
        try play(d1, store, mirror)
        let outcome = try open(store, mirror)
        #expect(outcome.data == d1)
        #expect(outcome.warning == nil)
        #expect(store.backups().isEmpty)
    }

    /// Latencia del proveedor: el espejo aún enseña una escritura nuestra anterior (d1) con fecha más nueva que la local
    /// (d2). Gana la local sin preguntar y el espejo se reescribe.
    @Test func ownEarlierMirrorLosesEvenWithNewerDate() throws {
        let (store, mirror) = try setup("own")
        try play(d1, store, mirror)
        try store.save(d2)   // p. ej. «recuperar» desde el detalle (sin espejo)
        try d1.write(to: mirror.url)
        try setDate(farFuture, mirror.url)
        let outcome = try open(store, mirror)
        #expect(outcome.data == d2)
        #expect(outcome.target?.mirrorPending == true)
        #expect(outcome.warning == .mirrorOlderKept)
    }

    /// Cambio externo con el reloj del otro equipo atrasado: la fecha no importa, se instala con copia y aviso.
    @Test func externalChangeIsInstalledWithBackupDespiteOldClock() throws {
        let (store, mirror) = try setup("external")
        try play(d1, store, mirror)
        try d3.write(to: mirror.url)
        try setDate(farPast, mirror.url)
        let outcome = try open(store, mirror)
        #expect(outcome.data == d3)
        #expect(try store.load() == d3)
        #expect(try Data(contentsOf: store.backupURL(1)) == d1)
        #expect(outcome.warning == .externalChange)
    }

    /// Divergencia con el reloj del otro equipo adelantado: se pregunta sin tocar nada; con la elección, la perdedora
    /// queda como momento «Conflicto …» y apartada.
    @Test func divergenceAsksThenKeepsLoserAsConflictMoment() throws {
        let (store, mirror) = try setup("diverge")
        try play(d1, store, mirror)
        try store.save(d2)
        try d3.write(to: mirror.url)
        try setDate(farFuture, mirror.url)
        do {
            _ = try open(store, mirror)
            Issue.record("debía preguntar")
        } catch let SaveOpening.Refusal.divergence(localDate, otherDate) {
            #expect(localDate != nil)
            #expect(otherDate == farFuture)
        }
        #expect(try store.load() == d2)
        #expect(try mirror.read() == d3)

        let moments = MomentStore(root: dir.appendingPathComponent("moments"), fingerprint: store.fingerprint)
        let outcome = try open(store, mirror, .init(divergence: .keepLocal, conflictMoments: moments))
        #expect(outcome.data == d2)
        #expect(outcome.warning == .divergenceResolved)
        #expect(store.keptCopies().contains { (try? Data(contentsOf: $0.url)) == d3 })
        let conflict = try #require(try moments.snapshot().moments.first)
        #expect(conflict.name.hasPrefix("Conflicto"))
        #expect(try moments.loadSRAM(.moment, conflict.id) == d3)
    }

    @Test func divergenceUseOtherBacksUpLocal() throws {
        let (store, mirror) = try setup("diverge-other")
        try play(d1, store, mirror)
        try store.save(d2)
        try d3.write(to: mirror.url)
        let moments = MomentStore(root: dir.appendingPathComponent("moments2"), fingerprint: store.fingerprint)
        let outcome = try open(store, mirror, .init(divergence: .useOther, conflictMoments: moments))
        #expect(outcome.data == d3)
        #expect(try store.load() == d3)
        #expect(try Data(contentsOf: store.backupURL(1)) == d2)
        #expect(try moments.loadSRAM(.moment, try #require(try moments.snapshot().moments.first).id) == d2)
    }

    /// Sin historial (primera vez): la regla por fecha de siempre, con copia de la que pierde.
    @Test func noHistoryUsesDateRuleWithBackup() throws {
        let (store, mirror) = try setup("first")
        try store.save(d1)
        try setDate(farPast, store.saveURL)
        try d3.write(to: mirror.url)
        let outcome = try open(store, mirror)
        #expect(outcome.data == d3)
        #expect(store.keptCopies().contains { (try? Data(contentsOf: $0.url)) == d1 })
    }

    /// Espejo borrado: se recrea desde la local.
    @Test func deletedMirrorIsRecreatedFromLocal() throws {
        let (store, mirror) = try setup("deleted")
        try play(d1, store, mirror)
        try FileManager.default.removeItem(at: mirror.url)
        let outcome = try open(store, mirror)
        #expect(outcome.data == d1)
        outcome.target?.retryMirrorIfNeeded(d1)
        let idle = DispatchSemaphore(value: 0)
        outcome.target?.whenMirrorIdle { idle.signal() }
        #expect(idle.wait(timeout: .now() + 2) == .success)
        #expect(try mirror.read() == d1)
    }

    // MARK: Copias en conflicto del proveedor

    @Test func providerConflictCopiesAreRecognised() {
        let yes = ["Pokemon Rojo 2.sav", "pokemon rojo 3.SAV", "Pokemon Rojo (1).sav",
                   "Pokemon Rojo.sync-conflict-20261008-101010-ABCDEFG.sav",
                   "Pokemon Rojo (Joel's conflicted copy 2026-10-08).sav",
                   "Pokemon Rojo (copia en conflicto de Joel 2026-10-08).sav"]
        let no = ["Pokemon Rojo.sav", "Pokemon Rojo 1.sav", "Pokemon Rojo.gb", "Pokemon Rojo 2.state",
                  "Pokemon Rojo Plus.sav", "Pokemon.sav", "Pokemon Rojo (beta).sav"]
        for name in yes { #expect(ConflictCopies.isConflictCopy(name, of: "Pokemon Rojo"), "\(name)") }
        for name in no { #expect(!ConflictCopies.isConflictCopy(name, of: "Pokemon Rojo"), "\(name)") }
    }

    @Test func conflictScanListsAndNeverTouches() throws {
        let folder = dir.appendingPathComponent("roms")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        for name in ["Juego.gb", "Juego.sav", "Juego 2.sav", "Juego (1).sav", "Otro.gb", "Juego 3.gb", "Juego 3.sav"] {
            try d1.write(to: folder.appendingPathComponent(name))
        }
        let found = ConflictCopies.scan(romURL: folder.appendingPathComponent("Juego.gb")).map(\.lastPathComponent)
        // «Juego 3.sav» es la partida de «Juego 3.gb», no una copia en conflicto.
        #expect(found == ["Juego (1).sav", "Juego 2.sav"])
        let after = try FileManager.default.contentsOfDirectory(atPath: folder.path)
        #expect(after.count == 7)
    }

    // MARK: Auditoría N7 iOS

    /// H7: con la local de tamaño incorrecto (cuarentena) o el espejo ignorado no hay linaje: ni «Conflicto» ni aviso.
    @Test func wrongSizesNeverProduceConflictMomentsOrLineageWarnings() throws {
        for wrongLocal in [true, false] {
            let (store, mirror) = try setup("h7")
            try play(d1, store, mirror)
            if wrongLocal { try store.save(Data([9, 9, 9])) } else { try store.save(d2) }
            try (wrongLocal ? d3 : Data([7, 7, 7, 7, 7])).write(to: mirror.url)
            let moments = MomentStore(root: dir.appendingPathComponent("m-\(wrongLocal)"), fingerprint: store.fingerprint)
            let outcome = try open(store, mirror, .init(divergence: nil, conflictMoments: moments))
            #expect(outcome.warning == (wrongLocal ? .localQuarantined : .mirrorIgnored))
            #expect(((try? moments.snapshot().moments) ?? []).isEmpty)
        }
    }

    /// H8: el nombre original de una copia en conflicto.
    @Test func originalStemOfConflictCopies() {
        #expect(ConflictCopies.originalStem("Pokemon Rojo 2") == "Pokemon Rojo")
        #expect(ConflictCopies.originalStem("Pokemon Rojo (1)") == "Pokemon Rojo")
        #expect(ConflictCopies.originalStem("Pokemon Rojo.sync-conflict-20261008-101010-ABCDEFG") == "Pokemon Rojo")
        #expect(ConflictCopies.originalStem("Pokemon Rojo (Joel's conflicted copy 2026-10-08)") == "Pokemon Rojo")
        #expect(ConflictCopies.originalStem("Pokemon Rojo") == "Pokemon Rojo")
        #expect(ConflictCopies.originalStem("Pokemon Rojo (beta)") == "Pokemon Rojo (beta)")
        #expect(ConflictCopies.originalStem("Juego 1") == "Juego 1")
    }

    /// H2: la elección de divergencia solo vale para el mismo juego y se consume siempre.
    @Test func pendingDivergenceOnlyAppliesToTheSameGame() {
        var pending: PendingDivergence? = PendingDivergence(entryID: "a.gb", choice: .useOther)
        #expect(PendingDivergence.take(&pending, entryID: "b.gb") == nil)
        #expect(pending == nil)   // descartada: no se aplica después a «a.gb» por sorpresa
        pending = PendingDivergence(entryID: "a.gb", choice: .keepLocal)
        #expect(PendingDivergence.take(&pending, entryID: "a.gb") == .keepLocal)
        #expect(pending == nil)
        #expect(PendingDivergence.take(&pending, entryID: "a.gb") == nil)
    }
}

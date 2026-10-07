import Foundation
import Testing
@testable import PocketGB

/// N1a: metadatos por huella, migración sin pérdida desde los formatos de N0, preferencias
/// robustas (cuarentena, errores de escritura) y, extremo a extremo con carpetas temporales
/// reales, mover un ROM y duplicados.
@MainActor
struct LibraryIdentityTests {
    let dir: URL

    init() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    private var prefsURL: URL { dir.appendingPathComponent("Library/preferences.json") }

    private func writePrefs(_ text: String) throws {
        try FileManager.default.createDirectory(at: prefsURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data(text.utf8).write(to: prefsURL)
    }

    private static func entry(_ id: String, fingerprint: String? = nil) -> RomEntry {
        var e = LibraryPreferencesTests.entry(id, (id as NSString).lastPathComponent.uppercased(), color: false)
        e.fingerprint = fingerprint
        return e
    }

    private static func nfc(_ s: String) -> String { s.precomposedStringWithCanonicalMapping }

    // MARK: Migración

    /// `preferences.json` tal como lo escribía la versión 1 (`JSONEncoder` por defecto: fechas en
    /// segundos desde 2001 y conjuntos como listas).
    static let v1Fixture = #"""
    {"favorites":["Rojo\/rojo.gb","nunca.gb"],"lastPlayed":{"Rojo\/rojo.gb":781000000,"Azul\/azul.gb":781500000},"fingerprints":{"Rojo\/rojo.gb":"fp-rojo","Azul\/azul.gb":"fp-azul"},"hiddenFingerprints":["fp-azul"],"hiddenPaths":["sin-abrir.gb"],"aliasesByFingerprint":{"fp-rojo":"Mi Rojo"},"aliasesByPath":{"nunca.gb":"Pendiente","Rojo\/rojo.gb":"Perdedor"},"layout":"list","sort":"recent"}
    """#

    /// `UserDefaults` `gameplaySettings` tal como lo escribe `GameplaySettings.update` hasta N0:
    /// los ajustes por juego van por ruta.
    static let legacyGameplayFixture = #"""
    {"opacity":50,"visibility":"always","haptics":true,"sizeScale":1,"integerScaleLandscape":true,"dpadStyle":"cross","volume":0.8,"playsInSilentMode":false,"colorForGameBoy":false,"compatPalette":0,"perGame":{"Rojo\/rojo.gb":{"colorForGameBoy":true,"compatPalette":5},"nunca.gb":{"gbaSaveType":3,"gbaRTC":2}}}
    """#

    @Test func version1FileMigratesWithoutLoss() throws {
        try writePrefs(Self.v1Fixture)
        let original = try Data(contentsOf: prefsURL)
        let prefs = LibraryPreferences(fileURL: prefsURL)
        prefs.waitForPendingWrites()
        #expect(prefs.issue == nil)

        let rojo = Self.entry("Rojo/rojo.gb")       // sin huella en el escaneo: la pista de la ruta
        let azul = Self.entry("Azul/azul.gb")
        let nunca = Self.entry("nunca.gb")
        let sinAbrir = Self.entry("sin-abrir.gb")
        let ref = { (t: TimeInterval) in Date(timeIntervalSinceReferenceDate: t) }
        // Lo mismo que mostraba la versión 1, juego por juego.
        #expect(prefs.isFavorite(rojo) && prefs.displayTitle(rojo) == "Mi Rojo" && !prefs.isHidden(rojo))
        #expect(prefs.lastPlayed(rojo) == ref(781_000_000))
        #expect(!prefs.isFavorite(azul) && prefs.isHidden(azul) && prefs.lastPlayed(azul) == ref(781_500_000))
        #expect(prefs.isFavorite(nunca) && prefs.displayTitle(nunca) == "Pendiente")
        #expect(prefs.isHidden(sinAbrir))
        #expect(prefs.data.layout == .list && prefs.data.sort == .recent)
        // Y ahora va por huella: una copia movida con la misma huella lo conserva todo.
        let moved = Self.entry("Otra/carpeta/rojo.gb", fingerprint: "fp-rojo")
        #expect(prefs.isFavorite(moved) && prefs.displayTitle(moved) == "Mi Rojo")
        #expect(prefs.data.games["fp-rojo"]?.lastPlayedPath == "Rojo/rojo.gb")
        #expect(prefs.data.pendingByPath.keys.sorted() == ["nunca.gb", "sin-abrir.gb"])

        // En disco: formato 2, y el original intacto en preferences.v1.json.
        let backup = prefsURL.deletingLastPathComponent().appendingPathComponent("preferences.v1.json")
        #expect(try Data(contentsOf: backup) == original)
        #expect(LibraryPreferences.fileVersion(try Data(contentsOf: prefsURL)) == 2)
        let reopened = LibraryPreferences(fileURL: prefsURL)
        #expect(reopened.data == prefs.data && reopened.issue == nil)
    }

    @Test func legacyPerGameSettingsMoveToTheFingerprint() throws {
        try writePrefs(Self.v1Fixture)
        let suite = try #require(UserDefaults(suiteName: "pocketgb-tests-\(UUID().uuidString)"))
        let legacyBlob = Data(Self.legacyGameplayFixture.utf8)
        suite.set(legacyBlob, forKey: GameplaySettings.key)
        let gameplay = GameplaySettings(defaults: suite)
        #expect(gameplay.data.opacity == 50 && gameplay.data.perGame.count == 2)

        let prefs = LibraryPreferences(fileURL: prefsURL)
        prefs.importLegacyGameSettings(gameplay.data.perGame)
        prefs.waitForPendingWrites()
        #expect(prefs.data.importedLegacyGameSettings)
        #expect(prefs.data.games["fp-rojo"]?.overrides == GameOverrides(colorForGameBoy: true, compatPalette: 5))
        #expect(prefs.data.pendingByPath["nunca.gb"]?.overrides == GameOverrides(gbaSaveType: 3, gbaRTC: 2))
        #expect(prefs.overrides(fingerprint: "fp-rojo", path: "Movido/rojo.gb").compatPalette == 5)
        #expect(prefs.overrides(fingerprint: nil, path: "Rojo/rojo.gb").compatPalette == 5)   // pista de la ruta
        // La copia antigua de UserDefaults no se toca (respaldo) y no se vuelve a importar.
        #expect(suite.data(forKey: GameplaySettings.key) == legacyBlob)
        prefs.importLegacyGameSettings(["Rojo/rojo.gb": GameOverrides(compatPalette: 9)])
        #expect(prefs.data.games["fp-rojo"]?.overrides.compatPalette == 5)

        // Al conocer la huella de «nunca.gb» (escaneo), todo lo provisional pasa a ella.
        prefs.adopt(["nunca.gb": "fp-nunca"])
        prefs.waitForPendingWrites()
        #expect(prefs.data.pendingByPath["nunca.gb"] == nil)
        let nunca = try #require(prefs.data.games["fp-nunca"])
        #expect(nunca.favorite && nunca.alias == "Pendiente" && nunca.overrides.gbaSaveType == 3)
        let reopened = LibraryPreferences(fileURL: prefsURL)
        #expect(reopened.data == prefs.data)
    }

    @Test func provisionalPathMetadataMergesIntoTheFingerprint() {
        let prefs = LibraryPreferences(fileURL: nil)
        let unknown = Self.entry("x.gb")
        prefs.toggleFavorite(unknown)
        prefs.setAlias("Provisional", for: unknown)
        #expect(prefs.data.pendingByPath["x.gb"]?.favorite == true)
        // La huella ya tenía metadatos de otra copia: se unen, y en conflicto gana la huella.
        let other = Self.entry("y.gb", fingerprint: "fp")
        prefs.setAlias("De la huella", for: other)
        prefs.adopt(["x.gb": "fp"])
        let known = Self.entry("x.gb", fingerprint: "fp")
        #expect(prefs.isFavorite(known) && prefs.isFavorite(other))
        #expect(prefs.displayTitle(known) == "De la huella")
        #expect(prefs.data.pendingByPath.isEmpty)
    }

    // MARK: Robustez

    @Test func corruptFileIsQuarantinedAndReported() throws {
        try writePrefs("{esto no es JSON")
        let bytes = try Data(contentsOf: prefsURL)
        let now = Date(timeIntervalSince1970: 1_791_000_000)
        let prefs = LibraryPreferences(fileURL: prefsURL, now: now)
        guard case .quarantined(let name) = prefs.issue else {
            Issue.record("Se esperaba la cuarentena, no \(String(describing: prefs.issue))")
            return
        }
        #expect(name.hasPrefix("preferences.corrupt-") && name.hasSuffix(".json"))
        let quarantined = prefsURL.deletingLastPathComponent().appendingPathComponent(name)
        #expect(try Data(contentsOf: quarantined) == bytes)
        #expect(!FileManager.default.fileExists(atPath: prefsURL.path))
        #expect(prefs.issue?.message.contains(name) == true)

        // La app sigue: escribe un archivo nuevo y el apartado no se toca.
        prefs.toggleFavorite(Self.entry("a.gb"))
        prefs.waitForPendingWrites()
        #expect(LibraryPreferences(fileURL: prefsURL).isFavorite(Self.entry("a.gb")))
        #expect(try Data(contentsOf: quarantined) == bytes)

        // Otro archivo dañado en el mismo segundo no pisa el primero.
        try writePrefs("[1,2,3]")
        let second = LibraryPreferences(fileURL: prefsURL, now: now)
        guard case .quarantined(let name2) = second.issue else {
            Issue.record("Segunda cuarentena ausente")
            return
        }
        #expect(name2 != name && name2.contains("-2"))
        #expect(try Data(contentsOf: quarantined) == bytes)
    }

    /// Una colección dañada no se pierde en silencio: todo el archivo va a cuarentena.
    @Test func structurallyInvalidVersion2IsQuarantinedNotEmptied() throws {
        try writePrefs(#"{"version":2,"games":[1,2]}"#)
        let prefs = LibraryPreferences(fileURL: prefsURL)
        guard case .quarantined = prefs.issue else {
            Issue.record("Se esperaba la cuarentena")
            return
        }
        // Un campo dañado dentro de un juego sí se tolera: solo ese campo vuelve al valor por defecto.
        try writePrefs(#"{"version":2,"games":{"fp":{"favorite":"sí","alias":"Nombre","hidden":true}}}"#)
        let tolerant = LibraryPreferences(fileURL: prefsURL)
        #expect(tolerant.issue == nil)
        #expect(tolerant.data.games["fp"]?.alias == "Nombre" && tolerant.data.games["fp"]?.hidden == true)
        #expect(tolerant.data.games["fp"]?.favorite == false)
    }

    @Test func unreadableFileIsNeverOverwritten() throws {
        // Un directorio donde debería estar el archivo: leerlo falla con un error de E/S.
        try FileManager.default.createDirectory(at: prefsURL, withIntermediateDirectories: true)
        let prefs = LibraryPreferences(fileURL: prefsURL)
        guard case .unreadable = prefs.issue else {
            Issue.record("Se esperaba .unreadable, no \(String(describing: prefs.issue))")
            return
        }
        prefs.toggleFavorite(Self.entry("a.gb"))
        prefs.waitForPendingWrites()
        #expect(prefs.isFavorite(Self.entry("a.gb")))                        // sigue en memoria
        var isDirectory: ObjCBool = false
        #expect(FileManager.default.fileExists(atPath: prefsURL.path, isDirectory: &isDirectory) && isDirectory.boolValue)
        #expect(!FileManager.default.fileExists(atPath: prefsURL.appendingPathExtension("tmp").path))
        #expect((try FileManager.default.contentsOfDirectory(atPath: prefsURL.path)).isEmpty)
    }

    @Test func newerVersionIsReadButNotOverwritten() throws {
        try writePrefs(#"{"version":3,"games":{"fp":{"favorite":true}},"futuro":{"x":1}}"#)
        let bytes = try Data(contentsOf: prefsURL)
        let prefs = LibraryPreferences(fileURL: prefsURL)
        #expect(prefs.issue == .newerVersion(3))
        #expect(prefs.isFavorite(Self.entry("a.gb", fingerprint: "fp")))
        prefs.toggleFavorite(Self.entry("b.gb"))
        prefs.waitForPendingWrites()
        #expect(try Data(contentsOf: prefsURL) == bytes)
    }

    /// H2: un archivo de una versión futura que esta no sabe decodificar no se aparta ni se pisa.
    @Test func undecodableNewerVersionIsBlockedNotQuarantined() throws {
        try writePrefs(#"{"version":3,"games":[1,2],"otra":"cosa"}"#)
        let bytes = try Data(contentsOf: prefsURL)
        let prefs = LibraryPreferences(fileURL: prefsURL)
        #expect(prefs.issue == .newerVersion(3))
        #expect(prefs.data.games.isEmpty)
        prefs.toggleFavorite(Self.entry("a.gb"))
        prefs.waitForPendingWrites()
        #expect(try Data(contentsOf: prefsURL) == bytes)
        let names = try FileManager.default.contentsOfDirectory(atPath: prefsURL.deletingLastPathComponent().path)
        #expect(names == ["preferences.json"])
    }

    /// H4: solo un archivo **sin** `version` es del formato 1; `null`, `1`, texto o booleano van a
    /// cuarentena (con sus bytes) en lugar de migrarse o leerse como formato 2 y sobrescribirse.
    @Test func invalidVersionValuesAreQuarantinedNotMigrated() throws {
        for (i, text) in [#"{"version":null,"favorites":["a.gb"]}"#, #"{"version":1,"favorites":["a.gb"]}"#,
                          #"{"version":"2","games":{}}"#, #"{"version":true}"#, #"{"version":2.5}"#].enumerated() {
            let url = dir.appendingPathComponent("caso\(i)/preferences.json")
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try Data(text.utf8).write(to: url)
            let prefs = LibraryPreferences(fileURL: url)
            guard case .quarantined(let name) = prefs.issue else {
                Issue.record("\(text): se esperaba la cuarentena, no \(String(describing: prefs.issue))")
                continue
            }
            let moved = url.deletingLastPathComponent().appendingPathComponent(name)
            #expect(try Data(contentsOf: moved) == Data(text.utf8), "\(text)")
            #expect(!FileManager.default.fileExists(
                atPath: url.deletingLastPathComponent().appendingPathComponent("preferences.v1.json").path))
            #expect(!prefs.isFavorite(Self.entry("a.gb")))
        }
    }

    /// H10: una copia `preferences.v1.json` previa con otro contenido no se pisa: la copia exacta va a
    /// `preferences.v1-<fecha>.json`.
    @Test func legacyBackupNeverOverwritesAPreviousCopy() throws {
        try writePrefs(Self.v1Fixture)
        let original = try Data(contentsOf: prefsURL)
        let folder = prefsURL.deletingLastPathComponent()
        let partial = Data("copia a medias".utf8)
        try partial.write(to: folder.appendingPathComponent("preferences.v1.json"))
        let prefs = LibraryPreferences(fileURL: prefsURL)
        prefs.waitForPendingWrites()
        #expect(prefs.issue == nil && prefs.isFavorite(Self.entry("nunca.gb")))
        #expect(try Data(contentsOf: folder.appendingPathComponent("preferences.v1.json")) == partial)
        let copies = try FileManager.default.contentsOfDirectory(atPath: folder.path)
            .filter { $0.hasPrefix("preferences.v1-") && $0.hasSuffix(".json") }
        #expect(copies.count == 1)
        #expect(try Data(contentsOf: folder.appendingPathComponent(copies[0])) == original)
        #expect(LibraryPreferences.fileVersion(try Data(contentsOf: prefsURL)) == 2)
    }

    /// H10: si la copia del formato 1 no se puede escribir, no se migra en disco: el original queda
    /// intacto, se usa en memoria y se avisa.
    @Test func legacyMigrationIsBlockedWhenTheBackupFails() throws {
        try writePrefs(Self.v1Fixture)
        let original = try Data(contentsOf: prefsURL)
        let folder = prefsURL.deletingLastPathComponent()
        try FileManager.default.setAttributes([.posixPermissions: 0o555], ofItemAtPath: folder.path)
        defer { try? FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: folder.path) }
        let prefs = LibraryPreferences(fileURL: prefsURL)
        guard case .migrationBackupFailed = prefs.issue else {
            Issue.record("Se esperaba .migrationBackupFailed, no \(String(describing: prefs.issue))")
            return
        }
        #expect(prefs.isFavorite(Self.entry("nunca.gb")))                   // migrado en memoria
        prefs.toggleFavorite(Self.entry("otro.gb"))
        prefs.waitForPendingWrites()
        #expect(try Data(contentsOf: prefsURL) == original)
    }

    @Test func writeErrorsAreReportedOncePerStreak() throws {
        let blocker = dir.appendingPathComponent("Bloqueo")
        try Data("no soy carpeta".utf8).write(to: blocker)
        let prefs = LibraryPreferences(fileURL: blocker.appendingPathComponent("Library/preferences.json"))
        var reported: [LibraryPreferences.Issue] = []
        prefs.onIssue = { reported.append($0) }
        prefs.toggleFavorite(Self.entry("a.gb"))
        prefs.waitForPendingWrites()
        guard case .writeFailed = prefs.issue else {
            Issue.record("Se esperaba .writeFailed, no \(String(describing: prefs.issue))")
            return
        }
        prefs.setAlias("Otro", for: Self.entry("a.gb"))
        prefs.waitForPendingWrites()
        #expect(reported.count == 1)
    }

    @Test func interruptedFirstWriteIsRecoveredFromTheTemporary() throws {
        var data = LibraryPreferencesData()
        data.games["fp"] = { var m = GameMetadata(); m.favorite = true; return m }()
        try FileManager.default.createDirectory(at: prefsURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try JSONEncoder().encode(data).write(to: prefsURL.appendingPathExtension("tmp"))
        let prefs = LibraryPreferences(fileURL: prefsURL)
        #expect(prefs.issue == nil && prefs.data.games["fp"]?.favorite == true)
        #expect(FileManager.default.fileExists(atPath: prefsURL.path))
    }

    // MARK: Extremo a extremo con carpetas reales

    private func makeStore(_ cache: URL) -> LibraryStore {
        LibraryStore(storage: NoBookmarkStorage(), cacheURL: cache, knownDefaults: RomFingerprintTests.defaults())
    }

    /// Mover un ROM de carpeta conserva favorito, alias, oculto, ajustes, partida, estados y portada.
    @Test func movingARomBetweenFoldersKeepsEverything() async throws {
        let support = dir.appendingPathComponent("Support", isDirectory: true)
        let library = dir.appendingPathComponent("Juegos", isDirectory: true)
        let rom = LibraryScannerTests.rom(title: "MOVER")
        let oldROM = library.appendingPathComponent("Rojo/mover.gb")
        try FileManager.default.createDirectory(at: oldROM.deletingLastPathComponent(), withIntermediateDirectories: true)
        try rom.write(to: oldROM)
        try Data(repeating: 3, count: 8_192).write(to: library.appendingPathComponent("Rojo/mover.sav"))
        let core = try CoreBridge().loadROM(rom, unixTime: 0).fingerprint

        let prefsFile = support.appendingPathComponent("Library/preferences.json")
        let prefs = LibraryPreferences(fileURL: prefsFile)
        // Antes de conocer la huella (nunca abierto): favorito provisional por ruta.
        prefs.toggleFavorite(Self.entry("Rojo/mover.gb"))
        let store = makeStore(support.appendingPathComponent("Library/fingerprint-cache.json"))
        store.onFingerprintsResolved = { prefs.adopt($0) }
        store.choose(folder: library)
        await store.waitUntilIdle()
        let before = try #require(store.entries.first)
        #expect(before.fingerprint == core)
        #expect(prefs.data.pendingByPath.isEmpty && prefs.isFavorite(before))

        prefs.setAlias("Mi juego", for: before)
        prefs.setOverrides(GameOverrides(colorForGameBoy: true, compatPalette: 4), for: before)
        prefs.recordPlayed(id: before.id, fingerprint: core, at: Date(timeIntervalSince1970: 1_234))
        prefs.hide(before)
        // Partida, estados y portada ya iban por huella.
        let saves = support.appendingPathComponent("Saves", isDirectory: true)
        try FileManager.default.createDirectory(at: saves, withIntermediateDirectories: true)
        try SaveStore(directory: saves, fingerprint: core).save(Data(repeating: 7, count: 8_192))
        let states = support.appendingPathComponent("States", isDirectory: true)
        try StateStore(root: states, fingerprint: core).save(Data("estado".utf8), thumbnail: nil, to: .auto)
        let artwork = GameArtworkStore(directory: support.appendingPathComponent("Artwork", isDirectory: true))
        var pixels = [UInt32](repeating: 0xFF00_00FF, count: FrameBuffers.pixelCount)
        pixels[0] = 0xFFFF_FFFF
        #expect(artwork.save(fingerprint: core, pixels: pixels))
        artwork.waitForPendingWork()

        // Joel mueve el ROM y su .sav a una subcarpeta profunda.
        let newFolder = library.appendingPathComponent("Pokémon/2ª generación", isDirectory: true)
        try FileManager.default.createDirectory(at: newFolder, withIntermediateDirectories: true)
        try FileManager.default.moveItem(at: oldROM, to: newFolder.appendingPathComponent("mover.gb"))
        try FileManager.default.moveItem(at: library.appendingPathComponent("Rojo/mover.sav"),
                                         to: newFolder.appendingPathComponent("mover.sav"))
        store.refresh()
        await store.waitUntilIdle()
        let moved = try #require(store.entries.first)
        #expect(Self.nfc(moved.id) == Self.nfc("Pokémon/2ª generación/mover.gb"))
        #expect(moved.folderPath.map(Self.nfc) == ["Pokémon", "2ª generación"].map(Self.nfc))
        #expect(moved.fingerprint == core)

        prefs.waitForPendingWrites()
        for p in [prefs, LibraryPreferences(fileURL: prefsFile)] {
            #expect(p.isFavorite(moved))
            #expect(p.displayTitle(moved) == "Mi juego")
            #expect(p.isHidden(moved))
            #expect(p.overrides(for: moved) == GameOverrides(colorForGameBoy: true, compatPalette: 4))
            #expect(p.lastPlayed(moved) == Date(timeIntervalSince1970: 1_234))
        }
        let fp = try #require(moved.fingerprint)
        #expect(try SaveStore(directory: saves, fingerprint: fp).load() == Data(repeating: 7, count: 8_192))
        #expect(StateStore(root: states, fingerprint: fp).entries()[.auto] != nil)
        #expect(FileManager.default.fileExists(atPath: try #require(artwork.fileURL(for: fp)).path))
        // El espejo .sav sigue junto al ROM, en su carpeta nueva.
        #expect(moved.mirrorSaveDate != nil)
        #expect(SaveMirror(romURL: moved.url).url.deletingLastPathComponent().path
            == moved.url.deletingLastPathComponent().path)
    }

    /// Misma huella en dos carpetas: las dos copias lo saben, comparten metadatos y partida, y
    /// «Continuar» muestra una sola (la que se abrió).
    @Test func duplicatesShareMetadataAndShowOnce() async throws {
        let library = dir.appendingPathComponent("Juegos", isDirectory: true)
        let rom = LibraryScannerTests.rom(title: "COPIA")
        for path in ["A/copia.gb", "B/C/copia.gb"] {
            let url = library.appendingPathComponent(path)
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try rom.write(to: url)
        }
        try LibraryScannerTests.rom(title: "OTRO").write(to: library.appendingPathComponent("otro.gb"))
        let store = makeStore(dir.appendingPathComponent("cache.json"))
        store.choose(folder: library)
        await store.waitUntilIdle()
        let byID = Dictionary(uniqueKeysWithValues: store.entries.map { ($0.id, $0) })
        let a = try #require(byID["A/copia.gb"])
        let b = try #require(byID["B/C/copia.gb"])
        let otro = try #require(byID["otro.gb"])
        #expect(a.fingerprint != nil && a.fingerprint == b.fingerprint)
        #expect(a.duplicatePaths == ["B/C/copia.gb"] && b.duplicatePaths == ["A/copia.gb"])
        #expect(a.isDuplicate && !otro.isDuplicate)
        #expect(RomEntry.displayPath(a.duplicatePaths[0]) == "B › C › copia.gb")

        let prefs = LibraryPreferences(fileURL: nil)
        prefs.toggleFavorite(a)
        #expect(prefs.isFavorite(b))
        let fp = try #require(a.fingerprint)
        prefs.recordPlayed(id: b.id, fingerprint: fp, at: Date(timeIntervalSince1970: 99))
        #expect(prefs.recent(store.entries).map(\.id) == ["B/C/copia.gb"])
        // La partida local es la misma (por huella); cada copia tiene su espejo junto a ella.
        let saves = dir.appendingPathComponent("Saves")
        #expect(SaveStore(directory: saves, fingerprint: fp).saveURL
            == SaveStore(directory: saves, fingerprint: try #require(b.fingerprint)).saveURL)
        #expect(SaveMirror(romURL: a.url).url != SaveMirror(romURL: b.url).url)
    }

    /// Espejo de un duplicado (documentado en docs/guia/carpetas.md): se jugó la copia A y la
    /// partida local avanzó; al abrir la copia B, su `.sav` viejo pierde frente a la local, queda
    /// en backup y se reescribe. La partida más nueva nunca se pierde.
    @Test func staleMirrorOfADuplicateIsBackedUpAndRefreshed() throws {
        let saves = dir.appendingPathComponent("Saves", isDirectory: true)
        try FileManager.default.createDirectory(at: saves, withIntermediateDirectories: true)
        let store = SaveStore(directory: saves, fingerprint: "fp-dup")
        let older = Data(repeating: 1, count: 8_192)
        let newer = Data(repeating: 2, count: 8_192)
        let mirrorB = SaveMirror(romURL: dir.appendingPathComponent("B/copia.gb"))
        try FileManager.default.createDirectory(at: mirrorB.url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try older.write(to: mirrorB.url)
        try FileManager.default.setAttributes([.modificationDate: Date(timeIntervalSinceNow: -3_600)],
                                              ofItemAtPath: mirrorB.url.path)
        try store.save(newer)                                                // se jugó la copia A
        let outcome = try SaveOpening.prepare(store: store, mirror: mirrorB, snapshot: mirrorB.snapshot(),
                                              validSizes: [8_192])
        #expect(outcome.data == newer)
        #expect(try Data(contentsOf: store.backupURL(1)) == older)
        #expect(outcome.target?.mirror?.url == mirrorB.url)
        #expect(try store.load() == newer)
        // H1: además, el perdedor queda en una copia apartada que no rota; abrir otra vez no la repite.
        #expect(store.keptCopies().count == 1)
        #expect(try Data(contentsOf: try #require(store.keptCopies().first).url) == older)
        _ = try SaveOpening.prepare(store: store, mirror: mirrorB, snapshot: mirrorB.snapshot(), validSizes: [8_192])
        #expect(store.keptCopies().count == 1)
    }

    /// H1 (regla 6): la copia B de un duplicado tiene su propio `.sav` **más nuevo** con otra partida.
    /// Gana por fecha (como siempre), pero la partida local que pierde no se va con la rotación:
    /// tras 6 guardados sigue apartada y se puede restaurar.
    @Test func newerMirrorOfADuplicateKeepsTheLocalOutsideTheRotation() throws {
        let saves = dir.appendingPathComponent("Saves", isDirectory: true)
        try FileManager.default.createDirectory(at: saves, withIntermediateDirectories: true)
        let store = SaveStore(directory: saves, fingerprint: "fp-dup")
        let playA = Data(repeating: 0xA, count: 8_192)
        let playB = Data(repeating: 0xB, count: 8_192)
        try store.save(playA)                                                // partida de la copia A
        try FileManager.default.setAttributes([.modificationDate: Date(timeIntervalSinceNow: -3_600)],
                                              ofItemAtPath: store.saveURL.path)
        let mirrorB = SaveMirror(romURL: dir.appendingPathComponent("Copias/juego.gb"))
        try FileManager.default.createDirectory(at: mirrorB.url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try playB.write(to: mirrorB.url)                                     // otra partida, más nueva
        let outcome = try SaveOpening.prepare(store: store, mirror: mirrorB, snapshot: mirrorB.snapshot(),
                                              validSizes: [8_192])
        #expect(outcome.data == playB)
        #expect(try store.load() == playB)
        let kept = try #require(store.keptCopies().first)
        #expect(try Data(contentsOf: kept.url) == playA)
        #expect(kept.url.lastPathComponent.hasPrefix("fp-dup.mirror-"))
        for byte in UInt8(1)...6 { try store.save(Data(repeating: byte, count: 8_192)) }
        let inBackups = store.backups().contains { (try? Data(contentsOf: store.backupURL($0.index))) == playA }
        #expect(!inBackups)                                                  // la rotación ya lo perdió…
        #expect(try Data(contentsOf: kept.url) == playA)                     // …pero sigue apartada
        try store.restore(kept: kept)
        #expect(try store.load() == playA)
        #expect(try Data(contentsOf: store.backupURL(1)) == Data(repeating: 6, count: 8_192))
        #expect(FileManager.default.fileExists(atPath: kept.url.path))
    }

    /// H1: un `.sav` de otro juego con el mismo nombre que el ROM, más nuevo o más viejo: el que
    /// pierde siempre queda apartado. Un espejo que escribió PocketGB no crea copias apartadas.
    @Test func foreignSaveWithTheSameNameIsAlwaysKept() throws {
        let saves = dir.appendingPathComponent("Saves", isDirectory: true)
        try FileManager.default.createDirectory(at: saves, withIntermediateDirectories: true)
        let mine = Data(repeating: 1, count: 8_192)
        let foreign = Data(repeating: 2, count: 8_192)
        for (i, foreignIsNewer) in [true, false].enumerated() {
            let store = SaveStore(directory: saves, fingerprint: "fp-ajeno-\(i)")
            try store.save(mine)
            let mirror = SaveMirror(romURL: dir.appendingPathComponent("Juegos\(i)/Juego.gb"))
            try FileManager.default.createDirectory(at: mirror.url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try foreign.write(to: mirror.url)
            let older = Date(timeIntervalSinceNow: -3_600)
            try FileManager.default.setAttributes([.modificationDate: older],
                                                  ofItemAtPath: (foreignIsNewer ? store.saveURL : mirror.url).path)
            let outcome = try SaveOpening.prepare(store: store, mirror: mirror, snapshot: mirror.snapshot(),
                                                  validSizes: [8_192])
            #expect(outcome.data == (foreignIsNewer ? foreign : mine))
            let kept = store.keptCopies()
            #expect(kept.count == 1)
            #expect(try Data(contentsOf: try #require(kept.first).url) == (foreignIsNewer ? mine : foreign))
        }
        // Espejo reconocido como escritura propia: se resuelve como antes, sin copia apartada.
        let owned = SaveStore(directory: saves, fingerprint: "fp-propio")
        try owned.save(mine)
        let mirror = SaveMirror(romURL: dir.appendingPathComponent("Propio/Juego.gb"))
        try FileManager.default.createDirectory(at: mirror.url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try foreign.write(to: mirror.url)
        try owned.recordSuccessfulMirror(foreign, observedDate: mirror.modificationDate)
        _ = try SaveOpening.prepare(store: owned, mirror: mirror, snapshot: mirror.snapshot(), validSizes: [8_192])
        #expect(owned.keptCopies().isEmpty)
    }

    /// H12: el cableado de `AppState` (`LibraryIdentityWiring`): aviso al arrancar con preferencias
    /// apartadas, aviso de un fallo de escritura y metadatos provisionales unidos a la huella.
    @Test func wiringAdoptsMetadataAndReportsIssues() async throws {
        try writePrefs("{dañado")
        let prefs = LibraryPreferences(fileURL: prefsURL)
        let library = dir.appendingPathComponent("Juegos", isDirectory: true)
        try FileManager.default.createDirectory(at: library, withIntermediateDirectories: true)
        let rom = LibraryScannerTests.rom(title: "CABLEADO")
        try rom.write(to: library.appendingPathComponent("x.gb"))
        let store = makeStore(dir.appendingPathComponent("cache.json"))
        var alerts: [String] = []
        var resolved: [Set<String>] = []
        LibraryIdentityWiring.connect(library: store, prefs: prefs, alert: { title, _ in alerts.append(title) },
                                      fingerprintsResolved: { resolved.append($0) })
        #expect(alerts == ["Preferencias de la biblioteca dañadas"])

        prefs.toggleFavorite(Self.entry("x.gb"))                             // provisional, sin huella
        store.choose(folder: library)
        await store.waitUntilIdle()
        let fp = try #require(store.entries.first?.fingerprint)
        #expect(fp == (try CoreBridge().loadROM(rom, unixTime: 0).fingerprint))
        #expect(prefs.data.games[fp]?.favorite == true && prefs.data.pendingByPath.isEmpty)
        #expect(resolved.contains { $0.contains(fp) })

        // Un fallo de escritura posterior también llega como aviso.
        let blocker = dir.appendingPathComponent("Bloqueo")
        try Data("no soy carpeta".utf8).write(to: blocker)
        let failing = LibraryPreferences(fileURL: blocker.appendingPathComponent("Library/preferences.json"))
        LibraryIdentityWiring.connect(library: makeStore(dir.appendingPathComponent("cache2.json")), prefs: failing,
                                      alert: { title, _ in alerts.append(title) }, fingerprintsResolved: { _ in })
        failing.toggleFavorite(Self.entry("a.gb"))
        failing.waitForPendingWrites()
        #expect(alerts.last == "No se pudieron guardar las preferencias")
    }

    /// H7: con un juego abierto el cálculo de huellas se pausa; al cerrarlo se reanuda con lo pendiente.
    @Test func hashingPausesWhileAGameIsOpenAndResumes() async throws {
        let library = dir.appendingPathComponent("Juegos", isDirectory: true)
        try FileManager.default.createDirectory(at: library, withIntermediateDirectories: true)
        let rom = LibraryScannerTests.rom(title: "PAUSA")
        try rom.write(to: library.appendingPathComponent("p.gb"))
        let store = makeStore(dir.appendingPathComponent("cache.json"))
        store.setHashingPaused(true)
        store.choose(folder: library)
        await store.waitUntilIdle()
        #expect(store.entries.count == 1 && store.entries.first?.fingerprint == nil && !store.isHashing)
        store.setHashingPaused(false)
        await store.waitUntilIdle()
        #expect(store.entries.first?.fingerprint == RomFingerprint.compute(data: rom, console: .gameBoy))
        #expect(!store.isHashing)
    }

    @Test func learnedFingerprintFromTheCoreUpdatesTheEntry() async throws {
        let library = dir.appendingPathComponent("Juegos", isDirectory: true)
        try FileManager.default.createDirectory(at: library, withIntermediateDirectories: true)
        try Data("plist".utf8).write(to: library.appendingPathComponent(".nube.gb.icloud"))
        let store = makeStore(dir.appendingPathComponent("cache.json"))
        store.choose(folder: library)
        await store.waitUntilIdle()
        #expect(store.entries.first?.fingerprint == nil)                    // sin descargar: no se hashea
        store.learnFingerprint("fp-del-nucleo", forPath: "nube.gb")
        #expect(store.entries.first?.fingerprint == "fp-del-nucleo")
    }
}

import CryptoKit
import Foundation
import Testing
@testable import PocketGB

/// ROMs de prueba libres (dmg-acid2, cgb-acid2, arm.gba). Se buscan en `FIXTURE_DIR` (lo define
/// `tools/ios-screenshots.sh` vía `TEST_RUNNER_FIXTURE_DIR`) y, si no, en el repo. Nunca en el repo.
enum RomFixtures {
    static let files = ["dmg-acid2.gb", "cgb-acid2.gbc", "arm.gba"]

    static func url(_ name: String) -> URL? {
        var candidates: [URL] = []
        if let dir = ProcessInfo.processInfo.environment["FIXTURE_DIR"], !dir.isEmpty {
            candidates.append(URL(fileURLWithPath: dir).appendingPathComponent(name))
        }
        let repo = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent()
        switch name {
        case "dmg-acid2.gb": candidates.append(repo.appendingPathComponent("core/tests/roms/dmg-acid2/dmg-acid2.gb"))
        case "cgb-acid2.gbc": candidates.append(repo.appendingPathComponent("core/tests/roms/cgb-acid2/cgb-acid2.gbc"))
        case "arm.gba": candidates.append(repo.appendingPathComponent("gba/tests/roms/gba-tests/arm/arm.gba"))
        default: break
        }
        return candidates.first { FileManager.default.isReadableFile(atPath: $0.path) }
    }

    static var available: Bool { files.allSatisfy { url($0) != nil } }
}

/// N1a: la huella calculada sin abrir el juego es idéntica a la del núcleo, y la caché
/// (ruta, tamaño, fecha) → huella se invalida al cambiar el archivo.
@MainActor
struct RomFingerprintTests {
    let dir: URL

    init() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    private func write(_ data: Data, _ name: String) throws -> URL {
        let url = dir.appendingPathComponent(name)
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: url)
        return url
    }

    private static func coreFingerprint(_ data: Data, console: Console) throws -> String {
        switch console {
        case .gameBoy: try CoreBridge().loadROM(data, unixTime: 0).fingerprint
        case .gameBoyAdvance: try GBACoreBridge().loadROM(data, bios: nil, unixTime: 0).fingerprint
        }
    }

    @Test func gameBoyFingerprintMatchesTheCore() throws {
        let rom = LibraryScannerTests.rom(title: "HUELLA")
        let url = try write(rom, "huella.gb")
        let core = try Self.coreFingerprint(rom, console: .gameBoy)
        #expect(core.count == 32)
        #expect(RomFingerprint.compute(data: rom, console: .gameBoy) == core)
        #expect(try RomFingerprint.compute(url: url, console: .gameBoy) == core)
    }

    /// El núcleo de Game Boy solo hashea lo que declara la cabecera: un volcado con relleno al final
    /// tiene la misma huella que el ROM recortado (no la del archivo entero).
    @Test func paddedGameBoyDumpHashesOnlyTheDeclaredSize() throws {
        let padded = LibraryScannerTests.rom(title: "RELLENO", size: 0xC000)   // cabecera: 32 KiB
        let url = try write(padded, "relleno.gb")
        let core = try Self.coreFingerprint(padded, console: .gameBoy)
        let whole = RomFingerprint.hex(SHA256.hash(data: padded))
        let declared = RomFingerprint.hex(SHA256.hash(data: padded.prefix(0x8000)))
        #expect(core == declared && core != whole)
        #expect(try RomFingerprint.compute(url: url, console: .gameBoy) == core)
    }

    /// Lo que el núcleo rechaza antes de hashear no tiene huella.
    @Test func romsTheCoreRejectsHaveNoFingerprint() throws {
        var truncated = LibraryScannerTests.rom(title: "CORTO")
        truncated[0x148] = 1                                                 // declara 64 KiB, mide 32
        #expect(throws: CoreError.self) { try Self.coreFingerprint(truncated, console: .gameBoy) }
        #expect(RomFingerprint.compute(data: truncated, console: .gameBoy) == nil)
        #expect(RomFingerprint.compute(data: Data(count: 0x100), console: .gameBoy) == nil)
        #expect(RomFingerprint.compute(data: Data(count: 0x400), console: .gameBoyAdvance) == nil)   // sin 0x96
    }

    @Test func gameBoyAdvanceFingerprintMatchesTheCore() throws {
        let rom = GBATests.rom()
        let url = try write(rom, "juego.gba")
        let core = try Self.coreFingerprint(rom, console: .gameBoyAdvance)
        #expect(RomFingerprint.compute(data: rom, console: .gameBoyAdvance) == core)
        #expect(try RomFingerprint.compute(url: url, console: .gameBoyAdvance) == core)
        #expect(core == RomFingerprint.hex(SHA256.hash(data: rom)))         // GBA: el archivo entero
    }

    /// Las ROMs de prueba del catálogo: misma huella que `CoreBridge`/`GBACoreBridge`.
    @Test(.enabled(if: RomFixtures.available, "Sin ROMs de prueba (tools/fetch-test-roms.sh o FIXTURE_DIR)"))
    func testROMFixturesMatchTheCore() throws {
        for name in RomFixtures.files {
            let url = try #require(RomFixtures.url(name))
            let console = Console(fileName: name)
            let data = try Data(contentsOf: url)
            let core = try Self.coreFingerprint(data, console: console)
            let computed = try RomFingerprint.compute(url: url, console: console)
            #expect(computed == core, "\(name)")
            print("N1a huella \(name): app=\(computed ?? "nil") núcleo=\(core)")
        }
    }

    @Test func iCloudPlaceholderIsNeverRead() throws {
        _ = try write(Data("plist".utf8), ".Gamma.gb.icloud")
        let real = dir.appendingPathComponent("Gamma.gb")
        #expect(!RomFingerprint.isLocallyAvailable(real))
        #expect(try RomFingerprint.compute(url: real, console: .gameBoy) == nil)
    }

    // MARK: Caché

    @Test func cacheLookupIsInvalidatedBySizeOrDate() {
        var cache = FingerprintCacheData()
        let date = Date(timeIntervalSince1970: 1_000)
        cache.items["a.gb"] = .init(size: 32_768, modified: date, fingerprint: "fp-a")
        #expect(cache.lookup(path: "a.gb", size: 32_768, modified: date) == .verified("fp-a"))
        #expect(cache.lookup(path: "a.gb", size: 65_536, modified: date) == .stale("fp-a"))
        #expect(cache.lookup(path: "a.gb", size: 32_768, modified: date.addingTimeInterval(1)) == .stale("fp-a"))
        #expect(cache.lookup(path: "a.gb", size: 32_768, modified: nil) == .stale("fp-a"))
        #expect(cache.lookup(path: "b.gb", size: 32_768, modified: date) == .missing)
    }

    @Test func resolveQueuesOnlyPlayableFilesWithoutAVerifiedFingerprint() {
        let date = Date(timeIntervalSince1970: 2_000)
        func entry(_ id: String, cloud: RomEntry.CloudState = .current) -> RomEntry {
            RomEntry(id: id, url: URL(fileURLWithPath: "/demo/\(id)"), fileName: id, title: id, isColor: false,
                     sizeBytes: 32_768, headerChecksumOK: true, cloud: cloud, problem: nil,
                     mirrorSaveDate: nil, modificationDate: date)
        }
        var cache = FingerprintCacheData()
        cache.items["ok.gb"] = .init(size: 32_768, modified: date, fingerprint: "fp-ok")
        cache.items["cambiado.gb"] = .init(size: 16_384, modified: date, fingerprint: "fp-viejo")
        cache.items["nube.gb"] = .init(size: 1, modified: nil, fingerprint: "fp-nube")
        let r = LibraryIdentity.resolve([entry("ok.gb"), entry("cambiado.gb"), entry("nuevo.gb"),
                                         entry("nube.gb", cloud: .notDownloaded)], cache: cache)
        let fps = Dictionary(uniqueKeysWithValues: r.entries.map { ($0.id, $0.fingerprint) })
        #expect(fps["ok.gb"] == "fp-ok" && fps["cambiado.gb"] == "fp-viejo" && fps["nube.gb"] == "fp-nube")
        #expect(fps["nuevo.gb"] == .some(nil))
        #expect(r.verified == ["ok.gb": "fp-ok"])
        // Nunca se pide hashear un archivo sin descargar.
        #expect(r.jobs.map(\.path).sorted() == ["cambiado.gb", "nuevo.gb"])
    }

    /// Extremo a extremo con `LibraryStore`: la caché se persiste, se reutiliza en el siguiente
    /// arranque y se invalida al cambiar el archivo (tamaño o fecha).
    @Test func storePersistsAndInvalidatesTheCache() async throws {
        let library = dir.appendingPathComponent("Juegos", isDirectory: true)
        let cacheURL = dir.appendingPathComponent("Library/fingerprint-cache.json")
        let rom = LibraryScannerTests.rom(title: "CACHE")
        let romURL = library.appendingPathComponent("Sub/cache.gb")
        try FileManager.default.createDirectory(at: romURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try rom.write(to: romURL)
        let expected = try Self.coreFingerprint(rom, console: .gameBoy)

        let store = LibraryStore(storage: NoBookmarkStorage(), cacheURL: cacheURL, knownDefaults: Self.defaults())
        store.choose(folder: library)
        await store.waitUntilIdle()
        #expect(store.entries.first?.fingerprint == expected)
        let saved = FingerprintCacheData.load(cacheURL)
        #expect(saved.items["Sub/cache.gb"]?.fingerprint == expected)
        #expect(saved.items["Sub/cache.gb"]?.size == rom.count)

        // Siguiente arranque: la huella sale de la caché verificada, sin volver a hashear.
        let reopened = LibraryStore(storage: NoBookmarkStorage(), cacheURL: cacheURL, knownDefaults: Self.defaults())
        var resolvedFromCache: [String: String] = [:]
        reopened.onFingerprintsResolved = { resolvedFromCache.merge($0) { _, b in b } }
        reopened.choose(folder: library)
        await reopened.waitUntilIdle()
        #expect(resolvedFromCache == ["Sub/cache.gb": expected])
        #expect(!reopened.isHashing)

        // Otro contenido (tamaño y fecha distintos) en la misma ruta: se recalcula.
        let other = LibraryScannerTests.rom(title: "OTRO", size: 0x10000)
        var patched = other
        patched[0x148] = 1                                                   // 64 KiB declarados
        var x: UInt8 = 0
        for i in 0x134...0x14C { x = x &- patched[i] &- 1 }
        patched[0x14D] = x
        try patched.write(to: romURL)
        try FileManager.default.setAttributes([.modificationDate: Date(timeIntervalSinceNow: 60)],
                                              ofItemAtPath: romURL.path)
        let changed = try Self.coreFingerprint(patched, console: .gameBoy)
        #expect(changed != expected)
        reopened.refresh()
        await reopened.waitUntilIdle()
        #expect(reopened.entries.first?.fingerprint == changed)
        #expect(FingerprintCacheData.load(cacheURL).items["Sub/cache.gb"]?.fingerprint == changed)
    }

    @Test func otherLibraryFolderResetsTheCache() async throws {
        let cacheURL = dir.appendingPathComponent("cache.json")
        let a = dir.appendingPathComponent("A", isDirectory: true)
        let b = dir.appendingPathComponent("B", isDirectory: true)
        for folder in [a, b] {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        }
        try LibraryScannerTests.rom(title: "EN A").write(to: a.appendingPathComponent("x.gb"))
        try LibraryScannerTests.rom(title: "EN B").write(to: b.appendingPathComponent("x.gb"))
        let store = LibraryStore(storage: NoBookmarkStorage(), cacheURL: cacheURL, knownDefaults: Self.defaults())
        store.choose(folder: a)
        await store.waitUntilIdle()
        let inA = try #require(store.entries.first?.fingerprint)
        store.choose(folder: b)
        await store.waitUntilIdle()
        let inB = try #require(store.entries.first?.fingerprint)
        #expect(inA != inB)
        #expect(inB == RomFingerprint.compute(data: LibraryScannerTests.rom(title: "EN B"), console: .gameBoy))
    }

    static func defaults() -> UserDefaults {
        UserDefaults(suiteName: "pocketgb-tests-\(UUID().uuidString)") ?? .standard
    }
}

/// Sin bookmark persistido (los tests eligen la carpeta en cada caso).
struct NoBookmarkStorage: BookmarkStorage {
    func load() -> Data? { nil }
    func save(_ data: Data) {}
}

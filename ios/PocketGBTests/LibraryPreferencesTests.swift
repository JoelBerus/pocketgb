import Foundation
import Testing
@testable import PocketGB

/// D3: favoritos, recientes, ocultos, filtros y portadas (D-README §5, criterios CI).
/// Todo en un directorio temporal propio; nunca dentro del repo.
@MainActor
struct LibraryPreferencesTests {
    let dir: URL

    init() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    private var prefsURL: URL { dir.appendingPathComponent("Library/preferences.json") }

    static func entry(_ id: String, _ title: String, color: Bool, url: URL? = nil) -> RomEntry {
        RomEntry(id: id, url: url ?? URL(fileURLWithPath: "/demo/\(id)"), fileName: (id as NSString).lastPathComponent,
                 title: title, isColor: color, sizeBytes: 32_768, headerChecksumOK: true, cloud: .current,
                 problem: nil, mirrorSaveDate: nil)
    }

    let entries = [
        Self.entry("b.gb", "Beta", color: false),
        Self.entry("a.gbc", "Alfa", color: true),
        Self.entry("sub/c.gb", "Canción", color: false),
        Self.entry("d.gbc", "Delta", color: true),
    ]

    @Test func favoritesRecentAndLayoutPersistAcrossLaunches() {
        let prefs = LibraryPreferences(fileURL: prefsURL)
        prefs.toggleFavorite(entries[0])
        prefs.recordPlayed(id: "a.gbc", fingerprint: "fa", at: Date(timeIntervalSince1970: 100))
        prefs.setLayout(.list)
        prefs.setSort(.recent)
        prefs.waitForPendingWrites()

        let reopened = LibraryPreferences(fileURL: prefsURL)
        #expect(reopened.isFavorite(entries[0]))
        #expect(!reopened.isFavorite(entries[1]))
        #expect(reopened.lastPlayed(entries[1]) == Date(timeIntervalSince1970: 100))
        #expect(reopened.fingerprint(of: entries[1]) == "fa")
        #expect(reopened.data.layout == .list)
        #expect(reopened.data.sort == .recent)
    }

    @Test func hidingPersistsTheFingerprintAndNeverTouchesFiles() throws {
        // ROM y partida reales en disco: ocultar no debe tocarlos.
        let rom = dir.appendingPathComponent("juego.gb")
        let romData = LibraryScannerTests.rom(title: "JUEGO")
        try romData.write(to: rom)
        let sav = dir.appendingPathComponent("juego.sav")
        try Data([1, 2, 3]).write(to: sav)
        let game = Self.entry("juego.gb", "JUEGO", color: false, url: rom)
        let before = try FileManager.default.contentsOfDirectory(atPath: dir.path).sorted()

        let prefs = LibraryPreferences(fileURL: prefsURL)
        prefs.recordPlayed(id: game.id, fingerprint: "huella-juego", at: Date())
        prefs.hide(game)
        prefs.waitForPendingWrites()

        let reopened = LibraryPreferences(fileURL: prefsURL)
        #expect(reopened.data.hiddenFingerprints == ["huella-juego"])
        #expect(reopened.isHidden(game))
        // Si el ROM se mueve de subcarpeta, sigue oculto por su huella.
        reopened.recordPlayed(id: "otra/juego.gb", fingerprint: "huella-juego", at: Date())
        #expect(reopened.isHidden(Self.entry("otra/juego.gb", "JUEGO", color: false)))

        #expect(try Data(contentsOf: rom) == romData)
        #expect(try Data(contentsOf: sav) == Data([1, 2, 3]))
        let after = try FileManager.default.contentsOfDirectory(atPath: dir.path).sorted()
        // Solo aparece la carpeta de preferencias de este test; nada más cambia.
        #expect(after == (before + ["Library"]).sorted())

        reopened.unhide(game)
        #expect(!reopened.isHidden(game))
    }

    @Test func neverOpenedGameIsHiddenByPath() {
        let prefs = LibraryPreferences(fileURL: nil)
        prefs.hide(entries[2])
        #expect(prefs.data.hiddenPaths == ["sub/c.gb"])
        #expect(prefs.visible(entries, filter: .all, query: "").map(\.id) == ["a.gbc", "b.gb", "d.gbc"])
    }

    @Test func filtersAreAllGBGBCAndFavorites() {
        #expect(LibraryFilter.allCases.map(\.title) == ["Todos", "GB", "GBC", "GBA", "Favoritos"])
        let prefs = LibraryPreferences(fileURL: nil)
        prefs.toggleFavorite(entries[3])
        #expect(prefs.visible(entries, filter: .all, query: "").map(\.title) == ["Alfa", "Beta", "Canción", "Delta"])
        #expect(prefs.visible(entries, filter: .gb, query: "").map(\.title) == ["Beta", "Canción"])
        #expect(prefs.visible(entries, filter: .gbc, query: "").map(\.title) == ["Alfa", "Delta"])
        #expect(prefs.visible(entries, filter: .favorites, query: "").map(\.title) == ["Delta"])
    }

    @Test func searchIsLiveCaseAndAccentInsensitiveWithinTheFilter() {
        let prefs = LibraryPreferences(fileURL: nil)
        #expect(prefs.visible(entries, filter: .all, query: "cancion").map(\.id) == ["sub/c.gb"])
        #expect(prefs.visible(entries, filter: .all, query: "  ALF ").map(\.id) == ["a.gbc"])
        #expect(prefs.visible(entries, filter: .gb, query: "alfa").isEmpty)
        #expect(prefs.visible(entries, filter: .all, query: "d.gbc").map(\.id) == ["d.gbc"])
    }

    @Test func recentSortAndRecentRowSkipHiddenGames() {
        let prefs = LibraryPreferences(fileURL: nil)
        prefs.recordPlayed(id: "b.gb", fingerprint: "fb", at: Date(timeIntervalSince1970: 10))
        prefs.recordPlayed(id: "d.gbc", fingerprint: "fd", at: Date(timeIntervalSince1970: 30))
        prefs.recordPlayed(id: "a.gbc", fingerprint: "fa", at: Date(timeIntervalSince1970: 20))
        prefs.hide(entries[1])
        prefs.setSort(.recent)
        #expect(prefs.visible(entries, filter: .all, query: "").map(\.id) == ["d.gbc", "b.gb", "sub/c.gb"])
        #expect(prefs.recent(entries).map(\.id) == ["d.gbc", "b.gb"])
    }

    @Test func oldPreferencesFileWithMissingKeysStillLoads() throws {
        try FileManager.default.createDirectory(at: prefsURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data(#"{"favorites":["b.gb"]}"#.utf8).write(to: prefsURL)
        let prefs = LibraryPreferences(fileURL: prefsURL)
        #expect(prefs.isFavorite(entries[0]))
        #expect(prefs.data.layout == .grid)
    }

    // MARK: Portadas

    @Test func placeholderIsDeterministicPerSeed() {
        #expect(PlaceholderSeed.value("abc") == PlaceholderSeed.value("abc"))
        #expect(PlaceholderSeed.glyph("huella-1") == PlaceholderSeed.glyph("huella-1"))
        // FNV-1a conocido: la semilla no depende del proceso (a diferencia de hashValue).
        #expect(PlaceholderSeed.value("") == 0xCBF2_9CE4_8422_2325)
        #expect(PlaceholderSeed.value("a") == 0xAF63_DC4C_8601_EC8C)
        #expect((0..<4).contains(PlaceholderSeed.colorIndex("x")))
        #expect(PlaceholderSeed.initials("DMG-ACID2") == "DA")
        #expect(PlaceholderSeed.initials("") == "?")
        let seeds = (0..<32).map { "juego-\($0)" }
        #expect(Set(seeds.map(PlaceholderSeed.colorIndex)).count == 4)
    }

    @Test func artworkIsSavedAsPNGAndReloaded() async throws {
        let artworkDir = dir.appendingPathComponent("Artwork", isDirectory: true)
        let store = GameArtworkStore(directory: artworkDir)
        var pixels = [UInt32](repeating: 0xFF00_00FF, count: FrameBuffers.pixelCount)   // rojo
        pixels[0] = 0xFFFF_FFFF
        #expect(store.save(fingerprint: "f1", pixels: pixels))
        store.waitForPendingWork()
        let png = try Data(contentsOf: artworkDir.appendingPathComponent("f1.png"))
        #expect(png.starts(with: [0x89, 0x50, 0x4E, 0x47]))

        let fresh = GameArtworkStore(directory: artworkDir)
        #expect(fresh.image(for: "f1") == nil)
        fresh.load("f1")
        fresh.waitForPendingWork()
        for _ in 0..<50 where fresh.image(for: "f1") == nil {
            try await Task.sleep(for: .milliseconds(20))
        }
        let image = try #require(fresh.image(for: "f1"))
        #expect(image.size.width == 160 && image.size.height == 144)
    }

    @Test func blankFrameIsNotArtwork() {
        let store = GameArtworkStore(directory: nil)
        let black = [UInt32](repeating: 0xFF00_0000, count: FrameBuffers.pixelCount)
        #expect(!store.save(fingerprint: "f", pixels: black))
        #expect(store.image(for: "f") == nil)
        #expect(!store.save(fingerprint: "f", pixels: [1, 2, 3]))
    }

    @Test func frameBytesMapToRGBA() throws {
        var pixels = [UInt32](repeating: 0xFF0F_BC9B, count: FrameBuffers.pixelCount)   // #9BBC0F
        pixels[1] = 0
        let image = try #require(GameArtworkStore.makeImage(pixels))
        let provider = try #require(image.dataProvider?.data as Data?)
        #expect(Array(provider.prefix(4)) == [0x9B, 0xBC, 0x0F, 0xFF])
    }
}

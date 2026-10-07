import Foundation
import Testing
@testable import PocketGB

/// N1b: escaneo recursivo de la carpeta con nombres reservados (ND11) sobre un árbol sintético
/// real en un directorio temporal. ROMs sintéticos generados aquí, nunca ROMs reales.
@MainActor
struct LibraryFoldersTests {
    let dir: URL

    init() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    @discardableResult
    private func write(_ data: Data, _ path: String) throws -> URL {
        let url = dir.appendingPathComponent(path)
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: url)
        return url
    }

    private static func nfc(_ s: String) -> String { s.precomposedStringWithCanonicalMapping }

    @Test func syntheticTreeHonoursDepthAndReservedNames() throws {
        let rom = LibraryScannerTests.rom(title: "JUEGO")
        try write(rom, "raiz.gb")
        try write(rom, "Pokémon/1ª generación/rojo.gb")
        try write(Data(count: 8_192), "Pokémon/1ª generación/rojo.sav")          // espejo junto al ROM
        try write(rom, "A/B/C/D/E/cinco.gb")                                     // 5 niveles: se lee
        try write(rom, "A/B/C/D/E/F/seis.gb")                                    // 6 niveles: demasiado profundo
        try write(rom, ".oculta/x.gb")
        try write(rom, "A/.oculta/y.gb")
        try write(rom, "_apartada/z.gb")
        try write(rom, "A/_Revisar/w.gb")
        try write(rom, "PocketGB/Intercambio/p.gb")                              // de la app (raíz)
        try write(rom, "PocketGB/Exportados/q.gba")
        try write(rom, "A/PocketGB/dentro.gb")                                   // solo es reservada en la raíz
        try write(Data("plist".utf8), "Kirby/.kirby.gba.icloud")                // placeholder de iCloud
        try write(Data("hola".utf8), "A/notas.txt")
        try write(Data("PK".utf8), "A/comprimido.zip")                           // .zip no se lee
        try write(Data(count: 0x100), "Errores/corto.gbc")                       // error por archivo, como hoy

        let entries = LibraryScanner.scan(folder: dir)
        let byID = Dictionary(uniqueKeysWithValues: entries.map { (Self.nfc($0.id), $0) })
        let expected = [
            "raiz.gb", "Pokémon/1ª generación/rojo.gb", "A/B/C/D/E/cinco.gb", "A/PocketGB/dentro.gb",
            "Kirby/kirby.gba", "Errores/corto.gbc",
        ].map(Self.nfc)
        #expect(Set(byID.keys) == Set(expected))

        #expect(byID[Self.nfc("raiz.gb")]?.folderPath == [])
        let rojo = try #require(byID[Self.nfc("Pokémon/1ª generación/rojo.gb")])
        #expect(rojo.folderPath.map(Self.nfc) == ["Pokémon", "1ª generación"].map(Self.nfc))
        #expect(Self.nfc(rojo.locationText) == Self.nfc("Pokémon › 1ª generación · rojo.gb"))
        #expect(rojo.mirrorSaveDate != nil)
        #expect(Self.nfc(SaveMirror(romURL: rojo.url).url.path)
            == Self.nfc(dir.appendingPathComponent("Pokémon/1ª generación/rojo.sav").path))
        #expect(byID["A/B/C/D/E/cinco.gb"]?.folderPath == ["A", "B", "C", "D", "E"])
        #expect(byID["A/PocketGB/dentro.gb"]?.folderPath == ["A", "PocketGB"])
        let kirby = try #require(byID["Kirby/kirby.gba"])
        #expect(kirby.cloud == .notDownloaded && kirby.folderPath == ["Kirby"] && kirby.console == .gameBoyAdvance)
        #expect(byID["Errores/corto.gbc"]?.problem == .invalidHeader)
        #expect(LibraryScanner.maxFolderDepth == 5)
    }

    @Test func reservedFolderRules() {
        #expect(LibraryScanner.isReservedFolder(".git", atRoot: false))
        #expect(LibraryScanner.isReservedFolder("_Revisar", atRoot: true))
        #expect(LibraryScanner.isReservedFolder("_Revisar", atRoot: false))
        #expect(LibraryScanner.isReservedFolder("PocketGB", atRoot: true))
        #expect(LibraryScanner.isReservedFolder("pocketgb", atRoot: true))   // sin distinguir mayúsculas
        #expect(!LibraryScanner.isReservedFolder("PocketGB", atRoot: false))
        #expect(!LibraryScanner.isReservedFolder("Pokémon", atRoot: true))
    }

    @Test func entryCapStopsTheScanDeterministically() throws {
        let rom = LibraryScannerTests.rom(title: "X")
        for name in ["a", "b", "c", "d", "e", "f"] { try write(rom, "Sub/\(name).gb") }
        let first = LibraryScanner.scanResult(folder: dir, limit: 4)
        #expect(first.limitReached && first.entries.count == 4)
        let again = LibraryScanner.scanResult(folder: dir, limit: 4)
        #expect(Set(again.entries.map(\.id)) == Set(first.entries.map(\.id)))
        #expect(Set(first.entries.map(\.id)) == ["Sub/a.gb", "Sub/b.gb", "Sub/c.gb", "Sub/d.gb"])
        let all = LibraryScanner.scanResult(folder: dir)
        #expect(!all.limitReached && all.entries.count == 6)
        #expect(LibraryScanner.maxEntries == 5_000)
    }

    @Test func symlinkedFoldersAreNotFollowed() throws {
        try write(LibraryScannerTests.rom(title: "X"), "A/x.gb")
        try FileManager.default.createSymbolicLink(at: dir.appendingPathComponent("Enlace"),
                                                   withDestinationURL: dir.appendingPathComponent("A"))
        #expect(LibraryScanner.scan(folder: dir).map(\.id) == ["A/x.gb"])
    }

    /// Búsqueda, filtros, favoritos y «Continuar» siguen funcionando con juegos en subcarpetas profundas.
    @Test func searchFiltersFavoritesAndRecentWorkInDeepFolders() throws {
        try write(LibraryScannerTests.rom(title: "PROFUNDO"), "A/B/C/D/E/profundo.gb")
        try write(LibraryScannerTests.rom(title: "COLOR", color: true), "Color/c.gbc")
        let entries = LibraryScanner.scan(folder: dir)
        let deep = try #require(entries.first { $0.id == "A/B/C/D/E/profundo.gb" })
        let prefs = LibraryPreferences(fileURL: nil)
        prefs.toggleFavorite(deep)
        prefs.recordPlayed(id: deep.id, fingerprint: "fp-profundo", at: Date(timeIntervalSince1970: 50))
        #expect(prefs.visible(entries, filter: .all, query: "profundo").map(\.id) == [deep.id])
        #expect(prefs.visible(entries, filter: .gb, query: "").map(\.id) == [deep.id])
        #expect(prefs.visible(entries, filter: .favorites, query: "").map(\.id) == [deep.id])
        #expect(prefs.recent(entries).map(\.id) == [deep.id])
    }
}

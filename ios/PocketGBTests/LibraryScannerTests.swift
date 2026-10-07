import Foundation
import Testing
@testable import PocketGB

/// D2: escaneo de la carpeta (docs/04 §Biblioteca, D-README §4) sobre un directorio
/// temporal con ROMs sintéticos generados aquí (nunca ROMs reales ni en el repo).
struct LibraryScannerTests {
    let dir: URL

    init() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    /// Cabecera mínima de Game Boy con título y checksum correctos.
    static func rom(title: String, color: Bool = false, size: Int = 0x8000) -> Data {
        var bytes = [UInt8](repeating: 0, count: size)
        for (i, c) in title.utf8.prefix(15).enumerated() { bytes[0x134 + i] = c }
        if color { bytes[0x143] = 0x80 }
        var x: UInt8 = 0
        for i in 0x134...0x14C { x = x &- bytes[i] &- 1 }
        bytes[0x14D] = x
        return Data(bytes)
    }

    private func write(_ data: Data, _ path: String) throws -> URL {
        let url = dir.appendingPathComponent(path)
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: url)
        return url
    }

    @Test func findsOnlyGameBoyFilesInSubfolders() throws {
        _ = try write(Self.rom(title: "ALPHA"), "alpha.gb")
        _ = try write(Self.rom(title: "BETA", color: true), "Sub/beta.GBC")
        _ = try write(Self.rom(title: "DEEP"), "Sub/Deeper/deep.gb")     // N1b: ya se lee (≤ 5 niveles)
        _ = try write(Data("hola".utf8), "notas.txt")
        _ = try write(Self.rom(title: "HIDDEN"), ".oculto.gb")
        let entries = LibraryScanner.scan(folder: dir)
        #expect(entries.map(\.id).sorted() == ["Sub/Deeper/deep.gb", "Sub/beta.GBC", "alpha.gb"])
        let beta = try #require(entries.first { $0.id == "Sub/beta.GBC" })
        #expect(beta.title == "BETA" && beta.isColor && beta.headerChecksumOK && beta.isPlayable)
        #expect(beta.subfolder == "Sub")
    }

    @Test func rejectsTooLargeAndInvalidFiles() throws {
        let big = dir.appendingPathComponent("grande.gb")
        FileManager.default.createFile(atPath: big.path, contents: nil)
        let handle = try FileHandle(forWritingTo: big)
        try handle.truncate(atOffset: UInt64(LibraryScanner.maxROMBytes + 1))   // disperso: no ocupa disco
        try handle.close()
        _ = try write(Data(repeating: 0, count: 0x100), "corto.gbc")
        let entries = LibraryScanner.scan(folder: dir)
        #expect(entries.first { $0.id == "grande.gb" }?.problem == .tooLarge)
        #expect(entries.first { $0.id == "corto.gbc" }?.problem == .invalidHeader)
        #expect(entries.allSatisfy { !$0.isPlayable })
    }

    @Test func iCloudPlaceholderIsListedAsNotDownloaded() throws {
        _ = try write(Data("plist".utf8), ".Gamma.gb.icloud")
        let entries = LibraryScanner.scan(folder: dir)
        let gamma = try #require(entries.first)
        #expect(gamma.id == "Gamma.gb" && gamma.cloud == .notDownloaded && !gamma.isPlayable)
        #expect(gamma.url.lastPathComponent == "Gamma.gb")
    }

    @Test func scanningDoesNotModifyROMs() throws {
        let url = try write(Self.rom(title: "ALPHA"), "alpha.gb")
        let before = try Data(contentsOf: url)
        let date = try FileManager.default.attributesOfItem(atPath: url.path)[.modificationDate] as? Date
        _ = LibraryScanner.scan(folder: dir)
        _ = try LibraryScanner.readROM(url)
        #expect(try Data(contentsOf: url) == before)
        #expect(try FileManager.default.attributesOfItem(atPath: url.path)[.modificationDate] as? Date == date)
    }

    @Test func mirrorSaveDateIsReported() throws {
        let url = try write(Self.rom(title: "ALPHA"), "alpha.gb")
        _ = try write(Data(count: 8192), "alpha.sav")
        let entry = try #require(LibraryScanner.scan(folder: dir).first)
        #expect(entry.mirrorSaveDate != nil)
        #expect(SaveMirror(romURL: url).url.lastPathComponent == "alpha.sav")
    }

    @Test func headerTitleRules() {
        #expect(RomHeader.parse(Self.rom(title: "POCKET")) == .init(title: "POCKET", isColor: false, checksumOK: true))
        #expect(RomHeader.parse(Data(count: 0x14F)) == nil)
        var bad = Self.rom(title: "X")
        bad[0x14D] &+= 1
        #expect(RomHeader.parse(bad)?.checksumOK == false)
    }
}

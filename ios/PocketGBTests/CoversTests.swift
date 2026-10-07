import CoreGraphics
import Foundation
import ImageIO
import Testing
import UIKit
import UniformTypeIdentifiers
@testable import PocketGB

/// N5 · portadas (paridad con Android, `CoversTest`/`CoverDecoderTest`): prioridad de fuentes, imagen junto
/// al ROM, imágenes hostiles (truncada, dimensiones enormes, extensión falsa, demasiado grande), ajustes por
/// dispositivo, importar/fijar/soltar, caché de la carpeta, almacenamiento y el carril «Continuar jugando».
@MainActor
struct CoversTests {
    let dir: URL

    init() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("covers-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    // MARK: Utilidades

    static func image(width: Int, height: Int) -> CGImage {
        let ctx = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                            space: CGColorSpace(name: CGColorSpace.sRGB)!,
                            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        ctx.setFillColor(red: 0.2, green: 0.4, blue: 0.8, alpha: 1)
        ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
        ctx.setFillColor(red: 1, green: 0.8, blue: 0.2, alpha: 1)
        ctx.fill(CGRect(x: 0, y: 0, width: width / 2, height: height / 2))
        return ctx.makeImage()!
    }

    static func encoded(_ type: UTType, width: Int, height: Int) -> Data {
        let out = NSMutableData()
        let dest = CGImageDestinationCreateWithData(out as CFMutableData, type.identifier as CFString, 1, nil)!
        CGImageDestinationAddImage(dest, image(width: width, height: height), nil)
        #expect(CGImageDestinationFinalize(dest))
        return out as Data
    }

    static func crc32(_ bytes: [UInt8]) -> UInt32 {
        var crc: UInt32 = 0xFFFF_FFFF
        for byte in bytes {
            crc ^= UInt32(byte)
            for _ in 0..<8 { crc = crc & 1 == 1 ? (crc >> 1) ^ 0xEDB8_8320 : crc >> 1 }
        }
        return ~crc
    }

    /// PNG con una cabecera IHDR válida (CRC correcto) que declara `width`×`height`, sin datos de imagen.
    static func pngHeader(width: UInt32, height: UInt32) -> Data {
        func be(_ v: UInt32) -> [UInt8] { [UInt8(v >> 24), UInt8(v >> 16 & 0xFF), UInt8(v >> 8 & 0xFF), UInt8(v & 0xFF)] }
        let ihdr: [UInt8] = Array("IHDR".utf8) + be(width) + be(height) + [8, 6, 0, 0, 0]
        var bytes: [UInt8] = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]
        bytes += be(13) + ihdr + be(crc32(ihdr))
        let idat: [UInt8] = Array("IDAT".utf8) + [0x78, 0x9C, 0x03, 0x00]
        bytes += be(4) + idat + be(crc32(idat))
        return Data(bytes)
    }

    static func entry(_ id: String, fingerprint: String? = nil, cover: String? = nil) -> RomEntry {
        var e = RomEntry(id: id, url: URL(fileURLWithPath: "/demo/\(id)"), fileName: (id as NSString).lastPathComponent,
                         title: id, isColor: false, sizeBytes: 32_768, headerChecksumOK: true, cloud: .current,
                         problem: nil, mirrorSaveDate: nil)
        e.fingerprint = fingerprint
        if let cover {
            e.coverURL = URL(fileURLWithPath: "/demo/\(cover)")
            e.coverStamp = "\(cover)|1|0"
        }
        return e
    }

    func store(readFolder: @escaping @Sendable (URL) -> Data? = { _ in nil }) -> CoverStore {
        CoverStore(captures: GameArtworkStore(directory: dir.appendingPathComponent("Artwork")),
                   pinned: GameArtworkStore(directory: dir.appendingPathComponent("ArtworkPinned")),
                   imported: GameArtworkStore(directory: dir.appendingPathComponent("Covers/Imported"), untrusted: true),
                   folderCache: GameArtworkStore(directory: dir.appendingPathComponent("Covers/Folder"), untrusted: true),
                   settingsURL: dir.appendingPathComponent("Covers/settings.json"), readFolderImage: readFolder)
    }

    static var framePixels: [UInt32] {
        var pixels = [UInt32](repeating: 0xFF00_00FF, count: FrameBuffers.pixelCount)
        pixels[0] = 0xFFFF_FFFF
        return pixels
    }

    /// Espera a que una condición asíncrona (cola + salto al hilo principal) se cumpla.
    func eventually(_ condition: () -> Bool) async throws {
        for _ in 0..<100 where !condition() { try await Task.sleep(for: .milliseconds(20)) }
        #expect(condition())
    }

    // MARK: Prioridad

    @Test func priorityFollowsChoiceAndPreference() {
        let all = CoverAvailability(imported: true, sidecar: true, capture: true)
        #expect(CoverResolver.candidates(.auto, .images, all) == [.imported, .sidecar, .capture, .generated])
        #expect(CoverResolver.candidates(.auto, .captures, all) == [.capture, .imported, .sidecar, .generated])
        #expect(CoverResolver.candidates(.image, .captures, all) == [.imported, .sidecar, .generated])
        #expect(CoverResolver.candidates(.capture, .images, all) == [.capture, .generated])
        #expect(CoverResolver.candidates(.generated, .images, all) == [.generated])
        // Importada > carpeta; elección explícita sin esa fuente → generada (no salta a otra).
        #expect(CoverResolver.resolve(.image, .images, CoverAvailability(sidecar: true)) == .sidecar)
        #expect(CoverResolver.resolve(.image, .images, CoverAvailability(capture: true)) == .generated)
        #expect(CoverResolver.resolve(.capture, .images, CoverAvailability(imported: true)) == .generated)
        #expect(CoverResolver.resolve(.auto, .images, CoverAvailability()) == .generated)
        #expect(CoverResolver.resolve(.auto, .images, CoverAvailability(capture: true)) == .capture)
        // Por defecto, «Preferir imágenes» y «Automática».
        #expect(CoverSettings().preference == .images)
        #expect(CoverSettings().choice(for: "x") == .auto)
    }

    // MARK: Imagen junto al ROM

    @Test func sidecarMatchesNameExtensionPriorityAndSingleGameFolders() {
        func s(_ name: String, size: Int? = 10) -> SidecarCover.Sibling {
            SidecarCover.Sibling(name: name, url: URL(fileURLWithPath: "/x/\(name)"), size: size, modified: nil)
        }
        let siblings = [s("Juego.WEBP"), s("juego.JPG"), s("Juego.png", size: 0), s(".Juego.jpeg"), s("cover.png")]
        // Sin distinguir mayúsculas; el PNG vacío no cuenta; jpg gana a webp; los ocultos no cuentan.
        #expect(SidecarCover.find(romName: "Juego.gb", siblings: siblings, romsInFolder: 2)?.name == "juego.JPG")
        #expect(SidecarCover.find(romName: "Juego.gb", siblings: [s("Juego.png"), s("Juego.jpg")], romsInFolder: 2)?.name == "Juego.png")
        // portada.*/cover.* solo con un juego en la carpeta.
        #expect(SidecarCover.find(romName: "Otro.gb", siblings: siblings, romsInFolder: 2) == nil)
        #expect(SidecarCover.find(romName: "Otro.gb", siblings: siblings, romsInFolder: 1)?.name == "cover.png")
        #expect(SidecarCover.find(romName: "Otro.gb", siblings: [s("cover.png"), s("Portada.webp")], romsInFolder: 1)?.name == "Portada.webp")
        #expect(SidecarCover.find(romName: "Otro.gb", siblings: [s("Otro.gif"), s("Otro.png.txt")], romsInFolder: 1) == nil)
    }

    @Test func scannerAttachesTheImageWithoutReadingIt() throws {
        let lib = dir.appendingPathComponent("Biblioteca", isDirectory: true)
        let solo = lib.appendingPathComponent("Solo", isDirectory: true)
        let dos = lib.appendingPathComponent("Dos", isDirectory: true)
        for d in [solo, dos] { try FileManager.default.createDirectory(at: d, withIntermediateDirectories: true) }
        let rom = Data(repeating: 0, count: 0x150)
        try rom.write(to: lib.appendingPathComponent("Raiz.gb"))
        try rom.write(to: solo.appendingPathComponent("Uno.gb"))
        try rom.write(to: dos.appendingPathComponent("A.gb"))
        try rom.write(to: dos.appendingPathComponent("B.gb"))
        // La imagen de Raiz no se puede leer (permisos 000): el escáner la adjunta igual, porque no la lee.
        let unreadable = lib.appendingPathComponent("raiz.PNG")
        try Data("no es una imagen".utf8).write(to: unreadable)
        try FileManager.default.setAttributes([.posixPermissions: 0], ofItemAtPath: unreadable.path)
        defer { try? FileManager.default.setAttributes([.posixPermissions: 0o644], ofItemAtPath: unreadable.path) }
        try Data("x".utf8).write(to: solo.appendingPathComponent("portada.jpg"))
        try Data("x".utf8).write(to: dos.appendingPathComponent("cover.webp"))
        try Data("x".utf8).write(to: dos.appendingPathComponent("B.jpeg"))

        let entries = LibraryScanner.scan(folder: lib)
        func cover(_ id: String) -> String? { entries.first { $0.id == id }?.coverURL?.lastPathComponent }
        #expect(cover("Raiz.gb") == "raiz.PNG")
        #expect(cover("Solo/Uno.gb") == "portada.jpg")
        #expect(cover("Dos/A.gb") == nil)          // cover.webp no vale con dos juegos
        #expect(cover("Dos/B.gb") == "B.jpeg")
        #expect(entries.first { $0.id == "Raiz.gb" }?.coverStamp?.hasPrefix("raiz.PNG|") == true)
        #expect(entries.count == 4)               // las imágenes no son juegos
    }

    // MARK: Imágenes hostiles

    @Test func formatIsRecognizedByBytesNotExtension() {
        #expect(CoverImageRules.sniff(Self.encoded(.png, width: 4, height: 4)) == .png)
        #expect(CoverImageRules.sniff(Self.encoded(.jpeg, width: 4, height: 4)) == .jpeg)
        #expect(CoverImageRules.sniff(Data("RIFF\0\0\0\0WEBPVP8 ".utf8)) == .webp)
        #expect(CoverImageRules.sniff(Data("RIFF\0\0\0\0WAVEfmt ".utf8)) == nil)
        #expect(CoverImageRules.sniff(Data("GIF89a......".utf8)) == nil)
        #expect(CoverImageRules.sniff(Data("<html>no soy un png</html>".utf8)) == nil)   // «extensión falsa»
        #expect(CoverImageRules.sniff(Data()) == nil)
        #expect(CoverImageRules.sniff(Data([0x89, 0x50])) == nil)
        #expect(CoverDecoder.decode(Data("<html>no soy un png</html>".utf8)) == nil)
        #expect(CoverDecoder.decode(Self.encoded(.gif, width: 8, height: 8)) == nil)
    }

    @Test func validImagesAreReducedToAtMost1024AsPNG() throws {
        for type in [UTType.png, .jpeg] {
            let data = Self.encoded(type, width: 3000, height: 1500)
            let image = try #require(CoverDecoder.decode(data))
            #expect(image.width == 1024 && image.height == 512)
            let png = try #require(CoverDecoder.reduce(data))
            #expect(CoverImageRules.sniff(png) == .png)
            let back = try #require(UIImage(data: png))
            #expect(back.size == CGSize(width: 1024, height: 512))
        }
        // Una pequeña no se amplía.
        let small = try #require(CoverDecoder.decode(Self.encoded(.png, width: 300, height: 400)))
        #expect(small.width == 300 && small.height == 400)
    }

    @Test func truncatedImagesNeverCrash() {
        let full = Self.encoded(.png, width: 800, height: 600)
        for cut in [9, 33, 60, full.count / 3, full.count - 20] {
            let image = CoverDecoder.decode(full.prefix(cut))
            if let image { #expect(max(image.width, image.height) <= 1024) }
        }
        let jpeg = Self.encoded(.jpeg, width: 800, height: 600)
        _ = CoverDecoder.decode(jpeg.prefix(jpeg.count / 2))
        #expect(CoverDecoder.decode(full.prefix(12)) == nil)
    }

    @Test func hugeDeclaredDimensionsAreRejectedBeforeDecoding() {
        #expect(!CoverImageRules.dimensionsOK(width: 16_385, height: 10))
        #expect(!CoverImageRules.dimensionsOK(width: 12_000, height: 12_000))   // 144 MP > 100 MP
        #expect(!CoverImageRules.dimensionsOK(width: 0, height: 10))
        #expect(CoverImageRules.dimensionsOK(width: 10_000, height: 10_000))
        #expect(CoverDecoder.decode(Self.pngHeader(width: 60_000, height: 60_000)) == nil)
        #expect(CoverDecoder.decode(Self.pngHeader(width: 16_000, height: 16_000)) == nil)
    }

    @Test func imagesOverTheByteLimitAreRejectedAndNotReadToTheEnd() throws {
        var big = Self.encoded(.png, width: 16, height: 16)
        big.append(Data(count: CoverImageRules.maxBytes))
        #expect(CoverDecoder.decode(big) == nil)
        let url = dir.appendingPathComponent("grande.png")
        try big.write(to: url)
        #expect(CoverDecoder.readLimited(url) == nil)
        let ok = dir.appendingPathComponent("ok.png")
        try Self.encoded(.png, width: 16, height: 16).write(to: ok)
        #expect(CoverDecoder.readLimited(ok) != nil)
    }

    /// N5iA-1: un «Juego.png» que por dentro es HEIC no llega al decodificador HEVC en la carpeta, ni una
    /// copia guardada que no sea PNG; al importar, HEIC sí vale (la mutación «aceptar HEIC en todas partes»
    /// rompe las dos primeras comprobaciones).
    @Test func heicIsOnlyAcceptedWhenImporting() async throws {
        let heic = Self.encoded(.heic, width: 64, height: 48)
        #expect(CoverImageRules.sniff(heic) == .heic)
        #expect(CoverDecoder.decode(heic) == nil)
        #expect(CoverDecoder.reduce(heic) == nil)
        #expect(CoverDecoder.decode(heic, formats: CoverFormat.imported) != nil)
        #expect(CoverDecoder.decode(Self.encoded(.jpeg, width: 8, height: 8), formats: CoverFormat.stored) == nil)

        let covers = store { _ in heic }
        let game = Self.entry("Juego.gb", fingerprint: "fa", cover: "Juego.png")
        covers.load(game, fingerprint: "fa")
        try await eventually { covers.failedFolder.count == 1 }
        #expect(covers.shown(game, fingerprint: "fa") == .generated)
        #expect(await covers.importImage(heic, fingerprint: "fa"))
        #expect(covers.shown(game, fingerprint: "fa").kind == .imported)
        // Una copia de la caché que no es PNG (manipulada) no se usa.
        let folder = dir.appendingPathComponent("Covers/Folder")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try Self.encoded(.jpeg, width: 8, height: 8).write(to: folder.appendingPathComponent("k.png"))
        let reread = GameArtworkStore(directory: folder, untrusted: true)
        reread.load("k")
        try await eventually { reread.missing.contains("k") }
    }

    /// N5iA-4: un escaneo de una carpeta inaccesible (vacío) no cuenta como completo y no purga.
    @Test func onlyACompleteScanAllowsPruning() throws {
        let missing = LibraryScanner.scanResult(folder: dir.appendingPathComponent("no-existe"))
        #expect(missing.entries.isEmpty && !missing.complete)
        let empty = dir.appendingPathComponent("vacia", isDirectory: true)
        try FileManager.default.createDirectory(at: empty, withIntermediateDirectories: true)
        #expect(LibraryScanner.scanResult(folder: empty).complete)
        let locked = empty.appendingPathComponent("Cerrada", isDirectory: true)
        try FileManager.default.createDirectory(at: locked, withIntermediateDirectories: true)
        try FileManager.default.setAttributes([.posixPermissions: 0], ofItemAtPath: locked.path)
        defer { try? FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: locked.path) }
        #expect(!LibraryScanner.scanResult(folder: empty).complete)
    }

    // MARK: Ajustes por dispositivo

    @Test func settingsPersistAndACorruptFileIsSetAside() throws {
        let url = dir.appendingPathComponent("Covers/settings.json")
        var file = CoverSettingsFile(url: url)
        #expect(file.load() == CoverSettings())
        var settings = CoverSettings().with(.capture, for: "0123abcd").with(.auto, for: "ffff")
        settings.preference = .captures
        #expect(file.save(settings))
        var again = CoverSettingsFile(url: url)
        let loaded = again.load()
        #expect(loaded == settings && loaded.choices.count == 1)

        try Data("{roto".utf8).write(to: url)
        var broken = CoverSettingsFile(url: url)
        #expect(broken.load() == CoverSettings())
        let names = try FileManager.default.contentsOfDirectory(atPath: url.deletingLastPathComponent().path)
        #expect(names.contains { $0.hasPrefix("settings.corrupt-") })
        #expect(!FileManager.default.fileExists(atPath: url.path))
    }

    @Test func aFutureVersionIsReadButNeverOverwritten() throws {
        let url = dir.appendingPathComponent("settings.json")
        let future = #"{"formatVersion":9,"preference":"captures","choices":{"abcd":"image","ABC/../x":"image","efgh":"nuevo"},"extra":1}"#
        try Data(future.utf8).write(to: url)
        var file = CoverSettingsFile(url: url)
        let loaded = file.load()
        #expect(loaded.preference == .captures)
        #expect(loaded.choices == ["abcd": .image])   // clave inválida y valor desconocido descartados
        #expect(!file.save(loaded))
        #expect(try String(contentsOf: url, encoding: .utf8) == future)
    }

    // MARK: Almacén

    @Test func importChoosesImageAndRemovingReturnsToAuto() async throws {
        let covers = store()
        let game = Self.entry("a.gb", fingerprint: "fa")
        #expect(covers.shown(game, fingerprint: "fa") == .generated)
        #expect(await covers.importImage(Self.encoded(.jpeg, width: 2000, height: 3000), fingerprint: "fa"))
        #expect(covers.choice(for: "fa") == .image)
        let shown = covers.shown(game, fingerprint: "fa")
        #expect(shown.kind == .imported && shown.image?.size == CGSize(width: 683, height: 1024))
        covers.waitForPendingWork()
        let png = try Data(contentsOf: dir.appendingPathComponent("Covers/Imported/fa.png"))
        #expect(CoverImageRules.sniff(png) == .png)
        // Una imagen hostil no cambia nada.
        #expect(!(await covers.importImage(Self.pngHeader(width: 60_000, height: 60_000), fingerprint: "fb")))
        #expect(covers.choice(for: "fb") == .auto && !covers.imported.has("fb"))
        covers.removeImported(game, fingerprint: "fa")
        #expect(covers.choice(for: "fa") == .auto)
        #expect(covers.shown(game, fingerprint: "fa") == .generated)
    }

    @Test func pinningChoosesCaptureAndSurvivesTheCloseCapture() {
        let covers = store()
        let game = Self.entry("a.gb", fingerprint: "fa")
        // Una escena lisa no se fija.
        #expect(!covers.pinCapture(fingerprint: "fa", pixels: [UInt32](repeating: 0xFFFF_FFFF, count: FrameBuffers.pixelCount)))
        #expect(covers.choice(for: "fa") == .auto)
        #expect(covers.pinCapture(fingerprint: "fa", pixels: Self.framePixels))
        #expect(covers.choice(for: "fa") == .capture)
        let pinnedImage = covers.pinned.image(for: "fa")
        // Al salir del juego se guarda el último fotograma: la fijada sigue mandando.
        var other = Self.framePixels
        other[1] = 0xFF00_FF00
        #expect(covers.captures.save(fingerprint: "fa", pixels: other))
        #expect(covers.shown(game, fingerprint: "fa").image === pinnedImage)
        covers.unpinCapture(fingerprint: "fa")
        #expect(covers.shown(game, fingerprint: "fa").image === covers.captures.image(for: "fa"))
    }

    @Test func aHostileFolderImageFallsBackToTheNextSourceAndIsNotRetried() async throws {
        final class Counter: @unchecked Sendable { var reads = 0 }
        let counter = Counter()
        let covers = store { _ in
            counter.reads += 1
            return Data("<html>falso .png</html>".utf8)
        }
        let game = Self.entry("a.gb", fingerprint: "fa", cover: "a.png")
        #expect(covers.captures.save(fingerprint: "fa", pixels: Self.framePixels))
        #expect(covers.shown(game, fingerprint: "fa").kind == .sidecar)   // leyendo: no se ve otra fuente
        covers.load(game, fingerprint: "fa")
        covers.load(game, fingerprint: "fa")                              // dos tarjetas: una sola lectura
        try await eventually { covers.failedFolder.count == 1 }
        #expect(covers.shown(game, fingerprint: "fa").kind == .capture)
        covers.load(game, fingerprint: "fa")
        covers.waitForPendingWork()
        #expect(counter.reads == 1)
        // Con «Imagen» elegida y la de la carpeta rota: la generada.
        covers.setChoice(.image, for: "fa")
        #expect(covers.shown(game, fingerprint: "fa") == .generated)
    }

    @Test func aGoodFolderImageIsCachedReducedAndPrunedWhenItChanges() async throws {
        let data = Self.encoded(.png, width: 2048, height: 1024)
        let covers = store { _ in data }
        let game = Self.entry("a.gb", fingerprint: nil, cover: "a.png")
        covers.load(game, fingerprint: nil)
        try await eventually { covers.shown(game, fingerprint: nil).image != nil }
        #expect(covers.shown(game, fingerprint: nil).image?.size == CGSize(width: 1024, height: 512))
        covers.waitForPendingWork()
        let folder = dir.appendingPathComponent("Covers/Folder")
        #expect(try FileManager.default.contentsOfDirectory(atPath: folder.path).count == 1)
        // La imagen cambió (otro sello): la copia antigua sobra y se purga; nunca se toca la del usuario.
        var changed = game
        changed.coverStamp = "a.png|2|5"
        #expect(covers.pruneFolderCache([changed]) == 1)
        covers.waitForPendingWork()
        #expect(try FileManager.default.contentsOfDirectory(atPath: folder.path).isEmpty)
    }

    @Test func removeAllClearsEveryCoverButKeepsChoicesAndStorageIgnoresSettings() async throws {
        let covers = store()
        let userImage = dir.appendingPathComponent("Biblioteca/Juego.png")
        try FileManager.default.createDirectory(at: userImage.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Self.encoded(.png, width: 8, height: 8).write(to: userImage)
        #expect(covers.captures.save(fingerprint: "fa", pixels: Self.framePixels))
        #expect(covers.pinCapture(fingerprint: "fb", pixels: Self.framePixels))
        #expect(await covers.importImage(Self.encoded(.png, width: 64, height: 64), fingerprint: "fc"))
        covers.waitForPendingWork()
        #expect(StorageUsage.measure(saves: nil, states: nil, artwork: covers.directories).artwork > 0)
        covers.removeAll()
        covers.waitForPendingWork()
        #expect(StorageUsage.measure(saves: nil, states: nil, artwork: covers.directories).artwork == 0)
        #expect(FileManager.default.fileExists(atPath: dir.appendingPathComponent("Covers/settings.json").path))
        #expect(covers.choice(for: "fc") == .image)
        #expect(FileManager.default.fileExists(atPath: userImage.path))
        #expect(!covers.imported.has("fc") && !covers.pinned.has("fb") && !covers.captures.has("fa"))
    }

    @Test func storedCopiesSurviveARestart() async throws {
        do {
            let covers = store()
            #expect(await covers.importImage(Self.encoded(.png, width: 64, height: 64), fingerprint: "fa"))
            covers.setPreference(.captures)
            covers.waitForPendingWork()
        }
        let fresh = store()
        let game = Self.entry("a.gb", fingerprint: "fa")
        #expect(fresh.settings.preference == .captures && fresh.choice(for: "fa") == .image)
        #expect(fresh.shown(game, fingerprint: "fa") == ShownCover(kind: .imported, image: nil))
        fresh.load(game, fingerprint: "fa")
        try await eventually { fresh.shown(game, fingerprint: "fa").image != nil }
    }

    // MARK: Continuar jugando (decisión de Joel 2026-10-07, sustituye a K10)

    @Test func continueRailShowsResumableGamesWithoutAnyCover() {
        let games = [Self.entry("con.gb", fingerprint: "f1"), Self.entry("sin.gb", fingerprint: "f2"),
                     Self.entry("nunca.gb", fingerprint: "f3")]
        var prefs = LibraryPreferencesData()
        for (fp, t) in [("f1", 2.0), ("f2", 1.0)] {
            var meta = GameMetadata()
            meta.lastPlayed = Date(timeIntervalSince1970: t)
            prefs.games[fp] = meta
        }
        prefs.fingerprints = ["con.gb": "f1", "sin.gb": "f2", "nunca.gb": "f3"]
        // Ningún juego tiene portada: los reanudables salen igual.
        let rail = LibraryQuery.continuePlaying(games, prefs: prefs) { _ in true }
        #expect(rail.map(\.id) == ["con.gb", "sin.gb"])
        let resumableOnly = LibraryQuery.continuePlaying(games, prefs: prefs) { $0.id == "sin.gb" }
        #expect(resumableOnly.map(\.id) == ["sin.gb"])
    }
}

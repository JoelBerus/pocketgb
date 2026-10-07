import CoreGraphics
import Foundation
import Testing
@testable import PocketGB

/// N3 (iOS): detalle adaptable, proporción por consola, información técnica y categorías.
/// ROMs sintéticos generados aquí, nunca ROMs reales.
@MainActor
struct AdaptiveLibraryTests {
    // MARK: Disposición del detalle

    /// Áreas útiles aproximadas del detalle (ventana menos barras y área segura) en vertical y horizontal.
    static let sizes: [(name: String, portrait: CGSize, landscape: CGSize)] = [
        ("iPhone SE (3.ª gen)", CGSize(width: 375, height: 559), CGSize(width: 667, height: 291)),
        ("iPhone 17 Pro", CGSize(width: 402, height: 726), CGSize(width: 750, height: 322)),
        ("iPhone 17 Pro Max", CGSize(width: 440, height: 808), CGSize(width: 832, height: 360)),
    ]

    static let gb = CGFloat(ScreenSize.gameBoy.aspectRatio)
    static let gba = CGFloat(ScreenSize.gameBoyAdvance.aspectRatio)

    @Test func landscapeUsesTwoColumnsWithTheWholeImageOnScreen() {
        for size in Self.sizes {
            for ratio in [Self.gb, Self.gba] {
                let layout = DetailLayout.make(available: size.landscape, aspectRatio: ratio)
                #expect(layout.kind == .twoColumns, "\(size.name)")
                // Entera en pantalla (alto) y sin pasar de la mitad del ancho útil.
                #expect(layout.artwork.height <= size.landscape.height - DetailLayout.verticalPadding + 0.5)
                let contentWidth = size.landscape.width - 2 * DetailLayout.margin
                #expect(layout.artwork.width <= (contentWidth - DetailLayout.columnSpacing) / 2 + 0.5)
                // La columna de información conserva al menos 300 pt.
                #expect(contentWidth - DetailLayout.columnSpacing - layout.artwork.width >= 300, "\(size.name)")
                // Proporción de la consola.
                #expect(abs(layout.artwork.width / layout.artwork.height - ratio) < 0.001)
            }
        }
    }

    @Test func portraitImageTakesAtMost45PercentOfTheHeight() {
        for size in Self.sizes {
            for ratio in [Self.gb, Self.gba] {
                let layout = DetailLayout.make(available: size.portrait, aspectRatio: ratio)
                #expect(layout.kind == .singleColumn, "\(size.name)")
                #expect(layout.artwork.height <= size.portrait.height * 0.45 + 0.5)
                #expect(layout.artwork.width <= size.portrait.width - 2 * DetailLayout.margin + 0.5)
                #expect(abs(layout.artwork.width / layout.artwork.height - ratio) < 0.001)
            }
        }
        // En el SE vertical, el 45 % limita al GB (la imagen se estrecha y se centra).
        let se = DetailLayout.make(available: Self.sizes[0].portrait, aspectRatio: Self.gb)
        #expect(se.artwork.height < 252 && se.artwork.width < 375 - 32)
    }

    @Test func wideAreasUseTwoColumnsEvenInPortrait() {
        // ≥ 600 pt de ancho (p. ej. una ventana ancha): dos columnas aunque sea más alta que ancha.
        #expect(DetailLayout.make(available: CGSize(width: 700, height: 900), aspectRatio: Self.gb).kind == .twoColumns)
        #expect(DetailLayout.make(available: CGSize(width: 599, height: 900), aspectRatio: Self.gb).kind == .singleColumn)
        #expect(DetailLayout.make(available: CGSize(width: 500, height: 499), aspectRatio: Self.gb).kind == .twoColumns)
        // Sin medir todavía: una columna, sin tamaños negativos.
        let zero = DetailLayout.make(available: .zero, aspectRatio: Self.gb)
        #expect(zero.kind == .singleColumn && zero.artwork.width >= 0 && zero.artwork.height >= 0)
    }

    // MARK: Proporción por consola

    @Test func artworkKeepsTheConsoleProportion() {
        #expect(abs(ArtworkStyle.console.frameAspectRatio(for: .gameBoy) - 10.0 / 9.0) < 0.001)
        #expect(abs(ArtworkStyle.console.frameAspectRatio(for: .gameBoyAdvance) - 1.5) < 0.001)
        // Las tarjetas comparten marco 10:9 (la captura GBA se recorta, no se deforma).
        #expect(ArtworkStyle.card.frameAspectRatio(for: .gameBoyAdvance) == ArtworkStyle.card.frameAspectRatio(for: .gameBoy))
        // Miniaturas de estados: por su tamaño en píxeles, o la consola si no hay imagen.
        #expect(ArtworkStyle.aspectRatio(of: CGSize(width: 240, height: 160), fallback: .gameBoy) == 1.5)
        #expect(abs(ArtworkStyle.aspectRatio(of: nil, fallback: .gameBoyAdvance) - 1.5) < 0.001)
        #expect(abs(ArtworkStyle.aspectRatio(of: nil, fallback: .gameBoy) - 10.0 / 9.0) < 0.001)
        #expect(Console(fileName: "x.gba").screen == .gameBoyAdvance)
    }

    // MARK: Información técnica

    /// Game Boy de 64 KiB (código 1) con el MBC, la RAM y el checksum global que se pidan.
    static func gbROM(cartType: UInt8, ramCode: UInt8, globalOK: Bool) -> Data {
        var bytes = [UInt8](repeating: 0, count: 0x10000)
        for (i, c) in "TECNICO".utf8.enumerated() { bytes[0x134 + i] = c }
        bytes[0x147] = cartType
        bytes[0x148] = 0x01
        bytes[0x149] = ramCode
        var x: UInt8 = 0
        for i in 0x134...0x14C { x = x &- bytes[i] &- 1 }
        bytes[0x14D] = x
        if globalOK {
            var sum: UInt16 = 0
            for (i, b) in bytes.enumerated() where i != 0x14E && i != 0x14F { sum &+= UInt16(b) }
            bytes[0x14E] = UInt8(sum >> 8)
            bytes[0x14F] = UInt8(sum & 0xFF)
        } else {
            bytes[0x14E] = 0x12
            bytes[0x14F] = 0x34
        }
        return Data(bytes)
    }

    /// Game Boy Advance con el medio de guardado de `tag` y el código de juego `code`.
    static func gbaROM(tag: String, code: String) -> Data {
        var rom = [UInt8](GBATests.rom(title: "TECNICO", tag: tag))
        for (i, c) in code.utf8.prefix(4).enumerated() { rom[0xAC + i] = c }
        rom[0xB0] = UInt8(ascii: "0")
        rom[0xB1] = UInt8(ascii: "1")
        rom[0xBC] = 2
        var sum: UInt8 = 0
        for i in 0xA0...0xBC { sum = sum &- rom[i] }
        rom[0xBD] = sum &- 0x19
        return Data(rom)
    }

    @Test func gameBoyInfoComesFromTheCore() throws {
        let rom = Self.gbROM(cartType: 0x10, ramCode: 0x03, globalOK: true)
        let info = try GameTechnicalInfoLoader.inspect(rom, console: .gameBoy).get()
        #expect(info.console == .gameBoy && info.headerTitle == "TECNICO")
        #expect(info.rows.map(\.label) == ["Título de cabecera", "Cartucho", "ROM", "Partida guardada",
                                           "Checksum de cabecera", "Checksum global"])
        #expect(info.rows.map(\.value) == ["TECNICO", "MBC3 + reloj + RAM + batería", "64 KiB",
                                           "32 KiB · batería · reloj", "Correcto", "Correcto"])
        #expect(!info.rows.contains { $0.warning })
        // SHA-256 completo (64 hex); la huella de la app son sus 32 primeros.
        #expect(info.sha256.count == 64 && info.sha256.allSatisfy(\.isHexDigit))
        #expect(info.sha256.hasPrefix(try #require(RomFingerprint.compute(data: rom, console: .gameBoy))))
    }

    @Test func gameBoyGlobalChecksumMismatchIsAWarningWithText() throws {
        let info = try GameTechnicalInfoLoader.inspect(Self.gbROM(cartType: 0x00, ramCode: 0, globalOK: false),
                                                       console: .gameBoy).get()
        let global = try #require(info.rows.first { $0.label == "Checksum global" })
        #expect(global.value == "No coincide (la consola real lo ignora)" && global.warning)
        #expect(info.rows.first { $0.label == "Cartucho" }?.value == "Solo ROM")
        #expect(info.saveDescription == "Sin RAM")
    }

    @Test func gameBoyAdvanceInfoShowsSaveTypeCodeAndClock() throws {
        let rom = Self.gbaROM(tag: "FLASH1M_V103", code: "BPEE")
        let info = try GameTechnicalInfoLoader.inspect(rom, console: .gameBoyAdvance).get()
        #expect(info.console == .gameBoyAdvance)
        #expect(info.rows.map(\.label) == ["Título de cabecera", "Tipo de partida", "Código del juego", "ROM",
                                           "Partida guardada", "Checksum de cabecera"])
        #expect(info.rows.map(\.value) == ["TECNICO", "Flash 128 KiB (detectado)", "BPEE · 01 · v2", "1 KiB",
                                           "128 KiB · reloj", "Correcto"])
        #expect(info.globalChecksumOK == nil)   // GBA no tiene checksum global
        #expect(info.sha256.hasPrefix(try #require(RomFingerprint.compute(data: rom, console: .gameBoyAdvance))))

        let eeprom = try GameTechnicalInfoLoader.inspect(Self.gbaROM(tag: "EEPROM_V124", code: "ABCE"),
                                                         console: .gameBoyAdvance).get()
        #expect(eeprom.saveDescription == "EEPROM (512 B u 8 KiB)" && !eeprom.hasRTC)
        let none = try GameTechnicalInfoLoader.inspect(Self.gbaROM(tag: "NADA", code: "ABCE"),
                                                       console: .gameBoyAdvance).get()
        #expect(none.rows.first { $0.label == "Tipo de partida" }?.value == "Sin partida (detectado)")
        #expect(none.saveDescription == "Sin partida")
    }

    @Test func rejectedAndMissingROMsExplainWhy() throws {
        var bad = [UInt8](Self.gbROM(cartType: 0x10, ramCode: 0x03, globalOK: true))
        bad[0x147] = 0xEE   // MBC que el núcleo no conoce
        #expect(GameTechnicalInfoLoader.inspect(Data(bad), console: .gameBoy)
            == .failure(.rejected(CoreError.unsupportedMBC.description)))
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        // Un archivo que no existe no se puede leer sin descargar: no se intenta.
        #expect(GameTechnicalInfoLoader.load(url: dir.appendingPathComponent("no.gb"), console: .gameBoy)
            == .failure(.notDownloaded))
        let url = dir.appendingPathComponent("si.gb")
        try Self.gbROM(cartType: 0x1B, ramCode: 0x04, globalOK: true).write(to: url)
        let info = try GameTechnicalInfoLoader.load(url: url, console: .gameBoy).get()
        #expect(info.rows.first { $0.label == "Partida guardada" }?.value == "128 KiB · batería")
    }

    @Test func byteFormatUsesBinaryUnitsAndDecimalComma() {
        #expect(ByteFormat.format(512) == "512 B")
        #expect(ByteFormat.format(1024) == "1 KiB")
        #expect(ByteFormat.format(1536) == "1,5 KiB")
        #expect(ByteFormat.format(8 * 1024 * 1024) == "8 MiB")
        #expect(ByteFormat.format(32_768 + 100) == "32,1 KiB")
        #expect(GameTechnicalInfo.cartridgeName(0x77) == "Desconocido (0x77)")
    }

    // MARK: Categorías (carpetas de primer nivel)

    static func entry(_ path: String) -> RomEntry {
        let file = (path as NSString).lastPathComponent
        return RomEntry(id: path, url: URL(fileURLWithPath: "/tmp/\(path)"), fileName: file,
                        title: (file as NSString).deletingPathExtension, isColor: false, sizeBytes: 32_768,
                        headerChecksumOK: true, cloud: .current, problem: nil, mirrorSaveDate: nil)
    }

    @Test func categoriesAreTheFirstLevelFolders() {
        let entries = [
            Self.entry("raiz.gb"),
            Self.entry("Pokémon/1ª generación/rojo.gb"),
            Self.entry("Pokémon/2ª generación/oro.gbc"),
            Self.entry("Kirby/kirby.gba"),
            Self.entry("Acción/A/B/C/D/hondo.gb"),
        ]
        #expect(LibraryCategory.available(in: entries) == [.folder("Acción"), .folder("Kirby"), .folder("Pokémon"),
                                                          .uncategorized])
        #expect(LibraryCategory.available(in: Array(entries.dropFirst())).last == .folder("Pokémon"))

        var prefs = LibraryPreferencesData()
        prefs.sort = .title
        func ids(_ category: LibraryCategory, filter: LibraryFilter = .all, query: String = "") -> [String] {
            LibraryQuery.visible(entries, prefs: prefs, filter: filter, query: query, category: category).map(\.id)
        }
        #expect(ids(.all).count == 5)
        #expect(ids(.folder("Pokémon")) == ["Pokémon/2ª generación/oro.gbc", "Pokémon/1ª generación/rojo.gb"])
        #expect(ids(.uncategorized) == ["raiz.gb"])
        #expect(ids(.folder("Acción")) == ["Acción/A/B/C/D/hondo.gb"])   // subcarpetas incluidas
        // Se combina con el filtro de consola y la búsqueda.
        #expect(ids(.folder("Pokémon"), filter: .gba).isEmpty)
        #expect(ids(.folder("Pokémon"), query: "oro") == ["Pokémon/2ª generación/oro.gbc"])
        // Mismo nombre en otra forma Unicode (iCloud puede dar NFD): misma categoría.
        let nfd = "Pokémon".decomposedStringWithCanonicalMapping
        #expect(ids(.folder(nfd)).count == 2)
    }

    @Test func hiddenGamesDoNotCreateCategories() {
        let prefs = LibraryPreferences(fileURL: nil)
        let hidden = Self.entry("Secreto/x.gb")
        prefs.hide(hidden)
        let categories = prefs.categories([Self.entry("Kirby/k.gba"), hidden])
        #expect(categories == [.folder("Kirby")])
        #expect(LibraryCategory.all.title == "Todas" && LibraryCategory.uncategorized.title == "Sin categoría")
    }

    // MARK: Estados de Game Boy Advance (visto en la captura `save-states-gba`)

    @Test func gameBoyAdvanceStatesAreNotReportedAsDamaged() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let store = StateStore(directory: dir)
        // Estado real del núcleo GBA (firma "PGBA") con su miniatura de 240×160.
        let core = try GBACoreBridge()
        _ = try core.loadROM(GBATests.rom(), bios: nil, unixTime: 0)
        core.runFrame()
        try store.save(try core.stateSave(), thumbnail: Data("png".utf8), to: .manual1)
        try store.save(Data("PGBS-gb".utf8), thumbnail: nil, to: .manual2)
        try store.save(Data("XXXX-roto".utf8), thumbnail: nil, to: .manual3)
        try store.save(try core.stateSave(), thumbnail: nil, to: .auto)
        let entries = store.entries()
        #expect(entries[.manual1]?.corrupt == false)
        #expect(entries[.manual2]?.corrupt == false)
        #expect(entries[.manual3]?.corrupt == true)
        // «Continuar» también para GBA: el automático válido cuenta.
        #expect(store.automaticEntry(newerThan: nil) != nil)
    }

    // MARK: Grupo flotante y carril en horizontal

    @Test func toolsFollowTheTabBarMinimizeRule() {
        typealias P = LibraryToolsPosition
        #expect(P.minimized(false, old: 0, new: 40))          // bajar: se encoge
        #expect(P.minimized(true, old: 40, new: 80))
        #expect(!P.minimized(true, old: 80, new: 60))         // subir: el grupo sube por si se despliega
        #expect(!P.minimized(true, old: 20, new: 4))          // arriba del todo: desplegada
        #expect(P.minimized(true, old: 80, new: 80))          // sin cambio: igual
        // iPhone 17 Pro horizontal (medido): ventana hasta 402; burbuja con el centro en 359 y
        // barra desplegada desde 337. Encogida: el grupo (48 pt) centrado con la burbuja.
        let window: CGFloat = 402, bubbleCenter: CGFloat = 359, barTop: CGFloat = 337
        #expect(window - P.bottomGap(minimized: true) - P.groupHeight / 2 == bubbleCenter)
        // Desplegada: el grupo acaba 8 pt por encima de la barra.
        #expect(window - P.bottomGap(minimized: false) == barTop - 8)
    }

    @Test func continueRowUsesTheGridColumnWidthInLandscape() {
        // 718 pt útiles (17 Pro horizontal) con mínimo 150 y separación 12: 4 columnas de 170,5.
        #expect(GridMetrics.columns(containerWidth: 718, minimum: 150, spacing: 12) == 4)
        #expect(abs(GridMetrics.columnWidth(containerWidth: 718, minimum: 150, spacing: 12) - 170.5) < 0.01)
        // SE horizontal (635 pt): 3 columnas; AX5 (mínimo 280): 2.
        #expect(GridMetrics.columns(containerWidth: 635, minimum: 150, spacing: 12) == 3)
        #expect(GridMetrics.columns(containerWidth: 635, minimum: 280, spacing: 12) == 2)
        #expect(GridMetrics.columns(containerWidth: 100, minimum: 150, spacing: 12) == 1)
        #expect(GridMetrics.columnWidth(containerWidth: 0, minimum: 150, spacing: 12) == 0)
    }

    @Test func panelsNeverCoverTheSectionTitle() {
        typealias P = LibraryToolsPosition
        // Título a la izquierda y panel a la derecha (no se solapan): el panel llega a la barra.
        let free = P.panelMaxHeight(groupTop: 335, visibleTop: 52,
                                    titleFrame: CGRect(x: 78, y: 150, width: 130, height: 22), panelMinX: 380)
        let expectedFree: CGFloat = 335 - 52 - 28
        #expect(free == expectedFree)
        // Título largo que pasa por debajo del panel: el panel se queda por debajo del título.
        let under = P.panelMaxHeight(groupTop: 335, visibleTop: 52,
                                     titleFrame: CGRect(x: 16, y: 150, width: 400, height: 22), panelMinX: 380)
        let expectedUnder: CGFloat = 335 - 172 - 28
        #expect(under == expectedUnder)
        // Sin sitio: dos filas como mínimo (se desplaza por dentro); sin medir: 220.
        #expect(P.panelMaxHeight(groupTop: 120, visibleTop: 52, titleFrame: .zero, panelMinX: 0) == 88)
        #expect(P.panelMaxHeight(groupTop: 0, visibleTop: 0, titleFrame: .zero, panelMinX: 0) == 220)
    }
}


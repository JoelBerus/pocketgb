import Foundation
import Testing
@testable import PocketGB

/// M9 lote 4: candidatos del selector de pareja (función pura).
struct LinkPartnersTests {
    static func entry(_ id: String, _ title: String, color: Bool = false, cloud: RomEntry.CloudState = .current,
                      problem: RomEntry.Problem? = nil, saved: Bool = false) -> RomEntry {
        RomEntry(id: id, url: URL(fileURLWithPath: "/demo/\(id)"), fileName: (id as NSString).lastPathComponent,
                 title: title, isColor: color, sizeBytes: 32_768, headerChecksumOK: true, cloud: cloud,
                 problem: problem, mirrorSaveDate: saved ? Date(timeIntervalSince1970: 50) : nil)
    }

    let source = entry("rojo.gb", "Rojo")

    /// Metadatos provisionales por ruta (N1a: juegos sin huella conocida).
    static func meta(_ change: (inout GameMetadata) -> Void) -> GameMetadata {
        var m = GameMetadata()
        change(&m)
        return m
    }

    @Test func excludesTheSameGameAdvanceHiddenProblemsAndNotDownloaded() {
        let entries = [
            source,
            Self.entry("amarillo.gbc", "Amarillo", color: true),
            Self.entry("advance.gba", "Advance"),
            Self.entry("oculto.gb", "Oculto"),
            Self.entry("roto.gb", "Roto", problem: .invalidHeader),
            Self.entry("nube.gb", "Nube", cloud: .notDownloaded),
            Self.entry("bajando.gb", "Bajando", cloud: .downloading),
        ]
        var prefs = LibraryPreferencesData()
        prefs.pendingByPath = ["oculto.gb": Self.meta { $0.hidden = true }]
        #expect(LinkPartners.candidates(for: source, in: entries, prefs: prefs).map(\.id) == ["amarillo.gbc"])
    }

    @Test func ordersByLastPlayedThenSavThenTitle() {
        let entries = [
            Self.entry("c.gb", "Charmander"),
            Self.entry("b.gb", "Bulbasaur", saved: true),
            Self.entry("a.gb", "Azul"),
            Self.entry("old.gb", "Antiguo"),
            Self.entry("new.gb", "Nuevo"),
        ]
        var prefs = LibraryPreferencesData()
        prefs.pendingByPath = ["old.gb": Self.meta { $0.lastPlayed = Date(timeIntervalSince1970: 100) },
                               "new.gb": Self.meta { $0.lastPlayed = Date(timeIntervalSince1970: 200) }]
        #expect(LinkPartners.candidates(for: source, in: entries, prefs: prefs).map(\.id)
                == ["new.gb", "old.gb", "b.gb", "a.gb", "c.gb"])
    }

    @Test func doesNotFilterByHavingASave() {
        let entries = [Self.entry("sin-partida.gb", "Sin partida")]
        #expect(LinkPartners.candidates(for: source, in: entries, prefs: LibraryPreferencesData()).count == 1)
    }

    @Test func titleOrderUsesTheAlias() {
        let entries = [Self.entry("x.gb", "Zeta"), Self.entry("y.gb", "Alfa")]
        var prefs = LibraryPreferencesData()
        prefs.pendingByPath = ["x.gb": Self.meta { $0.alias = "Aaa" }]
        #expect(LinkPartners.candidates(for: source, in: entries, prefs: prefs).map(\.id) == ["x.gb", "y.gb"])
    }
}

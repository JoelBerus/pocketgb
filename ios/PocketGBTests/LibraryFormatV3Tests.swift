import Foundation
import Testing
@testable import PocketGB

/// N4: `preferences.json` formato 3 (etiquetas y categoría virtual por huella, ajustes del inicio y
/// vista por categoría) con la robustez de N1: el formato 2 se lee tal cual y se reescribe como 3 tras
/// una copia exacta verificada; una versión futura se usa sin sobrescribirla ni apartarla; un 3 dañado
/// se aparta; las escrituras son atómicas.
@MainActor
struct LibraryFormatV3Tests {
    let dir: URL

    init() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    private var prefsURL: URL { dir.appendingPathComponent("Library/preferences.json") }
    private var folder: URL { prefsURL.deletingLastPathComponent() }

    private func writePrefs(_ text: String) throws {
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try Data(text.utf8).write(to: prefsURL)
    }

    private func json(_ url: URL) throws -> [String: Any] {
        try #require(try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
    }

    /// `preferences.json` tal como lo escribe N1/N3 (formato 2, `JSONEncoder` con claves ordenadas):
    /// huellas por ruta, alias, ocultos, ajustes por juego, una entrada provisional y la vista.
    static let v2Fixture = #"""
    {"fingerprints":{"Pokémon\/rojo.gb":"fp-rojo","Puzles\/tetra.gb":"fp-tetra"},"games":{"fp-rojo":{"alias":"Mi Rojo","favorite":true,"hidden":false,"lastPlayed":781000000,"lastPlayedPath":"Pokémon\/rojo.gb","overrides":{"colorForGameBoy":true,"compatPalette":5}},"fp-tetra":{"favorite":false,"hidden":true,"overrides":{}}},"importedLegacyGameSettings":true,"layout":"list","pendingByPath":{"nunca.gb":{"alias":"Pendiente","favorite":true,"hidden":false,"overrides":{}}},"sort":"recent","version":2}
    """#

    @Test func aVersion2FileIsReadAsIsAndWrittenAsVersion3AfterAVerifiedCopy() throws {
        try writePrefs(Self.v2Fixture)
        let original = try Data(contentsOf: prefsURL)
        let prefs = LibraryPreferences(fileURL: prefsURL)
        prefs.waitForPendingWrites()
        #expect(prefs.issue == nil)

        // Lo de la versión 2, tal cual.
        let rojo = LibraryOrganizationTests.game("Pokémon/rojo.gb", "fp-rojo")
        let tetra = LibraryOrganizationTests.game("Puzles/tetra.gb", "fp-tetra")
        let nunca = LibraryOrganizationTests.game("nunca.gb", nil)
        #expect(prefs.isFavorite(rojo) && prefs.displayTitle(rojo) == "Mi Rojo")
        #expect(prefs.overrides(for: rojo) == GameOverrides(colorForGameBoy: true, compatPalette: 5))
        #expect(prefs.lastPlayed(rojo) == Date(timeIntervalSinceReferenceDate: 781_000_000))
        #expect(prefs.isHidden(tetra))
        #expect(prefs.isFavorite(nunca) && prefs.displayTitle(nunca) == "Pendiente")
        #expect(prefs.data.layout == .list && prefs.data.sort == .recent && prefs.data.importedLegacyGameSettings)
        // Lo nuevo, vacío.
        #expect(prefs.tags(rojo).isEmpty && prefs.virtualFolder(rojo) == nil)
        #expect(prefs.data.home.isDefault && prefs.data.categoryLayouts.isEmpty)

        // En disco: el formato 3 y, antes, la copia exacta del 2.
        let backup = folder.appendingPathComponent("preferences.v2.json")
        #expect(try Data(contentsOf: backup) == original)
        #expect(LibraryPreferences.fileVersion(try Data(contentsOf: prefsURL)) == 3)
        let reopened = LibraryPreferences(fileURL: prefsURL)
        #expect(reopened.data == prefs.data && reopened.issue == nil)
        // Reabrir el formato 3 no vuelve a copiar nada.
        let names = try FileManager.default.contentsOfDirectory(atPath: folder.path).sorted()
        #expect(names == ["preferences.json", "preferences.v2.json"])
    }

    /// Lo nuevo de N4 se escribe por huella y vuelve igual; lo vacío no se escribe.
    @Test func tagsVirtualCategoryHomeAndLayoutsRoundTrip() throws {
        let prefs = LibraryPreferences(fileURL: prefsURL)
        let rojo = LibraryOrganizationTests.game("Pokémon/rojo.gb", "fp-rojo")
        #expect(prefs.addTag("rpg", to: rojo) == .added)
        #expect(prefs.moveToCategory(["Para jugar"], entry: rojo))
        prefs.updateHome { $0 = $0.hiding("Kirby", true).pinning("Pokémon", true) }
        prefs.updateHome { $0.showFavorites = false }
        prefs.setCategoryLayout(.list, for: ["Pokémon"])
        prefs.waitForPendingWrites()

        let object = try json(prefsURL)
        #expect(object["version"] as? Int == 3)
        let games = try #require(object["games"] as? [String: [String: Any]])
        #expect(games["fp-rojo"]?["tags"] as? [String] == ["rpg"])
        #expect(games["fp-rojo"]?["virtualFolder"] as? [String] == ["Para jugar"])
        #expect(games["fp-rojo"]?["favorite"] == nil)                 // lo vacío no se escribe
        let home = try #require(object["home"] as? [String: Any])
        #expect(home["hidden"] as? [String] == ["Kirby"] && home["pinned"] as? [String] == ["Pokémon"])
        #expect(home["showFavorites"] as? Bool == false)
        #expect(object["categoryLayouts"] as? [String: String] == ["Pokémon": "list"])
        // Nunca por ruta: nada provisional.
        #expect((object["pendingByPath"] as? [String: Any])?.isEmpty ?? true)

        let reopened = LibraryPreferences(fileURL: prefsURL)
        #expect(reopened.data == prefs.data)
        #expect(reopened.tags(rojo) == ["rpg"] && reopened.categoryPath(rojo) == ["Para jugar"])

        // Quitarlo todo deja el juego sin entrada.
        reopened.removeTag("rpg", from: rojo)
        reopened.returnToFolder(rojo)
        reopened.waitForPendingWrites()
        #expect(LibraryPreferences(fileURL: prefsURL).data.games["fp-rojo"] == nil)
    }

    /// Un campo nuevo dañado vuelve a su valor por defecto sin apartar el archivo ni perder el resto.
    @Test func damagedNewFieldsAreToleratedOneByOne() throws {
        try writePrefs(##"{"version":3,"games":{"fp":{"alias":"Nombre","tags":["b","A","a",7],"virtualFolder":["_Revisar"]},"fp2":{"tags":["x","#y","X"],"virtualFolder":["Pokémon","Para jugar"]}},"home":"roto","categoryLayouts":{"Pokémon":"mosaico"}}"##)
        let prefs = LibraryPreferences(fileURL: prefsURL)
        #expect(prefs.issue == nil)
        #expect(prefs.data.games["fp"]?.alias == "Nombre")
        #expect(prefs.data.games["fp"]?.tags.isEmpty == true)        // una lista con un número: fuera entera
        #expect(prefs.data.games["fp"]?.virtualFolder == nil)         // ruta reservada: no se usa
        #expect(prefs.data.games["fp2"]?.tags == ["x", "y"])          // saneadas: sin repetidas ni «#»
        #expect(prefs.data.games["fp2"]?.virtualFolder == ["Pokémon", "Para jugar"])
        #expect(prefs.data.home.isDefault && prefs.data.categoryLayouts.isEmpty)
    }

    @Test func aDamagedVersion3IsQuarantined() throws {
        try writePrefs(#"{"version":3,"games":[1,2]}"#)
        let bytes = try Data(contentsOf: prefsURL)
        let prefs = LibraryPreferences(fileURL: prefsURL)
        guard case .quarantined(let name) = prefs.issue else {
            Issue.record("Se esperaba la cuarentena, no \(String(describing: prefs.issue))")
            return
        }
        #expect(try Data(contentsOf: folder.appendingPathComponent(name)) == bytes)
    }

    /// Una versión futura (4+) se usa (también sus etiquetas y su inicio) pero nunca se sobrescribe ni
    /// se aparta: una app con N3 ve así un formato 3 (su versión futura).
    @Test func aFutureVersionIsUsedButNeverWrittenNorQuarantined() throws {
        try writePrefs(#"{"version":4,"games":{"fp":{"tags":["rpg"],"virtualFolder":["Para jugar"]}},"home":{"hidden":["Kirby"]},"nuevo":{"x":1}}"#)
        let bytes = try Data(contentsOf: prefsURL)
        let prefs = LibraryPreferences(fileURL: prefsURL)
        #expect(prefs.issue == .newerVersion(4))
        let game = LibraryOrganizationTests.game("Saga/a.gb", "fp")
        #expect(prefs.tags(game) == ["rpg"] && prefs.categoryPath(game) == ["Para jugar"])
        #expect(prefs.data.home.isHidden("Kirby"))
        #expect(prefs.addTag("otra", to: game) == .added)               // en memoria
        prefs.updateHome { $0.showFavorites = false }
        prefs.waitForPendingWrites()
        #expect(try Data(contentsOf: prefsURL) == bytes)
        #expect(try FileManager.default.contentsOfDirectory(atPath: folder.path) == ["preferences.json"])
    }

    /// Si la copia del formato 2 no se puede escribir, no se migra en disco: el original queda intacto,
    /// se usa en memoria y se avisa (como el formato 1, auditoría N1, H10).
    @Test func version2MigrationIsBlockedWhenTheCopyFails() throws {
        try writePrefs(Self.v2Fixture)
        let original = try Data(contentsOf: prefsURL)
        try FileManager.default.setAttributes([.posixPermissions: 0o555], ofItemAtPath: folder.path)
        defer { try? FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: folder.path) }
        let prefs = LibraryPreferences(fileURL: prefsURL)
        guard case .migrationBackupFailed = prefs.issue else {
            Issue.record("Se esperaba .migrationBackupFailed, no \(String(describing: prefs.issue))")
            return
        }
        let rojo = LibraryOrganizationTests.game("Pokémon/rojo.gb", "fp-rojo")
        #expect(prefs.displayTitle(rojo) == "Mi Rojo")
        prefs.addTag("rpg", to: rojo)
        prefs.waitForPendingWrites()
        #expect(try Data(contentsOf: prefsURL) == original)
    }

    /// Una copia `preferences.v2.json` previa con otro contenido no se pisa.
    @Test func version2CopyNeverOverwritesAPreviousOne() throws {
        try writePrefs(Self.v2Fixture)
        let original = try Data(contentsOf: prefsURL)
        let previous = Data("copia anterior".utf8)
        try previous.write(to: folder.appendingPathComponent("preferences.v2.json"))
        _ = LibraryPreferences(fileURL: prefsURL, now: Date(timeIntervalSince1970: 1_791_000_000))
        #expect(try Data(contentsOf: folder.appendingPathComponent("preferences.v2.json")) == previous)
        let copies = try FileManager.default.contentsOfDirectory(atPath: folder.path)
            .filter { $0.hasPrefix("preferences.v2-") && $0.hasSuffix(".json") }
        #expect(copies.count == 1)
        #expect(try Data(contentsOf: folder.appendingPathComponent(copies[0])) == original)
    }
}

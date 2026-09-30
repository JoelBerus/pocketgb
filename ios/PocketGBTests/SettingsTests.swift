import Foundation
import Testing
@testable import PocketGB

/// D6: ajustes globales y por juego, audio y almacenamiento (D-README §8).
struct SettingsTests {
    @Test func perGameOverridesFallBackToGlobal() {
        var data = GameplaySettingsData()
        data.colorForGameBoy = false
        data.compatPalette = 0
        #expect(data.emulation(for: "rojo.gb") == EmulationOptions(colorForGameBoy: false, compatPalette: 0))
        data.perGame["rojo.gb"] = GameOverrides(colorForGameBoy: true, compatPalette: nil)
        #expect(data.emulation(for: "rojo.gb") == EmulationOptions(colorForGameBoy: true, compatPalette: 0))
        data.compatPalette = 7
        #expect(data.emulation(for: "rojo.gb").compatPalette == 7)      // la paleta sigue siendo la global
        #expect(data.emulation(for: "otro.gb").colorForGameBoy == false)
        #expect(data.emulation(for: nil).colorForGameBoy == false)
    }

    @MainActor
    @Test func clearingEveryOverrideRemovesTheGameEntry() throws {
        let settings = GameplaySettings(defaults: nil)
        settings.setOverrides(GameOverrides(colorForGameBoy: true), for: "a.gb")
        #expect(settings.data.perGame["a.gb"] != nil)
        settings.setOverrides(GameOverrides(), for: "a.gb")
        #expect(settings.data.perGame["a.gb"] == nil)
    }

    @Test func audioAndEmulationSettingsPersistAndValidate() throws {
        var data = GameplaySettingsData()
        data.volume = 0.4
        data.playsInSilentMode = true
        data.colorForGameBoy = true
        data.compatPalette = 12
        data.perGame["x.gb"] = GameOverrides(compatPalette: 3)
        let decoded = try JSONDecoder().decode(GameplaySettingsData.self, from: JSONEncoder().encode(data))
        #expect(decoded == data)
        // Valores fuera de rango de una versión anterior o de un archivo dañado.
        let bad = try #require(#"{"volume": 3, "compatPalette": 40}"#.data(using: .utf8))
        let fixed = try JSONDecoder().decode(GameplaySettingsData.self, from: bad)
        #expect(fixed.volume == 1)
        #expect(fixed.compatPalette == 0)
        // Por defecto: respeta el interruptor de silencio (sesión .ambient) y sin color.
        #expect(GameplaySettingsData().playsInSilentMode == false)
        #expect(GameplaySettingsData().colorForGameBoy == false)
    }

    @Test func paletteNamesCoverAutoAndTwelveCombinations() {
        #expect(CompatPalette.title(0) == "Automática")
        #expect(CompatPalette.title(12) == "↓ + B")
        #expect(CompatPalette.title(99) == "Automática")
    }

    @Test func storageUsageCountsOnlyAppFolders() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let saves = root.appendingPathComponent("Saves"), states = root.appendingPathComponent("States")
        try FileManager.default.createDirectory(at: saves.appendingPathComponent("backups"), withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: states, withIntermediateDirectories: true)
        try Data(count: 1_000).write(to: saves.appendingPathComponent("a.sav"))
        try Data(count: 500).write(to: saves.appendingPathComponent("backups/a.1.sav"))
        try Data(count: 2_000).write(to: states.appendingPathComponent("s.state"))
        let usage = StorageUsage.measure(saves: saves, states: states, artwork: root.appendingPathComponent("nada"))
        #expect(usage == StorageUsage(saves: 1_500, states: 2_000, artwork: 0))
    }

    @Test func noBackgroundAudioMode() throws {
        let modes = Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") as? [String] ?? []
        #expect(!modes.contains("audio"))
    }
}

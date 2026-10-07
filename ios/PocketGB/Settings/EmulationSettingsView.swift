import SwiftUI

/// Ajustes › Emulación: juegos de Game Boy en color (en una Game Boy Color, con paleta)
/// y la paleta. Cada juego puede personalizarlo desde su detalle.
struct EmulationSettingsView: View {
    @Environment(AppState.self) private var state

    var body: some View {
        let gameplay = state.gameplay
        Form {
            Section {
                Toggle("Color en juegos de Game Boy",
                       isOn: Binding(get: { gameplay.data.colorForGameBoy },
                                     set: { v in gameplay.update { $0.colorForGameBoy = v } }))
                Picker("Paleta", selection: Binding(get: { gameplay.data.compatPalette },
                                                    set: { v in gameplay.update { $0.compatPalette = v } })) {
                    ForEach(0...UInt8(CompatPalette.count), id: \.self) { Text(CompatPalette.title($0)).tag($0) }
                }
                .disabled(!gameplay.data.colorForGameBoy)
            } header: {
                Text("Juegos de Game Boy")
            } footer: {
                Text("Como en una Game Boy Color: los juegos de Game Boy se ven con una paleta de color. “Automática” es la que elegiría la consola por el título; las demás, las combinaciones de botones al encenderla. Se aplica al abrir el juego. Los estados guardados en un modo no se cargan en el otro.")
            }
            Section {
                LabeledContent("Juegos de Game Boy Color", value: "Siempre en color")
            }
            Section {
                LabeledContent("BIOS", value: state.gbaBIOSStatus?.settingsText ?? "Comprobando…")
            } header: {
                Text("Juegos de Game Boy Advance")
            } footer: {
                Text("Opcional: copia tu propio volcado como “\(BIOSFile.fileName)” en la carpeta de juegos. Solo se usa si es la BIOS oficial; si no, PocketGB emula sus funciones y los juegos funcionan igual.")
            }
        }
        .scrollContentBackground(.hidden)
        .background(PocketColor.backgroundBase.ignoresSafeArea())
        .navigationTitle("Emulación")
        .onAppear { state.refreshBIOSStatus() }
    }
}

/// Ajustes de un juego (SPEC §8, `SettingsValueBadge`): cada valor dice con texto si es
/// "Global" o "Personalizado". Se guardan por huella (N1a): siguen al juego si se mueve de
/// carpeta y los comparten sus duplicados.
struct GameSettingsView: View {
    @Environment(AppState.self) private var state
    @Environment(\.dismiss) private var dismiss
    /// El juego tal como estaba al abrir la sheet; `entry` lo relee con su huella actual.
    private let opened: RomEntry

    init(entry: RomEntry) {
        opened = entry
    }

    private var entry: RomEntry {
        state.library.entries.first { $0.id == opened.id } ?? opened
    }

    private func set(_ overrides: GameOverrides) {
        state.libraryPrefs.setOverrides(overrides, for: entry)
    }

    var body: some View {
        let gameplay = state.gameplay
        let entry = self.entry
        let overrides = state.libraryPrefs.overrides(for: entry)
        let resolved = gameplay.data.emulation(with: overrides)
        NavigationStack {
            Form {
                if entry.badge != .gba {
                    Section {
                        Picker(selection: Binding(
                            get: { overrides.colorForGameBoy.map { $0 ? 1 : 2 } ?? 0 },
                            set: { v in
                                var o = overrides
                                o.colorForGameBoy = v == 0 ? nil : v == 1
                                set(o)
                            })) {
                            Text("Global (\(gameplay.data.colorForGameBoy ? "en color" : "sin color"))").tag(0)
                            Text("En color").tag(1)
                            Text("Sin color").tag(2)
                        } label: {
                            SettingRowLabel(title: "Color", customized: overrides.colorForGameBoy != nil)
                        }
                        Picker(selection: Binding(
                            get: { overrides.compatPalette.map(Int.init) ?? -1 },
                            set: { v in
                                var o = overrides
                                o.compatPalette = v < 0 ? nil : UInt8(v)
                                set(o)
                            })) {
                            Text("Global (\(CompatPalette.title(gameplay.data.compatPalette)))").tag(-1)
                            ForEach(0...CompatPalette.count, id: \.self) { Text(CompatPalette.title(UInt8($0))).tag($0) }
                        } label: {
                            SettingRowLabel(title: "Paleta", customized: overrides.compatPalette != nil)
                        }
                        .disabled(!resolved.colorForGameBoy)
                    } header: {
                        Text(entry.isColor ? "Juego de Game Boy Color: siempre en color" : "Juego de Game Boy")
                    } footer: {
                        Text("Se aplica la próxima vez que abras el juego.")
                    }
                    .disabled(entry.badge != .gb)
                }
                if entry.badge == .gba { gbaSection(overrides) }
                if !overrides.isEmpty {
                    Section {
                        Button("Usar los ajustes globales", systemImage: "arrow.uturn.backward") {
                            set(GameOverrides())
                        }
                    }
                }
            }
            .navigationTitle(entry.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Listo") { dismiss() } }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

extension GameSettingsView {
    /// Tipos de partida que se pueden forzar (`GBA_SAVE_*`); sin forzar, el núcleo detecta el tipo.
    static let gbaSaveTypes: [(value: UInt8, title: String)] = [
        (1, "Sin partida"), (2, "SRAM 32 KiB"), (3, "Flash 64 KiB"),
        (4, "Flash 128 KiB"), (5, "EEPROM 512 B"), (6, "EEPROM 8 KiB"),
    ]

    /// «Detectado (Flash 64 KiB)» con lo que el núcleo detectó la última vez que se abrió el juego
    /// sin ajustes forzados; solo «Detectado» si aún no se conoce.
    private var detectedRecord: SavesIndex.Record? {
        guard let fingerprint = state.libraryPrefs.fingerprint(of: entry),
              let dir = try? SaveStore.defaultDirectory() else { return nil }
        return SavesIndex(directory: dir).load()[fingerprint]
    }

    @ViewBuilder func gbaSection(_ overrides: GameOverrides) -> some View {
        let record = detectedRecord
        let detectedMedia = record?.gbaMedia.map { "Detectado (\($0))" } ?? "Detectado"
        let detectedRTC = record?.gbaHasRTC.map { "Detectado (\($0 ? "con reloj" : "sin reloj"))" } ?? "Detectado"
        Section {
            Picker(selection: Binding(
                get: { overrides.gbaSaveType.map(Int.init) ?? -1 },
                set: { v in
                    var o = overrides
                    o.gbaSaveType = v < 0 ? nil : UInt8(v)
                    set(o)
                })) {
                Text(detectedMedia).tag(-1)
                ForEach(Self.gbaSaveTypes, id: \.value) { Text($0.title).tag(Int($0.value)) }
            } label: {
                SettingRowLabel(title: "Tipo de partida", customized: overrides.gbaSaveType != nil)
            }
            Picker(selection: Binding(
                get: { overrides.gbaRTC.map(Int.init) ?? -1 },
                set: { v in
                    var o = overrides
                    o.gbaRTC = v < 0 ? nil : UInt8(v)
                    set(o)
                })) {
                Text(detectedRTC).tag(-1)
                Text("Con reloj").tag(1)
                Text("Sin reloj").tag(2)
            } label: {
                SettingRowLabel(title: "Reloj (RTC)", customized: overrides.gbaRTC != nil)
            }
            Picker(selection: Binding(
                get: { overrides.gbaUseBIOS == false ? 2 : 0 },
                set: { v in
                    var o = overrides
                    o.gbaUseBIOS = v == 2 ? false : nil
                    set(o)
                })) {
                Text("Global (la tuya si existe)").tag(0)
                Text("Emulada").tag(2)
            } label: {
                SettingRowLabel(title: "BIOS", customized: overrides.gbaUseBIOS != nil)
            }
        } header: {
            Text("Game Boy Advance")
        } footer: {
            Text("Cambia solo si un juego no guarda bien. Si el tipo no coincide con su partida guardada, PocketGB no la sobrescribe y avisa. Se aplica la próxima vez que abras el juego.")
        }
    }
}

/// Etiqueta de un ajuste con su origen en texto: "Global" o "Personalizado".
struct SettingRowLabel: View {
    let title: String
    let customized: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
            HStack(spacing: PocketSpacing.xxs) {
                Image(systemName: customized ? "slider.horizontal.3" : "globe")
                    .imageScale(.small)
                    .accessibilityHidden(true)
                Text(customized ? "Personalizado" : "Global")
            }
            .font(.caption)
            .foregroundStyle(customized ? PocketColor.accent : .secondary)
            .fixedSize()
        }
        .accessibilityElement(children: .combine)
    }
}

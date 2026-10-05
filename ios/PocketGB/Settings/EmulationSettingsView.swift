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
/// "Global" o "Personalizado".
struct GameSettingsView: View {
    @Environment(AppState.self) private var state
    @Environment(\.dismiss) private var dismiss
    let entry: RomEntry

    var body: some View {
        let gameplay = state.gameplay
        let overrides = gameplay.data.perGame[entry.id] ?? GameOverrides()
        let resolved = gameplay.data.emulation(for: entry.id)
        NavigationStack {
            Form {
                Section {
                    Picker(selection: Binding(
                        get: { overrides.colorForGameBoy.map { $0 ? 1 : 2 } ?? 0 },
                        set: { v in
                            var o = overrides
                            o.colorForGameBoy = v == 0 ? nil : v == 1
                            gameplay.setOverrides(o, for: entry.id)
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
                            gameplay.setOverrides(o, for: entry.id)
                        })) {
                        Text("Global (\(CompatPalette.title(gameplay.data.compatPalette)))").tag(-1)
                        ForEach(0...CompatPalette.count, id: \.self) { Text(CompatPalette.title(UInt8($0))).tag($0) }
                    } label: {
                        SettingRowLabel(title: "Paleta", customized: overrides.compatPalette != nil)
                    }
                    .disabled(!resolved.colorForGameBoy)
                } header: {
                    Text(entry.badge == .gba ? "Juego de Game Boy Advance: sin ajustes de color"
                         : entry.isColor ? "Juego de Game Boy Color: siempre en color" : "Juego de Game Boy")
                } footer: {
                    Text("Se aplica la próxima vez que abras el juego.")
                }
                .disabled(entry.badge != .gb)
                if !overrides.isEmpty {
                    Section {
                        Button("Usar los ajustes globales", systemImage: "arrow.uturn.backward") {
                            gameplay.setOverrides(GameOverrides(), for: entry.id)
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

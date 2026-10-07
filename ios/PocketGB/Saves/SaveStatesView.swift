import SwiftUI

/// Estados guardados (SPEC §9, `save-states`): la automática y cuatro manuales, con
/// selección y una barra de acciones de vidrio. Cargar pide confirmación y ofrece
/// "Guardar actual y cargar"; reemplazar una ranura ocupada también se confirma.
struct SaveStatesView: View {
    @Environment(AppState.self) private var state
    @State private var selected: StateSlot = .manual1

    private let columns = [GridItem(.flexible(), spacing: PocketSpacing.sm),
                           GridItem(.flexible(), spacing: PocketSpacing.sm)]

    var body: some View {
        let entries = state.stateEntries
        ScrollView {
            VStack(alignment: .leading, spacing: PocketSpacing.md) {
                LazyVGrid(columns: columns, spacing: PocketSpacing.md) {
                    ForEach(StateSlot.allCases) { slot in
                        Button { selected = slot } label: {
                            SaveStateCard(slot: slot, entry: entries[slot], selected: selected == slot,
                                          console: state.session?.info.console ?? .gameBoy)
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("state-\(slot.fileStem)")
                    }
                }
                Text("Los estados guardan el momento exacto del juego. Son distintos de la partida del cartucho, que se guarda sola.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            .padding(PocketSpacing.md)
            .padding(.bottom, 80)
        }
        .overlay(alignment: .bottom) { actionBar(entries[selected]) }
        .navigationTitle("Estados")
        .navigationBarTitleDisplayMode(.inline)
        .task { state.reloadStates() }
        .alert(state.pendingStateLoad.map { "¿Cargar \($0.title.lowercased())?" } ?? "",
               isPresented: Binding(get: { state.pendingStateLoad != nil },
                                    set: { if !$0 { state.pendingStateLoad = nil } })) {
            if let slot = state.pendingStateLoad {
                if slot != .auto {
                    Button("Guardar actual y cargar") { state.loadState(from: slot, saveCurrentFirst: true) }
                }
                Button("Cargar sin guardar", role: .destructive) { state.loadState(from: slot, saveCurrentFirst: false) }
            }
            Button("Cancelar", role: .cancel) {}
        } message: {
            Text(state.pendingStateLoad == .auto
                 ? "Perderás lo jugado desde el último estado. La partida del cartucho queda con copia de seguridad."
                 : "Perderás lo jugado desde el último estado. “Guardar actual y cargar” lo deja antes en la ranura automática.")
        }
        .alert(state.pendingStateReplace.map { "¿Reemplazar \($0.title.lowercased())?" } ?? "",
               isPresented: Binding(get: { state.pendingStateReplace != nil },
                                    set: { if !$0 { state.pendingStateReplace = nil } })) {
            if let slot = state.pendingStateReplace {
                Button("Reemplazar", role: .destructive) { state.saveState(to: slot) }
            }
            Button("Cancelar", role: .cancel) {}
        } message: {
            if let slot = state.pendingStateReplace, let date = entries[slot]?.date {
                Text("El estado guardado \(GameStatus.stateDate(date)) se sustituirá por el momento actual.")
            }
        }
    }

    private func actionBar(_ entry: StateStore.Entry?) -> some View {
        let occupied = entry != nil
        let corrupt = entry?.corrupt == true
        return GlassEffectContainer(spacing: PocketSpacing.xs) {
            HStack(spacing: PocketSpacing.xs) {
                Button("Cargar", systemImage: "arrow.down.doc") { state.pendingStateLoad = selected }
                    .pocketGlassButton(prominent: true)
                    .disabled(!occupied || corrupt)
                Button("Guardar aquí", systemImage: "square.and.arrow.down") {
                    if occupied && !corrupt { state.pendingStateReplace = selected } else { state.saveState(to: selected) }
                }
                .pocketGlassButton()
                .disabled(selected == .auto)
                Button("Borrar", systemImage: "trash", role: .destructive) { state.deleteState(selected) }
                    .pocketGlassButton()
                    .disabled(!occupied)
                    .labelStyle(.iconOnly)
            }
            .controlSize(.large)
        }
        .padding(.bottom, PocketSpacing.md)
    }
}

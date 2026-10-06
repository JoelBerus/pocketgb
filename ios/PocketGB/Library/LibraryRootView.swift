import SwiftUI

/// Shell de la app: tres tabs nativas con Liquid Glass del sistema (SPEC §4 y §6).
/// La tab bar se minimiza al desplazarse hacia abajo.
struct LibraryRootView: View {
    @Environment(AppState.self) private var state

    var body: some View {
        @Bindable var state = state
        TabView(selection: $state.selectedTab) {
            Tab("Biblioteca", systemImage: "square.grid.2x2", value: AppTab.library) {
                LibraryView()
            }
            Tab("Favoritos", systemImage: "star", value: AppTab.favorites) {
                FavoritesView()
            }
            Tab("Ajustes", systemImage: "gearshape", value: AppTab.settings) {
                SettingsView()
            }
        }
        .tabBarMinimizeBehavior(.onScrollDown)
        .modifier(HideGameAlert())
        .modifier(LinkContinueAlert())
        .sheet(item: $state.linkPartnerSource, onDismiss: { state.startPendingLink() }) { entry in
            LinkPartnerPicker(source: entry)
                .environment(state)
                #if DEBUG
                .modifier(DebugDynamicType())   // la sheet no hereda el tipo accesible forzado (captura AX5)
                #endif
        }
        .sheet(item: Binding(get: { state.gameSettingsEntry }, set: { state.gameSettingsEntry = $0 })) { entry in
            GameSettingsView(entry: entry)
                .environment(state)
        }
        .sheet(item: Binding(get: { state.renamingEntry }, set: { state.renamingEntry = $0 })) { entry in
            RenameGameView(entry: entry)
                .environment(state)
        }
    }
}

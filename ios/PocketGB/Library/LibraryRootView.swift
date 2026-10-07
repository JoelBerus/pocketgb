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
        .overlay { LibraryToolsOverlay() }
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

/// N3b: el grupo flotante de la biblioteca en horizontal, encima del `TabView` y en coordenadas
/// de la ventana. A la derecha; con la barra de pestañas encogida, en la fila de su burbuja;
/// desplegada, 8 pt por encima de ella (`LibraryToolsPosition`).
private struct LibraryToolsOverlay: View {
    @Environment(AppState.self) private var state
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var visible: Bool {
        guard verticalSizeClass == .compact, state.selectedTab == .library, state.libraryPath.isEmpty,
              state.libraryTools.showsGames, !state.librarySearchPresented,
              state.librarySearch.isEmpty, case .ready = state.library.phase else { return false }
        return !state.library.entries.isEmpty
    }

    var body: some View {
        GeometryReader { proxy in
            if visible {
                let tools = state.libraryTools
                let window = proxy.frame(in: .global)
                let bottom = window.maxY + proxy.safeAreaInsets.bottom
                let gap = LibraryToolsPosition.bottomGap(minimized: tools.tabBarMinimized)
                let trailing = window.maxX - PocketSpacing.md
                let groupTop = bottom - gap - LibraryToolsPosition.groupHeight
                let maxHeight = LibraryToolsPosition.panelMaxHeight(
                    groupTop: groupTop, visibleTop: tools.visibleTop, titleFrame: tools.sectionTitleFrame,
                    panelMinX: trailing - ToolPanelMetrics.width)
                LibraryToolsGroup(panelMaxHeight: maxHeight) { tools.searchRequests += 1 }
                    .position(x: trailing - LibraryToolsGroup.width / 2 - window.minX,
                              y: groupTop + LibraryToolsPosition.groupHeight / 2 - window.minY)
                    .animation(PocketMotion.reducesMotion(system: reduceMotion) ? nil : .spring(duration: 0.3),
                               value: tools.tabBarMinimized)
                    .transition(.opacity)
            }
        }
    }
}

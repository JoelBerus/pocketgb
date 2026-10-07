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
/// de la ventana. Solo al desplazar (barra de pestañas encogida en burbuja): a la derecha, en la
/// fila de la burbuja. En reposo las herramientas van en la barra de navegación
/// (`LibraryToolsPlacement`). El estado de la barra se reinicia al cambiar de pestaña, de ruta, de
/// orientación o de carpeta: la lista vuelve a mostrarse arriba con la barra desplegada (H2).
private struct LibraryToolsOverlay: View {
    @Environment(AppState.self) private var state
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var visible: Bool {
        // Solo con juegos en pantalla (carpeta lista y con juegos) y sin búsqueda abierta.
        guard LibraryToolsPlacement.placement(compactHeight: verticalSizeClass == .compact,
                                              tabBarMinimized: state.libraryTools.tabBarMinimized) == .floatingGroup,
              state.selectedTab == .library, state.libraryPath.isEmpty,
              !state.librarySearchPresented, state.librarySearch.isEmpty,
              case .ready = state.library.phase else { return false }
        return !state.library.entries.isEmpty
    }

    var body: some View {
        GeometryReader { proxy in
            if visible {
                let tools = state.libraryTools
                let window = proxy.frame(in: .global)
                let bottom = window.maxY + proxy.safeAreaInsets.bottom
                let trailing = window.maxX - PocketSpacing.md
                let groupTop = bottom - LibraryToolsPosition.bottomGap - LibraryToolsPosition.groupHeight
                let maxHeight = LibraryToolsPosition.panelMaxHeight(
                    groupTop: groupTop, visibleTop: tools.visibleTop, headerFrame: tools.sectionHeaderFrame)
                LibraryToolsGroup(panelMaxHeight: maxHeight) { tools.searchRequests += 1 }
                    .position(x: trailing - LibraryToolsGroup.width / 2 - window.minX,
                              y: groupTop + LibraryToolsPosition.groupHeight / 2 - window.minY)
                    .transition(PocketMotion.reducesMotion(system: reduceMotion) ? .opacity
                                : .opacity.combined(with: .move(edge: .bottom)))
            }
        }
        .animation(PocketMotion.reducesMotion(system: reduceMotion) ? nil : .spring(duration: 0.3), value: visible)
        .onChange(of: state.selectedTab) { state.libraryTools.reset() }
        .onChange(of: state.libraryPath) { state.libraryTools.reset() }
        .onChange(of: verticalSizeClass) { state.libraryTools.reset() }
        .onChange(of: state.library.rootURL) { state.libraryTools.reset() }
    }
}

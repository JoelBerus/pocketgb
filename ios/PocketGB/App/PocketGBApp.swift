import SwiftUI
import UIKit
import UniformTypeIdentifiers

@main
struct PocketGBApp: App {
    @State private var state = AppState()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(state)
                .onReceive(NotificationCenter.default.publisher(
                    for: UIApplication.didReceiveMemoryWarningNotification)) { _ in
                    state.memoryWarning()
                }
                #if DEBUG
                .task { state.openFromLaunchArguments() }
                #endif
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                state.enterForeground()
            } else {
                state.enterBackground()
            }
        }
    }
}

/// Raíz: la shell de tabs o, con un juego abierto, el gameplay a pantalla completa
/// (sin tab bar ni barra de estado).
struct RootView: View {
    @Environment(AppState.self) private var state
    @AppStorage(AppearancePreference.storageKey) private var appearance = AppearancePreference.system.rawValue

    /// Apariencia elegida en Ajustes; en DEBUG, `-uiStyle` manda. Con un juego
    /// abierto, siempre oscuro (también sus alertas; auditoría D1, H5).
    private var colorScheme: ColorScheme? {
        if state.session != nil { return .dark }
        #if DEBUG
        if let forced = DebugArguments.colorScheme { return forced }
        #endif
        return AppearancePreference(rawValue: appearance)?.colorScheme
    }

    var body: some View {
        @Bindable var state = state
        Group {
            if let session = state.session {
                GameScreen(session: session)
                    .environment(\.colorScheme, .dark)   // gameplay siempre oscuro (SPEC §9)
            } else {
                #if DEBUG
                if state.debugShowsLaunch {
                    LaunchPreviewView()
                } else if let unknown = state.debugUnknownScreen {
                    UnknownScreenView(id: unknown)
                } else {
                    LibraryRootView()
                }
                #else
                LibraryRootView()
                #endif
            }
        }
        .statusBarHidden(state.session != nil || state.debugShowsLaunch)
        .preferredColorScheme(colorScheme)
        .tint(PocketColor.accent)
        .sheet(isPresented: $state.pickingFolder) {
            FolderPicker { url in state.library.choose(folder: url) }
                .ignoresSafeArea()
        }
        .overlay {
            if state.opening {
                ProgressView("Abriendo…")
                    .padding(PocketSpacing.lg)
                    .glassEffect(.regular, in: RoundedRectangle(cornerRadius: PocketRadius.group))
            }
        }
        .alert(state.alertTitle ?? "PocketGB",
               isPresented: Binding(get: { state.alertMessage != nil },
                                    set: { if !$0 { state.alertMessage = nil; state.alertTitle = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(state.alertMessage ?? "")
        }
    }
}

#if DEBUG
/// `-screen` con un id que el router no conoce: error visible para que el CI lo detecte.
private struct UnknownScreenView: View {
    let id: String

    var body: some View {
        ContentUnavailableView("Pantalla desconocida",
                               systemImage: "exclamationmark.triangle",
                               description: Text(id))
            .accessibilityIdentifier("debug-unknown-screen")
    }
}
#endif

struct GameScreen: View {
    @Environment(AppState.self) private var state
    let session: EmulatorSession
    #if DEBUG
    @State private var showDebugHUD = DebugArguments.debugHUD
    #endif

    var body: some View {
        GeometryReader { geo in
            let landscape = geo.size.width > geo.size.height
            ZStack {
                Color.black.ignoresSafeArea()
                if landscape {
                    // Horizontal: pantalla completa; los controles usan el safe area por dentro.
                    ZStack {
                        GameMetalView(frames: session.frames, integerScale: true)
                            #if DEBUG
                            .overlay {
                                ThreeFingerTapInstaller { showDebugHUD.toggle() }
                                    .allowsHitTesting(false)
                            }
                            #endif
                        ControlsOverlay(buttons: session.buttons, portrait: false) { state.closeGame() }
                    }
                    .ignoresSafeArea()
                } else {
                    // Vertical: imagen bajo la Dynamic Island, controles hasta el borde inferior.
                    VStack(spacing: 0) {
                        GameMetalView(frames: session.frames, integerScale: false)
                            .aspectRatio(10.0 / 9.0, contentMode: .fit)
                            #if DEBUG
                            .overlay {
                                ThreeFingerTapInstaller { showDebugHUD.toggle() }
                                    .allowsHitTesting(false)
                            }
                            #endif
                        ControlsOverlay(buttons: session.buttons, portrait: true) { state.closeGame() }
                            .ignoresSafeArea(edges: .bottom)
                    }
                }
                if state.paused {
                    Color.black.opacity(0.5).ignoresSafeArea()
                    Button("Continuar") { state.resume() }
                        .buttonStyle(.borderedProminent)
                        .font(.title2)
                }
                #if DEBUG
                if showDebugHUD {
                    DebugHUD(session: session)
                        .frame(maxWidth: .infinity, maxHeight: .infinity,
                               alignment: .topLeading)
                        .padding(6)
                        .allowsHitTesting(false)
                }
                #endif
            }
        }
        .persistentSystemOverlays(.hidden)
        .defersSystemGestures(on: .all)
    }
}

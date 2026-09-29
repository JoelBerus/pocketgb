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
            if phase != .active { state.enterBackground() }
        }
    }
}

/// Raíz: la shell de tabs o, con un juego abierto, el gameplay a pantalla completa
/// (sin tab bar ni barra de estado).
struct RootView: View {
    @Environment(AppState.self) private var state
    @AppStorage(AppearancePreference.storageKey) private var appearance = AppearancePreference.system.rawValue

    /// Apariencia elegida en Ajustes; en DEBUG, `-uiStyle` manda.
    private var colorScheme: ColorScheme? {
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
            } else if state.debugShowsLaunch {
                LaunchPreviewView()
            } else if let unknown = state.debugUnknownScreen {
                UnknownScreenView(id: unknown)
            } else {
                LibraryRootView()
            }
        }
        .statusBarHidden(state.session != nil || state.debugShowsLaunch)
        .preferredColorScheme(colorScheme)
        .tint(PocketColor.accent)
        .sheet(isPresented: $state.pickingROM) {
            RomPicker { url in state.open(url: url) }
                .ignoresSafeArea()
        }
        .alert("La carpeta de juegos llega pronto", isPresented: $state.folderNoticeShown) {
            Button("Abrir un archivo") { state.pickingROM = true }
            Button("Cancelar", role: .cancel) {}
        } message: {
            Text("La biblioteca por carpeta con iCloud Drive llega en la próxima fase (D2). Mientras tanto puedes abrir un ROM suelto.")
        }
        .alert("PocketGB", isPresented: Binding(get: { state.alertMessage != nil },
                                                set: { if !$0 { state.alertMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(state.alertMessage ?? "")
        }
    }
}

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

/// `UIDocumentPickerViewController` para un solo archivo, como copia temporal
/// (la biblioteca con bookmark de carpeta llega en M6).
struct RomPicker: UIViewControllerRepresentable {
    let onPick: (URL) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onPick: onPick) }

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.data], asCopy: true)
        picker.allowsMultipleSelection = false
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onPick: (URL) -> Void
        init(onPick: @escaping (URL) -> Void) { self.onPick = onPick }

        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            if let url = urls.first { onPick(url) }
        }
    }
}

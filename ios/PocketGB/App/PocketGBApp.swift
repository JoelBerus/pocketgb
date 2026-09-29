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
                .statusBarHidden()
                #if DEBUG
                .preferredColorScheme(DebugArguments.colorScheme)
                #endif
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

struct RootView: View {
    @Environment(AppState.self) private var state
    @State private var picking = false

    var body: some View {
        @Bindable var state = state
        Group {
            if let session = state.session {
                GameScreen(session: session)
            } else {
                StartView(picking: $picking)
            }
        }
        .sheet(isPresented: $picking) {
            RomPicker { url in state.open(url: url) }
                .ignoresSafeArea()
        }
        .alert("PocketGB", isPresented: Binding(get: { state.alertMessage != nil },
                                                set: { if !$0 { state.alertMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(state.alertMessage ?? "")
        }
    }
}

struct StartView: View {
    @Binding var picking: Bool

    var body: some View {
        VStack(spacing: 24) {
            Text("PocketGB").font(.largeTitle.bold())
            Button {
                picking = true
            } label: {
                Label("Abrir ROM", systemImage: "folder")
                    .font(.title3)
            }
            .buttonStyle(.borderedProminent)
            Text("Solo ROMs volcados de tus propios cartuchos.")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
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

#if DEBUG
/// Argumentos de arranque solo para pruebas y capturas (ios/README.md).
enum DebugArguments {
    static var debugHUD: Bool { ProcessInfo.processInfo.arguments.contains("-debugHUD") }

    /// `-uiStyle light|dark` fuerza la apariencia.
    static var colorScheme: ColorScheme? {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-uiStyle"), i + 1 < args.count else { return nil }
        switch args[i + 1] {
        case "light": return .light
        case "dark": return .dark
        default: return nil
        }
    }
}
#endif

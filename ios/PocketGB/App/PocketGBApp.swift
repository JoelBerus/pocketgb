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
        #if DEBUG
        .modifier(DebugDynamicType())
        #endif
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
                    .pocketGlass(in: RoundedRectangle(cornerRadius: PocketRadius.group))
            }
        }
        .alert(state.alertTitle ?? "PocketGB",
               isPresented: Binding(get: { state.alertMessage != nil },
                                    set: { if !$0 { state.alertMessage = nil; state.alertTitle = nil } })) {
            if state.resumeFallbackEntry != nil {
                Button("Jugar desde el inicio") { state.playFromBeginningAfterResumeError() }
                Button("Cancelar", role: .cancel) { state.resumeFallbackEntry = nil }
            } else {
                Button("OK", role: .cancel) {}
            }
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

#if DEBUG
/// `-contentSizeCategory accessibility5`: Dynamic Type fijo para la captura `library-ax5`.
struct DebugDynamicType: ViewModifier {
    func body(content: Content) -> some View {
        if DebugArguments.value("-contentSizeCategory") == "accessibility5" {
            content.dynamicTypeSize(.accessibility5)
        } else {
            content
        }
    }
}
#endif

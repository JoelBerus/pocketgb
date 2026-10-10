import SwiftUI
import UIKit
import UniformTypeIdentifiers

@main
struct PocketGBApp: App {
    @State private var state = AppState()
    @Environment(\.scenePhase) private var scenePhase

    init() {
        PocketTips.configure()   // N9: consejos locales, sin red
    }

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
        // N7a · divergencia: se pregunta antes de cargar nada; las dos partidas se conservan.
        // Alerta (no hoja de acciones) para que «Cancelar» se vea siempre, como en Android: no abre el juego.
        .alert("Dos partidas distintas", isPresented: Binding(
            get: { state.divergencePrompt != nil }, set: { if !$0 { state.divergencePrompt = nil } })) {
            Button("Seguir con la de este iPhone") { state.resolveDivergence(.keepLocal) }
            Button("Usar la del otro equipo") { state.resolveDivergence(.useOther) }
            Button("Cancelar", role: .cancel) {}
        } message: {
            Text(state.divergencePrompt?.message ?? "")
        }
        // N7b · importar: «Abrir con PocketGB» y la elección ante una divergencia.
        .onOpenURL { url in state.handleIncomingFile(url) }
        .confirmationDialog(state.importPrompt?.title ?? "", isPresented: Binding(
            get: { state.importPrompt != nil }, set: { if !$0 { state.importPrompt = nil } }),
                            titleVisibility: .visible) {
            if state.importPrompt?.stateOnly == true {
                Button("Continuar donde lo dejaste\(state.importPrompt?.plan.origin.map { " en \($0)" } ?? "")") {
                    state.resolveImport(.useOther)
                }
                Button("Mantener el de este iPhone") { state.resolveImport(.keepLocal) }
            } else if state.importPrompt?.plan.raw == true {
                Button("Usar este .sav") { state.resolveImport(.useOther) }
            } else {
                Button("Usar la\(state.importPrompt?.plan.origin.map { " de \($0)" } ?? " del paquete")") {
                    state.resolveImport(.useOther)
                }
                Button("Seguir con la de este iPhone") { state.resolveImport(.keepLocal) }
            }
            Button("Cancelar", role: .cancel) {}
        } message: {
            Text(state.importPrompt?.message ?? "")
        }
        .confirmationDialog("¿De qué juego es «\(state.saveTargetChoice?.fileName ?? "")»?", isPresented: Binding(
            get: { state.saveTargetChoice != nil }, set: { if !$0 { state.saveTargetChoice = nil } }),
                            titleVisibility: .visible) {
            ForEach(state.saveTargetChoice?.candidates ?? [], id: \.id) { entry in
                Button("\(state.libraryPrefs.displayTitle(entry)) · \(entry.locationText)") {
                    if let choice = state.saveTargetChoice {
                        state.saveTargetChoice = nil
                        state.importSave(choice.data, into: entry)
                    }
                }
            }
            Button("Cancelar", role: .cancel) {}
        } message: {
            Text("Hay varios juegos con ese nombre. Elige en cuál importar la partida; se te pedirá confirmación.")
        }
        .overlay {
            if state.travelBusy {
                ProgressView("Preparando la partida…")
                    .padding(PocketSpacing.lg)
                    .pocketGlass(in: RoundedRectangle(cornerRadius: PocketRadius.group))
            }
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

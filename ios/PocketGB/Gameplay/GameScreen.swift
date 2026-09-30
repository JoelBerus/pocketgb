import SwiftUI

/// Gameplay a pantalla completa (SPEC §10): viewport 10:9 y una única capa multitáctil.
/// Vertical: imagen arriba y controles sobre `GameplayBackground`. Horizontal: imagen a
/// todo el alto y controles de vidrio claro dentro del área segura.
struct GameScreen: View {
    @Environment(AppState.self) private var state
    @Environment(\.accessibilityReduceTransparency) private var systemReduceTransparency
    let session: EmulatorSession
    #if DEBUG
    @State private var showDebugHUD = DebugArguments.debugHUD
    #endif

    private var reduceTransparency: Bool {
        #if DEBUG
        if DebugArguments.reduceTransparency { return true }
        #endif
        return systemReduceTransparency
    }

    var body: some View {
        GeometryReader { geo in
            let orientation: ControlsOrientation = geo.size.width > geo.size.height ? .landscape : .portrait
            ZStack {
                PocketColor.gameplayBackground.ignoresSafeArea()
                if orientation == .landscape {
                    ZStack {
                        Color.black
                        viewport(integerScale: state.gameplay.data.integerScaleLandscape)
                        controls(.landscape)
                    }
                    .ignoresSafeArea()
                } else {
                    // Vertical: imagen bajo la Dynamic Island (no dentro), controles hasta el borde.
                    VStack(spacing: 0) {
                        viewport(integerScale: false)
                            .aspectRatio(10.0 / 9.0, contentMode: .fit)
                        controls(.portrait)
                            .ignoresSafeArea(edges: .bottom)
                    }
                }
                if state.paused {
                    Color.black.opacity(0.5).ignoresSafeArea()
                    Button("Continuar") { state.resume() }
                        .buttonStyle(.glassProminent)
                        .controlSize(.large)
                }
                if state.editingControls {
                    ControlsEditorBar(orientation: orientation)
                }
                #if DEBUG
                if showDebugHUD {
                    DebugHUD(session: session)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                        .padding(6)
                        .allowsHitTesting(false)
                }
                #endif
            }
        }
        .persistentSystemOverlays(.hidden)
        .defersSystemGestures(on: .all)
        .confirmationDialog("Juego", isPresented: Binding(get: { state.showingGameMenu },
                                                          set: { state.showingGameMenu = $0 })) {
            Button("Personalizar controles") { state.editingControls = true }
            Button("Salir del juego", role: .destructive) { state.closeGame() }
            Button("Seguir jugando", role: .cancel) {}
        } message: {
            Text("La partida se guarda al salir.")
        }
    }

    private func viewport(integerScale: Bool) -> some View {
        GameMetalView(frames: session.frames, integerScale: integerScale)
            .accessibilityLabel("Pantalla del juego")
            #if DEBUG
            .overlay {
                ThreeFingerTapInstaller { showDebugHUD.toggle() }
                    .allowsHitTesting(false)
            }
            #endif
    }

    private func controls(_ orientation: ControlsOrientation) -> some View {
        ControlsOverlay(buttons: session.buttons, orientation: orientation, settings: state.gameplay.data,
                        editing: state.editingControls, reduceTransparency: reduceTransparency,
                        onMenu: { state.showingGameMenu = true },
                        onMove: { id, point in state.gameplay.move(id, to: point, orientation: orientation) })
    }
}

/// Barra del editor de controles (SPEC §9, `customize-controls-*`): qué orientación se
/// edita, restablecer y terminar. Arrastrar un control lo mueve; se guarda al soltar.
struct ControlsEditorBar: View {
    @Environment(AppState.self) private var state
    let orientation: ControlsOrientation

    var body: some View {
        VStack(spacing: PocketSpacing.xs) {
            GlassEffectContainer(spacing: PocketSpacing.xs) {
                HStack(spacing: PocketSpacing.xs) {
                    Button("Restablecer", systemImage: "arrow.counterclockwise") {
                        state.gameplay.resetLayout(orientation)
                    }
                    .buttonStyle(.glass)
                    Text("Controles · \(orientation.title)")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, PocketSpacing.sm)
                        .frame(minHeight: PocketSpacing.minTouch)
                        .glassEffect(.regular, in: Capsule())
                    Button("Listo") { state.editingControls = false }
                        .buttonStyle(.glassProminent)
                }
            }
            Text("Arrastra cada control. Se guarda solo para la orientación \(orientation.title.lowercased()).")
                .font(.footnote)
                .foregroundStyle(.white)
                .padding(.horizontal, PocketSpacing.sm)
                .padding(.vertical, PocketSpacing.xxs)
                .background(PocketColor.controlScrim.opacity(0.55), in: Capsule())
        }
        // Horizontal: en el centro (sobre la imagen, entre D-pad y A/B). Vertical: arriba,
        // sobre la imagen, lejos de los controles.
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: orientation == .landscape ? .center : .top)
        .padding(PocketSpacing.md)
    }
}

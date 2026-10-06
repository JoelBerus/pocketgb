import SwiftUI

/// Gameplay a pantalla completa (SPEC §10): viewport 10:9 (GB) o 3:2 (GBA) y una única capa multitáctil.
/// Vertical: imagen arriba y controles sobre `GameplayBackground`. Horizontal: imagen a
/// todo el alto y controles de vidrio claro dentro del área segura.
struct GameScreen: View {
    @Environment(AppState.self) private var state
    @Environment(\.accessibilityReduceTransparency) private var systemReduceTransparency
    let session: EmulatorSession
    #if DEBUG
    @State private var showDebugHUD = DebugArguments.debugHUD
    #endif

    private var isAdvance: Bool { session.info.console == .gameBoyAdvance }

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
                    .overlay(alignment: .top) { hud.padding(.top, 2) }
                } else {
                    // Vertical: imagen bajo la Dynamic Island (no dentro), controles hasta el borde.
                    VStack(spacing: 0) {
                        viewport(integerScale: false)
                            .aspectRatio(session.frames.size.aspectRatio, contentMode: .fit)
                        controls(.portrait)
                            .ignoresSafeArea(edges: .bottom)
                            .overlay(alignment: .top) { hud.padding(.top, 6) }
                            .overlay(alignment: .topTrailing) { peerPreview(width: 72) }
                    }
                }
                if orientation == .landscape {
                    // Hija del ZStack raíz: respeta el área segura (recorte y Dynamic Island).
                    peerPreview(width: 96)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                }
                if state.paused {
                    // Frame atenuado detrás de la sheet de pausa.
                    Color.black.opacity(0.45).ignoresSafeArea().allowsHitTesting(false)
                }
                if let toast = state.gameToast {
                    ToastView(text: toast)
                        .frame(maxHeight: .infinity, alignment: .top)
                        .padding(.top, PocketSpacing.xl)
                        .transition(.opacity)
                }
                if state.editingControls {
                    ControlsEditorBar(orientation: orientation, shoulders: isAdvance)
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
        // La sheet aparece con la emulación ya parada; cerrarla (gesto o "Continuar") reanuda.
        .sheet(isPresented: Binding(get: { state.paused && !state.editingControls },
                                    set: { if !$0 && state.paused { state.resume() } })) {
            PauseView()
                .environment(state)
        }
    }

    @ViewBuilder private var hud: some View {
        if !state.editingControls {
            GameplayHUD()
        }
    }

    /// Miniatura del otro juego del cable. Con el editor abierto se oculta con opacidad (no con
    /// `if`) para no recrear la `MTKView`.
    @ViewBuilder private func peerPreview(width: CGFloat) -> some View {
        if let link = state.link {
            LinkPeerPreview(link: link, width: width)
                .padding(PocketSpacing.xs)
                .opacity(state.editingControls ? 0 : 1)
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
                        showsShoulders: isAdvance,
                        controllerConnected: state.gamepad.connected,
                        editing: state.editingControls, reduceTransparency: reduceTransparency,
                        onMenu: { state.pauseGame() },
                        onMove: { id, point in state.gameplay.move(id, to: point, orientation: orientation, shoulders: isAdvance) },
                        selected: state.editorSelection,
                        onSelect: { state.editorSelection = $0 })
    }
}

/// Barra del editor de controles (SPEC §9, `customize-controls-*`): qué orientación se
/// edita, restablecer y terminar. Arrastrar un control lo mueve; se guarda al soltar.
struct ControlsEditorBar: View {
    @Environment(AppState.self) private var state
    let orientation: ControlsOrientation
    let shoulders: Bool

    var body: some View {
        VStack(spacing: PocketSpacing.xs) {
            GlassEffectContainer(spacing: PocketSpacing.xs) {
                HStack(spacing: PocketSpacing.xs) {
                    Button("Restablecer", systemImage: "arrow.counterclockwise") {
                        state.gameplay.resetLayout(orientation, shoulders: shoulders)
                    }
                    .buttonStyle(.glass)
                    Text("Controles · \(shoulders ? "GBA" : "GB") · \(orientation.title)")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, PocketSpacing.sm)
                        .frame(minHeight: PocketSpacing.minTouch)
                        .glassEffect(.regular.tint(PocketColor.controlScrim.opacity(0.6)), in: Capsule())
                    Button("Listo") { state.editingControls = false }
                        .buttonStyle(.glassProminent)
                }
            }
            if let id = state.editorSelection {
                SizeStepper(id: id, orientation: orientation, shoulders: shoulders)
            }
            Text("Arrastra un control para moverlo; tócalo para cambiar su tamaño. Se guarda solo para \(shoulders ? "Game Boy Advance" : "Game Boy") en \(orientation.title.lowercased()).")
                .font(.footnote)
                .foregroundStyle(.white)
                .padding(.horizontal, PocketSpacing.sm)
                .padding(.vertical, PocketSpacing.xxs)
                .background(PocketColor.controlScrim.opacity(0.55), in: Capsule())
        }
        // Barra de herramientas sobre el lienzo del juego: con tipos accesibles se desbordaba
        // (el texto se partía letra a letra), así que su texto se limita al mayor tamaño no accesible.
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
        // Horizontal: en el centro (sobre la imagen, entre D-pad y A/B). Vertical: arriba,
        // sobre la imagen, lejos de los controles.
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: orientation == .landscape ? .center : .top)
        .padding(PocketSpacing.md)
    }
}

/// Tamaño del control elegido en el editor: − / + en pasos del 10 %.
private struct SizeStepper: View {
    @Environment(AppState.self) private var state
    let id: ControlID
    let orientation: ControlsOrientation
    let shoulders: Bool

    private var scale: CGFloat { state.gameplay.data.layout(orientation, shoulders: shoulders).scale(id) }

    var body: some View {
        GlassEffectContainer(spacing: PocketSpacing.xs) {
            HStack(spacing: PocketSpacing.xs) {
                Button("Más pequeño", systemImage: "minus") {
                    state.gameplay.resize(id, by: -0.1, orientation: orientation, shoulders: shoulders)
                }
                .labelStyle(.iconOnly)
                .buttonStyle(.glass)
                .disabled(scale <= ControlsLayout.scaleRange.lowerBound)
                Text("\(id.editorTitle) · \(Int((scale * 100).rounded())) %")
                    .font(.subheadline.weight(.semibold).monospacedDigit())
                    .foregroundStyle(.white)
                    .padding(.horizontal, PocketSpacing.sm)
                    .frame(minHeight: PocketSpacing.minTouch)
                    .glassEffect(.regular.tint(PocketColor.controlScrim.opacity(0.6)), in: Capsule())
                    .accessibilityLabel("Tamaño de \(id.editorTitle): \(Int((scale * 100).rounded())) por ciento")
                Button("Más grande", systemImage: "plus") {
                    state.gameplay.resize(id, by: 0.1, orientation: orientation, shoulders: shoulders)
                }
                .labelStyle(.iconOnly)
                .buttonStyle(.glass)
                .disabled(scale >= ControlsLayout.scaleRange.upperBound)
            }
            .controlSize(.large)
        }
    }
}

extension ControlID {
    var editorTitle: String {
        switch self {
        case .dpad: "Cruceta"
        case .a: "A"
        case .b: "B"
        case .start: "Start"
        case .select: "Select"
        case .menu: "Menú"
        case .l: "L"
        case .r: "R"
        }
    }
}

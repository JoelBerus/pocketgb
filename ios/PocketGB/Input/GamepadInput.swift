import CoreGraphics
import Foundation
import GameController
import Observation
import PocketGBACore
import PocketGBCore

/// Lectura instantánea de un mando: valores ya normalizados, sin tipos de GameController
/// para poder probar el mapeo sin un mando físico (GamepadMappingTests).
struct GamepadSnapshot: Equatable, Sendable {
    var dpadX: Float = 0, dpadY: Float = 0          // -1…1; y positiva = arriba
    var stickX: Float = 0, stickY: Float = 0
    /// Botones por posición física, no por letra (Xbox, PlayStation y Switch difieren).
    var faceRight = false, faceBottom = false, faceLeft = false, faceTop = false
    var menu = false, options = false
    var shoulderLeft = false, shoulderRight = false   // Game Boy Advance: L y R
}

/// Mapeo por posición (D-README §8): derecho = A, inferior = B (como en la Game Boy),
/// Menu = Start, Options = Select, hombros = L/R (GBA). Cruceta y stick izquierdo dan la misma máscara.
enum GamepadMapping {
    /// Umbral de inclinación del stick y del D-pad analógico.
    static let threshold: Float = 0.5

    static func mask(_ s: GamepadSnapshot) -> UInt16 {
        var mask: UInt16 = 0
        if s.faceRight { mask |= UInt16(GB_BTN_A) }
        if s.faceBottom { mask |= UInt16(GB_BTN_B) }
        if s.menu { mask |= UInt16(GB_BTN_START) }
        if s.options { mask |= UInt16(GB_BTN_SELECT) }
        if s.shoulderLeft { mask |= UInt16(GBA_BTN_L) }
        if s.shoulderRight { mask |= UInt16(GBA_BTN_R) }
        mask |= UInt16(directions(x: s.dpadX, y: s.dpadY))
        mask |= UInt16(directions(x: s.stickX, y: s.stickY))
        // Cruceta y stick a la vez en sentidos contrarios: nunca direcciones opuestas.
        let up = UInt16(GB_BTN_UP), down = UInt16(GB_BTN_DOWN), left = UInt16(GB_BTN_LEFT), right = UInt16(GB_BTN_RIGHT)
        if mask & (up | down) == (up | down) { mask &= ~(up | down) }
        if mask & (left | right) == (left | right) { mask &= ~(left | right) }
        return mask
    }

    /// 8 sectores de 45° con zona muerta propia (el mando no usa el ajuste de diagonales táctil).
    static func directions(x: Float, y: Float) -> UInt8 {
        guard hypot(x, y) >= threshold else { return 0 }
        return ControlsGeometry.dpadMask(dx: CGFloat(x), dy: CGFloat(-y), radius: 0, diagonals: .normal)
    }
}

/// Mandos conectados (GameController). Publica la máscara en su propio `ButtonMask`; la
/// sesión la combina por OR con la táctil. Sin mando físico, nada cambia.
@MainActor @Observable
final class GamepadInput {
    private(set) var connected = false
    private(set) var controllerName: String?
    /// Máscara del mando para la sesión abierta (nil sin juego).
    @ObservationIgnored var target: ButtonMask? { didSet { target?.set(currentMask) } }
    @ObservationIgnored private var currentMask: UInt16 = 0
    @ObservationIgnored private var observers: [NSObjectProtocol] = []

    /// - Parameter observesHardware: false en el catálogo de capturas DEBUG, para que un mando
    ///   emparejado con el Mac del CI no oculte los controles de las capturas.
    init(observesHardware: Bool = true) {
        guard observesHardware else { return }
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: .GCControllerDidConnect, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.refresh() }
        })
        observers.append(center.addObserver(forName: .GCControllerDidDisconnect, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.refresh() }
        })
        refresh()
    }

    #if DEBUG
    /// `-demoController connected`: solo el estado visual (sin mando real).
    func applyDemo() {
        connected = true
        controllerName = "Mando de demostración"
    }
    #endif

    private func refresh() {
        let pad = GCController.controllers().first { $0.extendedGamepad != nil }
        connected = pad != nil
        controllerName = pad?.vendorName
        pad?.extendedGamepad?.valueChangedHandler = { [weak self] gamepad, _ in
            let snapshot = Self.snapshot(gamepad)
            MainActor.assumeIsolated { self?.publish(GamepadMapping.mask(snapshot)) }
        }
        if pad == nil { publish(0) }
    }

    private func publish(_ mask: UInt16) {
        currentMask = mask
        target?.set(mask)
    }

    nonisolated private static func snapshot(_ g: GCExtendedGamepad) -> GamepadSnapshot {
        GamepadSnapshot(dpadX: g.dpad.xAxis.value, dpadY: g.dpad.yAxis.value,
                        stickX: g.leftThumbstick.xAxis.value, stickY: g.leftThumbstick.yAxis.value,
                        faceRight: g.buttonB.isPressed, faceBottom: g.buttonA.isPressed,
                        faceLeft: g.buttonX.isPressed, faceTop: g.buttonY.isPressed,
                        menu: g.buttonMenu.isPressed, options: g.buttonOptions?.isPressed ?? false,
                        shoulderLeft: g.leftShoulder.isPressed, shoulderRight: g.rightShoulder.isPressed)
    }
}

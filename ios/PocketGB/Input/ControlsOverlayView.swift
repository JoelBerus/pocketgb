import os
import PocketGBCore
import SwiftUI
import UIKit

/// Máscara de botones publicada por la UI y leída por el hilo de emulación cada frame.
final class ButtonMask: Sendable {
    private let state = OSAllocatedUnfairLock(initialState: UInt8(0))
    var value: UInt8 { state.withLock { $0 } }
    func set(_ mask: UInt8) { state.withLock { $0 = mask } }
}

/// Una sola UIView multitáctil para todos los controles (docs/04 §Controles).
/// Versión básica de M4: sin háptica, sin editor de disposición ni avance rápido.
final class ControlsOverlayView: UIView {
    enum Control: Hashable {
        case dpad, a, b, ab, start, select, menu
    }

    var buttons: ButtonMask?
    var onMenu: (() -> Void)?
    /// Vertical: controles bajo la imagen, sobre fondo sólido y opacidad 1.0.
    var portrait = false { didSet { if portrait != oldValue { setNeedsLayout() } } }

    private static let idleAlpha: CGFloat = 0.30
    private static let pressedAlpha: CGFloat = 0.60
    private static let dpadRadius: CGFloat = 70
    private static let faceRadius: CGFloat = 34
    private static let abRadius: CGFloat = 18
    private static let pillSize = CGSize(width: 60, height: 24)
    private static let menuSize: CGFloat = 36

    private var touches: [UITouch: Control] = [:]
    private var frames: [Control: CGRect] = [:]
    private var shapes: [Control: (shape: CAShapeLayer, label: CATextLayer?)] = [:]
    private var pressed: Set<Control> = []

    override init(frame: CGRect) {
        super.init(frame: frame)
        isMultipleTouchEnabled = true
        backgroundColor = .clear
        for control in [Control.dpad, .a, .b, .start, .select, .menu] {
            let shape = CAShapeLayer()
            layer.addSublayer(shape)
            var label: CATextLayer?
            if let text = Self.title(control) {
                let l = CATextLayer()
                l.string = text
                l.alignmentMode = .center
                l.contentsScale = UIScreen.main.scale
                l.font = UIFont.systemFont(ofSize: 12, weight: .bold)
                layer.addSublayer(l)
                label = l
            }
            shapes[control] = (shape, label)
        }
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) no se usa") }

    private static func title(_ c: Control) -> String? {
        switch c {
        case .a: "A"
        case .b: "B"
        case .start: "START"
        case .select: "SELECT"
        case .menu: "☰"
        default: nil
        }
    }

    // MARK: - Disposición

    override func layoutSubviews() {
        super.layoutSubviews()
        let area = bounds.inset(by: safeAreaInsets)
        backgroundColor = portrait ? UIColor(white: 0.11, alpha: 1) : .clear

        func point(_ x: CGFloat, _ y: CGFloat) -> CGPoint {
            CGPoint(x: area.minX + area.width * x, y: area.minY + area.height * y)
        }
        func circle(_ c: CGPoint, _ r: CGFloat) -> CGRect {
            CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r)
        }
        func pill(_ c: CGPoint) -> CGRect {
            CGRect(x: c.x - Self.pillSize.width / 2, y: c.y - Self.pillSize.height / 2,
                   width: Self.pillSize.width, height: Self.pillSize.height)
        }

        // Horizontal: tabla de docs/04. Vertical: misma idea dentro del área inferior.
        let dpad = portrait ? point(0.25, 0.42) : point(0.18, 0.62)
        let a = portrait ? point(0.85, 0.34) : point(0.88, 0.55)
        let b = portrait ? point(0.66, 0.50) : point(0.78, 0.68)
        let start = portrait ? point(0.58, 0.82) : point(0.54, 0.92)
        let select = portrait ? point(0.42, 0.82) : point(0.46, 0.92)
        let menu = portrait ? point(0.5, 0.06) : CGPoint(x: area.midX, y: area.minY + Self.menuSize / 2 + 4)

        frames = [
            .dpad: circle(dpad, Self.dpadRadius),
            .a: circle(a, Self.faceRadius),
            .b: circle(b, Self.faceRadius),
            .ab: circle(CGPoint(x: (a.x + b.x) / 2, y: (a.y + b.y) / 2), Self.abRadius),
            .start: pill(start),
            .select: pill(select),
            .menu: circle(menu, Self.menuSize / 2),
        ]

        CATransaction.begin()
        CATransaction.setDisableActions(true)
        for (control, layers) in shapes {
            guard let rect = frames[control] else { continue }
            let path: UIBezierPath
            switch control {
            case .start, .select: path = UIBezierPath(roundedRect: rect, cornerRadius: rect.height / 2)
            case .dpad: path = Self.dpadPath(in: rect)
            default: path = UIBezierPath(ovalIn: rect)
            }
            layers.shape.path = path.cgPath
            layers.shape.lineWidth = 1.5
            if let label = layers.label {
                let fontSize: CGFloat = control == .a || control == .b ? 20 : (control == .menu ? 18 : 11)
                label.fontSize = fontSize
                label.frame = CGRect(x: rect.minX, y: rect.midY - fontSize * 0.62,
                                     width: rect.width, height: fontSize * 1.3)
            }
        }
        CATransaction.commit()
        updateAppearance(animated: false)
    }

    /// Cruz dentro del círculo del D-pad.
    private static func dpadPath(in rect: CGRect) -> UIBezierPath {
        let arm = rect.width / 3
        let path = UIBezierPath(roundedRect: CGRect(x: rect.midX - arm / 2, y: rect.minY, width: arm, height: rect.height),
                                cornerRadius: 6)
        path.append(UIBezierPath(roundedRect: CGRect(x: rect.minX, y: rect.midY - arm / 2, width: rect.width, height: arm),
                                 cornerRadius: 6))
        return path
    }

    private func updateAppearance(animated: Bool) {
        CATransaction.begin()
        CATransaction.setAnimationDuration(animated ? 0.08 : 0)
        CATransaction.setDisableActions(!animated)
        for (control, layers) in shapes {
            let isPressed = pressed.contains(control)
                || (control == .a || control == .b) && pressed.contains(.ab)
            // Vertical: opacos (gris sólido, más claro al pulsar). Horizontal: blanco translúcido.
            let fill = portrait ? UIColor(white: isPressed ? 0.55 : 0.38, alpha: 1)
                                : UIColor(white: 1, alpha: isPressed ? Self.pressedAlpha : Self.idleAlpha)
            let stroke: CGFloat = portrait ? 1 : min(1, (isPressed ? Self.pressedAlpha : Self.idleAlpha) * 2)
            layers.shape.fillColor = fill.cgColor
            layers.shape.strokeColor = UIColor(white: 1, alpha: stroke).cgColor
            layers.label?.foregroundColor = UIColor(white: 1, alpha: stroke).cgColor
        }
        CATransaction.commit()
    }

    // MARK: - Toques

    private func control(at p: CGPoint) -> Control? {
        // A+B primero: es una zona pequeña entre ambos.
        for c in [Control.ab, .a, .b, .dpad, .start, .select, .menu] {
            guard let r = frames[c] else { continue }
            switch c {
            case .start, .select:
                if r.insetBy(dx: -8, dy: -8).contains(p) { return c }
            default:
                if hypot(p.x - r.midX, p.y - r.midY) <= r.width / 2 { return c }
            }
        }
        return nil
    }

    /// Direcciones del D-pad por ángulo: 8 sectores de 45°, zona muerta del 25 %.
    /// Nunca produce direcciones opuestas.
    private func dpadMask(for p: CGPoint) -> UInt8 {
        guard let r = frames[.dpad] else { return 0 }
        let dx = p.x - r.midX, dy = p.y - r.midY
        guard hypot(dx, dy) >= Self.dpadRadius * 0.25 else { return 0 }
        let angle: CGFloat = atan2(-dy, dx) // 0 = derecha, sentido antihorario
        let turn: CGFloat = 2 * .pi
        let shifted: CGFloat = (angle + turn + .pi / 8).truncatingRemainder(dividingBy: turn)
        let sector = Int(shifted / (.pi / 4)) % 8
        let right = UInt8(GB_BTN_RIGHT), up = UInt8(GB_BTN_UP), left = UInt8(GB_BTN_LEFT), down = UInt8(GB_BTN_DOWN)
        return [right, right | up, up, up | left, left, left | down, down, down | right][sector]
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        for t in touches {
            guard let c = control(at: t.location(in: self)) else { continue }
            if c == .menu {
                onMenu?()
                continue
            }
            self.touches[t] = c
        }
        publish()
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        for t in touches {
            // El D-pad captura su toque hasta que se levanta; el resto se puede deslizar.
            guard let current = self.touches[t], current != .dpad else { continue }
            if let c = control(at: t.location(in: self)), c != .menu, c != .dpad {
                self.touches[t] = c
            } else {
                self.touches[t] = nil
            }
        }
        publish()
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        for t in touches { self.touches[t] = nil }
        publish()
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        touchesEnded(touches, with: event)
    }

    private func publish() {
        var mask: UInt8 = 0
        var nowPressed: Set<Control> = []
        for (t, c) in touches {
            switch c {
            case .dpad:
                let m = dpadMask(for: t.location(in: self))
                mask |= m
                if m != 0 { nowPressed.insert(.dpad) }
            case .a: mask |= UInt8(GB_BTN_A); nowPressed.insert(.a)
            case .b: mask |= UInt8(GB_BTN_B); nowPressed.insert(.b)
            case .ab: mask |= UInt8(GB_BTN_A | GB_BTN_B); nowPressed.insert(.ab)
            case .start: mask |= UInt8(GB_BTN_START); nowPressed.insert(.start)
            case .select: mask |= UInt8(GB_BTN_SELECT); nowPressed.insert(.select)
            case .menu: break
            }
        }
        buttons?.set(mask)
        if nowPressed != pressed {
            pressed = nowPressed
            updateAppearance(animated: true)
        }
    }
}

struct ControlsOverlay: UIViewRepresentable {
    let buttons: ButtonMask
    let portrait: Bool
    let onMenu: () -> Void

    func makeUIView(context: Context) -> ControlsOverlayView {
        let view = ControlsOverlayView(frame: .zero)
        view.buttons = buttons
        view.portrait = portrait
        view.onMenu = onMenu
        return view
    }

    func updateUIView(_ view: ControlsOverlayView, context: Context) {
        view.portrait = portrait
        view.onMenu = onMenu
    }
}

import PocketGBCore
import Synchronization
import SwiftUI
import UIKit

/// Máscara de botones publicada por la UI y leída por el hilo de emulación cada frame.
final class ButtonMask: Sendable {
    private let state = Atomic<UInt8>(0)
    var value: UInt8 { state.load(ordering: .acquiring) }
    func set(_ mask: UInt8) { state.store(mask, ordering: .releasing) }
}

/// Una sola UIView multitáctil para todos los controles (docs/04 §Controles, SPEC §10).
/// El motor de input (`ControlsInputEngine`) decide qué está pulsado; las vistas de cada
/// control solo lo dibujan y no participan en el hit testing. La opacidad visual nunca
/// cambia el área táctil.
final class ControlsOverlayView: UIView {
    var buttons: ButtonMask?
    var onMenu: (() -> Void)?
    /// El editor guarda aquí la nueva posición relativa de un control.
    var onMove: ((ControlID, CGPoint) -> Void)?

    var orientation: ControlsOrientation = .landscape {
        didSet { if orientation != oldValue { releaseAll(); setNeedsLayout() } }
    }
    var settings = GameplaySettingsData() {
        didSet {
            haptics.enabled = settings.haptics
            if settings != oldValue { setNeedsLayout() }
        }
    }
    var editing = false {
        didSet { if editing != oldValue { releaseAll(); showControls(); setNeedsLayout() } }
    }
    var forceReduceTransparency = false {
        didSet { if forceReduceTransparency != oldValue { updateAppearance(animated: false) } }
    }

    private var engine = ControlsInputEngine()
    private var visuals: [ControlID: ControlVisualView] = [:]
    private var area: CGRect = .zero
    private var lastSize: CGSize = .zero
    private let haptics = ControlsHaptics()
    private var lastPressed: Set<ControlID> = []
    private var lastDpadMask: UInt8 = 0
    /// Editor: dedo → control arrastrado y desfase respecto a su centro.
    private var drags: [Int: (id: ControlID, offset: CGPoint)] = [:]
    private let guides = CAShapeLayer()
    private let hint = UILabel()
    private var fadeTask: Task<Void, Never>?

    private var reduceTransparency: Bool {
        forceReduceTransparency || UIAccessibility.isReduceTransparencyEnabled
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        isMultipleTouchEnabled = true
        backgroundColor = .clear
        guides.fillColor = UIColor.clear.cgColor
        guides.strokeColor = UIColor.white.withAlphaComponent(0.55).cgColor
        guides.lineWidth = 1
        guides.lineDashPattern = [6, 4]
        guides.isHidden = true
        layer.addSublayer(guides)
        for id in ControlID.allCases {
            let visual = ControlVisualView(id: id)
            visuals[id] = visual
            addSubview(visual)
        }
        hint.text = "Controles ocultos · abre el menú para mostrarlos"
        hint.font = .preferredFont(forTextStyle: .footnote)
        hint.textColor = .white
        hint.textAlignment = .center
        hint.backgroundColor = UIColor.black.withAlphaComponent(0.55)
        hint.layer.cornerRadius = 14
        hint.clipsToBounds = true
        hint.alpha = 0
        hint.isUserInteractionEnabled = false
        addSubview(hint)
        haptics.prepare()
        NotificationCenter.default.addObserver(self, selector: #selector(reduceTransparencyChanged),
                                               name: UIAccessibility.reduceTransparencyStatusDidChangeNotification,
                                               object: nil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) no se usa") }

    @objc private func reduceTransparencyChanged() {
        updateAppearance(animated: false)
    }

    // MARK: - Disposición

    override func layoutSubviews() {
        super.layoutSubviews()
        // Rotación o cambio de tamaño: se sueltan todos los dedos (máscara cero, SPEC §10.5).
        if bounds.size != lastSize {
            lastSize = bounds.size
            releaseAll()
        }
        area = bounds.inset(by: safeAreaInsets)
        backgroundColor = orientation == .portrait ? UIColor(named: "GameplayBackground") : .clear
        let metrics = ControlMetrics(scale: settings.sizeScale)
        let geometry = ControlsGeometry(layout: settings.layout(orientation), orientation: orientation,
                                        area: area, metrics: metrics)
        engine.geometry = geometry
        for (id, visual) in visuals where drags.values.first(where: { $0.id == id }) == nil {
            guard let frame = geometry.frames[id] else { continue }
            visual.bounds = CGRect(origin: .zero, size: frame.size)
            visual.center = CGPoint(x: frame.midX, y: frame.midY)
            visual.scale = metrics.scale
        }
        guides.frame = bounds
        guides.path = UIBezierPath(roundedRect: area, cornerRadius: 12).cgPath
        guides.isHidden = !editing
        let hintSize = hint.sizeThatFits(CGSize(width: area.width - 32, height: 100))
        hint.bounds = CGRect(x: 0, y: 0, width: hintSize.width + 28, height: 28)
        hint.center = CGPoint(x: area.midX, y: area.maxY - 24)
        updateAccessibility(geometry)
        updateAppearance(animated: false)
        if settings.visibility == .hidden && !editing { flashHint() }
        scheduleFade()
    }

    private func updateAppearance(animated: Bool) {
        let style: ControlVisualView.Style = orientation == .portrait ? .solidGlass : .clearGlass
        let pressed = engine.pressed
        for (id, visual) in visuals {
            // El menú lo dibuja el HUD de SwiftUI (GameplayHUD) en el mismo sitio.
            let hidden = id == .menu || (!editing && settings.visibility == .hidden)
            visual.configure(style: style, opacity: CGFloat(settings.opacity) / 100,
                             reduceTransparency: reduceTransparency, pressed: pressed.contains(id),
                             editing: editing, animated: animated)
            visual.isHidden = hidden
        }
    }

    private func updateAccessibility(_ geometry: ControlsGeometry) {
        var elements: [UIAccessibilityElement] = []
        for id in ControlID.allCases {
            guard let frame = geometry.frames[id], id != .menu,
                  settings.visibility != .hidden else { continue }
            let element = UIAccessibilityElement(accessibilityContainer: self)
            element.accessibilityFrameInContainerSpace = frame
            element.accessibilityTraits = .button
            element.accessibilityIdentifier = "control-\(id.rawValue)"
            switch id {
            case .dpad:
                element.accessibilityLabel = "Cruceta"
                element.accessibilityHint = "Desliza para cambiar de dirección"
            case .a: element.accessibilityLabel = "A"
            case .b: element.accessibilityLabel = "B"
            case .start: element.accessibilityLabel = "Start"
            case .select: element.accessibilityLabel = "Select"
            case .menu: element.accessibilityLabel = "Abrir menú"
            }
            elements.append(element)
        }
        accessibilityElements = elements
    }

    /// VoiceOver: la acción mágica (dos dedos, doble toque) abre el menú.
    override func accessibilityPerformMagicTap() -> Bool {
        onMenu?()
        return true
    }

    // MARK: - Visibilidad

    private func flashHint() {
        UIView.animate(withDuration: 0.18) { self.hint.alpha = 1 }
        Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(3))
            UIView.animate(withDuration: 0.18) { self?.hint.alpha = 0 }
        }
    }

    /// "Al tocar": se desvanecen tras 3 s sin toques; cualquier toque los muestra.
    private func scheduleFade() {
        fadeTask?.cancel()
        guard settings.visibility == .touch, !editing else { return }
        fadeTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: PocketMotion.controlsAutohide)
            guard !Task.isCancelled, let self, self.engine.touches.isEmpty else { return }
            UIView.animate(withDuration: 0.3) {
                for (id, v) in self.visuals where id != .menu { v.alpha = 0 }
            }
        }
    }

    private func showControls() {
        for v in visuals.values where v.alpha < 1 { v.alpha = 1 }
    }

    // MARK: - Toques

    private func key(_ t: UITouch) -> Int { ObjectIdentifier(t).hashValue }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        if editing {
            for t in touches { beginDrag(t) }
            return
        }
        if settings.visibility == .touch { showControls() }
        for t in touches {
            let p = t.location(in: self)
            // Ocultos: solo el menú responde; el resto de la pantalla no tiene áreas invisibles.
            if settings.visibility == .hidden, engine.geometry?.hit(at: p) != .control(.menu) {
                flashHint()
                continue
            }
            if engine.began(key(t), at: p) { onMenu?() }
        }
        publish()
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        if editing {
            for t in touches { moveDrag(t) }
            return
        }
        for t in touches { engine.moved(key(t), to: t.location(in: self)) }
        publish()
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        if editing {
            for t in touches { endDrag(t) }
            return
        }
        for t in touches { engine.ended(key(t)) }
        publish()
        scheduleFade()
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        touchesEnded(touches, with: event)
    }

    private func releaseAll() {
        engine.cancelAll()
        drags.removeAll()
        publish()
    }

    private func publish() {
        let mask = editing ? 0 : engine.mask
        buttons?.set(mask)
        let pressed: Set<ControlID> = editing ? [] : engine.pressed
        // Háptica solo al entrar en pulsado o al cambiar de sector del D-pad.
        let newlyPressed = pressed.subtracting(lastPressed).subtracting([.dpad])
        if !newlyPressed.isEmpty { haptics.buttonDown() }
        let dpad = mask & UInt8(GB_BTN_UP | GB_BTN_DOWN | GB_BTN_LEFT | GB_BTN_RIGHT)
        if dpad != lastDpadMask && dpad != 0 { haptics.dpadChanged() }
        lastDpadMask = dpad
        if pressed != lastPressed {
            lastPressed = pressed
            updateAppearance(animated: true)
        }
    }

    // MARK: - Editor

    private func beginDrag(_ t: UITouch) {
        let p = t.location(in: self)
        // El visual más pequeño bajo el dedo (el D-pad es grande y no debe tapar a los demás).
        let candidates = visuals.filter { !$0.value.isHidden && $0.value.frame.insetBy(dx: -8, dy: -8).contains(p) }
        guard let (id, visual) = candidates.min(by: { $0.value.bounds.width < $1.value.bounds.width }),
              !drags.values.contains(where: { $0.id == id }) else { return }
        drags[key(t)] = (id, CGPoint(x: visual.center.x - p.x, y: visual.center.y - p.y))
        visual.setDragging(true)
    }

    private func moveDrag(_ t: UITouch) {
        guard let drag = drags[key(t)], let visual = visuals[drag.id] else { return }
        let p = t.location(in: self)
        let raw = CGPoint(x: p.x + drag.offset.x, y: p.y + drag.offset.y)
        visual.center = ControlsGeometry.clamp(raw, size: visual.bounds.size, in: area)
    }

    private func endDrag(_ t: UITouch) {
        guard let drag = drags.removeValue(forKey: key(t)), let visual = visuals[drag.id] else { return }
        visual.setDragging(false)
        // Ajuste a una rejilla de 4 pt y dentro del área segura.
        let snapped = CGPoint(x: (visual.center.x / 4).rounded() * 4, y: (visual.center.y / 4).rounded() * 4)
        let center = ControlsGeometry.clamp(snapped, size: visual.bounds.size, in: area)
        visual.center = center
        onMove?(drag.id, ControlsGeometry.relative(center, in: area))
    }
}

/// Dibujo de un control (SPEC §10.3): scrim oscuro localizado, vidrio `UIGlassEffect`
/// y etiqueta. Con Reduce Transparency, relleno sólido ≥ 90 % y borde de 1,5 pt.
/// No recibe toques.
final class ControlVisualView: UIView {
    enum Style { case solidGlass, clearGlass }

    let id: ControlID
    var scale: CGFloat = 1 { didSet { if scale != oldValue { setNeedsLayout() } } }

    private let scrim = CAShapeLayer()
    private let effectView = UIVisualEffectView(effect: nil)
    private let solid = CAShapeLayer()
    private let ring = CAShapeLayer()
    private let glyph = CAShapeLayer()
    private let label = UILabel()
    private let symbol = UIImageView()
    private var currentStyle: Style?
    private var currentReduce: Bool?
    private var editing = false

    init(id: ControlID) {
        self.id = id
        super.init(frame: .zero)
        isUserInteractionEnabled = false
        layer.addSublayer(scrim)
        addSubview(effectView)
        effectView.isUserInteractionEnabled = false
        layer.addSublayer(solid)
        layer.addSublayer(ring)
        layer.addSublayer(glyph)
        for l in [scrim, solid, ring, glyph] as [CALayer] { l.actions = ["path": NSNull(), "bounds": NSNull()] }
        glyph.fillColor = UIColor.clear.cgColor
        glyph.strokeColor = UIColor.white.cgColor
        glyph.lineCap = .round
        glyph.lineJoin = .round
        ring.fillColor = UIColor.clear.cgColor
        switch id {
        case .a, .b: label.text = id == .a ? "A" : "B"
        case .start: label.text = "START"
        case .select: label.text = "SELECT"
        case .menu:
            symbol.image = UIImage(systemName: "ellipsis")
            symbol.tintColor = .white
            symbol.contentMode = .center
            addSubview(symbol)
        case .dpad: break
        }
        label.textColor = .white
        label.textAlignment = .center
        addSubview(label)
        // Sombra oscura en etiquetas y cruz: se leen también sobre un frame blanco.
        for l in [label.layer, glyph, symbol.layer] {
            l.shadowColor = UIColor.black.cgColor
            l.shadowOpacity = 0.6
            l.shadowRadius = 1.5
            l.shadowOffset = .zero
        }
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) no se usa") }

    private var isCapsule: Bool { id == .start || id == .select }

    private func shapePath(_ rect: CGRect) -> UIBezierPath {
        isCapsule ? UIBezierPath(roundedRect: rect, cornerRadius: rect.height / 2) : UIBezierPath(ovalIn: rect)
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let rect = bounds
        scrim.frame = rect
        scrim.path = shapePath(rect.insetBy(dx: -3, dy: -3)).cgPath
        effectView.frame = rect
        effectView.layer.cornerRadius = min(rect.width, rect.height) / 2
        effectView.layer.cornerCurve = .continuous
        effectView.clipsToBounds = true
        solid.frame = rect
        solid.path = shapePath(rect.insetBy(dx: 0.75, dy: 0.75)).cgPath
        ring.frame = rect
        ring.path = shapePath(rect.insetBy(dx: 2, dy: 2)).cgPath
        glyph.frame = rect
        if id == .dpad {
            glyph.path = Self.cross(in: rect).cgPath
            glyph.lineWidth = max(2, rect.width * 0.02)
        }
        let size: CGFloat = switch id {
        case .a, .b: 24
        case .start, .select: 11
        default: 14
        }
        label.font = .systemFont(ofSize: size * scale, weight: .bold)
        label.frame = rect
        symbol.frame = rect
        symbol.preferredSymbolConfiguration = UIImage.SymbolConfiguration(pointSize: 18, weight: .bold)
    }

    /// Cruz del D-pad con los brazos marcados y un hueco central.
    private static func cross(in rect: CGRect) -> UIBezierPath {
        let arm = rect.width * 0.3, len = rect.width * 0.4
        let c = CGPoint(x: rect.midX, y: rect.midY)
        let outer = UIBezierPath()
        let h = arm / 2
        let points = [
            CGPoint(x: -h, y: -len), CGPoint(x: h, y: -len), CGPoint(x: h, y: -h), CGPoint(x: len, y: -h),
            CGPoint(x: len, y: h), CGPoint(x: h, y: h), CGPoint(x: h, y: len), CGPoint(x: -h, y: len),
            CGPoint(x: -h, y: h), CGPoint(x: -len, y: h), CGPoint(x: -len, y: -h), CGPoint(x: -h, y: -h),
        ]
        outer.move(to: CGPoint(x: c.x + points[0].x, y: c.y + points[0].y))
        for p in points.dropFirst() { outer.addLine(to: CGPoint(x: c.x + p.x, y: c.y + p.y)) }
        outer.close()
        return outer
    }

    func configure(style: Style, opacity: CGFloat, reduceTransparency: Bool, pressed: Bool, editing: Bool,
                   animated: Bool) {
        self.editing = editing
        if style != currentStyle || reduceTransparency != currentReduce {
            currentStyle = style
            currentReduce = reduceTransparency
            if reduceTransparency {
                effectView.effect = nil
            } else {
                effectView.effect = UIGlassEffect(style: style == .clearGlass ? .clear : .regular)
            }
        }
        CATransaction.begin()
        CATransaction.setDisableActions(!animated)
        CATransaction.setAnimationDuration(pressed ? 0.07 : 0.09)
        let surface: CGFloat
        let scrimAlpha: CGFloat
        let labelAlpha: CGFloat
        if reduceTransparency {
            // Superficie sólida oscura ≥ 90 %, borde de 1,5 pt y texto al 100 %.
            surface = 1
            scrimAlpha = 0
            labelAlpha = 1
            solid.fillColor = UIColor(white: pressed ? 0.24 : 0.1, alpha: 0.94).cgColor
            solid.strokeColor = UIColor.white.withAlphaComponent(0.85).cgColor
            solid.lineWidth = 1.5
        } else if style == .solidGlass {
            // Vertical: fondo uniforme bajo el juego; vidrio regular a opacidad completa.
            surface = 1
            scrimAlpha = 0
            labelAlpha = 1
            solid.fillColor = UIColor.white.withAlphaComponent(pressed ? 0.22 : 0.06).cgColor
            solid.strokeColor = UIColor.white.withAlphaComponent(0.25).cgColor
            solid.lineWidth = 1
        } else {
            // Horizontal sobre el juego: vidrio claro con scrim localizado. La opacidad
            // elegida cambia el scrim y el vidrio; la etiqueta nunca baja del 70 %.
            surface = max(opacity, 0.3)
            scrimAlpha = min(0.32 + 0.3 * opacity + (pressed ? 0.15 : 0), 0.85)
            labelAlpha = max(0.7, opacity)
            solid.fillColor = UIColor.white.withAlphaComponent(pressed ? 0.25 : 0).cgColor
            solid.strokeColor = UIColor.white.withAlphaComponent(0.35 + 0.4 * opacity).cgColor
            solid.lineWidth = 1
        }
        scrim.fillColor = UIColor.black.withAlphaComponent(scrimAlpha).cgColor
        effectView.alpha = reduceTransparency ? 0 : surface
        // A y B: anillo cálido/frío además de la letra y la posición (SPEC §13).
        if id == .a || id == .b {
            let color = UIColor(named: id == .a ? "ControlAWarm" : "ControlBCool") ?? .white
            ring.strokeColor = color.withAlphaComponent(labelAlpha).cgColor
            ring.lineWidth = 2.5
        } else {
            ring.strokeColor = UIColor.clear.cgColor
        }
        glyph.opacity = Float(labelAlpha)
        CATransaction.commit()
        label.alpha = labelAlpha
        symbol.alpha = labelAlpha
        layer.borderWidth = 0
        let target = pressed ? CGAffineTransform(scaleX: 0.9, y: 0.9) : .identity
        if animated {
            UIView.animate(withDuration: pressed ? 0.07 : 0.09, delay: 0, options: [.curveEaseOut, .allowUserInteraction]) {
                self.transform = target
            }
        } else {
            transform = target
        }
        setDragging(false)
    }

    /// Editor: contorno discontinuo; más marcado mientras se arrastra.
    func setDragging(_ dragging: Bool) {
        guard editing else {
            layer.sublayers?.first(where: { $0.name == "handle" })?.removeFromSuperlayer()
            return
        }
        let handle = (layer.sublayers?.first(where: { $0.name == "handle" }) as? CAShapeLayer) ?? {
            let l = CAShapeLayer()
            l.name = "handle"
            l.fillColor = UIColor.clear.cgColor
            l.lineDashPattern = [5, 3]
            layer.addSublayer(l)
            return l
        }()
        handle.frame = bounds
        handle.path = UIBezierPath(roundedRect: bounds.insetBy(dx: -6, dy: -6), cornerRadius: 10).cgPath
        handle.strokeColor = (UIColor(named: "AccentPrimary") ?? .systemBlue)
            .withAlphaComponent(dragging ? 1 : 0.8).cgColor
        handle.lineWidth = dragging ? 2.5 : 1.5
    }
}

struct ControlsOverlay: UIViewRepresentable {
    let buttons: ButtonMask
    let orientation: ControlsOrientation
    let settings: GameplaySettingsData
    var editing = false
    var reduceTransparency = false
    let onMenu: () -> Void
    var onMove: (ControlID, CGPoint) -> Void = { _, _ in }

    func makeUIView(context: Context) -> ControlsOverlayView {
        let view = ControlsOverlayView(frame: .zero)
        view.buttons = buttons
        update(view)
        return view
    }

    func updateUIView(_ view: ControlsOverlayView, context: Context) {
        update(view)
    }

    private func update(_ view: ControlsOverlayView) {
        view.orientation = orientation
        view.settings = settings
        view.editing = editing
        view.forceReduceTransparency = reduceTransparency
        view.onMenu = onMenu
        view.onMove = onMove
    }
}

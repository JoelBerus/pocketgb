import PocketGBCore
import Synchronization
import SwiftUI
import UIKit

/// Máscara de botones publicada por la UI y leída por el hilo de emulación cada frame.
final class ButtonMask: Sendable {
    private let state = Atomic<UInt16>(0)
    var value: UInt16 { state.load(ordering: .acquiring) }
    func set(_ mask: UInt16) { state.store(mask, ordering: .releasing) }
}

/// Una sola UIView multitáctil para todos los controles (docs/04 §Controles, SPEC §10).
/// El motor de input (`ControlsInputEngine`) decide qué está pulsado; las vistas de cada
/// control solo lo dibujan y no participan en el hit testing. La opacidad visual nunca
/// cambia el área táctil.
final class ControlsOverlayView: UIView {
    var buttons: ButtonMask?
    /// Game Boy Advance: dibuja y atiende L y R.
    var showsShoulders = false {
        didSet { if showsShoulders != oldValue { releaseAll(); setNeedsLayout() } }
    }
    var onMenu: (() -> Void)?
    /// El editor guarda aquí la nueva posición relativa de un control.
    var onMove: ((ControlID, CGPoint) -> Void)?
    /// Editor: control seleccionado (para cambiar su tamaño).
    var onSelect: ((ControlID) -> Void)?
    var selectedControl: ControlID? {
        didSet { if selectedControl != oldValue { updateAppearance(animated: false) } }
    }

    var orientation: ControlsOrientation = .landscape {
        didSet { if orientation != oldValue { releaseAll(); setNeedsLayout() } }
    }
    var settings = GameplaySettingsData() {
        didSet {
            haptics.enabled = settings.haptics
            engine.diagonals = settings.dpadDiagonals
            if settings != oldValue { setNeedsLayout() }
        }
    }
    var editing = false {
        didSet { if editing != oldValue { releaseAll(); showControls(); setNeedsLayout() } }
    }
    /// Mando conectado: solo se oculta el dibujo; los toques siguen funcionando.
    var controllerConnected = false {
        didSet { if controllerConnected != oldValue { updateAppearance(animated: false) } }
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
    /// Dirección que dibuja la cruceta (solo el brazo o la flecha pulsada, N2).
    private var lastDpadMask: UInt8 = 0
    private var dpadHaptics = DpadHapticGate()
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
        let layout = settings.layout(orientation, shoulders: showsShoulders)
        let geometry = ControlsGeometry(layout: layout, orientation: orientation, area: area, metrics: metrics,
                                        shoulders: showsShoulders, dpadStyle: settings.dpadStyle)
        engine.geometry = geometry
        for (id, visual) in visuals where drags.values.first(where: { $0.id == id }) == nil {
            guard let frame = geometry.frames[id] else { continue }
            visual.bounds = CGRect(origin: .zero, size: frame.size)
            visual.center = CGPoint(x: frame.midX, y: frame.midY)
            visual.scale = metrics.scale
            visual.separatedArrows = settings.dpadStyle == .separated
            visual.arrowSpacing = layout.spacing
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
        #if DEBUG
        applyDebugPress()
        #endif
    }

    #if DEBUG
    /// `-uiPressedDpad up|down|left|right|upright|…`: un dedo simulado en el centro del brazo (o
    /// de la flecha) de esa dirección, por el motor real, para las capturas de N2.
    private func applyDebugPress() {
        let angles: [String: CGFloat] = ["right": 0, "upright": 45, "up": 90, "upleft": 135,
                                         "left": 180, "downleft": 225, "down": 270, "downright": 315]
        guard let raw = DebugArguments.value("-uiPressedDpad"), let angle = angles[raw], !editing,
              let frame = engine.geometry?.frames[.dpad] else { return }
        let spacing = settings.layout(orientation, shoulders: showsShoulders).spacing
        let arrow = DpadArrows.rects(in: frame, spacing: spacing)[0]
        let target = settings.dpadStyle == .cross ? DpadCross.armCenters(in: frame)[0] : CGPoint(x: arrow.midX, y: arrow.midY)
        let distance = hypot(target.x - frame.midX, target.y - frame.midY)
        let radians = angle * .pi / 180
        _ = engine.began(-1, at: CGPoint(x: frame.midX + distance * cos(radians), y: frame.midY - distance * sin(radians)))
        publish()
    }
    #endif

    private func updateAppearance(animated: Bool) {
        let style: ControlVisualView.Style = orientation == .portrait ? .solidGlass : .clearGlass
        let pressed: Set<ControlID> = editing ? [] : engine.pressed
        let dpad: UInt8 = editing ? 0 : engine.dpadMask
        for (id, visual) in visuals {
            // El menú lo dibuja el HUD de SwiftUI (GameplayHUD) en el mismo sitio.
            let hidden = id == .menu || (id.isShoulder && !showsShoulders) || (!editing && (settings.visibility == .hidden || controllerConnected))
            visual.configure(style: style, opacity: CGFloat(settings.opacity) / 100,
                             reduceTransparency: reduceTransparency, pressed: pressed.contains(id),
                             dpadMask: id == .dpad ? dpad : 0, editing: editing, animated: animated)
            visual.setSelected(editing && selectedControl == id)
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
            case .l: element.accessibilityLabel = "L"
            case .r: element.accessibilityLabel = "R"
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
        // Háptica al entrar en pulsado o, en la cruceta, al activarse una dirección nueva (N2).
        let newlyPressed = pressed.subtracting(lastPressed).subtracting([.dpad])
        if !newlyPressed.isEmpty { haptics.buttonDown() }
        let dpad: UInt8 = editing ? 0 : engine.dpadMask
        if dpadHaptics.shouldFire(mask: dpad) { haptics.dpadChanged() }
        if pressed != lastPressed || dpad != lastDpadMask {
            lastPressed = pressed
            lastDpadMask = dpad
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
        onSelect?(id)
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

/// Dibujo de un control (SPEC §10.3, N2): una sola capa de vidrio `UIGlassEffect` con la forma
/// del control (`cornerConfiguration`, sin recortar su borde), encima un velo oscuro con la
/// forma exacta (en horizontal) y un trazo fino y uniforme, y debajo una sombra suave centrada
/// que lo separa del juego. Sin scrim inflado bajo el vidrio: el vidrio lo refractaba en su
/// borde y se veía un doble anillo desplazado. Con Reduce Transparency, relleno sólido ≥ 90 %
/// y borde de 1,5 pt.
/// La cruceta no se escala al pulsar: solo se marca el brazo o la flecha de `dpadMask`.
/// No recibe toques.
final class ControlVisualView: UIView {
    enum Style { case solidGlass, clearGlass }

    let id: ControlID
    var scale: CGFloat = 1 { didSet { if scale != oldValue { setNeedsLayout() } } }
    /// Cruceta estilo PlayStation: cuatro flechas separadas en lugar de la cruz.
    var separatedArrows = false {
        didSet { if separatedArrows != oldValue { currentStyle = nil; setNeedsLayout() } }
    }
    /// Separación de las flechas (0,7…1,5): la misma que usa la geometría táctil.
    var arrowSpacing: CGFloat = 1 { didSet { if arrowSpacing != oldValue { setNeedsLayout() } } }

    private let shadowLayer = CALayer()
    private let effectView = UIVisualEffectView(effect: nil)
    /// Vidrio de cada flecha en el estilo separado (arriba, derecha, abajo, izquierda).
    private let arrowGlass = (0..<4).map { _ in UIVisualEffectView(effect: nil) }
    /// Relleno (pulsado o Reduce Transparency) y trazo fino del borde.
    private let surface = CAShapeLayer()
    /// A y B: el anillo de color es su borde.
    private let ring = CAShapeLayer()
    private let glyph = CAShapeLayer()
    /// Hundido sutil en el centro de la cruz.
    private let dimple = CAShapeLayer()
    /// Solo el brazo o la flecha pulsada.
    private let pressedPart = CAShapeLayer()
    /// Flechas de la cruceta (arriba, derecha, abajo, izquierda): dentro de cada brazo o flecha.
    private let arrowIcons = ["arrowtriangle.up.fill", "arrowtriangle.right.fill", "arrowtriangle.down.fill",
                              "arrowtriangle.left.fill"].map { UIImageView(image: UIImage(systemName: $0)) }
    private let label = UILabel()
    private let symbol = UIImageView()
    private var currentStyle: Style?
    private var currentReduce: Bool?
    private var editing = false
    private var dpadMask: UInt8 = 0
    private var borderWidth: CGFloat = 1

    init(id: ControlID) {
        self.id = id
        super.init(frame: .zero)
        isUserInteractionEnabled = false
        shadowLayer.shadowColor = UIColor.black.cgColor
        shadowLayer.shadowOffset = .zero
        layer.addSublayer(shadowLayer)
        // Las cuatro vistas de vidrio de las flechas solo existen en la cruceta.
        for v in glassViews {
            v.isUserInteractionEnabled = false
            v.cornerConfiguration = .capsule()
            addSubview(v)
        }
        for l in [surface, ring, glyph, pressedPart, dimple] {
            layer.addSublayer(l)
        }
        for l in [shadowLayer, surface, ring, glyph, pressedPart, dimple] as [CALayer] {
            l.actions = ["path": NSNull(), "bounds": NSNull(), "position": NSNull(), "shadowPath": NSNull()]
        }
        for icon in arrowIcons {
            icon.contentMode = .center
            icon.isHidden = id != .dpad
            addSubview(icon)
        }
        glyph.lineCap = .round
        glyph.lineJoin = .round
        ring.fillColor = UIColor.clear.cgColor
        switch id {
        case .a, .b: label.text = id == .a ? "A" : "B"
        case .start: label.text = "START"
        case .l: label.text = "L"
        case .r: label.text = "R"
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

    private var isCapsule: Bool { id == .start || id == .select || id.isShoulder }

    private var arrowsMode: Bool { id == .dpad && separatedArrows }

    private var glassViews: [UIVisualEffectView] { id == .dpad ? [effectView] + arrowGlass : [effectView] }

    /// Forma del control metida `inset` puntos hacia dentro (para trazar bordes sin salirse).
    private func shapePath(_ rect: CGRect, inset: CGFloat = 0) -> UIBezierPath {
        if arrowsMode {
            let path = UIBezierPath()
            for r in DpadArrows.rects(in: rect, spacing: arrowSpacing) {
                path.append(UIBezierPath(ovalIn: r.insetBy(dx: inset, dy: inset)))
            }
            return path
        }
        let r = rect.insetBy(dx: inset, dy: inset)
        return isCapsule ? UIBezierPath(roundedRect: r, cornerRadius: r.height / 2) : UIBezierPath(ovalIn: r)
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let rect = bounds
        shadowLayer.frame = rect
        shadowLayer.shadowPath = shapePath(rect).cgPath
        effectView.frame = rect
        effectView.isHidden = arrowsMode
        let arrows = DpadArrows.rects(in: rect, spacing: arrowSpacing)
        for (v, r) in zip(arrowGlass, arrows) {
            v.frame = r
            v.isHidden = !arrowsMode
        }
        for l in [surface, ring, glyph, pressedPart, dimple] { l.frame = rect }
        updatePaths()
        if id == .dpad {
            // Flechas proporcionales al control (no 16 pt fijos): en la cruz, oscuras dentro de
            // cada brazo blanco; en las flechas separadas, blancas en el centro de cada círculo.
            let arms = DpadCross.arms(in: rect)
            for (i, icon) in arrowIcons.enumerated() {
                let frame = arrowsMode ? arrows[i] : arms[i]
                let side = arrowsMode ? frame.width : min(frame.width, frame.height)
                icon.frame = frame
                let config = UIImage.SymbolConfiguration(pointSize: max(6, side * (arrowsMode ? 0.36 : 0.42)),
                                                         weight: .regular)
                icon.preferredSymbolConfiguration = config
                let glyphSize = icon.image?.applyingSymbolConfiguration(config)?.size ?? .zero
                icon.layer.shadowPath = Self.triangle(in: CGRect(x: (frame.width - glyphSize.width) / 2,
                                                                 y: (frame.height - glyphSize.height) / 2,
                                                                 width: glyphSize.width, height: glyphSize.height),
                                                      pointing: i).cgPath
            }
        }
        let size: CGFloat = switch id {
        case .a, .b: 24
        case .start, .select: 11
        case .l, .r: 16
        default: 14
        }
        label.font = .systemFont(ofSize: size * scale, weight: .bold)
        label.frame = rect
        symbol.frame = rect
        symbol.preferredSymbolConfiguration = UIImage.SymbolConfiguration(pointSize: 18, weight: .bold)
    }

    private func updatePaths() {
        let rect = bounds
        surface.lineWidth = borderWidth
        surface.path = shapePath(rect, inset: borderWidth / 2).cgPath
        ring.lineWidth = 2.5
        ring.path = shapePath(rect, inset: 1.25).cgPath
        guard id == .dpad else { return }
        glyph.path = arrowsMode ? nil : Self.cross(in: rect).cgPath
        glyph.shadowPath = glyph.path
        let t = rect.width * DpadCross.thicknessRatio
        let d = t * 0.5
        dimple.path = arrowsMode ? nil
            : UIBezierPath(ovalIn: CGRect(x: rect.midX - d / 2, y: rect.midY - d / 2, width: d, height: d)).cgPath
        let pressed = UIBezierPath()
        let parts = arrowsMode ? DpadArrows.rects(in: rect, spacing: arrowSpacing) : DpadCross.arms(in: rect)
        for (i, part) in parts.enumerated() where dpadMask & DpadDirection.arms[i] != 0 {
            pressed.append(arrowsMode ? UIBezierPath(ovalIn: part) : Self.arm(part, index: i, thickness: t))
        }
        pressedPart.path = pressed.cgPath
    }

    /// Cruz continua y rellena; la unión de dos rectángulos redondeados elimina
    /// los vértices agresivos sin cambiar el área táctil ni las diagonales.
    private static func cross(in rect: CGRect) -> UIBezierPath {
        let length = rect.width * DpadCross.lengthRatio
        let thickness = rect.width * DpadCross.thicknessRatio
        let radius = thickness * 0.3
        let horizontal = CGRect(x: rect.midX - length / 2, y: rect.midY - thickness / 2,
                                width: length, height: thickness)
        let vertical = CGRect(x: rect.midX - thickness / 2, y: rect.midY - length / 2,
                              width: thickness, height: length)
        let path = UIBezierPath(roundedRect: horizontal, cornerRadius: radius)
        path.append(UIBezierPath(roundedRect: vertical, cornerRadius: radius))
        return path
    }

    /// Triángulo que ocupa `rect` apuntando arriba (0), a la derecha (1), abajo (2) o a la
    /// izquierda (3): la sombra de cada flecha sin render fuera de pantalla.
    private static func triangle(in rect: CGRect, pointing index: Int) -> UIBezierPath {
        let corners: [CGPoint] = switch index {
        case 0: [CGPoint(x: rect.midX, y: rect.minY), CGPoint(x: rect.maxX, y: rect.maxY), CGPoint(x: rect.minX, y: rect.maxY)]
        case 1: [CGPoint(x: rect.maxX, y: rect.midY), CGPoint(x: rect.minX, y: rect.maxY), CGPoint(x: rect.minX, y: rect.minY)]
        case 2: [CGPoint(x: rect.midX, y: rect.maxY), CGPoint(x: rect.minX, y: rect.minY), CGPoint(x: rect.maxX, y: rect.minY)]
        default: [CGPoint(x: rect.minX, y: rect.midY), CGPoint(x: rect.maxX, y: rect.minY), CGPoint(x: rect.maxX, y: rect.maxY)]
        }
        let path = UIBezierPath()
        path.move(to: corners[0])
        corners.dropFirst().forEach { path.addLine(to: $0) }
        path.close()
        return path
    }

    /// Un brazo de la cruz con la punta redondeada como la cruz y el lado del centro recto.
    private static func arm(_ rect: CGRect, index: Int, thickness: CGFloat) -> UIBezierPath {
        let corners: UIRectCorner = [[.topLeft, .topRight], [.topRight, .bottomRight],
                                     [.bottomLeft, .bottomRight], [.topLeft, .bottomLeft]][index]
        let r = thickness * 0.3
        return UIBezierPath(roundedRect: rect, byRoundingCorners: corners, cornerRadii: CGSize(width: r, height: r))
    }

    func configure(style: Style, opacity: CGFloat, reduceTransparency: Bool, pressed: Bool, dpadMask: UInt8 = 0,
                   editing: Bool, animated: Bool) {
        self.editing = editing
        if style != currentStyle || reduceTransparency != currentReduce {
            currentStyle = style
            currentReduce = reduceTransparency
            for v in glassViews {
                v.effect = reduceTransparency ? nil : UIGlassEffect(style: style == .clearGlass ? .clear : .regular)
            }
        }
        CATransaction.begin()
        CATransaction.setDisableActions(!animated)
        CATransaction.setAnimationDuration(pressed ? 0.07 : 0.09)
        // La cruceta nunca se ve pulsada entera: solo su brazo o flecha (`pressedPart`).
        let wholePressed = pressed && id != .dpad
        // Colores y alfas en ControlPalette (los mismos que mide DpadContrastTests, N2-H1).
        // Vertical: fondo uniforme bajo el juego, vidrio regular a opacidad completa. Horizontal:
        // vidrio claro, un velo oscuro con la forma exacta encima del vidrio (contraste sobre
        // escenas claras sin que el vidrio lo refracte en un segundo borde) y una sombra suave
        // centrada. La opacidad elegida cambia velo, sombra y vidrio; la etiqueta nunca baja
        // del 70 %. Reduce Transparency: sólido ≥ 90 %, borde de 1,5 pt y texto al 100 %.
        let palette = ControlPalette(surface: style == .clearGlass ? .clearGlass : .solidGlass,
                                     opacity: opacity, reduceTransparency: reduceTransparency)
        let labelAlpha = palette.labelAlpha
        borderWidth = palette.borderWidth
        surface.fillColor = palette.fill(pressed: wholePressed).cgColor
        shadowLayer.shadowOpacity = Float(palette.shadowOpacity)
        shadowLayer.shadowRadius = palette.shadowRadius
        for v in glassViews { v.alpha = palette.glassAlpha }
        // A y B: anillo cálido/frío además de la letra y la posición (SPEC §13). Es su único
        // borde (antes había un trazo blanco y el anillo: dos bordes concéntricos).
        if id == .a || id == .b {
            let color = UIColor(named: id == .a ? "ControlAWarm" : "ControlBCool") ?? .white
            ring.strokeColor = color.withAlphaComponent(labelAlpha).cgColor
            surface.strokeColor = UIColor.clear.cgColor
        } else {
            ring.strokeColor = UIColor.clear.cgColor
            surface.strokeColor = palette.border.cgColor
        }
        if id == .dpad {
            self.dpadMask = editing ? 0 : dpadMask
            glyph.fillColor = UIColor.white.cgColor
            glyph.strokeColor = UIColor.clear.cgColor
            glyph.opacity = Float(labelAlpha)
            dimple.fillColor = ControlPalette.dimpleFill.cgColor
            dimple.strokeColor = ControlPalette.dimpleStroke.cgColor
            dimple.lineWidth = 1
            dimple.opacity = Float(labelAlpha)
            // Cruz: el brazo pulsado se hunde (gris oscuro opaco). Flechas: el disco pulsado se
            // vuelve casi blanco. En los dos casos el triángulo se invierte. Contraste ≥ 3:1.
            pressedPart.fillColor = (arrowsMode ? ControlPalette.arrowPressed : ControlPalette.crossPressed).cgColor
            pressedPart.opacity = 1
            for (i, icon) in arrowIcons.enumerated() {
                let down = self.dpadMask & DpadDirection.arms[i] != 0
                let color = arrowsMode ? (down ? ControlPalette.arrowPressedIcon : ControlPalette.arrowIcon)
                    : (down ? ControlPalette.crossPressedIcon : palette.crossIcon)
                icon.tintColor = UIColor(color)
                icon.alpha = down ? 1 : labelAlpha
                icon.layer.shadowColor = UIColor.black.cgColor
                icon.layer.shadowOffset = .zero
                icon.layer.shadowRadius = 1.5
                // Sombra solo en los triángulos blancos de las flechas en reposo, con su
                // `shadowPath` (sin render fuera de pantalla).
                icon.layer.shadowOpacity = arrowsMode && !down ? 0.6 : 0
            }
            updatePaths()
        } else {
            surface.lineWidth = borderWidth
            surface.path = shapePath(bounds, inset: borderWidth / 2).cgPath
        }
        CATransaction.commit()
        label.alpha = labelAlpha
        symbol.alpha = labelAlpha
        layer.borderWidth = 0
        // A, B, Start, Select, L y R se encogen al pulsar (SPEC §7.5); la cruceta no.
        let target = wholePressed ? CGAffineTransform(scaleX: 0.9, y: 0.9) : .identity
        if animated {
            UIView.animate(withDuration: pressed ? 0.07 : 0.09, delay: 0, options: [.curveEaseOut, .allowUserInteraction]) {
                self.transform = target
            }
        } else {
            transform = target
        }
        setDragging(false)
    }

    private var isSelectedInEditor = false

    /// Editor: el control elegido para cambiar de tamaño lleva el contorno continuo.
    func setSelected(_ selected: Bool) {
        isSelectedInEditor = selected
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
        handle.lineWidth = dragging || isSelectedInEditor ? 2.5 : 1.5
        handle.lineDashPattern = isSelectedInEditor ? nil : [5, 3]
    }
}

struct ControlsOverlay: UIViewRepresentable {
    let buttons: ButtonMask
    let orientation: ControlsOrientation
    let settings: GameplaySettingsData
    var showsShoulders = false
    var controllerConnected = false
    var editing = false
    var reduceTransparency = false
    let onMenu: () -> Void
    var onMove: (ControlID, CGPoint) -> Void = { _, _ in }
    var selected: ControlID?
    var onSelect: (ControlID) -> Void = { _ in }

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
        view.showsShoulders = showsShoulders
        view.orientation = orientation
        view.settings = settings
        view.editing = editing
        view.controllerConnected = controllerConnected
        view.forceReduceTransparency = reduceTransparency
        view.onMenu = onMenu
        view.onMove = onMove
        view.onSelect = onSelect
        view.selectedControl = selected
    }
}

extension PaletteColor {
    var cgColor: CGColor { CGColor(srgbRed: r, green: g, blue: b, alpha: a) }
}

extension UIColor {
    convenience init(_ color: PaletteColor) {
        self.init(red: color.r, green: color.g, blue: color.b, alpha: color.a)
    }
}

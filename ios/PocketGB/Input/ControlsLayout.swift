import CoreGraphics
import Foundation
import PocketGBACore
import PocketGBCore

/// Controles táctiles de gameplay (SPEC §10). `l` y `r` solo existen en Game Boy Advance.
enum ControlID: String, Codable, CaseIterable, Sendable {
    case dpad, a, b, start, select, menu, l, r

    var isShoulder: Bool { self == .l || self == .r }
}

/// Orientación del layout: cada una se guarda por separado (D-README §6).
enum ControlsOrientation: String, Codable, CaseIterable, Sendable {
    case portrait, landscape

    var title: String { self == .portrait ? "Vertical" : "Horizontal" }
}

/// Posiciones relativas (0…1) del centro de cada control dentro del área segura.
struct ControlsLayout: Codable, Equatable, Sendable {
    var centers: [ControlID: CGPoint]
    /// Tamaño de cada control respecto al normal (1 = 100 %), ajustado en el editor.
    var scales: [ControlID: CGFloat] = [:]

    static let scaleRange: ClosedRange<CGFloat> = 0.6...1.6

    init(centers: [ControlID: CGPoint], scales: [ControlID: CGFloat] = [:]) {
        self.centers = centers
        self.scales = scales
    }

    // `scales` es opcional: un layout guardado antes se sigue leyendo.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        centers = try c.decode([ControlID: CGPoint].self, forKey: .centers)
        scales = (try? c.decodeIfPresent([ControlID: CGFloat].self, forKey: .scales)) ?? [:]
    }

    func scale(_ id: ControlID) -> CGFloat {
        min(max(scales[id] ?? 1, Self.scaleRange.lowerBound), Self.scaleRange.upperBound)
    }

    /// Disposición por defecto. Game Boy: imagen 10:9. Game Boy Advance (`shoulders`): imagen 3:2,
    /// mucho más ancha; en horizontal (iPhone 17 Pro, imagen a escala entera ≈560×373 pt) la cruceta,
    /// A, B, Start y Select van en los márgenes laterales y L/R arriba, a los lados de la imagen.
    static func defaults(_ orientation: ControlsOrientation, shoulders: Bool = false) -> ControlsLayout {
        switch (orientation, shoulders) {
        case (.portrait, false):
            ControlsLayout(centers: [
                .dpad: CGPoint(x: 0.25, y: 0.44), .a: CGPoint(x: 0.84, y: 0.36), .b: CGPoint(x: 0.64, y: 0.52),
                .start: CGPoint(x: 0.59, y: 0.86), .select: CGPoint(x: 0.41, y: 0.86), .menu: CGPoint(x: 0.5, y: 0.07),
            ])
        case (.landscape, false):
            ControlsLayout(centers: [
                .dpad: CGPoint(x: 0.12, y: 0.62), .a: CGPoint(x: 0.91, y: 0.52), .b: CGPoint(x: 0.81, y: 0.72),
                .start: CGPoint(x: 0.56, y: 0.93), .select: CGPoint(x: 0.44, y: 0.93), .menu: CGPoint(x: 0.5, y: 0.06),
            ])
        case (.portrait, true):
            ControlsLayout(centers: [
                .dpad: CGPoint(x: 0.25, y: 0.44), .a: CGPoint(x: 0.84, y: 0.36), .b: CGPoint(x: 0.64, y: 0.52),
                .start: CGPoint(x: 0.59, y: 0.86), .select: CGPoint(x: 0.41, y: 0.86), .menu: CGPoint(x: 0.5, y: 0.07),
                .l: CGPoint(x: 0.17, y: 0.14), .r: CGPoint(x: 0.83, y: 0.14),
            ])
        case (.landscape, true):
            // Márgenes de ≈97 pt a cada lado de la imagen: la cruceta se reduce al mínimo (0,6)
            // y A y B se apilan en vertical; L/R arriba en los márgenes.
            ControlsLayout(centers: [
                .dpad: CGPoint(x: 0.065, y: 0.62), .a: CGPoint(x: 0.94, y: 0.45), .b: CGPoint(x: 0.94, y: 0.74),
                .start: CGPoint(x: 0.94, y: 0.93), .select: CGPoint(x: 0.065, y: 0.93), .menu: CGPoint(x: 0.5, y: 0.06),
                .l: CGPoint(x: 0.065, y: 0.1), .r: CGPoint(x: 0.94, y: 0.1),
            ], scales: [.dpad: 0.6, .l: 0.9, .r: 0.9])
        }
    }

    func center(_ id: ControlID, orientation: ControlsOrientation, shoulders: Bool = false) -> CGPoint {
        centers[id] ?? Self.defaults(orientation, shoulders: shoulders).centers[id] ?? CGPoint(x: 0.5, y: 0.5)
    }
}

/// Tamaños visuales y táctiles (pt) con la escala elegida en Ajustes.
struct ControlMetrics: Equatable, Sendable {
    var scale: CGFloat = 1

    var dpadDiameter: CGFloat { 140 * scale }
    var faceDiameter: CGFloat { 68 * scale }
    var abDiameter: CGFloat { 36 * scale }
    var pillSize: CGSize { CGSize(width: 66 * scale, height: 28 * scale) }
    var menuDiameter: CGFloat { 40 }
    var shoulderSize: CGSize { CGSize(width: 92 * scale, height: 40 * scale) }
    /// El área táctil nunca baja de 44 pt (SPEC §7.2), aunque el dibujo sea menor.
    static let minTouch: CGFloat = 44

    func size(_ id: ControlID) -> CGSize {
        switch id {
        case .dpad: CGSize(width: dpadDiameter, height: dpadDiameter)
        case .a, .b: CGSize(width: faceDiameter, height: faceDiameter)
        case .start, .select: pillSize
        case .menu: CGSize(width: menuDiameter, height: menuDiameter)
        case .l, .r: shoulderSize
        }
    }
}

/// Qué toca un dedo: un control o la zona invisible A+B.
enum ControlHit: Hashable, Sendable {
    case control(ControlID)
    case ab
}

/// Geometría resuelta en puntos: marcos visuales, marcos táctiles y máscara del D-pad.
/// Pura (sin UIKit) para poder probarla (ControlsLayoutTests).
struct ControlsGeometry: Equatable, Sendable {
    let frames: [ControlID: CGRect]
    let abFrame: CGRect

    /// Resuelve un layout dentro de `area` (bounds menos safe area). Cada control se clama
    /// para quedar entero dentro del área: nunca bajo la Dynamic Island ni el Home Indicator.
    /// `shoulders`: dibuja y atiende L y R (solo Game Boy Advance).
    init(layout: ControlsLayout, orientation: ControlsOrientation, area: CGRect, metrics: ControlMetrics,
         shoulders: Bool = false) {
        var frames: [ControlID: CGRect] = [:]
        for id in ControlID.allCases where shoulders || !id.isShoulder {
            let base = metrics.size(id)
            let k = layout.scale(id)
            let size = CGSize(width: base.width * k, height: base.height * k)
            let rel = layout.center(id, orientation: orientation, shoulders: shoulders)
            let center = Self.clamp(CGPoint(x: area.minX + area.width * rel.x, y: area.minY + area.height * rel.y),
                                    size: size, in: area)
            frames[id] = CGRect(x: center.x - size.width / 2, y: center.y - size.height / 2,
                                width: size.width, height: size.height)
        }
        self.frames = frames
        let a = frames[.a] ?? .zero, b = frames[.b] ?? .zero
        let d = metrics.abDiameter * (layout.scale(.a) + layout.scale(.b)) / 2
        abFrame = CGRect(x: (a.midX + b.midX - d) / 2, y: (a.midY + b.midY - d) / 2, width: d, height: d)
    }

    static func clamp(_ p: CGPoint, size: CGSize, in area: CGRect) -> CGPoint {
        let halfW = min(size.width / 2, area.width / 2), halfH = min(size.height / 2, area.height / 2)
        return CGPoint(x: min(max(p.x, area.minX + halfW), area.maxX - halfW),
                       y: min(max(p.y, area.minY + halfH), area.maxY - halfH))
    }

    /// Posición relativa de un centro en puntos (para guardar tras editar).
    static func relative(_ p: CGPoint, in area: CGRect) -> CGPoint {
        guard area.width > 0, area.height > 0 else { return CGPoint(x: 0.5, y: 0.5) }
        return CGPoint(x: (p.x - area.minX) / area.width, y: (p.y - area.minY) / area.height)
    }

    /// Marco táctil: el visual, agrandado hasta 44 pt si hace falta.
    func touchFrame(_ id: ControlID) -> CGRect {
        guard let r = frames[id] else { return .null }
        let dx = max(0, (ControlMetrics.minTouch - r.width) / 2)
        let dy = max(0, (ControlMetrics.minTouch - r.height) / 2)
        return r.insetBy(dx: -dx, dy: -dy)
    }

    /// Menú y hombros L/R primero (así nunca quedan inalcanzables aunque otro control se
    /// mueva encima); luego A+B (zona pequeña entre ambos), los círculos por distancia y
    /// Start/Select por rectángulo.
    func hit(at p: CGPoint) -> ControlHit? {
        if Self.inCircle(p, touchFrame(.menu)) { return .control(.menu) }
        for id in [ControlID.l, .r] where frames[id] != nil && touchFrame(id).insetBy(dx: -6, dy: -6).contains(p) {
            return .control(id)
        }
        if Self.inCircle(p, abFrame) { return .ab }
        for id in [ControlID.a, .b, .dpad] where Self.inCircle(p, touchFrame(id)) {
            return .control(id)
        }
        for id in [ControlID.start, .select] where touchFrame(id).insetBy(dx: -6, dy: -6).contains(p) {
            return .control(id)
        }
        return nil
    }

    private static func inCircle(_ p: CGPoint, _ r: CGRect) -> Bool {
        hypot(p.x - r.midX, p.y - r.midY) <= r.width / 2
    }

    /// D-pad: 8 sectores de 45°, zona muerta del 25 % del radio, nunca direcciones opuestas.
    /// Funciona fuera del radio (el dedo queda capturado hasta que se levanta).
    func dpadMask(at p: CGPoint) -> UInt8 {
        guard let r = frames[.dpad] else { return 0 }
        return Self.dpadMask(dx: p.x - r.midX, dy: p.y - r.midY, radius: r.width / 2)
    }

    static func dpadMask(dx: CGFloat, dy: CGFloat, radius: CGFloat) -> UInt8 {
        guard hypot(dx, dy) >= radius * 0.25 else { return 0 }
        let angle = atan2(-dy, dx)   // 0 = derecha, sentido antihorario (y de pantalla hacia abajo)
        let turn: CGFloat = 2 * .pi
        let shifted = (angle + turn + .pi / 8).truncatingRemainder(dividingBy: turn)
        let sector = Int(shifted / (.pi / 4)) % 8
        let right = UInt8(GB_BTN_RIGHT), up = UInt8(GB_BTN_UP), left = UInt8(GB_BTN_LEFT), down = UInt8(GB_BTN_DOWN)
        return [right, right | up, up, up | left, left, left | down, down, down | right][sector]
    }
}

/// Motor de input táctil: tabla dedo → control y máscara resultante (SPEC §10.4).
/// Los dedos se identifican con un entero (en la vista, `ObjectIdentifier` del `UITouch`).
struct ControlsInputEngine: Sendable {
    var geometry: ControlsGeometry?
    private(set) var touches: [Int: ControlHit] = [:]
    private var points: [Int: CGPoint] = [:]

    init(geometry: ControlsGeometry? = nil) {
        self.geometry = geometry
    }

    /// Devuelve true si el toque abrió el menú (no se registra como botón).
    mutating func began(_ id: Int, at p: CGPoint) -> Bool {
        guard let hit = geometry?.hit(at: p) else { return false }
        if hit == .control(.menu) { return true }
        touches[id] = hit
        points[id] = p
        return false
    }

    /// El D-pad captura su dedo; A, B, A+B, Start y Select se pueden deslizar (B→A).
    mutating func moved(_ id: Int, to p: CGPoint) {
        guard let current = touches[id] else { return }
        points[id] = p
        guard current != .control(.dpad) else { return }
        if let hit = geometry?.hit(at: p), hit != .control(.menu), hit != .control(.dpad) {
            touches[id] = hit
        } else {
            touches[id] = nil
            points[id] = nil
        }
    }

    mutating func ended(_ id: Int) {
        touches[id] = nil
        points[id] = nil
    }

    /// Rotación, edición o controles ocultos: suelta todo (máscara cero).
    mutating func cancelAll() {
        touches.removeAll()
        points.removeAll()
    }

    var mask: UInt16 {
        var mask: UInt16 = 0
        for (id, hit) in touches {
            switch hit {
            case .control(.dpad): mask |= UInt16(points[id].flatMap { geometry?.dpadMask(at: $0) } ?? 0)
            case .control(.a): mask |= UInt16(GB_BTN_A)
            case .control(.b): mask |= UInt16(GB_BTN_B)
            case .ab: mask |= UInt16(GB_BTN_A | GB_BTN_B)
            case .control(.start): mask |= UInt16(GB_BTN_START)
            case .control(.select): mask |= UInt16(GB_BTN_SELECT)
            case .control(.l): mask |= UInt16(GBA_BTN_L)
            case .control(.r): mask |= UInt16(GBA_BTN_R)
            case .control(.menu): break
            }
        }
        return mask
    }

    /// Controles que se ven pulsados (A+B enciende los dos).
    var pressed: Set<ControlID> {
        var set: Set<ControlID> = []
        for (id, hit) in touches {
            switch hit {
            case .ab: set.formUnion([.a, .b])
            case .control(.dpad):
                if let p = points[id], geometry?.dpadMask(at: p) ?? 0 != 0 { set.insert(.dpad) }
            case .control(let c): set.insert(c)
            }
        }
        return set
    }
}

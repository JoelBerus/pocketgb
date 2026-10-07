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
    /// Flechas separadas (N2, ND10): distancia de cada flecha al centro respecto a la normal
    /// (1 = 100 %). Solo cuenta con el estilo «Flechas separadas»; la cruz la ignora.
    var arrowSpacing: CGFloat = 1

    static let scaleRange: ClosedRange<CGFloat> = 0.6...1.6
    static let arrowSpacingRange: ClosedRange<CGFloat> = 0.7...1.5

    init(centers: [ControlID: CGPoint], scales: [ControlID: CGFloat] = [:], arrowSpacing: CGFloat = 1) {
        self.centers = centers
        self.scales = scales
        self.arrowSpacing = arrowSpacing
    }

    // `scales` y `arrowSpacing` son opcionales: un layout guardado antes se sigue leyendo.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        centers = try c.decode([ControlID: CGPoint].self, forKey: .centers)
        scales = (try? c.decodeIfPresent([ControlID: CGFloat].self, forKey: .scales)) ?? [:]
        arrowSpacing = (try? c.decodeIfPresent(CGFloat.self, forKey: .arrowSpacing)) ?? 1
    }

    func scale(_ id: ControlID) -> CGFloat {
        min(max(scales[id] ?? 1, Self.scaleRange.lowerBound), Self.scaleRange.upperBound)
    }

    /// Separación de las flechas, siempre dentro de 0,7…1,5 (un valor guardado fuera se recorta).
    var spacing: CGFloat {
        guard arrowSpacing.isFinite else { return 1 }
        return min(max(arrowSpacing, Self.arrowSpacingRange.lowerBound), Self.arrowSpacingRange.upperBound)
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
    /// Separación de las flechas si la cruceta usa el estilo «Flechas separadas» (nil: la cruz).
    let dpadArrowSpacing: CGFloat?

    /// Resuelve un layout dentro de `area` (bounds menos safe area). Cada control se clama
    /// para quedar entero dentro del área: nunca bajo la Dynamic Island ni el Home Indicator.
    /// `shoulders`: dibuja y atiende L y R (solo Game Boy Advance). Con `dpadStyle == .separated`
    /// el marco de la cruceta crece o encoge con la separación de las flechas, y con él su zona táctil.
    init(layout: ControlsLayout, orientation: ControlsOrientation, area: CGRect, metrics: ControlMetrics,
         shoulders: Bool = false, dpadStyle: DpadStyle = .cross) {
        var frames: [ControlID: CGRect] = [:]
        dpadArrowSpacing = dpadStyle == .separated ? layout.spacing : nil
        for id in ControlID.allCases where shoulders || !id.isShoulder {
            let base = metrics.size(id)
            var k = layout.scale(id)
            if id == .dpad && dpadStyle == .separated { k *= DpadArrows.extentFactor(spacing: layout.spacing) }
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

    /// Cruceta: dirección del punto `p` según el modo de diagonales y la dirección que ya
    /// tenía ese dedo (histéresis). Funciona fuera del radio (el dedo queda capturado).
    /// Con flechas separadas (N2-H2), todo el disco de cada flecha pulsa su dirección (también
    /// sus bordes interior y laterales, sin diagonal) y la zona muerta no pasa del borde
    /// interior de las flechas; entre flechas y fuera de ellas decide el ángulo.
    func dpadMask(at p: CGPoint, diagonals: DpadDiagonals, previous: UInt8 = 0) -> UInt8 {
        guard let r = frames[.dpad] else { return 0 }
        var maxDeadZone = CGFloat.infinity
        if let spacing = dpadArrowSpacing {
            let arrows = DpadArrows.rects(in: r, spacing: spacing)
            for (disc, direction) in zip(arrows, DpadDirection.arms) {
                // El dedo que ya pulsa esta flecha la mantiene hasta salir un margen más allá.
                let hold = previous == direction ? DpadTuning.arrowHoldMargin : 0
                if hypot(p.x - disc.midX, p.y - disc.midY) <= disc.width / 2 + hold { return direction }
            }
            maxDeadZone = DpadArrows.innerEdge(in: r, spacing: spacing) - DpadTuning.arrowInnerMargin
        }
        return Self.dpadMask(dx: p.x - r.midX, dy: p.y - r.midY, radius: r.width / 2,
                             diagonals: diagonals, previous: previous, maxDeadZone: maxDeadZone)
    }

    /// Cruceta (N2): zona muerta del 30 % del radio (como mucho `maxDeadZone` pt); diagonal solo
    /// a ±`diagonals.halfAngle` de 45°, 135°…; nunca direcciones opuestas. Con `previous`
    /// distinto de cero hay histéresis: se suelta por debajo del 24 % del radio y la dirección
    /// actual se mantiene hasta que el dedo pasa `hysteresisDegrees` más allá del borde de su
    /// sector, así un temblor del dedo en la frontera no hace parpadear entre direcciones.
    /// `radius == 0`: sin zona muerta (mando).
    static func dpadMask(dx: CGFloat, dy: CGFloat, radius: CGFloat, diagonals: DpadDiagonals,
                         previous: UInt8 = 0, maxDeadZone: CGFloat = .infinity) -> UInt8 {
        let distance = hypot(dx, dy)
        let fraction = previous == 0 ? DpadTuning.deadZone : DpadTuning.releaseZone
        let limit = previous == 0 ? maxDeadZone : maxDeadZone * DpadTuning.releaseZone / DpadTuning.deadZone
        guard distance > 0, distance >= min(radius * fraction, max(limit, 0)) else { return 0 }
        var angle = atan2(-dy, dx) * 180 / .pi   // 0 = derecha, antihorario (y de pantalla hacia abajo)
        if angle < 0 { angle += 360 }
        if previous != 0, let center = DpadDirection.angle(of: previous) {
            let half = DpadDirection.isDiagonal(previous) ? diagonals.halfAngle : 45 - diagonals.halfAngle
            if DpadDirection.separation(angle, center) <= half + DpadTuning.hysteresisDegrees { return previous }
        }
        return DpadDirection.raw(angle: angle, diagonals: diagonals)
    }
}

/// Ajustes finos de la cruceta táctil (N2, docs/hitos/N-README.md §4).
enum DpadTuning {
    /// Fracción del radio por debajo de la cual un dedo nuevo no pulsa nada.
    static let deadZone: CGFloat = 0.30
    /// Un dedo que ya pulsa una dirección se suelta solo por debajo de esta fracción.
    static let releaseZone: CGFloat = 0.24
    /// Grados que el dedo debe pasar del borde de su sector para cambiar de dirección.
    static let hysteresisDegrees: CGFloat = 8
    /// Flechas separadas: la zona muerta acaba este margen (pt) antes del borde interior.
    static let arrowInnerMargin: CGFloat = 2
    /// Flechas separadas: el dedo mantiene su flecha hasta salir este margen (pt) del disco.
    static let arrowHoldMargin: CGFloat = 4
}

/// Cuánto ángulo ocupan las diagonales de la cruceta táctil (Ajustes › Controles).
enum DpadDiagonals: String, Codable, CaseIterable, Sendable {
    /// Ocho sectores iguales de 45°.
    case normal
    /// Diagonal solo a ±15° de 45° (por defecto): las direcciones rectas ocupan 60°.
    case reduced
    /// Solo arriba, abajo, izquierda y derecha.
    case off

    var title: String {
        switch self {
        case .normal: "Normales"
        case .reduced: "Reducidas"
        case .off: "Desactivadas"
        }
    }

    /// Mitad del sector de cada diagonal, en grados.
    var halfAngle: CGFloat {
        switch self {
        case .normal: 22.5
        case .reduced: 15
        case .off: 0
        }
    }
}

/// Direcciones de la cruceta como máscaras del núcleo y su ángulo (0° = derecha, antihorario).
enum DpadDirection {
    static let right = UInt8(GB_BTN_RIGHT), up = UInt8(GB_BTN_UP)
    static let left = UInt8(GB_BTN_LEFT), down = UInt8(GB_BTN_DOWN)
    /// Orden de brazos y flechas en el dibujo (arriba, derecha, abajo, izquierda).
    static let arms: [UInt8] = [up, right, down, left]
    /// Las ocho direcciones en orden antihorario desde la derecha (cada 45°).
    static let ordered: [UInt8] = [right, right | up, up, up | left, left, left | down, down, down | right]

    static func angle(of mask: UInt8) -> CGFloat? {
        ordered.firstIndex(of: mask).map { CGFloat($0) * 45 }
    }

    static func isDiagonal(_ mask: UInt8) -> Bool { mask.nonzeroBitCount == 2 }

    /// Quita las direcciones opuestas (arriba+abajo, izquierda+derecha) de una máscara de botones:
    /// dos dedos en la cruceta, o dedo y mando a la vez, nunca llegan así al núcleo (N2-H3).
    static func withoutOpposites(_ mask: UInt16) -> UInt16 {
        let vertical = UInt16(up | down), horizontal = UInt16(left | right)
        var mask = mask
        if mask & vertical == vertical { mask &= ~vertical }
        if mask & horizontal == horizontal { mask &= ~horizontal }
        return mask
    }

    /// Distancia angular entre dos ángulos en grados (0…180).
    static func separation(_ a: CGFloat, _ b: CGFloat) -> CGFloat {
        let d = abs(a - b).truncatingRemainder(dividingBy: 360)
        return min(d, 360 - d)
    }

    /// Dirección sin histéresis: diagonal dentro de ±`halfAngle` de su eje; si no, la recta más cercana.
    static func raw(angle: CGFloat, diagonals: DpadDiagonals) -> UInt8 {
        for i in stride(from: 1, to: 8, by: 2) where separation(angle, CGFloat(i) * 45) < diagonals.halfAngle {
            return ordered[i]
        }
        let quadrant = Int(((angle + 45).truncatingRemainder(dividingBy: 360)) / 90) % 4
        return [right, up, left, down][quadrant]
    }
}

/// Háptica de la cruceta (N2): solo al cambiar de dirección. Volver a la misma dirección tras
/// pasar por la zona muerta con el mismo dedo no vibra; levantar el dedo la reinicia.
struct DpadHapticGate: Sendable {
    private(set) var last: UInt8 = 0

    mutating func shouldFire(mask: UInt8, fingerDown: Bool) -> Bool {
        guard fingerDown else {
            last = 0
            return false
        }
        guard mask != 0, mask != last else { return false }
        last = mask
        return true
    }
}

/// Geometría de las flechas separadas (N2, ND10). Con separación 1 coincide con la de antes:
/// círculos de 0,36 × el ancho normal de la cruceta, a 0,32 × ese ancho del centro.
enum DpadArrows {
    static let diameterRatio: CGFloat = 0.36
    static let offsetRatio: CGFloat = 0.32
    /// Con separaciones pequeñas las flechas encogen para no tocarse: su diámetro no pasa
    /// del 92 % de la distancia entre los centros de dos flechas vecinas.
    static let neighbourGap: CGFloat = 0.92

    static func diameterRatio(spacing: CGFloat) -> CGFloat {
        min(diameterRatio, offsetRatio * spacing * 2.squareRoot() * neighbourGap)
    }

    /// Lado del marco de la cruceta respecto al ancho normal (1 con separación 1).
    static func extentFactor(spacing: CGFloat) -> CGFloat {
        2 * offsetRatio * spacing + diameterRatio(spacing: spacing)
    }

    /// Distancia del centro de la cruceta al borde interior de las flechas.
    static func innerEdge(in rect: CGRect, spacing: CGFloat) -> CGFloat {
        let base = rect.width / extentFactor(spacing: spacing)
        return base * (offsetRatio * spacing - diameterRatio(spacing: spacing) / 2)
    }

    /// Círculos de arriba, derecha, abajo e izquierda dentro del marco `rect` de la cruceta.
    static func rects(in rect: CGRect, spacing: CGFloat) -> [CGRect] {
        let base = rect.width / extentFactor(spacing: spacing)
        let d = base * diameterRatio(spacing: spacing)
        let offset = base * offsetRatio * spacing
        return [CGPoint(x: 0, y: -offset), CGPoint(x: offset, y: 0), CGPoint(x: 0, y: offset), CGPoint(x: -offset, y: 0)]
            .map { CGRect(x: rect.midX + $0.x - d / 2, y: rect.midY + $0.y - d / 2, width: d, height: d) }
    }
}

/// Geometría de la cruz estilo Game Boy dentro del marco de la cruceta.
enum DpadCross {
    static let lengthRatio: CGFloat = 0.76
    static let thicknessRatio: CGFloat = 0.29

    /// Brazos de arriba, derecha, abajo e izquierda: desde el cuadrado central hasta la punta.
    static func arms(in rect: CGRect) -> [CGRect] {
        let length = rect.width * lengthRatio, t = rect.width * thicknessRatio
        let reach = (length - t) / 2
        return [
            CGRect(x: rect.midX - t / 2, y: rect.midY - length / 2, width: t, height: reach),
            CGRect(x: rect.midX + t / 2, y: rect.midY - t / 2, width: reach, height: t),
            CGRect(x: rect.midX - t / 2, y: rect.midY + t / 2, width: t, height: reach),
            CGRect(x: rect.midX - length / 2, y: rect.midY - t / 2, width: reach, height: t),
        ]
    }

    /// Centro de cada brazo (para las flechas y para simular un toque en las capturas).
    static func armCenters(in rect: CGRect) -> [CGPoint] {
        arms(in: rect).map { CGPoint(x: $0.midX, y: $0.midY) }
    }
}

/// Motor de input táctil: tabla dedo → control y máscara resultante (SPEC §10.4).
/// Los dedos se identifican con un entero (en la vista, `ObjectIdentifier` del `UITouch`).
/// Cada dedo de la cruceta guarda su dirección actual: es la base de la histéresis.
struct ControlsInputEngine: Sendable {
    var geometry: ControlsGeometry?
    var diagonals: DpadDiagonals = .reduced
    private(set) var touches: [Int: ControlHit] = [:]
    private var dpadDirections: [Int: UInt8] = [:]

    init(geometry: ControlsGeometry? = nil, diagonals: DpadDiagonals = .reduced) {
        self.geometry = geometry
        self.diagonals = diagonals
    }

    /// Devuelve true si el toque abrió el menú (no se registra como botón).
    mutating func began(_ id: Int, at p: CGPoint) -> Bool {
        guard let hit = geometry?.hit(at: p) else { return false }
        if hit == .control(.menu) { return true }
        touches[id] = hit
        dpadDirections[id] = hit == .control(.dpad) ? geometry?.dpadMask(at: p, diagonals: diagonals) ?? 0 : nil
        return false
    }

    /// El D-pad captura su dedo; A, B, A+B, Start y Select se pueden deslizar (B→A).
    mutating func moved(_ id: Int, to p: CGPoint) {
        guard let current = touches[id] else { return }
        if current == .control(.dpad) {
            dpadDirections[id] = geometry?.dpadMask(at: p, diagonals: diagonals, previous: dpadDirections[id] ?? 0) ?? 0
            return
        }
        if let hit = geometry?.hit(at: p), hit != .control(.menu), hit != .control(.dpad) {
            touches[id] = hit
        } else {
            touches[id] = nil
        }
    }

    mutating func ended(_ id: Int) {
        touches[id] = nil
        dpadDirections[id] = nil
    }

    /// Rotación, edición o controles ocultos: suelta todo (máscara cero).
    mutating func cancelAll() {
        touches.removeAll()
        dpadDirections.removeAll()
    }

    /// Direcciones de la cruceta pulsadas ahora: OR de sus dedos sin direcciones opuestas.
    var dpadMask: UInt8 {
        UInt8(DpadDirection.withoutOpposites(UInt16(dpadDirections.values.reduce(0, |))))
    }

    /// Hay al menos un dedo que empezó en la cruceta (aunque esté en la zona muerta).
    var dpadFingerDown: Bool { !dpadDirections.isEmpty }

    var mask: UInt16 {
        var mask = UInt16(dpadMask)
        for hit in touches.values {
            switch hit {
            case .control(.a): mask |= UInt16(GB_BTN_A)
            case .control(.b): mask |= UInt16(GB_BTN_B)
            case .ab: mask |= UInt16(GB_BTN_A | GB_BTN_B)
            case .control(.start): mask |= UInt16(GB_BTN_START)
            case .control(.select): mask |= UInt16(GB_BTN_SELECT)
            case .control(.l): mask |= UInt16(GBA_BTN_L)
            case .control(.r): mask |= UInt16(GBA_BTN_R)
            case .control(.dpad), .control(.menu): break
            }
        }
        return mask
    }

    /// Controles que se ven pulsados (A+B enciende los dos). La cruceta entra si alguna
    /// dirección está pulsada; qué brazo se resalta lo dice `dpadMask`.
    var pressed: Set<ControlID> {
        var set: Set<ControlID> = []
        for hit in touches.values {
            switch hit {
            case .ab: set.formUnion([.a, .b])
            case .control(.dpad): break
            case .control(let c): set.insert(c)
            }
        }
        if dpadMask != 0 { set.insert(.dpad) }
        return set
    }
}

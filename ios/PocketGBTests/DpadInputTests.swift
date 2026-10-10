import CoreGraphics
import Foundation
import PocketGBCore
import Testing
@testable import PocketGB

/// N2: la cruceta solo responde en la dirección pulsada. Diagonales por modo, zona muerta
/// del 30 % con histéresis, háptica solo al cambiar de dirección y flechas separadas con
/// separación ajustable (docs/hitos/N-README.md §4 N2).
struct DpadInputTests {
    static let right = UInt8(GB_BTN_RIGHT), left = UInt8(GB_BTN_LEFT)
    static let up = UInt8(GB_BTN_UP), down = UInt8(GB_BTN_DOWN)

    static func mask(_ angle: Double, r: Double = 50, radius: Double = 70,
                     _ diagonals: DpadDiagonals, previous: UInt8 = 0) -> UInt8 {
        let rad = angle * .pi / 180
        return ControlsGeometry.dpadMask(dx: CGFloat(cos(rad) * r), dy: CGFloat(-sin(rad) * r),
                                         radius: CGFloat(radius), diagonals: diagonals, previous: previous)
    }

    // MARK: - Sectores por modo

    @Test func reducedDiagonalsOnlyWithin15DegreesOf45() {
        #expect(DpadDiagonals.allCases.count == 3)
        #expect(GameplaySettingsData().dpadDiagonals == .reduced)
        let cases: [(Double, UInt8)] = [
            (0, Self.right), (29.9, Self.right), (30.1, Self.right | Self.up), (45, Self.right | Self.up),
            (59.9, Self.right | Self.up), (60.1, Self.up), (90, Self.up), (119.9, Self.up),
            (120.1, Self.up | Self.left), (150.1, Self.left), (209.9, Self.left), (210.1, Self.left | Self.down),
            (240.1, Self.down), (300.1, Self.down | Self.right), (329.9, Self.down | Self.right), (330.1, Self.right),
        ]
        for (angle, expected) in cases {
            #expect(Self.mask(angle, .reduced) == expected, "ángulo \(angle)")
        }
        // Las rectas ocupan 60° y las diagonales 30°: 2/3 del círculo son direcciones rectas.
        let straight = (0..<3600).filter { Self.mask(Double($0) / 10, .reduced).nonzeroBitCount == 1 }.count
        #expect(abs(Double(straight) / 3600 - 2.0 / 3.0) < 0.01)
    }

    @Test func normalDiagonalsKeepEightEqualSectors() {
        #expect(Self.mask(22.4, .normal) == Self.right)
        #expect(Self.mask(22.6, .normal) == Self.right | Self.up)
        #expect(Self.mask(67.6, .normal) == Self.up)
        let straight = (0..<3600).filter { Self.mask(Double($0) / 10, .normal).nonzeroBitCount == 1 }.count
        #expect(abs(Double(straight) / 3600 - 0.5) < 0.01)
    }

    @Test func diagonalsOffGivesOnlyStraightDirections() {
        for tenth in 0..<3600 {
            #expect(Self.mask(Double(tenth) / 10, .off).nonzeroBitCount == 1, "ángulo \(Double(tenth) / 10)")
        }
        #expect(Self.mask(44.9, .off) == Self.right)
        #expect(Self.mask(45.1, .off) == Self.up)
        #expect(Self.mask(134.9, .off) == Self.up)
        #expect(Self.mask(135.1, .off) == Self.left)
    }

    // MARK: - Histéresis

    @Test func angularHysteresisKeepsTheCurrentDirectionNearTheBorder() {
        // Reducidas: UP llega hasta 60°; con la histéresis de 8° se mantiene hasta 52°.
        #expect(Self.mask(55, .reduced) == Self.right | Self.up)                   // dedo nuevo
        #expect(Self.mask(55, .reduced, previous: Self.up) == Self.up)             // ya pulsaba UP
        #expect(Self.mask(51, .reduced, previous: Self.up) == Self.right | Self.up)
        // Una diagonal (±15°) se mantiene hasta ±23°.
        #expect(Self.mask(66, .reduced, previous: Self.right | Self.up) == Self.right | Self.up)
        #expect(Self.mask(69, .reduced, previous: Self.right | Self.up) == Self.up)
        // Desactivadas: UP se mantiene hasta 37°, pero nunca llega a una dirección opuesta.
        #expect(Self.mask(40, .off, previous: Self.up) == Self.up)
        #expect(Self.mask(36, .off, previous: Self.up) == Self.right)
        // Una diagonal que quedó de otro modo se suelta en cuanto sale de su eje.
        #expect(Self.mask(80, .off, previous: Self.right | Self.up) == Self.up)
    }

    @Test func radialHysteresisReleasesBelow24Percent() {
        // Dedo nuevo: necesita el 30 % del radio (21 pt de 70).
        #expect(Self.mask(90, r: 19, .reduced) == 0)
        // El mismo dedo ya en UP: se mantiene hasta el 24 % (16,8 pt).
        #expect(Self.mask(90, r: 19, .reduced, previous: Self.up) == Self.up)
        #expect(Self.mask(90, r: 16.5, .reduced, previous: Self.up) == 0)
    }

    @Test func tremblingOnABorderDoesNotFlicker() {
        // Un dedo que tiembla ±3° sobre la frontera de UP (60° en «Reducidas») no cambia nunca.
        var previous = Self.mask(62, .reduced)
        #expect(previous == Self.up)
        for i in 0..<200 {
            let angle = 60 + 3 * sin(Double(i))
            previous = Self.mask(angle, .reduced, previous: previous)
            #expect(previous == Self.up, "muestra \(i)")
        }
    }

    // MARK: - Criterio objetivo: temblor de ±8 pt en el brazo ↑

    /// Generador determinista (SplitMix64): la misma traza en cada ejecución.
    struct SplitMix64 {
        var state: UInt64
        mutating func next() -> UInt64 {
            state &+= 0x9E37_79B9_7F4A_7C15
            var z = state
            z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
            z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
            return z ^ (z >> 31)
        }
        /// Uniforme en -1…1.
        mutating func unit() -> CGFloat { CGFloat(Double(next() >> 11) / Double(1 << 53)) * 2 - 1 }
    }

    /// Fracción de muestras que dan solo UP: el dedo se apoya en el centro del brazo ↑ (o de la
    /// flecha ↑ si la geometría es de flechas separadas), girado `offAxis` grados hacia la derecha,
    /// y tiembla hasta ±`amplitude` pt en cada eje, como una traza continua de un solo dedo (o
    /// toques sueltos). La primera muestra es el punto de apoyo exacto.
    static func upRatio(_ geometry: ControlsGeometry, diagonals: DpadDiagonals, amplitude: CGFloat = 8,
                        offAxis: CGFloat = 0, separateTaps: Bool = false, samples: Int = 10_000,
                        seed: UInt64 = 0x5EED_0002) -> Double {
        let frame = geometry.frames[.dpad]!
        let up = geometry.dpadArrowSpacing.map { DpadArrows.rects(in: frame, spacing: $0)[0] }
            .map { CGPoint(x: $0.midX, y: $0.midY) } ?? DpadCross.armCenters(in: frame)[0]
        let distance = hypot(up.x - frame.midX, up.y - frame.midY), angle = (90 - offAxis) * .pi / 180
        let target = CGPoint(x: frame.midX + distance * cos(angle), y: frame.midY - distance * sin(angle))
        var engine = ControlsInputEngine(geometry: geometry, diagonals: diagonals)
        var rng = SplitMix64(state: seed)
        var onlyUp = 0
        for i in 0..<samples {
            let p = i == 0 ? target : CGPoint(x: target.x + amplitude * rng.unit(), y: target.y + amplitude * rng.unit())
            if i == 0 || separateTaps {
                engine.cancelAll()
                _ = engine.began(1, at: p)
            } else {
                engine.moved(1, to: p)
            }
            if engine.mask == UInt16(Self.up) { onlyUp += 1 }
        }
        return Double(onlyUp) / Double(samples)
    }

    @Test func jitterOf8PointsAroundTheUpArmGivesOnlyUp() {
        let area = CGRect(x: 60, y: 0, width: 752, height: 381)
        // Cruceta normal (140 pt) en las cuatro disposiciones y la más pequeña (60 %, GBA horizontal).
        // Las cuatro disposiciones (la cruceta de GBA horizontal es la más pequeña, al 60 %) y
        // los dos estilos, cada uno con su geometría real (`dpadStyle`).
        for orientation in ControlsOrientation.allCases {
            for shoulders in [false, true] {
                for style in DpadStyle.allCases {
                    let g = ControlsGeometry(layout: .defaults(orientation, shoulders: shoulders), orientation: orientation,
                                             area: area, metrics: ControlMetrics(), shoulders: shoulders, dpadStyle: style)
                    #expect(g.dpadArrowSpacing == (style == .separated ? 1 : nil))
                    let ratio = Self.upRatio(g, diagonals: .reduced)
                    #expect(ratio >= 0.99, "\(orientation) \(shoulders ? "GBA" : "GB") \(style): solo UP en \(ratio * 100) %")
                }
            }
        }
        // Margen sin histéresis (cada muestra es un toque nuevo, cruz de 140 pt, ±13 pt): con
        // «Reducidas» sigue siendo solo UP; con los sectores de 45° de antes de N2 ya hay diagonales.
        let gb = ControlsGeometry(layout: .defaults(.landscape), orientation: .landscape, area: area,
                                  metrics: ControlMetrics())
        #expect(Self.upRatio(gb, diagonals: .reduced, amplitude: 13, separateTaps: true) >= 0.99)
        #expect(Self.upRatio(gb, diagonals: .normal, amplitude: 13, separateTaps: true) < 0.98)
    }

    /// Traza continua que distingue «Reducidas» de «Normales» (nota de la auditoría): el pulgar
    /// se apoya en el brazo ↑ girado 26° hacia la derecha (64°) y tiembla ±4 pt. Con «Reducidas»
    /// eso es arriba (rectas de 60°) y la histéresis lo mantiene; con «Normales» es diagonal.
    @Test func restingOffAxisDiscriminatesReducedFromNormal() {
        let g = ControlsGeometry(layout: .defaults(.portrait), orientation: .portrait,
                                 area: CGRect(x: 0, y: 0, width: 402, height: 420), metrics: ControlMetrics())
        let reduced = Self.upRatio(g, diagonals: .reduced, amplitude: 4, offAxis: 26)
        let normal = Self.upRatio(g, diagonals: .normal, amplitude: 4, offAxis: 26)
        #expect(reduced >= 0.99, "Reducidas: \(reduced)")
        #expect(normal <= 0.05, "Normales: \(normal)")
    }

    // MARK: - Motor y háptica

    @Test func engineHighlightsOnlyThePressedDirection() throws {
        let g = ControlsLayoutTests.geometry()
        let d = try #require(g.frames[.dpad])
        var engine = ControlsInputEngine(geometry: g)
        #expect(engine.diagonals == .reduced)
        let upArm = DpadCross.armCenters(in: d)[0]
        _ = engine.began(1, at: upArm)
        #expect(engine.dpadMask == Self.up)
        #expect(engine.mask == UInt16(Self.up))
        #expect(engine.pressed == [.dpad])
        // A 55° (diagonal para un dedo nuevo) el mismo dedo sigue en UP por la histéresis.
        let r = hypot(upArm.x - d.midX, upArm.y - d.midY)
        engine.moved(1, to: CGPoint(x: d.midX + r * cos(55 * .pi / 180), y: d.midY - r * sin(55 * .pi / 180)))
        #expect(engine.dpadMask == Self.up)
        // En la zona muerta: el dedo sigue capturado pero no pulsa nada ni se ve pulsado.
        engine.moved(1, to: CGPoint(x: d.midX + 2, y: d.midY - 2))
        #expect(engine.dpadMask == 0 && engine.dpadFingerDown)
        #expect(engine.pressed.isEmpty)
        engine.ended(1)
        #expect(!engine.dpadFingerDown && engine.mask == 0)
    }

    @Test func engineFollowsTheDiagonalsSetting() throws {
        let g = ControlsLayoutTests.geometry()
        let d = try #require(g.frames[.dpad])
        let diagonal = CGPoint(x: d.midX + 30, y: d.midY - 30)   // 45° exactos
        for (diagonals, expected) in [(DpadDiagonals.normal, Self.right | Self.up), (.reduced, Self.right | Self.up),
                                      (.off, Self.up)] {
            var engine = ControlsInputEngine(geometry: g, diagonals: diagonals)
            _ = engine.began(1, at: diagonal)
            // A 45° exactos «Desactivadas» elige la recta del cuadrante (arriba).
            #expect(engine.dpadMask.nonzeroBitCount == expected.nonzeroBitCount, "\(diagonals)")
            if diagonals != .off { #expect(engine.dpadMask == expected) }
        }
    }

    /// Regla común con Android: vibra cuando se activa una dirección que no estaba activa.
    @Test func hapticFiresWhenADirectionTurnsOn() {
        var gate = DpadHapticGate()
        let ur = Self.up | Self.right
        let trace: [(UInt8, Bool)] = [
            (0, false), (Self.up, true), (Self.up, false),        // ↑ sostenido: una vez
            (ur, true),                                           // ↑ → ↑→: se activa →
            (Self.up, false),                                     // ↑→ → ↑: no se activa nada
            (0, false), (Self.up, true),                          // ↑ → nada → ↑: sí
            (Self.right, true), (Self.down | Self.right, true),   // cada dirección nueva
            (Self.down, false), (0, false),
        ]
        for (i, (mask, expected)) in trace.enumerated() {
            #expect(gate.shouldFire(mask: mask) == expected, "paso \(i)")
        }
    }

    // MARK: - Flechas separadas: separación (ND10)

    static let area = CGRect(x: 60, y: 0, width: 752, height: 381)

    /// Cruceta lejos de los bordes (en (0,3; 0,6) del área) para que crecer no la desplace.
    static func arrowsLayout(spacing: CGFloat, scale: CGFloat = 1) -> ControlsLayout {
        var layout = ControlsLayout.defaults(.landscape)
        layout.centers[.dpad] = CGPoint(x: 0.3, y: 0.6)
        layout.arrowSpacing = spacing
        layout.scales[.dpad] = scale
        return layout
    }

    static func arrowsGeometry(spacing: CGFloat, scale: CGFloat = 1, style: DpadStyle = .separated) -> ControlsGeometry {
        ControlsGeometry(layout: arrowsLayout(spacing: spacing, scale: scale), orientation: .landscape, area: area,
                         metrics: ControlMetrics(), dpadStyle: style)
    }

    @Test func arrowSpacingDefaultsClampsAndDecodesOldLayouts() throws {
        #expect(ControlsLayout.defaults(.portrait).spacing == 1)
        #expect(ControlsLayout.arrowSpacingRange == 0.7...1.5)
        var layout = ControlsLayout.defaults(.landscape)
        layout.arrowSpacing = 9
        #expect(layout.spacing == 1.5)
        layout.arrowSpacing = 0.1
        #expect(layout.spacing == 0.7)
        layout.arrowSpacing = .nan
        #expect(layout.spacing == 1)
        let old = try #require(#"{"centers":["a",[0.5,0.5]],"scales":["dpad",0.8]}"#.data(using: .utf8))
        #expect(try JSONDecoder().decode(ControlsLayout.self, from: old).spacing == 1)
    }

    @Test func separatedFrameAndArrowsFollowTheSpacing() throws {
        let cross = try #require(Self.arrowsGeometry(spacing: 1, style: .cross).frames[.dpad])
        #expect(abs(cross.width - 140) < 0.01)
        for spacing in stride(from: CGFloat(0.7), through: 1.5, by: 0.1) {
            let g = Self.arrowsGeometry(spacing: spacing)
            let frame = try #require(g.frames[.dpad])
            #expect(abs(frame.width - 140 * DpadArrows.extentFactor(spacing: spacing)) < 0.01)
            #expect(abs(frame.midX - cross.midX) < 0.001 && abs(frame.midY - cross.midY) < 0.001, "el centro no se mueve")
            let rects = DpadArrows.rects(in: frame, spacing: spacing)
            // Fórmula común con Android: 0,32 × 140 × k con k ≥ 1; por debajo, de 0,32 × 140 a
            // 0,265 × 140 en línea recta (k = 0,7). El diámetro no cambia (0,36 × 140).
            let expected = spacing >= 1 ? 44.8 * spacing : 37.1 + (spacing - 0.7) / 0.3 * (44.8 - 37.1)
            for r in rects {
                #expect(abs(hypot(r.midX - frame.midX, r.midY - frame.midY) - expected) < 0.01, "k = \(spacing)")
                #expect(abs(r.width - 50.4) < 0.01, "k = \(spacing)")
            }
            // Nunca se tocan dos flechas vecinas, y caben en el marco.
            #expect(hypot(rects[0].midX - rects[1].midX, rects[0].midY - rects[1].midY) > rects[0].width)
            for r in rects { #expect(frame.insetBy(dx: -0.01, dy: -0.01).contains(r)) }
        }
        // Separación 1: igual que antes de N2 (círculos de 0,36 × 140 a 0,32 × 140 del centro).
        let normal = try #require(Self.arrowsGeometry(spacing: 1).frames[.dpad])
        #expect(abs(normal.width - 140) < 0.01)
        #expect(abs(DpadArrows.rects(in: normal, spacing: 1)[0].width - 50.4) < 0.01)
        // La cruz ignora la separación.
        let g = Self.arrowsGeometry(spacing: 1.5, style: .cross)
        #expect(abs((g.frames[.dpad]?.width ?? 0) - 140) < 0.01)
    }

    /// N2-H2: todo el disco de cada flecha (centro, borde interior, bordes laterales y exterior)
    /// pulsa su dirección y solo esa, con cualquier separación, tamaño y modo de diagonales; la
    /// zona muerta acaba antes del borde interior de las flechas.
    @Test func wholeDiscOfEachArrowPressesItsDirection() throws {
        for spacing in [CGFloat(0.7), 1, 1.5] {
            for scale in [CGFloat(0.6), 1, 1.6] {
                let g = Self.arrowsGeometry(spacing: spacing, scale: scale)
                let frame = try #require(g.frames[.dpad])
                let discs = DpadArrows.rects(in: frame, spacing: spacing)
                for diagonals in DpadDiagonals.allCases {
                    var failures = 0
                    for (disc, direction) in zip(discs, DpadDirection.arms) {
                        for step in 0...20 {
                            let rf = min(CGFloat(step) / 20, 0.999) * disc.width / 2
                            for degrees in stride(from: 0, to: 360, by: 10) {
                                let a = CGFloat(degrees) * .pi / 180
                                let p = CGPoint(x: disc.midX + rf * cos(a), y: disc.midY - rf * sin(a))
                                var engine = ControlsInputEngine(geometry: g, diagonals: diagonals)
                                _ = engine.began(1, at: p)
                                if g.hit(at: p) != .control(.dpad) || engine.dpadMask != direction { failures += 1 }
                            }
                        }
                    }
                    #expect(failures == 0, "separación \(spacing), tamaño \(scale), \(diagonals): \(failures) puntos")
                }
                // Zona muerta: el centro no pulsa nada y acaba 2 pt antes del borde interior.
                let inner = DpadArrows.innerEdge(in: frame, spacing: spacing)
                let deadZone = min(frame.width / 2 * DpadTuning.deadZone, inner - DpadTuning.arrowInnerMargin)
                #expect(deadZone > 0 && deadZone <= inner - 2)
                var engine = ControlsInputEngine(geometry: g)
                _ = engine.began(1, at: CGPoint(x: frame.midX, y: frame.midY))
                #expect(engine.dpadMask == 0)
                engine.moved(1, to: CGPoint(x: frame.midX, y: frame.midY - (deadZone - 0.5)))
                #expect(engine.dpadMask == 0)
                engine.cancelAll()
                _ = engine.began(1, at: CGPoint(x: frame.midX, y: frame.midY - (inner - 0.5)))
                #expect(engine.dpadMask == Self.up, "hueco junto al borde interior de ↑")
            }
        }
    }

    /// Un dedo que tiembla ±2 pt sobre el borde lateral de la flecha ↑ (separación 0,7, donde el
    /// disco llega a 47° y la diagonal empieza a 60°) no parpadea entre ↑ y la diagonal.
    @Test func tremblingOnTheSideOfAnArrowDoesNotFlicker() throws {
        let g = Self.arrowsGeometry(spacing: 0.7)
        let frame = try #require(g.frames[.dpad])
        let disc = DpadArrows.rects(in: frame, spacing: 0.7)[0]
        let edge = CGPoint(x: disc.maxX, y: disc.midY)
        var engine = ControlsInputEngine(geometry: g)
        _ = engine.began(1, at: CGPoint(x: disc.midX, y: disc.midY))
        var rng = SplitMix64(state: 7)
        for i in 0..<2000 {
            engine.moved(1, to: CGPoint(x: edge.x + 2 * rng.unit(), y: edge.y + 2 * rng.unit()))
            #expect(engine.dpadMask == Self.up, "muestra \(i)")
        }
    }

    // MARK: - Direcciones opuestas (N2-H3)

    @Test func twoFingersOnOppositeArmsNeverSendOpposites() throws {
        let g = ControlsLayoutTests.geometry()
        let d = try #require(g.frames[.dpad])
        let arms = DpadCross.armCenters(in: d)
        var engine = ControlsInputEngine(geometry: g)
        _ = engine.began(1, at: arms[0])                        // ↑
        _ = engine.began(2, at: arms[2])                        // ↓
        #expect(engine.dpadMask == 0 && engine.mask == 0)
        _ = engine.began(3, at: arms[1])                        // → además
        #expect(engine.dpadMask == Self.right)
        #expect(engine.pressed == [.dpad])
        engine.ended(2)
        #expect(engine.dpadMask == Self.up | Self.right)
        for mask in 0...UInt16(0x3FF) {
            let clean = DpadDirection.withoutOpposites(mask)
            #expect(clean & 0xC0 != 0xC0 && clean & 0x30 != 0x30)
            #expect(clean & ~UInt16(0xF0) == mask & ~UInt16(0xF0), "no toca los demás botones")
        }
    }

    @MainActor
    @Test func sessionNeverSendsOppositeDirectionsToTheCore() async throws {
        let up = UInt16(Self.up), down = UInt16(Self.down), left = UInt16(Self.left), right = UInt16(Self.right)
        let a = UInt16(GB_BTN_A)
        #expect(EmulatorSession.combinedButtons(touch: up | a, pad: down | left) == a | left)
        #expect(EmulatorSession.combinedButtons(touch: left, pad: right | up) == up)
        #expect(EmulatorSession.combinedButtons(touch: up | right, pad: 0) == up | right)
        // Extremo a extremo: lo que llega al núcleo en cada frame.
        let core = FakeCore()
        let session = EmulatorSession(core: core, info: EmulatorSessionContractTests.info, persisters: [],
                                      loadWarning: nil, onAudioInterrupted: {})
        session.buttons.set(up | a)
        session.padButtons.set(down | left)
        session.start()
        let deadline = Date().addingTimeInterval(5)
        while core.lastButtons == nil && Date() < deadline { try await Task.sleep(for: .milliseconds(20)) }
        session.stop()
        #expect(core.lastButtons == a | left)
    }

    @MainActor
    @Test func spacingIsSavedPerLayoutAndResetGoesBackTo100Percent() throws {
        let suite = "DpadInputTests-\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }

        let settings = GameplaySettings(defaults: defaults)
        for _ in 0..<20 { settings.respaceArrows(by: 0.1, orientation: .landscape) }
        #expect(settings.data.landscapeLayout.spacing == 1.5)
        for _ in 0..<20 { settings.respaceArrows(by: -0.1, orientation: .portrait, shoulders: true) }
        #expect(settings.data.gbaPortraitLayout.spacing == 0.7)
        settings.respaceArrows(by: -0.1, orientation: .portrait)
        settings.respaceArrows(by: -0.1, orientation: .portrait)
        settings.respaceArrows(by: -0.1, orientation: .portrait)
        // Pasos exactos del 10 % (sin arrastrar errores de coma flotante).
        #expect(settings.data.portraitLayout.spacing == 0.7)
        #expect(settings.data.gbaLandscapeLayout.spacing == 1)
        settings.update { $0.dpadDiagonals = .off }

        let reopened = GameplaySettings(defaults: defaults)
        #expect(reopened.data.landscapeLayout.spacing == 1.5)
        #expect(reopened.data.gbaPortraitLayout.spacing == 0.7)
        #expect(reopened.data.portraitLayout.spacing == 0.7)
        #expect(reopened.data.gbaLandscapeLayout.spacing == 1)
        #expect(reopened.data.dpadDiagonals == .off)

        reopened.resetLayout(.landscape)
        #expect(reopened.data.landscapeLayout.spacing == 1)
        #expect(reopened.data.landscapeLayout == .defaults(.landscape))
        #expect(reopened.data.gbaPortraitLayout.spacing == 0.7, "restablecer solo toca su disposición")
    }

    @Test func diagonalsSettingDecodesOldAndInvalidValues() throws {
        let old = try #require(#"{"opacity": 50}"#.data(using: .utf8))
        #expect(try JSONDecoder().decode(GameplaySettingsData.self, from: old).dpadDiagonals == .reduced)
        let invalid = try #require(#"{"dpadDiagonals": "sometimes"}"#.data(using: .utf8))
        #expect(try JSONDecoder().decode(GameplaySettingsData.self, from: invalid).dpadDiagonals == .reduced)
        var data = GameplaySettingsData()
        data.dpadDiagonals = .normal
        #expect(try JSONDecoder().decode(GameplaySettingsData.self, from: JSONEncoder().encode(data)).dpadDiagonals == .normal)
    }
}

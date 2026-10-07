import CoreGraphics
import Foundation
import PocketGBACore
import PocketGBCore
import Testing
@testable import PocketGB

/// D4: geometría y motor de input de los controles táctiles (D-README §6, criterios CI).
struct ControlsLayoutTests {
    static let right = UInt16(GB_BTN_RIGHT), left = UInt16(GB_BTN_LEFT)
    static let up = UInt16(GB_BTN_UP), down = UInt16(GB_BTN_DOWN)
    static let a = UInt16(GB_BTN_A), b = UInt16(GB_BTN_B)

    /// Máscara para un punto a `angle` grados (0 = derecha, antihorario) y distancia `r`,
    /// con diagonales «Normales» (ocho sectores de 45°) salvo que se indique otro modo.
    static func mask(_ angle: Double, r: Double = 60, radius: Double = 70,
                     diagonals: DpadDiagonals = .normal, previous: UInt8 = 0) -> UInt16 {
        let rad = angle * .pi / 180
        return UInt16(ControlsGeometry.dpadMask(dx: CGFloat(cos(rad) * r), dy: CGFloat(-sin(rad) * r),
                                                radius: CGFloat(radius), diagonals: diagonals, previous: previous))
    }

    @Test func normalDiagonalsGiveEightSectorsOf45Degrees() {
        let expected: [(Double, UInt16)] = [
            (0, Self.right), (45, Self.right | Self.up), (90, Self.up), (135, Self.up | Self.left),
            (180, Self.left), (225, Self.left | Self.down), (270, Self.down), (315, Self.down | Self.right),
        ]
        for (angle, mask) in expected {
            #expect(Self.mask(angle) == mask, "ángulo \(angle)")
        }
    }

    @Test func boundaryAnglesAt22Point5Degrees() {
        // Justo antes y justo después de cada frontera de sector (±22,5° de cada eje).
        #expect(Self.mask(22.4) == Self.right)
        #expect(Self.mask(22.6) == Self.right | Self.up)
        #expect(Self.mask(67.4) == Self.right | Self.up)
        #expect(Self.mask(67.6) == Self.up)
        #expect(Self.mask(-22.4) == Self.right)
        #expect(Self.mask(-22.6) == Self.down | Self.right)
        #expect(Self.mask(157.6) == Self.left)
        #expect(Self.mask(202.4) == Self.left)
        #expect(Self.mask(202.6) == Self.left | Self.down)
    }

    @Test func deadZoneIs30PercentOfRadius() {
        for diagonals in DpadDiagonals.allCases {
            #expect(Self.mask(0, r: 20.9, diagonals: diagonals) == 0)        // < 30 % de 70
            #expect(Self.mask(0, r: 21.1, diagonals: diagonals) == Self.right)
            #expect(Self.mask(123, r: 0, diagonals: diagonals) == 0)
        }
    }

    @Test func neverOppositeDirections() {
        for diagonals in DpadDiagonals.allCases {
            var previous: UInt8 = 0
            for tenth in 0..<3600 {
                // Barrido sin histéresis y como un dedo que rueda (con histéresis).
                for m in [Self.mask(Double(tenth) / 10, r: 50, diagonals: diagonals),
                          Self.mask(Double(tenth) / 10, r: 50, diagonals: diagonals, previous: previous)] {
                    #expect(m & (Self.up | Self.down) != (Self.up | Self.down))
                    #expect(m & (Self.left | Self.right) != (Self.left | Self.right))
                    #expect(m != 0)
                }
                previous = UInt8(Self.mask(Double(tenth) / 10, r: 50, diagonals: diagonals, previous: previous))
            }
            // También muy fuera del radio (dedo capturado).
            #expect(Self.mask(90, r: 500, diagonals: diagonals) == Self.up)
        }
    }

    static let area = CGRect(x: 60, y: 0, width: 752, height: 381)   // horizontal con safe area

    static func geometry(_ layout: ControlsLayout = .defaults(.landscape),
                         orientation: ControlsOrientation = .landscape) -> ControlsGeometry {
        ControlsGeometry(layout: layout, orientation: orientation, area: area, metrics: ControlMetrics())
    }

    @Test func abZoneBetweenAAndBPressesBoth() throws {
        let g = Self.geometry()
        var engine = ControlsInputEngine(geometry: g)
        let opensMenu = engine.began(1, at: CGPoint(x: g.abFrame.midX, y: g.abFrame.midY))
        #expect(!opensMenu)
        #expect(engine.mask == Self.a | Self.b)
        #expect(engine.pressed == [.a, .b])
    }

    @Test func twoFingersAAndBAndSlideBToA() throws {
        let g = Self.geometry()
        let a = try #require(g.frames[.a]), b = try #require(g.frames[.b])
        var engine = ControlsInputEngine(geometry: g)
        _ = engine.began(1, at: CGPoint(x: a.midX, y: a.midY))
        _ = engine.began(2, at: CGPoint(x: b.midX, y: b.midY))
        #expect(engine.mask == Self.a | Self.b)
        engine.ended(1)
        #expect(engine.mask == Self.b)
        // B → A sin levantar el dedo.
        engine.moved(2, to: CGPoint(x: a.midX, y: a.midY))
        #expect(engine.mask == Self.a)
        engine.ended(2)
        #expect(engine.mask == 0)
    }

    @Test func dpadCapturesItsFingerOutsideTheRadius() throws {
        let g = Self.geometry()
        let d = try #require(g.frames[.dpad])
        var engine = ControlsInputEngine(geometry: g)
        _ = engine.began(1, at: CGPoint(x: d.midX + 40, y: d.midY))
        #expect(engine.mask == Self.right)
        // Muy por encima del D-pad (encima incluso de otros controles): sigue siendo el D-pad.
        engine.moved(1, to: CGPoint(x: d.midX, y: d.midY - 300))
        #expect(engine.mask == Self.up)
    }

    @Test func menuOpensWithoutPressingButtons() throws {
        let g = Self.geometry()
        let m = try #require(g.frames[.menu])
        var engine = ControlsInputEngine(geometry: g)
        let opensMenu = engine.began(1, at: CGPoint(x: m.midX, y: m.midY))
        #expect(opensMenu)
        #expect(engine.mask == 0)
    }

    @Test func rotationCancelsEveryFingerWithZeroMask() throws {
        let g = Self.geometry()
        let a = try #require(g.frames[.a]), d = try #require(g.frames[.dpad])
        var engine = ControlsInputEngine(geometry: g)
        _ = engine.began(1, at: CGPoint(x: a.midX, y: a.midY))
        _ = engine.began(2, at: CGPoint(x: d.midX - 50, y: d.midY))
        #expect(engine.mask != 0)
        engine.cancelAll()
        #expect(engine.mask == 0)
        #expect(engine.pressed.isEmpty)
    }

    @Test func positionsAreClampedInsideTheSafeArea() {
        var layout = ControlsLayout.defaults(.landscape)
        layout.centers[.dpad] = CGPoint(x: -1, y: 2)      // fuera por la izquierda y abajo
        layout.centers[.a] = CGPoint(x: 1, y: 0)          // esquina superior derecha
        let g = Self.geometry(layout)
        for id in ControlID.allCases {
            let frame = g.frames[id] ?? .null
            #expect(Self.area.contains(frame), "\(id) fuera del área segura: \(frame)")
        }
    }

    @Test func smallControlsKeepA44PointTouchTarget() {
        let g = ControlsGeometry(layout: .defaults(.portrait), orientation: .portrait,
                                 area: CGRect(x: 0, y: 0, width: 402, height: 420),
                                 metrics: ControlMetrics(scale: 0.85))
        for id in [ControlID.start, .select, .menu] {
            let t = g.touchFrame(id)
            #expect(t.width >= 44 && t.height >= 44, "\(id): \(t)")
        }
    }

    @MainActor
    @Test func portraitAndLandscapeLayoutsPersistSeparately() throws {
        let suite = "ControlsLayoutTests-\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }

        let settings = GameplaySettings(defaults: defaults)
        settings.move(.a, to: CGPoint(x: 0.7, y: 0.3), orientation: .portrait)
        settings.move(.b, to: CGPoint(x: 0.2, y: 0.8), orientation: .landscape)
        settings.update { $0.opacity = 30 }

        let reopened = GameplaySettings(defaults: defaults)
        #expect(reopened.data.portraitLayout.centers[.a] == CGPoint(x: 0.7, y: 0.3))
        #expect(reopened.data.landscapeLayout.centers[.a] == ControlsLayout.defaults(.landscape).centers[.a])
        #expect(reopened.data.landscapeLayout.centers[.b] == CGPoint(x: 0.2, y: 0.8))
        #expect(reopened.data.portraitLayout.centers[.b] == ControlsLayout.defaults(.portrait).centers[.b])
        #expect(reopened.data.opacity == 30)

        reopened.resetLayout(.portrait)
        #expect(reopened.data.portraitLayout == .defaults(.portrait))
        #expect(reopened.data.landscapeLayout.centers[.b] == CGPoint(x: 0.2, y: 0.8))
    }

    @Test func invalidStoredOpacityFallsBackToDefault() throws {
        let data = try #require(#"{"opacity": 42, "sizeScale": 9}"#.data(using: .utf8))
        let decoded = try JSONDecoder().decode(GameplaySettingsData.self, from: data)
        #expect(decoded.opacity == 70)
        #expect(decoded.sizeScale == 1.15)
    }

    @MainActor
    @Test func perControlSizeIsClampedAndPerOrientation() throws {
        let settings = GameplaySettings(defaults: nil)
        for _ in 0..<10 { settings.resize(.a, by: 0.1, orientation: .portrait) }
        #expect(settings.data.portraitLayout.scale(.a) == 1.6)
        #expect(settings.data.landscapeLayout.scale(.a) == 1)
        for _ in 0..<20 { settings.resize(.dpad, by: -0.1, orientation: .landscape) }
        #expect(settings.data.landscapeLayout.scale(.dpad) == 0.6)

        // La geometría usa el tamaño elegido y sigue dentro del área segura.
        let g = Self.geometry(settings.data.landscapeLayout)
        let d = try #require(g.frames[.dpad])
        #expect(abs(d.width - 140 * 0.6) < 0.01)
        #expect(Self.area.contains(d))

        // Un layout guardado sin tamaños se sigue leyendo.
        let old = try #require(#"{"centers":["a",[0.5,0.5]]}"#.data(using: .utf8))
        let decoded = try JSONDecoder().decode(ControlsLayout.self, from: old)
        #expect(decoded.scale(.a) == 1)
    }

    @Test func dpadStyleDefaultsToGameBoyAndPersists() throws {
        #expect(GameplaySettingsData().dpadStyle == .cross)
        var data = GameplaySettingsData()
        data.dpadStyle = .separated
        let decoded = try JSONDecoder().decode(GameplaySettingsData.self, from: JSONEncoder().encode(data))
        #expect(decoded.dpadStyle == .separated)
    }

    // MARK: - Game Boy Advance (G8-H1)

    /// iPhone 17 Pro en horizontal: pantalla, safe area (= `area`) e imagen 3:2 a escala
    /// entera (7 px por píxel del juego a 3x ⇒ ≈560×373 pt), centrada en toda la pantalla.
    static let screen = CGSize(width: 874, height: 402)
    static let gbaImage = CGRect(x: (874 - 560) / 2, y: (402 - 373.0) / 2, width: 560, height: 373)

    static func gbaGeometry(_ layout: ControlsLayout? = nil, orientation: ControlsOrientation = .landscape,
                            area: CGRect = Self.area) -> ControlsGeometry {
        ControlsGeometry(layout: layout ?? .defaults(orientation, shoulders: true), orientation: orientation,
                         area: area, metrics: ControlMetrics(), shoulders: true)
    }

    @MainActor
    @Test func editingGameBoyLayoutLeavesAdvanceUntouched() {
        let settings = GameplaySettings(defaults: nil)
        let gbaBefore = (settings.data.gbaPortraitLayout, settings.data.gbaLandscapeLayout)
        settings.move(.a, to: CGPoint(x: 0.5, y: 0.5), orientation: .landscape)
        settings.move(.dpad, to: CGPoint(x: 0.3, y: 0.3), orientation: .portrait)
        settings.resize(.b, by: 0.2, orientation: .landscape)
        #expect(settings.data.gbaPortraitLayout == gbaBefore.0 && settings.data.gbaLandscapeLayout == gbaBefore.1)
        #expect(settings.data.landscapeLayout.centers[.a] == CGPoint(x: 0.5, y: 0.5))

        // Y al revés: editar GBA no mueve la disposición de Game Boy.
        let gbBefore = (settings.data.portraitLayout, settings.data.landscapeLayout)
        settings.move(.l, to: CGPoint(x: 0.4, y: 0.4), orientation: .landscape, shoulders: true)
        settings.resize(.a, by: 0.3, orientation: .portrait, shoulders: true)
        #expect(settings.data.portraitLayout == gbBefore.0 && settings.data.landscapeLayout == gbBefore.1)
        #expect(settings.data.gbaLandscapeLayout.centers[.l] == CGPoint(x: 0.4, y: 0.4))
        settings.resetLayout(.landscape, shoulders: true)
        #expect(settings.data.gbaLandscapeLayout == .defaults(.landscape, shoulders: true))
        #expect(settings.data.landscapeLayout.centers[.a] == CGPoint(x: 0.5, y: 0.5))
    }

    @Test func advanceLandscapeDefaultsDoNotCoverThe3x2Image() throws {
        let g = Self.gbaGeometry()
        let ids: [ControlID] = [.dpad, .a, .b, .start, .select, .l, .r]
        for id in ids {
            let f = try #require(g.frames[id])
            #expect(Self.area.contains(f), "\(id) fuera del área")
            #expect(!f.intersects(Self.gbaImage), "\(id) pisa la imagen 3:2: \(f)")
        }
        for (i, x) in ids.enumerated() {
            for y in ids[(i + 1)...] {
                #expect(!g.touchFrame(x).intersects(g.touchFrame(y)), "\(x) y \(y) se solapan")
            }
        }
    }

    @Test func advancePortraitDefaultsKeepShouldersClearOfHUDAndControls() throws {
        // Controles bajo la imagen 3:2 (402×268): ≈510 pt de alto.
        let area = CGRect(x: 0, y: 0, width: 402, height: 510)
        let g = Self.gbaGeometry(orientation: .portrait, area: area)
        let l = try #require(g.frames[.l]), r = try #require(g.frames[.r])
        #expect(area.contains(l) && area.contains(r) && !l.intersects(r))
        // HUD real (pausa + avance ≈ 100×44 pt) centrado arriba, y el botón de menú.
        let hud = CGRect(x: 201 - 50, y: 6, width: 100, height: 44)
        for t in [g.touchFrame(.l), g.touchFrame(.r)] {
            #expect(!t.intersects(hud) && !t.intersects(g.touchFrame(.menu)))
            for id in [ControlID.a, .b, .dpad, .start, .select] {
                #expect(!t.intersects(g.touchFrame(id)), "\(id) solapa un hombro")
            }
        }
    }

    @Test func shouldersStayTappableWhenDpadIsMovedOnTop() throws {
        var layout = ControlsLayout.defaults(.landscape, shoulders: true)
        let base = Self.gbaGeometry(layout)
        let l = try #require(base.frames[.l]), r = try #require(base.frames[.r])
        let area = Self.area
        // La cruceta y A se arrastran justo encima de L y R.
        layout.centers[.dpad] = ControlsGeometry.relative(CGPoint(x: l.midX, y: l.midY), in: area)
        layout.centers[.a] = ControlsGeometry.relative(CGPoint(x: r.midX, y: r.midY), in: area)
        let g = Self.gbaGeometry(layout)
        #expect(try #require(g.frames[.dpad]).contains(CGPoint(x: l.midX, y: l.midY)))
        #expect(g.hit(at: CGPoint(x: l.midX, y: l.midY)) == .control(.l))
        #expect(g.hit(at: CGPoint(x: r.midX, y: r.midY)) == .control(.r))
    }

    @Test func oldStoredSettingsKeepGameBoyLayoutAndAdvanceStartsWithItsDefaults() throws {
        let old = try #require(#"{"portraitLayout":{"centers":["a",[0.7,0.3]]},"landscapeLayout":{"centers":["b",[0.2,0.8]]}}"#
            .data(using: .utf8))
        let data = try JSONDecoder().decode(GameplaySettingsData.self, from: old)
        #expect(data.portraitLayout.centers[.a] == CGPoint(x: 0.7, y: 0.3))
        #expect(data.landscapeLayout.centers[.b] == CGPoint(x: 0.2, y: 0.8))
        #expect(data.gbaPortraitLayout == .defaults(.portrait, shoulders: true))
        #expect(data.gbaLandscapeLayout == .defaults(.landscape, shoulders: true))
        #expect(data.layout(.landscape, shoulders: true) == data.gbaLandscapeLayout)
        #expect(data.layout(.landscape) == data.landscapeLayout)
    }

    @Test func shoulderButtonsExistOnlyOnGameBoyAdvance() throws {
        #expect(ControlID.allCases.map(\.rawValue).sorted() == ["a", "b", "dpad", "l", "menu", "r", "select", "start"])
        // Game Boy: sin marcos ni zona táctil para L y R.
        let gb = Self.geometry()
        #expect(gb.frames[.l] == nil && gb.frames[.r] == nil)
        // Game Boy Advance: L (bit 9) y R (bit 8) llegan al núcleo.
        let gba = Self.gbaGeometry()
        let l = try #require(gba.frames[.l]), r = try #require(gba.frames[.r])
        #expect(Self.area.contains(l) && Self.area.contains(r))
        var engine = ControlsInputEngine(geometry: gba)
        _ = engine.began(1, at: CGPoint(x: l.midX, y: l.midY))
        _ = engine.began(2, at: CGPoint(x: r.midX, y: r.midY))
        #expect(engine.mask == UInt16(GBA_BTN_L) | UInt16(GBA_BTN_R))
        #expect(engine.pressed == [.l, .r])
    }

    @Test func slidingASingleFingerBetweenShouldersSwitchesOrReleases() throws {
        let g = Self.gbaGeometry()
        let l = try #require(g.frames[.l]), r = try #require(g.frames[.r])
        var engine = ControlsInputEngine(geometry: g)
        _ = engine.began(1, at: CGPoint(x: l.midX, y: l.midY))
        #expect(engine.mask == UInt16(GBA_BTN_L))
        // De L a R con el mismo dedo: L se suelta y queda R.
        engine.moved(1, to: CGPoint(x: r.midX, y: r.midY))
        #expect(engine.mask == UInt16(GBA_BTN_R))
        #expect(engine.pressed == [.r])
        // De R a un punto sin control: se suelta todo.
        engine.moved(1, to: CGPoint(x: Self.area.midX, y: Self.area.midY))
        #expect(g.hit(at: CGPoint(x: Self.area.midX, y: Self.area.midY)) == nil)
        #expect(engine.mask == 0 && engine.pressed.isEmpty)
        engine.cancelAll()
        #expect(engine.mask == 0)
    }
}

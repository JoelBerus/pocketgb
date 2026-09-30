import CoreGraphics
import Foundation
import PocketGBCore
import Testing
@testable import PocketGB

/// D4: geometría y motor de input de los controles táctiles (D-README §6, criterios CI).
struct ControlsLayoutTests {
    static let right = UInt8(GB_BTN_RIGHT), left = UInt8(GB_BTN_LEFT)
    static let up = UInt8(GB_BTN_UP), down = UInt8(GB_BTN_DOWN)
    static let a = UInt8(GB_BTN_A), b = UInt8(GB_BTN_B)

    /// Máscara para un punto a `angle` grados (0 = derecha, antihorario) y distancia `r`.
    static func mask(_ angle: Double, r: Double = 60, radius: Double = 70) -> UInt8 {
        let rad = angle * .pi / 180
        return ControlsGeometry.dpadMask(dx: CGFloat(cos(rad) * r), dy: CGFloat(-sin(rad) * r),
                                         radius: CGFloat(radius))
    }

    @Test func eightSectorsOf45Degrees() {
        let expected: [(Double, UInt8)] = [
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

    @Test func deadZoneIs25PercentOfRadius() {
        #expect(Self.mask(0, r: 17.4) == 0)        // < 25 % de 70
        #expect(Self.mask(0, r: 17.6) == Self.right)
        #expect(Self.mask(123, r: 0) == 0)
    }

    @Test func neverOppositeDirections() {
        for tenth in 0..<3600 {
            let m = Self.mask(Double(tenth) / 10, r: 50)
            #expect(m & (Self.up | Self.down) != (Self.up | Self.down))
            #expect(m & (Self.left | Self.right) != (Self.left | Self.right))
            #expect(m != 0)
        }
        // También muy fuera del radio (dedo capturado).
        #expect(Self.mask(90, r: 500) == Self.up)
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

    @Test func dpadStyleDefaultsToGameBoyAndPersists() throws {
        #expect(GameplaySettingsData().dpadStyle == .cross)
        var data = GameplaySettingsData()
        data.dpadStyle = .separated
        let decoded = try JSONDecoder().decode(GameplaySettingsData.self, from: JSONEncoder().encode(data))
        #expect(decoded.dpadStyle == .separated)
    }

    @Test func noShoulderButtons() {
        #expect(ControlID.allCases.map(\.rawValue).sorted() == ["a", "b", "dpad", "menu", "select", "start"])
    }
}

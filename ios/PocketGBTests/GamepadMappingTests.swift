import Foundation
import PocketGBCore
import Testing
@testable import PocketGB

/// D6: mapeo del mando sin mando físico (D-README §8).
struct GamepadMappingTests {
    static let a = UInt8(GB_BTN_A), b = UInt8(GB_BTN_B), start = UInt8(GB_BTN_START), select = UInt8(GB_BTN_SELECT)
    static let up = UInt8(GB_BTN_UP), down = UInt8(GB_BTN_DOWN), left = UInt8(GB_BTN_LEFT), right = UInt8(GB_BTN_RIGHT)

    @Test func faceButtonsMapByPosition() {
        #expect(GamepadMapping.mask(GamepadSnapshot(faceRight: true)) == Self.a)
        #expect(GamepadMapping.mask(GamepadSnapshot(faceBottom: true)) == Self.b)
        // Los botones izquierdo y superior no tienen equivalente en la Game Boy.
        #expect(GamepadMapping.mask(GamepadSnapshot(faceLeft: true, faceTop: true)) == 0)
    }

    @Test func menuIsStartAndOptionsIsSelect() {
        #expect(GamepadMapping.mask(GamepadSnapshot(menu: true)) == Self.start)
        #expect(GamepadMapping.mask(GamepadSnapshot(options: true)) == Self.select)
    }

    @Test func dpadAndLeftStickGiveTheSameMask() {
        let cases: [(Float, Float, UInt8)] = [
            (1, 0, Self.right), (0, 1, Self.up), (-1, 0, Self.left), (0, -1, Self.down),
            (0.7, 0.7, Self.up | Self.right), (-0.7, -0.7, Self.down | Self.left),
        ]
        for (x, y, expected) in cases {
            #expect(GamepadMapping.mask(GamepadSnapshot(dpadX: x, dpadY: y)) == expected)
            #expect(GamepadMapping.mask(GamepadSnapshot(stickX: x, stickY: y)) == expected)
        }
    }

    @Test func stickDeadZoneAndNoOpposites() {
        #expect(GamepadMapping.mask(GamepadSnapshot(stickX: 0.3, stickY: 0.2)) == 0)
        // Cruceta a la izquierda y stick a la derecha: se anulan, nunca ambas.
        #expect(GamepadMapping.mask(GamepadSnapshot(dpadX: -1, stickX: 1)) == 0)
    }

    @Test func padMaskCombinesWithTouchByOR() {
        let touch = ButtonMask(), pad = ButtonMask()
        touch.set(Self.a)
        pad.set(GamepadMapping.mask(GamepadSnapshot(dpadX: 1)))
        #expect(touch.value | pad.value == Self.a | Self.right)
    }

    @Test func fastForwardCyclesOneTwoFour() {
        #expect(FastForward.next(1) == 2)
        #expect(FastForward.next(2) == 4)
        #expect(FastForward.next(4) == 1)
        #expect(FastForward.next(3) == 1)
    }
}

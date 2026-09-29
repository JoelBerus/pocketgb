import Testing
import UIKit
@testable import PocketGB

/// D1: los color sets de SPEC §7.1 existen en el catálogo, con variante oscura
/// donde el valor cambia, y el router DEBUG conoce todas las pantallas de D1.
@MainActor
struct DesignTokensTests {
    private func resolved(_ name: String, _ style: UIUserInterfaceStyle) -> UIColor? {
        UIColor(named: name, in: Bundle(for: AppState.self), compatibleWith: nil)?
            .resolvedColor(with: UITraitCollection(userInterfaceStyle: style))
    }

    @Test func allColorAssetsExist() {
        #expect(PocketColor.assetNames.count == 12)
        for name in PocketColor.assetNames {
            #expect(resolved(name, .light) != nil, "Falta el color set \(name)")
        }
    }

    @Test func adaptiveColorsChangeInDarkMode() {
        let fixed: Set<String> = ["GameplayBackground", "ControlScrim"]
        for name in PocketColor.assetNames where !fixed.contains(name) {
            #expect(resolved(name, .light) != resolved(name, .dark), "\(name) no cambia en oscuro")
        }
    }

    #if DEBUG
    @Test func routerKnowsD1Screens() {
        let ids = Set(DebugScreen.allCases.map(\.rawValue))
        for id in ["launch", "library-no-folder", "library-empty",
                   "settings-main", "settings-appearance", "settings-about"] {
            #expect(ids.contains(id))
        }
    }
    #endif
}

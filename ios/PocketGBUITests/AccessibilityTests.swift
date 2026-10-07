import XCTest

/// D7: cobertura del catálogo, etiquetas accesibles, áreas táctiles y reflow con AX5.
final class ShellAccessibilityTests: XCTestCase {
    /// IDs de docs/diseno/SPEC.md §9 (sin `gameplay-portrait-hud`, sustituida por decisión de Joel).
    static let specIDs: Set<String> = ["customize-controls-landscape","customize-controls-portrait","favorites","game-context-menu","game-details","gameplay-landscape","gameplay-landscape-clear","gameplay-landscape-hidden","gameplay-pause","gameplay-portrait","gameplay-reduce-transparency","gameplay-link-landscape","gameplay-link-pause","gameplay-link-portrait","gameplay-link-reduce-transparency","gameplay-link-switched","launch","library-ax5","library-cloud-downloading","library-cloud-pending","library-continue","library-empty","library-folder-unavailable","library-grid","library-list","library-no-folder","library-reduce-transparency","library-rom-error","library-scan-progress","library-scan-summary","link-continue-warning","link-open-refused","link-partner-picker","link-partner-picker-ax5","load-state-confirm","remove-game-confirm","replace-state-confirm","save-data-error","save-states","search-active","search-no-results","search-results","settings-about","settings-appearance","settings-audio","settings-controls","settings-display","settings-library","settings-main","settings-saves","settings-storage"]

    @MainActor
    func testCatalogCoversEverySpecScreenWithoutContradictions() throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "screens", withExtension: "txt"))
        let lines = try String(contentsOf: url, encoding: .utf8).split(separator: "\n")
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty && !$0.hasPrefix("#") }
        var seen: [String: String] = [:]
        var ids: Set<String> = []
        for line in lines {
            let parts = line.split(separator: " ", omittingEmptySubsequences: true).map(String.init)
            XCTAssertGreaterThanOrEqual(parts.count, 3, line)
            ids.insert(parts[0])
            // Misma captura (id + orientación + apariencia) con argumentos distintos: contradicción.
            let key = parts.prefix(3).joined(separator: " ")
            let args = parts.dropFirst(3).joined(separator: " ")
            if let previous = seen[key] { XCTAssertEqual(previous, args, "Captura repetida: \(key)") }
            seen[key] = args
        }
        XCTAssertTrue(Self.specIDs.isSubset(of: ids), "Faltan: \(Self.specIDs.subtracting(ids).sorted())")
    }

    @MainActor
    func testLibraryLabelsAndTouchTargets() throws {
        let app = XCUIApplication()
        // N4: sin estanterías (`-demoHome off`): se miden las tarjetas de «Todos los juegos».
        app.launchArguments = ["-uiStyle", "light", "-demoLibrary", "standard", "-demoHome", "off"]
        app.launch()
        let card = app.buttons["game-card-dmg-acid2.gb"]
        XCTAssertTrue(card.waitForExistence(timeout: 10))
        // Un solo elemento con título, sistema y última partida.
        XCTAssertTrue(card.label.contains("DMG-ACID2"), card.label)
        XCTAssertTrue(card.label.contains("Game Boy"), card.label)
        let favorite = app.buttons["game-card-cgb-acid2.gbc"]
        XCTAssertTrue(favorite.label.contains("Favorito"), favorite.label)
        // Solo controles propios: los botones de barra del sistema se dibujan a 36 pt y
        // iOS gestiona su área táctil.
        for element in [card, favorite] where element.exists {
            XCTAssertGreaterThanOrEqual(element.frame.width, 44, element.debugDescription)
            XCTAssertGreaterThanOrEqual(element.frame.height, 44, element.debugDescription)
        }
    }

    @MainActor
    func testGridReflowsToOneColumnAtAX5() throws {
        // El simulador conserva la orientación del test anterior (el catálogo termina en
        // horizontal): en horizontal caben varias columnas incluso con AX5.
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments = ["-uiStyle", "light", "-demoLibrary", "standard", "-demoHome", "off",
                               "-contentSizeCategory", "accessibility5"]
        app.launch()
        let first = app.buttons["game-card-cgb-acid2.gbc"]
        XCTAssertTrue(app.tabBars.firstMatch.waitForExistence(timeout: 10))
        // La lista es perezosa y con AX5 cada card casi llena la pantalla: se desplaza en
        // pasos cortos (un tercio de pantalla) hasta que exista la primera card.
        let from = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.7))
        let to = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.4))
        for _ in 0..<20 where !first.exists {
            from.press(forDuration: 0.05, thenDragTo: to)
        }
        XCTAssertTrue(first.exists, app.debugDescription)
        // Una columna: con AX5 la card ocupa casi todo el ancho de la pantalla (con dos
        // columnas mediría la mitad). No depende de cuánto se haya desplazado la lista.
        XCTAssertGreaterThan(first.frame.width, app.frame.width * 0.7, first.debugDescription)
    }
}

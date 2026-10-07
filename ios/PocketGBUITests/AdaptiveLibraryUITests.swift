import XCTest

/// N3b: biblioteca en horizontal. Sin filtro segmentado ni buscador arriba; el grupo flotante
/// de la derecha filtra por consola y por categoría y abre la búsqueda. En vertical, la lupa de
/// la barra abre la búsqueda («como una opción más»).
final class AdaptiveLibraryUITests: XCTestCase {
    @MainActor
    private func launch(_ extra: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-uiStyle", "light", "-screen", "library-landscape", "-demoLibrary", "adaptive"] + extra
        app.launch()
        return app
    }

    @MainActor
    func testLandscapeToolsFilterByConsoleAndCategory() throws {
        let app = launch()
        XCUIDevice.shared.orientation = .landscapeLeft
        defer { XCUIDevice.shared.orientation = .portrait }
        let filters = app.buttons["library-tool-filters"]
        XCTAssertTrue(filters.waitForExistence(timeout: 10))
        // En horizontal no hay segmentado ni campo de búsqueda arriba.
        XCTAssertFalse(app.segmentedControls.firstMatch.exists)
        XCTAssertFalse(app.searchFields.firstMatch.exists)
        for id in ["library-tool-search", "library-tool-filters", "library-tool-categories", "library-tool-view"] {
            let button = app.buttons[id]
            XCTAssertTrue(button.isHittable, id)
            XCTAssertGreaterThanOrEqual(button.frame.width, 44, id)
            XCTAssertGreaterThanOrEqual(button.frame.height, 44, id)
        }

        // Filtros › GBA: solo juegos de Game Boy Advance.
        filters.tap()
        let gba = app.buttons["library-filter-gba"]
        XCTAssertTrue(gba.waitForExistence(timeout: 5))
        // El panel se abre hacia arriba: queda por encima del botón.
        XCTAssertLessThan(gba.frame.maxY, filters.frame.minY)
        gba.tap()
        XCTAssertTrue(app.buttons["game-card-Pruebas/arm.gba"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["game-card-dmg-acid2.gb"].exists)
        XCTAssertEqual(app.buttons["library-tool-filters"].value as? String, "GBA")

        // Filtros › Todos y Categorías › Blargg: solo esa carpeta de primer nivel.
        filters.tap()
        app.buttons["library-filter-all"].tap()
        app.buttons["library-tool-categories"].tap()
        let blargg = app.buttons["library-category-folder:Blargg"]
        XCTAssertTrue(blargg.waitForExistence(timeout: 5))
        blargg.tap()
        XCTAssertTrue(app.buttons["game-card-Blargg/cpu_instrs.gb"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["game-card-Pruebas/arm.gba"].exists)
        let title = app.descendants(matching: .any)["library-section-title"]
        XCTAssertTrue(title.label.contains("Blargg"), title.label)
    }

    @MainActor
    func testLandscapeSearchOpensFromTheGroupAndGoesAwayOnCancel() throws {
        let app = launch()
        XCUIDevice.shared.orientation = .landscapeLeft
        defer { XCUIDevice.shared.orientation = .portrait }
        let search = app.buttons["library-tool-search"]
        XCTAssertTrue(search.waitForExistence(timeout: 10))
        search.tap()
        let field = app.searchFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.typeText("cpu")
        XCTAssertTrue(app.buttons["game-row-Blargg/cpu_instrs.gb"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["game-row-dmg-acid2.gb"].exists)
        // Cerrar (la «X» junto al campo en iOS 26): la búsqueda desaparece de arriba y vuelve el grupo.
        let labels = app.buttons.allElementsBoundByIndex.map { "\($0.identifier)|\($0.label)" }
        let close = ["Cerrar", "Cancelar", "Close", "Cancel", "close"].lazy.map { app.buttons[$0] }.first { $0.exists }
        guard let close else { return XCTFail("Sin botón para cerrar la búsqueda: \(labels)") }
        close.tap()
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        XCTAssertFalse(app.searchFields.firstMatch.exists)
    }

    @MainActor
    func testPortraitKeepsTheSegmentedFilterAndAddsTheSearchButton() throws {
        XCUIDevice.shared.orientation = .portrait
        let app = launch()
        let button = app.buttons["library-search-button"]
        XCTAssertTrue(button.waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["GBA"].exists)            // segmentado de siempre
        XCTAssertFalse(app.buttons["library-tool-filters"].exists)
        button.tap()
        XCTAssertTrue(app.searchFields.firstMatch.waitForExistence(timeout: 5))
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
    }

    /// El grupo nunca pisa la barra de pestañas: arriba (barra desplegada) va encima de ella; al
    /// bajar (barra encogida en burbuja) se alinea con la burbuja; al subir vuelve arriba.
    @MainActor
    func testToolsAlignWithTheTabBarWithoutOverlapping() throws {
        let app = launch()
        XCUIDevice.shared.orientation = .landscapeLeft
        defer { XCUIDevice.shared.orientation = .portrait }
        let ids = ["library-tool-search", "library-tool-filters", "library-tool-categories", "library-tool-view"]
        let tools = ids.map { app.buttons[$0] }
        XCTAssertTrue(tools[0].waitForExistence(timeout: 10))
        let bar = app.tabBars.firstMatch
        XCTAssertTrue(bar.waitForExistence(timeout: 5))
        let tabs = ["Biblioteca", "Favoritos", "Ajustes"].map { bar.buttons[$0] }

        func drag(_ from: Double, _ to: Double) {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: from))
                .press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: to)))
            Thread.sleep(forTimeInterval: 1.2)
        }
        func check(_ moment: String) -> (tools: CGRect, tabs: [CGRect]) {
            let group = tools.map(\.frame).reduce(CGRect.null) { $0.union($1) }
            let visibleTabs = tabs.filter { $0.exists && $0.isHittable }.map(\.frame)
            print("N3 \(moment): grupo=\(group) barra=\(bar.frame) pestañas=\(visibleTabs)")
            for tab in visibleTabs {
                XCTAssertFalse(group.intersects(tab), "\(moment): el grupo \(group) pisa la pestaña \(tab)")
            }
            for tool in tools { XCTAssertTrue(tool.isHittable, "\(moment): \(tool.identifier) no se puede tocar") }
            return (group, visibleTabs)
        }

        let top = check("arriba")
        drag(0.6, 0.1)                     // bajar: la barra se encoge
        let down = check("desplazada")
        XCTAssertGreaterThan(down.tools.midY, top.tools.midY + 20, "El grupo no baja junto a la burbuja")
        if down.tabs.count == 1, let bubble = down.tabs.first {   // encogida: solo la burbuja
            XCTAssertEqual(down.tools.midY, bubble.midY, accuracy: 6, "El grupo no queda en la fila de la burbuja")
            XCTAssertGreaterThan(down.tools.minX, bubble.maxX, "El grupo no queda a la derecha de la burbuja")
        }
        // En la fila de la burbuja el grupo recibe los toques (no pasan a la tarjeta de debajo).
        tools[1].tap()
        let gba = app.buttons["library-filter-gba"]
        XCTAssertTrue(gba.waitForExistence(timeout: 3), "Filtros no abre su panel con la barra encogida")
        XCTAssertLessThan(gba.frame.maxY, tools[1].frame.minY, "El panel no se abre hacia arriba")
        XCTAssertFalse(app.buttons["game-details-play"].exists, "El toque abrió el detalle de la tarjeta de debajo")
        app.buttons["library-filter-all"].tap()
        Thread.sleep(forTimeInterval: 1)
        drag(0.3, 0.55)                    // subir un poco: la barra se despliega
        let up = check("al subir")
        XCTAssertLessThan(up.tools.midY, down.tools.midY - 20, "El grupo no sube al desplegarse la barra")
    }
}


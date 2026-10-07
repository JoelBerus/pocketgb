import XCTest

/// N3b: biblioteca en horizontal. Sin filtro segmentado ni buscador arriba. En reposo, Buscar,
/// Filtros, Categorías y Vista van en la barra de navegación (paneles hacia abajo); al desplazar
/// (barra de pestañas encogida) pasan al grupo flotante de la derecha (paneles hacia arriba), que
/// nunca coincide con la barra desplegada. En vertical, la lupa de la barra abre la búsqueda.
final class AdaptiveLibraryUITests: XCTestCase {
    static let panels = ["filters", "categories", "view"]

    @MainActor
    private func launch(_ extra: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-uiStyle", "light", "-screen", "library-landscape", "-demoLibrary", "adaptive"] + extra
        app.launch()
        return app
    }

    @MainActor
    private func drag(_ app: XCUIApplication, from: Double, to: Double) {
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: from))
            .press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: to)))
        Thread.sleep(forTimeInterval: 1.2)
    }

    @MainActor
    private func groupButtons(_ app: XCUIApplication) -> [XCUIElement] {
        (["search"] + Self.panels).map { app.buttons["library-tool-\($0)"] }
    }

    /// Las pestañas visibles de la barra (desplegada: tres; encogida: la burbuja).
    @MainActor
    private func visibleTabs(_ app: XCUIApplication) -> [CGRect] {
        let bar = app.tabBars.firstMatch
        return ["Biblioteca", "Favoritos", "Ajustes"].map { bar.buttons[$0] }
            .filter { $0.exists && $0.isHittable }.map(\.frame)
    }

    /// Ningún botón del grupo (si está) pisa una pestaña.
    @MainActor
    private func assertGroupClearOfTabs(_ app: XCUIApplication, _ moment: String) {
        let tabs = visibleTabs(app)
        for button in groupButtons(app) where button.exists {
            for tab in tabs {
                XCTAssertFalse(button.frame.intersects(tab), "\(moment): \(button.identifier) \(button.frame) pisa \(tab)")
            }
        }
    }

    @MainActor
    func testAtRestTheToolsAreInTheNavigationBar() throws {
        let app = launch()
        XCUIDevice.shared.orientation = .landscapeLeft
        defer { XCUIDevice.shared.orientation = .portrait }
        let filters = app.buttons["library-bar-filters"]
        XCTAssertTrue(filters.waitForExistence(timeout: 10))
        // En horizontal no hay segmentado ni campo de búsqueda arriba, y en reposo no hay grupo.
        XCTAssertFalse(app.segmentedControls.firstMatch.exists)
        XCTAssertFalse(app.searchFields.firstMatch.exists)
        XCTAssertFalse(app.buttons["library-tool-filters"].exists)
        XCTAssertTrue(app.buttons["library-search-button"].isHittable)
        for id in Self.panels { XCTAssertTrue(app.buttons["library-bar-\(id)"].isHittable, id) }

        // Filtros › GBA desde la barra: el panel sale del botón y se despliega hacia abajo (en
        // iOS 26 el popover de un botón de la barra nace del propio botón y cubre la barra
        // mientras está abierto).
        let button = filters.frame
        filters.tap()
        let gba = app.buttons["library-filter-gba"]
        XCTAssertTrue(gba.waitForExistence(timeout: 5))
        XCTAssertGreaterThan(gba.frame.midY, button.midY, "El panel no se despliega hacia abajo")
        XCTAssertGreaterThan(gba.frame.maxY, button.maxY)
        gba.tap()
        XCTAssertTrue(app.buttons["game-card-Pruebas/arm.gba"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["game-card-dmg-acid2.gb"].exists)
        XCTAssertEqual(app.buttons["library-bar-filters"].value as? String, "GBA")

        // Filtros › Todos y Categorías › Blargg: solo esa carpeta de primer nivel.
        filters.tap()
        app.buttons["library-filter-all"].tap()
        app.buttons["library-bar-categories"].tap()
        let blargg = app.buttons["library-category-folder:Blargg"]
        XCTAssertTrue(blargg.waitForExistence(timeout: 5))
        blargg.tap()
        XCTAssertTrue(app.buttons["game-card-Blargg/cpu_instrs.gb"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["game-card-Pruebas/arm.gba"].exists)
        let title = app.descendants(matching: .any)["library-section-title"]
        XCTAssertTrue(title.label.contains("Blargg"), title.label)
    }

    /// Auditoría N3, H1: en horizontal, cada tarjeta del carril mide lo mismo que una columna.
    @MainActor
    func testContinueCardsAreAsWideAsAGridColumn() throws {
        let app = launch()
        XCUIDevice.shared.orientation = .landscapeLeft
        defer { XCUIDevice.shared.orientation = .portrait }
        let continueCard = app.buttons["continue-card-dmg-acid2.gb"]
        let gridCard = app.buttons["game-card-cgb-acid2.gbc"]
        XCTAssertTrue(continueCard.waitForExistence(timeout: 10))
        XCTAssertTrue(gridCard.waitForExistence(timeout: 5))
        XCTAssertEqual(continueCard.frame.width, gridCard.frame.width, accuracy: 1,
                       "Carril \(continueCard.frame) frente a cuadrícula \(gridCard.frame)")
    }

    @MainActor
    func testScrolledTheGroupReplacesTheBarButtonsAndAlignsWithTheBubble() throws {
        let app = launch()
        XCUIDevice.shared.orientation = .landscapeLeft
        defer { XCUIDevice.shared.orientation = .portrait }
        XCTAssertTrue(app.buttons["library-bar-filters"].waitForExistence(timeout: 10))
        assertGroupClearOfTabs(app, "arriba")

        drag(app, from: 0.6, to: 0.1)                     // bajar: la barra se encoge
        let tools = groupButtons(app)
        XCTAssertTrue(tools[0].waitForExistence(timeout: 3), "Sin grupo al desplazar")
        XCTAssertFalse(app.buttons["library-bar-filters"].exists, "Botones repetidos en la barra")
        let tabs = visibleTabs(app)
        let group = tools.map(\.frame).reduce(CGRect.null) { $0.union($1) }
        print("N3 desplazada: grupo=\(group) pestañas=\(tabs)")
        assertGroupClearOfTabs(app, "desplazada")
        if tabs.count == 1, let bubble = tabs.first {
            XCTAssertEqual(group.midY, bubble.midY, accuracy: 6, "El grupo no queda en la fila de la burbuja")
            XCTAssertGreaterThan(group.minX, bubble.maxX)
        }
        for tool in tools { XCTAssertTrue(tool.isHittable, "\(tool.identifier) no se puede tocar") }

        // El grupo recibe los toques (no la tarjeta de debajo) y su panel se abre hacia arriba.
        tools[1].tap()
        let gba = app.buttons["library-filter-gba"]
        XCTAssertTrue(gba.waitForExistence(timeout: 3), "Filtros no abre su panel con la barra encogida")
        XCTAssertLessThan(gba.frame.maxY, tools[1].frame.minY)
        XCTAssertFalse(app.buttons["game-details-play"].exists, "El toque abrió el detalle de la tarjeta de debajo")
        app.buttons["library-filter-all"].tap()
        Thread.sleep(forTimeInterval: 1)

        drag(app, from: 0.3, to: 0.6)                     // subir: vuelven los botones de la barra
        XCTAssertTrue(app.buttons["library-bar-filters"].waitForExistence(timeout: 3))
        XCTAssertFalse(app.buttons["library-tool-filters"].exists)
        assertGroupClearOfTabs(app, "al subir")
    }

    /// Auditoría N3, H2: al volver del detalle, cambiar de pestaña o girar, la lista vuelve con la
    /// barra desplegada y sin grupo (que nunca coincide con ella).
    @MainActor
    func testGroupNeverCoincidesWithTheExpandedTabBar() throws {
        let app = launch()
        XCUIDevice.shared.orientation = .landscapeLeft
        defer { XCUIDevice.shared.orientation = .portrait }
        XCTAssertTrue(app.buttons["library-bar-filters"].waitForExistence(timeout: 10))

        // Detalle y vuelta.
        drag(app, from: 0.6, to: 0.1)
        XCTAssertTrue(app.buttons["library-tool-filters"].waitForExistence(timeout: 3))
        let card = app.buttons["game-card-Blargg/cpu_instrs.gb"]
        if card.exists && card.isHittable {
            card.tap()
            XCTAssertTrue(app.buttons["game-details-play"].waitForExistence(timeout: 5))
            app.navigationBars.buttons.element(boundBy: 0).tap()
            Thread.sleep(forTimeInterval: 1.5)
            assertGroupClearOfTabs(app, "tras el detalle")
        }

        // Otra pestaña y vuelta.
        drag(app, from: 0.6, to: 0.1)
        app.tabBars.firstMatch.buttons.element(boundBy: 0).tap()        // la burbuja despliega la barra
        Thread.sleep(forTimeInterval: 1)
        let favorites = app.tabBars.firstMatch.buttons["Favoritos"]
        if favorites.waitForExistence(timeout: 3) && favorites.isHittable {
            favorites.tap()
            Thread.sleep(forTimeInterval: 1)
            app.tabBars.firstMatch.buttons["Biblioteca"].tap()
            Thread.sleep(forTimeInterval: 1.5)
            assertGroupClearOfTabs(app, "tras cambiar de pestaña")
        }

        // Girar con el grupo a la vista.
        drag(app, from: 0.6, to: 0.1)
        XCUIDevice.shared.orientation = .portrait
        Thread.sleep(forTimeInterval: 1.5)
        XCTAssertFalse(app.buttons["library-tool-filters"].exists, "Grupo en vertical")
        XCUIDevice.shared.orientation = .landscapeLeft
        Thread.sleep(forTimeInterval: 1.5)
        assertGroupClearOfTabs(app, "tras girar")
        XCTAssertTrue(app.buttons["library-bar-filters"].waitForExistence(timeout: 3))
    }

    @MainActor
    func testLandscapeSearchOpensFromTheBarAndGoesAwayOnClose() throws {
        let app = launch()
        XCUIDevice.shared.orientation = .landscapeLeft
        defer { XCUIDevice.shared.orientation = .portrait }
        let search = app.buttons["library-search-button"]
        XCTAssertTrue(search.waitForExistence(timeout: 10))
        search.tap()
        let field = app.searchFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.typeText("cpu")
        XCTAssertTrue(app.buttons["game-row-Blargg/cpu_instrs.gb"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["game-row-dmg-acid2.gb"].exists)
        // Cerrar (la «X» junto al campo en iOS 26): la búsqueda desaparece de arriba y vuelven los
        // botones de la barra.
        let labels = app.buttons.allElementsBoundByIndex.map { "\($0.identifier)|\($0.label)" }
        let close = ["Cerrar", "Cancelar", "Close", "Cancel", "close"].lazy.map { app.buttons[$0] }.first { $0.exists }
        guard let close else { return XCTFail("Sin botón para cerrar la búsqueda: \(labels)") }
        close.tap()
        XCTAssertTrue(app.buttons["library-bar-filters"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.searchFields.firstMatch.exists)
        assertGroupClearOfTabs(app, "tras cerrar la búsqueda")
    }

    /// Auditoría N3, H3: abrir la búsqueda en vertical y girar con ella abierta (sin texto).
    @MainActor
    func testRotatingWithTheSearchOpenKeepsIt() throws {
        XCUIDevice.shared.orientation = .portrait
        let app = launch()
        defer { XCUIDevice.shared.orientation = .portrait }
        let button = app.buttons["library-search-button"]
        XCTAssertTrue(button.waitForExistence(timeout: 10))
        button.tap()
        XCTAssertTrue(app.searchFields.firstMatch.waitForExistence(timeout: 5))
        XCUIDevice.shared.orientation = .landscapeLeft
        Thread.sleep(forTimeInterval: 1.5)
        // O sigue el campo, o (si iOS lo cerró al girar) vuelven los botones: nunca nada.
        XCTAssertTrue(app.searchFields.firstMatch.exists || app.buttons["library-bar-filters"].exists,
                      "Tras girar no hay ni búsqueda ni herramientas")
    }

    @MainActor
    func testPortraitKeepsTheSegmentedFilterAndAddsTheSearchButton() throws {
        XCUIDevice.shared.orientation = .portrait
        let app = launch()
        let button = app.buttons["library-search-button"]
        XCTAssertTrue(button.waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["GBA"].exists)            // segmentado de siempre
        XCTAssertTrue(app.searchFields.firstMatch.exists)   // y el buscador bajo el título
        XCTAssertFalse(app.buttons["library-tool-filters"].exists)
        XCTAssertFalse(app.buttons["library-bar-filters"].exists)
        button.tap()
        XCTAssertTrue(app.searchFields.firstMatch.waitForExistence(timeout: 5))
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
    }
}

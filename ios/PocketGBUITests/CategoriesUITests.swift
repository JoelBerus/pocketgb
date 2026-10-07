import XCTest

/// N4: inicio con estanterías → «Ver todo» → subcategoría → juego → atrás; centro de ajustes del juego
/// (renombrar, etiqueta, mostrar en otra categoría y volver); horizontal con el inicio completo (hasta
/// «Todos los juegos», grupo flotante y panel sin tapar el título); Ajustes › Biblioteca › Inicio.
/// Biblioteca de demostración `-demoLibrary n4` (juegos libres en varias carpetas y niveles).
final class CategoriesUITests: XCTestCase {
    @MainActor
    private func launch(_ extra: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-uiStyle", "light", "-demoLibrary", "n4"] + extra
        app.launch()
        return app
    }

    @MainActor
    private func drag(_ app: XCUIApplication, from: Double, to: Double) {
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: from))
            .press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: to)))
        Thread.sleep(forTimeInterval: 0.8)
    }

    /// Desplaza hacia abajo hasta que el elemento se pueda tocar.
    @MainActor
    private func scrollTo(_ element: XCUIElement, in app: XCUIApplication, attempts: Int = 10) {
        for _ in 0..<attempts where !(element.exists && element.isHittable) {
            drag(app, from: 0.75, to: 0.35)
        }
    }

    /// En un `Form` las filas fuera de pantalla no existen todavía (iPhone SE): se desplaza hasta verla.
    @MainActor
    private func reveal(_ element: XCUIElement, in app: XCUIApplication) {
        for _ in 0..<6 where !(element.exists && element.isHittable) {
            drag(app, from: 0.7, to: 0.4)
        }
    }

    @MainActor
    private func back(_ app: XCUIApplication) {
        app.navigationBars.buttons.element(boundBy: 0).tap()
        Thread.sleep(forTimeInterval: 0.8)
    }

    @MainActor
    func testHomeSeeAllSubcategoryGameAndBack() throws {
        XCUIDevice.shared.orientation = .portrait
        let app = launch()
        XCTAssertTrue(app.tabBars.firstMatch.waitForExistence(timeout: 10))
        // Las estanterías del inicio: Favoritos y una por categoría, con su número de juegos.
        let favorites = app.buttons["home-see-all-favorites"]
        XCTAssertTrue(favorites.waitForExistence(timeout: 5))
        XCTAssertTrue(favorites.label.contains("3 juegos"), favorites.label)
        let seeAll = app.buttons["home-see-all-Pruebas"]
        scrollTo(seeAll, in: app)
        XCTAssertTrue(seeAll.isHittable, "No se llega a «Ver todo» de Pruebas")
        XCTAssertTrue(seeAll.label.contains("Pruebas") && seeAll.label.contains("5 juegos"), seeAll.label)
        seeAll.tap()

        // Pantalla de la categoría: migas, subcategorías con su número y los juegos de todo el subárbol.
        XCTAssertTrue(app.navigationBars["Pruebas"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["category-crumb-library"].exists)
        let blargg = app.buttons["category-sub-Pruebas/Blargg"]
        XCTAssertTrue(blargg.exists)
        XCTAssertEqual(blargg.label, "Subcategoría Blargg, 3 juegos")
        XCTAssertTrue(app.buttons["category-sub-Pruebas/Mooneye"].exists)
        let gamesTitle = app.descendants(matching: .any).matching(identifier: "category-games-title").firstMatch
        XCTAssertTrue(gamesTitle.label.contains("Juegos · 5"), gamesTitle.label)
        blargg.tap()

        XCTAssertTrue(app.navigationBars["Blargg"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["category-crumb-Pruebas"].exists)
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "category-crumb-current").firstMatch.label.contains("Blargg"))
        XCTAssertTrue(app.buttons["category-sub-Pruebas/Blargg/Sonido"].exists)
        // Un juego de una subcarpeta (Sonido) también está en Blargg.
        let card = app.buttons["category-card-Pruebas/Blargg/Sonido/dmg_sound.gb"]
        scrollTo(card, in: app)
        card.tap()
        XCTAssertTrue(app.buttons["game-details-play"].waitForExistence(timeout: 5))

        back(app)
        XCTAssertTrue(app.navigationBars["Blargg"].waitForExistence(timeout: 5))
        // Vista por categoría: lista en Blargg, cuadrícula en Pruebas (cada una recuerda la suya).
        app.buttons["category-layout"].tap()
        XCTAssertTrue(app.buttons["category-row-Pruebas/Blargg/cpu_instrs.gb"].waitForExistence(timeout: 5))
        // Migas: «Pruebas» vuelve a ese nivel.
        app.buttons["category-crumb-Pruebas"].tap()
        XCTAssertTrue(app.navigationBars["Pruebas"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["category-card-Pruebas/Blargg/cpu_instrs.gb"].waitForExistence(timeout: 5))
        back(app)
        XCTAssertTrue(app.navigationBars["Biblioteca"].waitForExistence(timeout: 5))
        // Y otra vez dentro: Blargg sigue en lista.
        app.buttons["home-see-all-Pruebas"].tap()
        app.buttons["category-sub-Pruebas/Blargg"].tap()
        XCTAssertTrue(app.buttons["category-row-Pruebas/Blargg/cpu_instrs.gb"].waitForExistence(timeout: 5))
        app.buttons["category-crumb-library"].tap()
        XCTAssertTrue(app.navigationBars["Biblioteca"].waitForExistence(timeout: 5))
    }

    @MainActor
    func testGameCenterRenamesTagsMovesAndReturns() throws {
        XCUIDevice.shared.orientation = .portrait
        let app = launch()
        // Detalle de dmg-acid2 desde la estantería Acid.
        let seeAll = app.buttons["home-see-all-Acid"]
        XCTAssertTrue(seeAll.waitForExistence(timeout: 10))
        scrollTo(seeAll, in: app)
        seeAll.tap()
        let card = app.buttons["category-card-Acid/dmg-acid2.gb"]
        XCTAssertTrue(card.waitForExistence(timeout: 5))
        card.tap()
        let settings = app.buttons["Ajustes del juego"]
        XCTAssertTrue(settings.waitForExistence(timeout: 5))
        if !settings.isHittable { app.swipeUp() }
        settings.tap()

        // Nombre.
        let name = app.buttons["game-center-name"]
        XCTAssertTrue(name.waitForExistence(timeout: 5))
        name.tap()
        let field = app.textFields["game-center-name-field"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        field.typeText("Ácido")
        app.buttons["game-center-name-save"].tap()
        XCTAssertTrue(name.waitForExistence(timeout: 5))
        XCTAssertTrue(name.label.contains("Ácido"), name.label)

        // Etiqueta.
        app.buttons["game-center-tags"].tap()
        let tagField = app.textFields["tag-new-field"]
        XCTAssertTrue(tagField.waitForExistence(timeout: 5))
        tagField.tap()
        tagField.typeText("Plataformas\n")
        XCTAssertTrue(app.buttons["tag-remove-Plataformas"].waitForExistence(timeout: 5))
        // La misma sin mayúsculas ni acentos no se repite.
        tagField.tap()
        tagField.typeText("plataformas\n")
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "tag-message").firstMatch.waitForExistence(timeout: 5))
        back(app)
        let tags = app.buttons["game-center-tags"]
        XCTAssertTrue(tags.waitForExistence(timeout: 5))
        XCTAssertTrue(tags.label.contains("Plataformas"), tags.label)

        // Mostrar en otra categoría (escrita con otras mayúsculas: se usa la existente).
        app.buttons["game-center-change-category"].tap()
        let categoryField = app.textFields["category-new-field"]
        _ = categoryField.waitForExistence(timeout: 3)
        reveal(categoryField, in: app)
        XCTAssertTrue(categoryField.waitForExistence(timeout: 5))
        categoryField.tap()
        categoryField.typeText("para JUGAR")
        let notice = app.descendants(matching: .any).matching(identifier: "category-new-notice").firstMatch
        XCTAssertTrue(notice.waitForExistence(timeout: 5))
        XCTAssertTrue(notice.label.contains("Para jugar"), notice.label)
        app.buttons["category-new-apply"].tap()
        let category = app.descendants(matching: .any).matching(identifier: "game-center-category").firstMatch
        XCTAssertTrue(category.waitForExistence(timeout: 5))
        XCTAssertTrue(category.label.contains("Para jugar") && category.label.contains("Movido en la app"), category.label)
        let ownFolder = app.descendants(matching: .any).matching(identifier: "game-center-own-folder").firstMatch
        XCTAssertTrue(ownFolder.label.contains("Acid"), ownFolder.label)

        // Volver a su carpeta.
        app.buttons["game-center-return-to-folder"].tap()
        XCTAssertTrue(category.label.contains("Acid"), category.label)
        XCTAssertFalse(category.label.contains("Movido"), category.label)
        XCTAssertFalse(app.descendants(matching: .any).matching(identifier: "game-center-own-folder").firstMatch.exists)

        // Y otra vez a una categoría existente desde la lista: el detalle dice dónde se ve.
        app.buttons["game-center-change-category"].tap()
        let option = app.buttons["category-option-Game Boy Advance"]
        XCTAssertTrue(option.waitForExistence(timeout: 5))
        reveal(option, in: app)
        option.tap()
        XCTAssertTrue(category.waitForExistence(timeout: 5))
        XCTAssertTrue(category.label.contains("Game Boy Advance"), category.label)
        app.buttons["game-center-done"].tap()
        let shownIn = app.descendants(matching: .any).matching(identifier: "game-details-shown-in").firstMatch
        XCTAssertTrue(shownIn.waitForExistence(timeout: 5))
        XCTAssertTrue(shownIn.label.contains("Game Boy Advance"), shownIn.label)
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "game-details-tags").firstMatch.label.contains("Plataformas"))
    }

    /// Auditoría N4 Android, H3: en horizontal con el inicio completo (Favoritos y estanterías), al
    /// desplazar hasta «Todos los juegos» el título queda fijado, aparece el grupo flotante y su panel
    /// no tapa el título.
    @MainActor
    func testLandscapeWithTheWholeHomeReachesAllGamesAndPanelsKeepTheTitle() throws {
        let app = launch(["-screen", "n4-home-landscape"])
        XCUIDevice.shared.orientation = .landscapeLeft
        defer { XCUIDevice.shared.orientation = .portrait }
        XCTAssertTrue(app.buttons["library-bar-filters"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["home-see-all-favorites"].exists)
        let title = app.descendants(matching: .any).matching(identifier: "library-section-title").firstMatch
        let bar = app.navigationBars.firstMatch
        var pinned = false
        for _ in 0..<14 {
            drag(app, from: 0.7, to: 0.35)
            if title.exists, title.frame.minY <= bar.frame.maxY + 24 {
                pinned = true
                break
            }
        }
        XCTAssertTrue(pinned, "«Todos los juegos» no llega arriba: \(title.frame) barra \(bar.frame)")
        XCTAssertTrue(title.label.contains("Todos los juegos"), title.label)
        let tool = app.buttons["library-tool-filters"]
        XCTAssertTrue(tool.waitForExistence(timeout: 3), "Sin grupo flotante al desplazar")
        XCTAssertFalse(app.buttons["library-bar-filters"].exists)
        tool.tap()
        let gba = app.buttons["library-filter-gba"]
        XCTAssertTrue(gba.waitForExistence(timeout: 3))
        let tag = app.buttons["library-tag-pendiente"]
        XCTAssertTrue(tag.exists, "Sin etiquetas en el panel de filtros")
        let panelTop = app.staticTexts["Mostrar"].exists ? app.staticTexts["Mostrar"].frame.minY : gba.frame.minY
        XCTAssertGreaterThanOrEqual(panelTop, title.frame.maxY, "El panel tapa el título \(title.frame)")
        // Filtrar por la etiqueta: el título lo dice y el inicio deja sitio a los resultados.
        if tag.isHittable {
            tag.tap()
            XCTAssertTrue(title.waitForExistence(timeout: 5))
            XCTAssertTrue(title.label.contains("pendiente"), title.label)
            XCTAssertFalse(app.buttons["home-see-all-favorites"].exists)
        }
    }

    @MainActor
    func testHomeSettingsHideAndPinCategories() throws {
        XCUIDevice.shared.orientation = .portrait
        let app = launch()
        XCTAssertTrue(app.buttons["home-see-all-Acid"].waitForExistence(timeout: 10))
        let tabs = app.tabBars.firstMatch
        tabs.buttons["Ajustes"].tap()
        // La fila de Ajustes (no la pestaña, que se llama igual).
        let library = app.collectionViews.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Biblioteca")).firstMatch
        XCTAssertTrue(library.waitForExistence(timeout: 5))
        library.tap()
        let home = app.buttons["settings-library-home"]
        XCTAssertTrue(home.waitForExistence(timeout: 5))
        home.tap()
        XCTAssertTrue(app.navigationBars["Inicio"].waitForExistence(timeout: 5))
        // Ocultar Acid y fijar Pruebas.
        let shown = app.switches["home-settings-shown-Acid"]
        XCTAssertTrue(shown.waitForExistence(timeout: 5))
        let inner = shown.switches.firstMatch
        (inner.exists ? inner : shown).tap()
        XCTAssertEqual(shown.value as? String, "0", "Acid sigue marcada «En el inicio»")
        reveal(app.buttons["home-settings-pin-Pruebas"], in: app)
        XCTAssertTrue(app.buttons["home-settings-pin-Pruebas"].exists)
        app.buttons["home-settings-pin-Pruebas"].tap()
        XCTAssertEqual(app.buttons["home-settings-pin-Pruebas"].label, "Soltar Pruebas")

        // Tras desplazar, la barra de pestañas puede estar encogida en la burbuja: se despliega antes.
        if !tabs.buttons["Biblioteca"].exists { tabs.buttons.element(boundBy: 0).tap(); Thread.sleep(forTimeInterval: 1) }
        if tabs.buttons["Biblioteca"].exists { tabs.buttons["Biblioteca"].tap() }
        let pruebas = app.buttons["home-see-all-Pruebas"]
        XCTAssertTrue(pruebas.waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["home-see-all-Acid"].exists, "Acid sigue en el inicio")
        // Fijada: la primera estantería tras Favoritos.
        let gba = app.buttons["home-see-all-Game Boy Advance"]
        scrollTo(gba, in: app)
        XCTAssertLessThan(pruebas.frame.minY, gba.frame.minY)
    }
}

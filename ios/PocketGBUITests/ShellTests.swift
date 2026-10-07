import XCTest

/// D1: la shell tiene las tres tabs y Ajustes navega a Apariencia y Acerca de.
final class ShellTests: XCTestCase {
    /// Desplaza la lista hasta que el elemento exista y se pueda tocar (hasta 4 veces).
    @MainActor
    private func scrollTo(_ element: XCUIElement, in app: XCUIApplication) {
        for _ in 0..<4 where !(element.exists && element.isHittable) {
            let list = app.collectionViews.firstMatch
            if list.exists { list.swipeUp() } else { app.swipeUp() }
            _ = element.waitForExistence(timeout: 1)
        }
    }

    @MainActor
    func testTabsAndSettingsNavigation() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-uiStyle", "light", "-demoFolderState", "none"]
        app.launch()

        let tabs = app.tabBars.firstMatch
        XCTAssertTrue(tabs.waitForExistence(timeout: 10))
        for name in ["Biblioteca", "Favoritos", "Ajustes"] {
            XCTAssertTrue(tabs.buttons[name].exists, "Falta la tab \(name)")
        }
        XCTAssertTrue(app.buttons["Elegir carpeta"].exists)
        // Nada de GBA ni de "Abrir ROM" en la pantalla inicial (SPEC §9, library-no-folder).
        XCTAssertFalse(app.staticTexts["GBA"].exists)
        XCTAssertFalse(app.buttons["Abrir ROM"].exists)

        // La tab puede ignorar el primer toque mientras termina el arranque: se reintenta.
        for _ in 0..<3 where !app.navigationBars["Ajustes"].waitForExistence(timeout: 3) {
            tabs.buttons["Ajustes"].tap()
        }
        XCTAssertTrue(app.navigationBars["Ajustes"].exists, "No se abrió la tab Ajustes")
        // Ajustes tiene más filas desde D4: Apariencia puede quedar bajo el pliegue.
        let appearance = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Apariencia")).firstMatch
        scrollTo(appearance, in: app)
        appearance.tap()
        XCTAssertTrue(app.navigationBars["Apariencia"].waitForExistence(timeout: 5))
        app.navigationBars.buttons.element(boundBy: 0).tap()
        let about = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Acerca de")).firstMatch
        scrollTo(about, in: app)
        about.tap()
        XCTAssertTrue(app.navigationBars["Acerca de"].waitForExistence(timeout: 5))
    }
}

/// D2: "Elegir carpeta" presenta el selector de carpetas del sistema.
// Nombre con prefijo "Shell": XCTest ordena las clases por nombre y el primer lanzamiento
// tras los tests unitarios (que usan la app como host) agotó el tiempo en el CI.
final class ShellFolderPickerTests: XCTestCase {
    @MainActor
    func testChooseFolderOpensPicker() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-uiStyle", "light", "-demoFolderState", "none"]
        app.launch()
        XCTAssertTrue(app.tabBars.firstMatch.waitForExistence(timeout: 10))
        app.buttons["Elegir carpeta"].tap()
        let cancel = app.buttons.matching(NSPredicate(format: "label IN %@", ["Cancel", "Cancelar"])).firstMatch
        XCTAssertTrue(cancel.waitForExistence(timeout: 10), "No se presentó el selector de carpetas")
    }

    @MainActor
    func testUnavailableFolderOffersChooseAgain() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-uiStyle", "light", "-demoFolderState", "stale"]
        app.launch()
        XCTAssertTrue(app.buttons["Elegir de nuevo"].waitForExistence(timeout: 10))
    }
}

/// D3: card → detalle → ocultar con confirmación; el juego desaparece de la biblioteca.
final class ShellLibraryTests: XCTestCase {
    @MainActor
    func testDetailsAndHideGame() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-uiStyle", "light", "-demoLibrary", "standard"]
        app.launch()

        let card = app.buttons["game-card-Pruebas/rtc3test.gb"]
        XCTAssertTrue(card.waitForExistence(timeout: 10))
        // Filtros por consola, GBA incluido desde G7.
        XCTAssertTrue(app.buttons["GBC"].exists)
        XCTAssertTrue(app.buttons["GBA"].exists)
        if !card.isHittable { app.swipeUp() }
        card.tap()
        XCTAssertTrue(app.buttons["game-details-play"].waitForExistence(timeout: 5))

        let hide = app.buttons["Ocultar de PocketGB"]
        if !hide.isHittable { app.swipeUp() }
        hide.tap()
        let alert = app.alerts.firstMatch
        XCTAssertTrue(alert.waitForExistence(timeout: 5))
        XCTAssertTrue(alert.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "no se modifica")).firstMatch.exists)
        alert.buttons["Ocultar"].tap()

        // Vuelve a la biblioteca sin el juego oculto.
        XCTAssertTrue(app.buttons["game-card-dmg-acid2.gb"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["game-card-Pruebas/rtc3test.gb"].exists)
    }
}

/// M9: cable link virtual. Abre dmg-acid2 y cgb-acid2 unidos (necesita `FIXTURE_DIR`).
final class ShellLinkTests: XCTestCase {
    @MainActor
    func testSwitchAndExitTheCable() throws {
        let fixtures = ProcessInfo.processInfo.environment["FIXTURE_DIR"] ?? ""
        try XCTSkipIf(fixtures.isEmpty, "Sin FIXTURE_DIR")
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments = ["-uiStyle", "dark", "-rom", "\(fixtures)/dmg-acid2.gb",
                               "-linkROM", "\(fixtures)/cgb-acid2.gbc"]
        app.launch()

        let toggle = app.buttons["hud-link-switch"]
        XCTAssertTrue(toggle.waitForExistence(timeout: 15), "No hay botón de cambio de juego")
        XCTAssertGreaterThanOrEqual(toggle.frame.width, 44 - 0.01)
        XCTAssertGreaterThanOrEqual(toggle.frame.height, 44 - 0.01)
        XCTAssertEqual(toggle.label, "Cambiar a CGB-ACID2")
        toggle.tap()
        XCTAssertTrue(app.buttons["Cambiar a DMG-ACID2"].waitForExistence(timeout: 5), toggle.label)

        app.buttons["hud-menu"].tap()
        XCTAssertTrue(app.buttons["pause-link-exit"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["pause-link-switch"].exists)
        XCTAssertFalse(app.staticTexts["Estados guardados"].exists)
        XCTAssertFalse(app.buttons["Estados guardados"].exists)
        app.buttons["pause-link-exit"].tap()
        XCTAssertTrue(app.tabBars.firstMatch.waitForExistence(timeout: 10), "No volvió a las tabs")
    }
}

/// N2: la cruceta conserva su elemento de VoiceOver y el editor ajusta la separación de las
/// flechas separadas (ND10), que «Restablecer» devuelve al 100 % (necesita `FIXTURE_DIR`).
final class ShellControlsTests: XCTestCase {
    @MainActor
    func testDpadAccessibilityAndArrowSpacingEditor() throws {
        let fixtures = ProcessInfo.processInfo.environment["FIXTURE_DIR"] ?? ""
        try XCTSkipIf(fixtures.isEmpty, "Sin FIXTURE_DIR")
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        // `-dpadStyle` deja los ajustes de controles solo en memoria.
        app.launchArguments = ["-uiStyle", "dark", "-rom", "\(fixtures)/dmg-acid2.gb", "-dpadStyle", "separated"]
        app.launch()

        let dpad = app.descendants(matching: .any)["control-dpad"]
        XCTAssertTrue(dpad.waitForExistence(timeout: 15), "La cruceta no tiene elemento accesible")
        XCTAssertEqual(dpad.label, "Cruceta")
        for id in ["control-a", "control-b", "control-start", "control-select"] {
            XCTAssertTrue(app.descendants(matching: .any)[id].exists, "Falta \(id)")
        }
        let before = dpad.frame.width
        XCTAssertGreaterThanOrEqual(before, 44)

        app.buttons["hud-menu"].tap()
        let customize = app.buttons["Personalizar controles"]
        XCTAssertTrue(customize.waitForExistence(timeout: 5))
        customize.tap()
        // Elegir la cruceta en el editor muestra el tamaño y la separación.
        XCTAssertTrue(dpad.waitForExistence(timeout: 5))
        dpad.tap()
        let farther = app.buttons["editor-arrows-farther"]
        XCTAssertTrue(farther.waitForExistence(timeout: 5), "Sin control de separación")
        XCTAssertTrue(app.staticTexts["Separación de las flechas: 100 por ciento"].exists)
        farther.tap()
        XCTAssertTrue(app.staticTexts["Separación de las flechas: 110 por ciento"].waitForExistence(timeout: 5))
        // La zona táctil sigue a la geometría: el marco de la cruceta crece con la separación.
        XCTAssertGreaterThan(dpad.frame.width, before + 4, "\(dpad.frame.width) vs \(before)")
        app.buttons["Restablecer"].tap()
        XCTAssertTrue(app.staticTexts["Separación de las flechas: 100 por ciento"].waitForExistence(timeout: 5))
        XCTAssertEqual(dpad.frame.width, before, accuracy: 0.5)
        app.buttons["Listo"].tap()
        XCTAssertFalse(farther.waitForExistence(timeout: 2))
    }
}

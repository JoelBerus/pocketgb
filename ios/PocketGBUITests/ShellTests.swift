import XCTest

/// D1: la shell tiene las tres tabs y Ajustes navega a Apariencia y Acerca de.
final class ShellTests: XCTestCase {
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

        tabs.buttons["Ajustes"].tap()
        app.buttons["Apariencia"].tap()
        XCTAssertTrue(app.navigationBars["Apariencia"].waitForExistence(timeout: 5))
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.buttons["Acerca de"].tap()
        XCTAssertTrue(app.navigationBars["Acerca de"].waitForExistence(timeout: 5))
    }
}

/// La única forma de jugar hasta D2: abrir un ROM suelto. Los dos caminos deben
/// presentar el selector de documentos (auditoría D1, H1).
// Nombre con prefijo "Shell": XCTest ordena las clases por nombre y el primer lanzamiento
// tras los tests unitarios (que usan la app como host) agotó el tiempo en el CI.
final class ShellOpenFileTests: XCTestCase {
    @MainActor
    private func launchLibrary() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-uiStyle", "light", "-demoFolderState", "none"]
        app.launch()
        XCTAssertTrue(app.tabBars.firstMatch.waitForExistence(timeout: 10))
        return app
    }

    /// El selector de documentos del sistema tiene un botón de cancelar.
    @MainActor
    private func assertPickerShown(_ app: XCUIApplication, file: StaticString = #filePath, line: UInt = #line) {
        let cancel = app.buttons.matching(NSPredicate(format: "label IN %@", ["Cancel", "Cancelar"])).firstMatch
        XCTAssertTrue(cancel.waitForExistence(timeout: 10), "No se presentó el selector de documentos",
                      file: file, line: line)
    }

    @MainActor
    func testMenuOpensFilePicker() throws {
        let app = launchLibrary()
        app.buttons["Más opciones"].tap()
        app.buttons["Abrir un archivo…"].tap()
        assertPickerShown(app)
    }

    @MainActor
    func testFolderNoticeOpensFilePicker() throws {
        let app = launchLibrary()
        app.buttons["Elegir carpeta"].tap()
        let alert = app.alerts.firstMatch
        XCTAssertTrue(alert.waitForExistence(timeout: 5))
        alert.buttons["Abrir un archivo"].tap()
        assertPickerShown(app)
    }
}

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

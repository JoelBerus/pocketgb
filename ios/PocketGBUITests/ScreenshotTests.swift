import XCTest

/// Recorre `screens.txt`, abre la app en cada pantalla/estado y guarda una captura.
/// - `SCREENSHOT_DIR` (vía `TEST_RUNNER_SCREENSHOT_DIR`): carpeta de salida de los PNG.
/// - `FIXTURE_DIR` (vía `TEST_RUNNER_FIXTURE_DIR`): ROMs de prueba libres.
/// Las capturas también quedan como adjuntos en el .xcresult.
final class ScreenshotTests: XCTestCase {
    @MainActor
    func testScreenCatalog() throws {
        let env = ProcessInfo.processInfo.environment
        let outDir = env["SCREENSHOT_DIR"].map { URL(fileURLWithPath: $0, isDirectory: true) }
        let fixtures = env["FIXTURE_DIR"] ?? ""
        let listURL = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "screens", withExtension: "txt"))
        let lines = try String(contentsOf: listURL, encoding: .utf8).split(separator: "\n")

        for raw in lines {
            let line = raw.trimmingCharacters(in: .whitespaces)
            guard !line.isEmpty, !line.hasPrefix("#") else { continue }
            let parts = line.split(separator: " ", omittingEmptySubsequences: true).map(String.init)
            guard parts.count >= 3 else { XCTFail("Línea inválida: \(line)"); continue }
            let (name, orientation, style) = (parts[0], parts[1], parts[2])
            let args = parts.dropFirst(3).map { $0.replacingOccurrences(of: "$FIXTURES", with: fixtures) }
            // El nombre de la captura es el id de SPEC §9 que abre `-screen` (auditoría D1, H3).
            if let i = args.firstIndex(of: "-screen"), i + 1 < args.count {
                XCTAssertEqual(name, args[i + 1], "El nombre no coincide con -screen en: \(line)")
            }

            let app = XCUIApplication()
            app.launchArguments = ["-uiStyle", style] + args
            app.launch()
            // Rotar con la app ya en primer plano: en el simulador sin pantalla del CI,
            // rotar antes de lanzar agota el tiempo de confirmación.
            let target: UIDeviceOrientation = orientation == "landscape" ? .landscapeLeft : .portrait
            if XCUIDevice.shared.orientation != target { XCUIDevice.shared.orientation = target }
            Thread.sleep(forTimeInterval: 3) // deja correr la emulación y las animaciones
            // `-uiLongPress <id>`: menú contextual (la app ignora el argumento).
            if let i = args.firstIndex(of: "-uiLongPress"), i + 1 < args.count {
                let target = app.descendants(matching: .any).matching(identifier: args[i + 1]).firstMatch
                XCTAssertTrue(target.waitForExistence(timeout: 5), "No existe \(args[i + 1]) en \(name)")
                target.press(forDuration: 1.2)
                Thread.sleep(forTimeInterval: 1.5)
            }
            // Una app caída deja capturas de la pantalla de inicio: eso es un fallo, no una captura.
            XCTAssertEqual(app.state, .runningForeground, "La app no sigue en primer plano en \(name)")
            // Un `-screen` que el router DEBUG no conoce es un error del catálogo.
            XCTAssertFalse(app.descendants(matching: .any)["debug-unknown-screen"].exists,
                           "Pantalla desconocida en screens.txt: \(name)")

            let shot = XCUIScreen.main.screenshot()
            let file = "\(name)-\(orientation)-\(style).png"
            let attachment = XCTAttachment(screenshot: shot)
            attachment.name = file
            attachment.lifetime = .keepAlways
            add(attachment)
            if let outDir {
                try shot.pngRepresentation.write(to: outDir.appendingPathComponent(file))
            }
            app.terminate()
        }
    }
}

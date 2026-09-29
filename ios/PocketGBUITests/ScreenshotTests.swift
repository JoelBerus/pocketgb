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

            let app = XCUIApplication()
            app.launchArguments = ["-uiStyle", style] + args
            app.launch()
            // Rotar con la app ya en primer plano: en el simulador sin pantalla del CI,
            // rotar antes de lanzar agota el tiempo de confirmación.
            let target: UIDeviceOrientation = orientation == "landscape" ? .landscapeLeft : .portrait
            if XCUIDevice.shared.orientation != target { XCUIDevice.shared.orientation = target }
            Thread.sleep(forTimeInterval: 3) // deja correr la emulación y las animaciones

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

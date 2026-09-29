import Foundation
import Observation
import UIKit

/// Estado global de la app en M4: un ROM abierto con el selector de documentos.
@MainActor @Observable
final class AppState {
    private(set) var session: EmulatorSession?
    /// Pausa por ciclo de vida: se sale solo con "Continuar" (docs/04 §Ciclo de vida).
    private(set) var paused = false
    var alertMessage: String?

    private static let maxROMBytes = 8 * 1024 * 1024

    /// `url` es una copia temporal del selector (asCopy); se borra tras leerla.
    func open(url: URL, deleteAfterReading: Bool = true) {
        defer { if deleteAfterReading { try? FileManager.default.removeItem(at: url) } }
        closeGame()
        do {
            let size = try FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int ?? 0
            guard size <= Self.maxROMBytes else {
                alertMessage = "El archivo supera los 8 MiB; no es un ROM de Game Boy."
                return
            }
            let data = try Data(contentsOf: url)
            let session = try EmulatorSession(romData: data, savesDirectory: SaveStore.defaultDirectory())
            session.start()
            self.session = session
            paused = false
            if let warning = session.loadWarning {
                alertMessage = warning
            } else if !session.info.headerChecksumOK {
                alertMessage = "La cabecera del ROM no coincide con su checksum. Puede ser un volcado dañado."
            }
        } catch let e as CoreError {
            alertMessage = e.description
        } catch {
            alertMessage = "No se pudo abrir el archivo: \(error.localizedDescription)"
        }
    }

    #if DEBUG
    /// Solo pruebas en el simulador: `-rom <ruta>` abre ese archivo al arrancar.
    func openFromLaunchArguments() {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-rom"), i + 1 < args.count else { return }
        open(url: URL(fileURLWithPath: args[i + 1]), deleteAfterReading: false)
        // `-paused`: abre el juego ya en pausa (captura del estado de pausa).
        if args.contains("-paused") {
            Task { @MainActor in
                try? await Task.sleep(for: .seconds(1))
                enterBackground()
            }
        }
        // `-memoryWarningAfter N`: publica el aviso de memoria baja de UIKit tras N s (prueba H7).
        if let j = args.firstIndex(of: "-memoryWarningAfter"), j + 1 < args.count, let secs = Double(args[j + 1]) {
            Task { @MainActor in
                try? await Task.sleep(for: .seconds(secs))
                NotificationCenter.default.post(name: UIApplication.didReceiveMemoryWarningNotification, object: nil)
            }
        }
    }
    #endif

    /// Guarda de forma síncrona y vuelve a la pantalla inicial.
    func closeGame() {
        session?.stop()
        session = nil
        paused = false
    }

    /// `.inactive`/`.background`: pausar y hacer flush síncrono de la SRAM.
    func enterBackground() {
        guard let session, !paused else { return }
        session.pause()
        paused = true
    }

    /// `didReceiveMemoryWarning`: se guarda y se sigue (docs/04 §Ciclo de vida).
    func memoryWarning() {
        session?.requestFlush()
    }

    func resume() {
        session?.resume()
        paused = false
    }
}

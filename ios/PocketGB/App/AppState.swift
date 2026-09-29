import Foundation
import Observation
import UIKit

/// Tabs de la shell (SPEC §4).
enum AppTab: Hashable {
    case library, favorites, settings
}

/// Pantallas empujadas en la pila de Ajustes.
enum SettingsRoute: Hashable {
    case appearance, about, licenses, saves
    case backups(fingerprint: String)
}

/// Estado global de la app: shell de tabs, biblioteca y el juego abierto.
@MainActor @Observable
final class AppState {
    var selectedTab: AppTab = .library
    var settingsPath: [SettingsRoute] = []
    let library = LibraryStore()
    /// Selector de carpeta de la biblioteca.
    var pickingFolder = false
    /// Solo lo rellena el router DEBUG (`-screen`); en Release siempre vale nil/false.
    var debugUnknownScreen: String?
    var debugShowsLaunch = false

    private(set) var session: EmulatorSession?
    /// Pausa por ciclo de vida: se sale solo con "Continuar" (docs/04 §Ciclo de vida).
    private(set) var paused = false
    /// Abriendo un juego (lectura coordinada, quizá esperando a iCloud).
    private(set) var opening = false
    var alertTitle: String?
    var alertMessage: String?

    private static let maxROMBytes = LibraryScanner.maxROMBytes

    init() {
        #if DEBUG
        DebugScreenRouter.apply(to: self)
        if debugUnknownScreen == nil && !DebugScreenRouter.overridesLibrary {
            library.restore()
        }
        #else
        library.restore()
        #endif
    }

    func chooseFolder() {
        pickingFolder = true
    }

    /// Volver a primer plano: reescanear la carpeta (docs/04 §Ciclo de vida).
    func enterForeground() {
        guard session == nil else { return }
        #if DEBUG
        if DebugScreenRouter.overridesLibrary { return }   // biblioteca de demostración fija
        #endif
        if case .unavailable = library.phase {
            library.restore()   // quizá vuelve el acceso (iCloud con conexión; auditoría D2, H6)
        } else {
            library.refresh()
        }
    }

    /// Abre un juego de la biblioteca: lectura coordinada fuera del hilo principal,
    /// y la partida con su espejo junto al ROM.
    func open(entry: RomEntry) {
        guard entry.problem == nil else {
            showAlert("No se puede abrir “\(entry.fileName)”", entry.problem?.message ?? "")
            return
        }
        guard entry.cloud == .current else {
            library.download(entry)
            return
        }
        guard !opening else { return }
        opening = true
        let url = entry.url
        // Dos ROMs con el mismo nombre base (Juego.gb y Juego.gbc) compartirían el .sav
        // junto al ROM: en ese caso no se usa el espejo (auditoría D2, H5).
        let mirror = SaveMirror(romURL: url)
        // Sin distinguir mayúsculas: iCloud Drive y APFS no las distinguen (auditoría D2, N5).
        let key = mirror.url.path.lowercased()
        let shared = library.entries.contains {
            $0.id != entry.id && SaveMirror(romURL: $0.url).url.path.lowercased() == key
        }
        Task.detached(priority: .userInitiated) { [weak self] in
            // ROM y espejo se leen aquí, fuera del hilo principal: la lectura coordinada
            // puede esperar a que iCloud descargue (auditoría D2, H1/H2).
            let result = Result { try LibraryScanner.readROM(url) }
            let snapshot: SaveMirror.Snapshot = shared ? .absent : mirror.snapshot()
            await self?.finishOpening(entry: entry, result: result, mirror: shared ? nil : mirror,
                                      snapshot: snapshot, shared: shared)
        }
    }

    private func finishOpening(entry: RomEntry, result: Result<Data, Error>, mirror: SaveMirror?,
                               snapshot: SaveMirror.Snapshot, shared: Bool) {
        opening = false
        switch result {
        case .success(let data):
            start(romData: data, mirror: mirror, snapshot: snapshot, fileName: entry.fileName,
                  extraWarning: shared ? .mirrorShared : nil)
        case .failure(let error as CocoaError) where error.code == .fileReadTooLarge:
            showAlert("No se puede abrir “\(entry.fileName)”", RomEntry.Problem.tooLarge.message)
        case .failure(let error):
            showAlert("No se puede abrir “\(entry.fileName)”", error.localizedDescription)
        }
    }

    private func showAlert(_ title: String, _ message: String) {
        alertTitle = title
        alertMessage = message
    }

    /// Crea la sesión con la partida local y, si hay biblioteca, su espejo.
    private func start(romData: Data, mirror: SaveMirror?, snapshot: SaveMirror.Snapshot = .absent,
                       fileName: String, extraWarning: SaveLoadWarning? = nil) {
        closeGame()
        do {
            let savesDirectory = try SaveStore.defaultDirectory()
            let session = try EmulatorSession(romData: romData, savesDirectory: savesDirectory,
                                              mirror: mirror, mirrorSnapshot: snapshot) { [weak self] in
                self?.enterBackground()
            }
            SavesIndex(directory: savesDirectory).record(fingerprint: session.info.fingerprint,
                                                        title: session.info.title, fileName: fileName)
            session.start()
            self.session = session
            paused = false
            if let warning = session.loadWarning ?? (session.info.hasBattery ? extraWarning : nil) {
                showAlert(warning.title, warning.message)
            } else if !session.info.headerChecksumOK {
                showAlert("Cabecera dañada", "La cabecera del ROM no coincide con su checksum. Puede ser un volcado dañado.")
            }
        } catch SaveOpening.Refusal.mirrorNotDownloaded {
            showAlert("Partida de iCloud sin descargar",
                      "La partida de “\(fileName)” está en iCloud y no se pudo descargar. Para no empezar de cero ni pisarla, el juego no se abre. Vuelve a intentarlo con conexión.")
        } catch let e as CoreError {
            showAlert("No se puede abrir “\(fileName)”", e.description)
        } catch {
            showAlert("No se puede abrir “\(fileName)”", error.localizedDescription)
        }
    }

    #if DEBUG
    /// Solo pruebas en el simulador: abre un ROM por ruta, sin biblioteca ni espejo.
    func open(url: URL) {
        guard let data = try? Data(contentsOf: url), data.count <= Self.maxROMBytes else {
            showAlert("No se puede abrir “\(url.lastPathComponent)”", "No se pudo leer el archivo.")
            return
        }
        start(romData: data, mirror: nil, fileName: url.lastPathComponent)
    }
    #endif

    #if DEBUG
    /// Solo pruebas en el simulador: `-rom <ruta>` abre ese archivo al arrancar.
    func openFromLaunchArguments() {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-rom"), i + 1 < args.count else { return }
        open(url: URL(fileURLWithPath: args[i + 1]))
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

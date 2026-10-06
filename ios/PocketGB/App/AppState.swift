import Foundation
import Observation
import UIKit

/// La closure de expiración se fabrica fuera de `MainActor`: UIKit puede invocarla
/// desde otro hilo (misma precaución que el render block de audio de M5 H0).
@MainActor
private final class LocalSaveBackgroundTask {
    private var identifier: UIBackgroundTaskIdentifier = .invalid

    func begin() {
        identifier = UIApplication.shared.beginBackgroundTask(
            withName: "Guardar partida",
            expirationHandler: Self.makeExpirationHandler(self))
    }

    func end() {
        guard identifier != .invalid else { return }
        UIApplication.shared.endBackgroundTask(identifier)
        identifier = .invalid
    }

    /// Para el espejo en reposo: llega desde su cola serie, así que salta al actor principal.
    nonisolated static func makeEndHandler(
        _ task: LocalSaveBackgroundTask
    ) -> @Sendable () -> Void {
        { Task { @MainActor in task.end() } }
    }

    /// UIKit llama a la expiración en el hilo principal y exige terminar la tarea
    /// antes de volver; si no, iOS puede matar la app. Se termina en el acto.
    nonisolated static func makeExpirationHandler(
        _ task: LocalSaveBackgroundTask
    ) -> @Sendable () -> Void {
        {
            if Thread.isMainThread {
                MainActor.assumeIsolated { task.end() }
            } else {
                Task { @MainActor in task.end() }
            }
        }
    }
}

/// Tabs de la shell (SPEC §4).
enum AppTab: Hashable {
    case library, favorites, settings
}

/// Pantallas de la sheet de pausa.
enum PauseRoute: Hashable {
    case states
}

/// Pantallas empujadas en la pila de Ajustes.
enum SettingsRoute: Hashable {
    case appearance, about, licenses, saves, library, controls, display, emulation, audio, storage
    case backups(fingerprint: String)
}

enum GameLaunchMode: Sendable {
    case fresh
    case resumeAutomatic
}

private enum ContinuationOpenError: LocalizedError, Sendable {
    case unavailable

    var errorDescription: String? {
        "El estado automático ya no está disponible o dejó de ser vigente. Tu partida guardada no se modificó."
    }
}

private struct GameOpeningPayload: Sendable {
    let rom: Data
    let automaticState: Data?
}

/// Estado global de la app: shell de tabs, biblioteca y el juego abierto.
@MainActor @Observable
final class AppState {
    var selectedTab: AppTab = .library
    var settingsPath: [SettingsRoute] = []
    let library = LibraryStore()
    /// Favoritos, recientes, ocultos, vista y orden (D3).
    let libraryPrefs: LibraryPreferences
    /// Portadas locales: el último frame de cada juego (SPEC §11).
    let artwork: GameArtworkStore
    var libraryPath: [LibraryRoute] = []
    var favoritesPath: [LibraryRoute] = []
    var libraryFilter: LibraryFilter = .all
    var librarySearch = ""
    var librarySearchPresented = false
    /// Controles y pantalla del gameplay (D4).
    let gameplay: GameplaySettings
    /// Editor de la disposición de los controles, sobre el juego en pausa.
    var editingControls = false {
        didSet {
            editorSelection = nil
            guard editingControls != oldValue, let session else { return }
            // Editar no juega: la emulación se detiene mientras se mueven los controles.
            if editingControls { session.pause() } else if !paused { session.resume() }
        }
    }
    var showingGameMenu = false
    /// Mando físico (GameController).
    let gamepad: GamepadInput
    /// Avance rápido del juego abierto: 1, 2 o 4.
    private(set) var gameSpeed = 1
    /// Control elegido en el editor para cambiar su tamaño.
    var editorSelection: ControlID?
    /// Pila de la sheet de pausa (Estados).
    var pausePath: [PauseRoute] = []
    /// Save states del juego abierto.
    private(set) var stateEntries: [StateSlot: StateStore.Entry] = [:]
    private(set) var stateStore: StateStore?
    /// Confirmaciones de la pantalla de estados.
    var pendingStateLoad: StateSlot?
    var pendingStateReplace: StateSlot?
    /// Aviso breve sobre el juego ("Estado guardado").
    private(set) var gameToast: String?
    /// Juego cuyos ajustes se están editando (sheet).
    var gameSettingsEntry: RomEntry?
    /// Juego cuyo alias visual se está editando (sheet).
    var renamingEntry: RomEntry?
    /// Juego pendiente de confirmar "Ocultar de PocketGB".
    var hideCandidate: RomEntry?
    /// Selector de carpeta de la biblioteca.
    var pickingFolder = false
    /// Solo lo rellena el router DEBUG (`-screen`); en Release siempre vale nil/false.
    var debugUnknownScreen: String?
    var debugShowsLaunch = false
    var debugOpensControlsEditor = false

    private(set) var session: EmulatorSession?
    /// Pausa por ciclo de vida: se sale solo con "Continuar" (docs/04 §Ciclo de vida).
    private(set) var paused = false
    /// Abriendo un juego (lectura coordinada, quizá esperando a iCloud).
    private(set) var opening = false
    var alertTitle: String?
    var alertMessage: String?
    /// Permite recuperarse explícitamente de un autoestado ausente, dañado o ajeno.
    var resumeFallbackEntry: RomEntry?
    private(set) var resumableFingerprints: Set<String> = []

    private static let maxROMBytes = LibraryScanner.maxROMBytes

    init() {
        #if DEBUG
        // Capturas y tests de UI: preferencias y portadas solo en memoria.
        let inMemory = DebugScreenRouter.overridesLibrary
        libraryPrefs = LibraryPreferences(fileURL: inMemory ? nil : LibraryPreferences.defaultFileURL())
        artwork = GameArtworkStore(directory: inMemory ? nil : GameArtworkStore.defaultDirectory())
        // Con argumentos de controles o `-screen`, ajustes solo en memoria (no persistentes).
        let fixedControls = inMemory || DebugArguments.value("-controlOpacity") != nil
            || DebugArguments.value("-controlsVisibility") != nil
            || DebugArguments.value("-dpadStyle") != nil
        gameplay = GameplaySettings(defaults: fixedControls ? nil : .standard)
        gameplay.applyDebugArguments()
        let catalogRun = DebugArguments.screen != nil || DebugArguments.arguments.contains("-rom")
        gamepad = GamepadInput(observesHardware: !catalogRun)
        if DebugArguments.value("-demoController") == "connected" { gamepad.applyDemo() }
        #else
        libraryPrefs = LibraryPreferences(fileURL: LibraryPreferences.defaultFileURL())
        artwork = GameArtworkStore(directory: GameArtworkStore.defaultDirectory())
        gameplay = GameplaySettings(defaults: .standard)
        gamepad = GamepadInput()
        #endif
        #if DEBUG
        DebugScreenRouter.apply(to: self)
        if debugUnknownScreen == nil && !DebugScreenRouter.overridesLibrary {
            library.restore()
        }
        #else
        library.restore()
        #endif
        refreshContinuations()
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

    /// Toque en una card o fila: detalle si se puede jugar; si no, descarga o explicación.
    func select(_ entry: RomEntry, in tab: AppTab, source: String? = nil) {
        if entry.problem != nil {
            explainProblem(entry)
        } else if entry.cloud == .notDownloaded {
            library.download(entry)
        } else if entry.cloud == .current {
            showDetails(entry, in: tab, source: source)
        }
    }

    func showDetails(_ entry: RomEntry, in tab: AppTab, source: String? = nil) {
        let route = LibraryRoute.details(id: entry.id, source: source ?? entry.id)
        if tab == .favorites {
            favoritesPath.append(route)
        } else {
            libraryPath.append(route)
        }
    }

    /// Oculta el juego (solo preferencias: ROM, partida y copias intactos) y cierra su detalle.
    func hide(_ entry: RomEntry) {
        libraryPrefs.hide(entry)
        hideCandidate = nil
        libraryPath.removeAll { $0.entryID == entry.id }
        favoritesPath.removeAll { $0.entryID == entry.id }
    }

    func explainProblem(_ entry: RomEntry) {
        let place = entry.subfolder.isEmpty ? "en la carpeta de juegos" : "en la subcarpeta “\(entry.subfolder)”"
        showAlert("No se puede abrir “\(entry.fileName)”",
                  "\(entry.problem?.message ?? "") Está \(place). PocketGB no lo abrirá ni lo modificará.")
    }

    /// Abre un juego de la biblioteca: lectura coordinada fuera del hilo principal,
    /// y la partida con su espejo junto al ROM.
    func open(entry: RomEntry, mode: GameLaunchMode = .fresh) {
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
        let fingerprint = libraryPrefs.fingerprint(of: entry)
        Task.detached(priority: .userInitiated) { [weak self] in
            // ROM y espejo se leen aquí, fuera del hilo principal: la lectura coordinada
            // puede esperar a que iCloud descargue (auditoría D2, H1/H2).
            let result = Result<GameOpeningPayload, Error> {
                let rom = try LibraryScanner.readROM(url)
                guard mode == .resumeAutomatic else {
                    return GameOpeningPayload(rom: rom, automaticState: nil)
                }
                guard let fingerprint else { throw ContinuationOpenError.unavailable }
                let states = StateStore(root: try StateStore.defaultRoot(), fingerprint: fingerprint)
                let saves = SaveStore(directory: try SaveStore.defaultDirectory(), fingerprint: fingerprint)
                guard states.automaticEntry(newerThan: saves.modificationDate) != nil else {
                    throw ContinuationOpenError.unavailable
                }
                return GameOpeningPayload(rom: rom, automaticState: try states.load(.auto))
            }
            let snapshot: SaveMirror.Snapshot = shared ? .absent : mirror.snapshot()
            await self?.finishOpening(entry: entry, result: result, mirror: shared ? nil : mirror,
                                      snapshot: snapshot, shared: shared, mode: mode)
        }
    }

    private func finishOpening(entry: RomEntry, result: Result<GameOpeningPayload, Error>, mirror: SaveMirror?,
                               snapshot: SaveMirror.Snapshot, shared: Bool, mode: GameLaunchMode) {
        opening = false
        switch result {
        case .success(let payload):
            start(romData: payload.rom, mirror: mirror, snapshot: snapshot, fileName: entry.fileName,
                  entryID: entry.id, restoring: payload.automaticState,
                  resumeFallback: mode == .resumeAutomatic ? entry : nil,
                  extraWarning: shared ? .mirrorShared : nil)
        case .failure(let error as CocoaError) where error.code == .fileReadTooLarge:
            showAlert("No se puede abrir “\(entry.fileName)”", RomEntry.Problem.tooLarge.message)
        case .failure(let error):
            if mode == .resumeAutomatic {
                showResumeFailure(entry, message: error.localizedDescription)
            } else {
                showAlert("No se puede abrir “\(entry.fileName)”", error.localizedDescription)
            }
        }
    }

    private func showAlert(_ title: String, _ message: String) {
        alertTitle = title
        alertMessage = message
    }

    private func discardStaleAutomaticState(of entry: RomEntry) {
        guard let fingerprint = libraryPrefs.fingerprint(of: entry) else { return }
        resumableFingerprints.remove(fingerprint)
        if let root = try? StateStore.defaultRoot() {
            try? StateStore(root: root, fingerprint: fingerprint).delete(.auto)
        }
    }

    private func showResumeFailure(_ entry: RomEntry, message: String) {
        resumeFallbackEntry = entry
        showAlert("No se pudo continuar", "\(message) Puedes conservar tu partida y jugar desde el inicio.")
    }

    func playFromBeginningAfterResumeError() {
        guard let entry = resumeFallbackEntry else { return }
        resumeFallbackEntry = nil
        alertTitle = nil
        alertMessage = nil
        open(entry: entry, mode: .fresh)
    }

    /// Crea la sesión con la partida local y, si hay biblioteca, su espejo.
    private func start(romData: Data, mirror: SaveMirror?, snapshot: SaveMirror.Snapshot = .absent,
                       fileName: String, entryID: String? = nil, restoring automaticState: Data? = nil,
                       resumeFallback: RomEntry? = nil, extraWarning: SaveLoadWarning? = nil) {
        closeGame()
        do {
            let savesDirectory = try SaveStore.defaultDirectory()
            let session = try EmulatorSession(romData: romData, savesDirectory: savesDirectory,
                                              mirror: mirror, mirrorSnapshot: snapshot,
                                              emulation: gameplay.data.emulation(for: entryID)) { [weak self] in
                self?.enterBackground()
            }
            session.applyAudioPreferences(audioPreferences)
            try session.start(restoring: automaticState)
            SavesIndex(directory: savesDirectory).record(fingerprint: session.info.fingerprint,
                                                        title: session.info.title, fileName: fileName)
            if let entryID {
                libraryPrefs.recordPlayed(id: entryID, fingerprint: session.info.fingerprint, at: Date())
            }
            self.session = session
            gamepad.target = session.padButtons
            gameSpeed = 1
            stateStore = (try? StateStore.defaultRoot()).map { StateStore(root: $0, fingerprint: session.info.fingerprint) }
            #if DEBUG
            if let demo = DebugScreenRouter.demoStateStore() { stateStore = demo }
            #endif
            reloadStates()
            paused = false
            if let warning = session.loadWarning ?? (session.info.hasBattery ? extraWarning : nil) {
                showAlert(warning.title, warning.message)
            } else if !session.info.headerChecksumOK {
                showAlert("Cabecera dañada", "La cabecera del ROM no coincide con su checksum. Puede ser un volcado dañado.")
            }
        } catch SaveOpening.Refusal.mirrorNotDownloaded {
            showAlert("Partida de iCloud sin descargar",
                      "La partida de “\(fileName)” está en iCloud y no se pudo descargar. Para no empezar de cero ni pisarla, el juego no se abre. Vuelve a intentarlo con conexión.")
        } catch EmulatorSession.StateError.notCurrent {
            // El .auto quedó obsoleto (partida más nueva): se retira para que «Continuar»
            // deje de ofrecerse y fallar en cada intento (D81V2-H2). La partida no se toca.
            if let resumeFallback {
                discardStaleAutomaticState(of: resumeFallback)
                showResumeFailure(resumeFallback, message: EmulatorSession.StateError.notCurrent.localizedDescription)
            } else {
                showAlert("No se puede abrir “\(fileName)”", EmulatorSession.StateError.notCurrent.localizedDescription)
            }
        } catch let e as CoreError {
            if let resumeFallback { showResumeFailure(resumeFallback, message: e.description) }
            else { showAlert("No se puede abrir “\(fileName)”", e.description) }
        } catch {
            if let resumeFallback { showResumeFailure(resumeFallback, message: error.localizedDescription) }
            else { showAlert("No se puede abrir “\(fileName)”", error.localizedDescription) }
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
        if debugOpensControlsEditor { editingControls = true }
        DebugScreenRouter.afterGameOpened(self)
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
        if let session {
            // Estado automático al salir (SPEC §12). La pausa hace antes el flush síncrono
            // de la SRAM; un fallo del estado nunca impide salir ni guardar la partida.
            if !paused && !editingControls { session.pause() }
            saveAutomaticState(of: session)
            session.stop()
            saveArtwork(session)
        }
        stateStore = nil
        stateEntries = [:]
        gamepad.target = nil
        gameSpeed = 1
        pausePath = []
        session = nil
        paused = false
        editingControls = false
        showingGameMenu = false
    }

    /// Guarda el estado `.auto` con la sesión aparcada (después del flush de la SRAM, para
    /// que su fecha quede posterior a la de la partida). Un fallo nunca impide salir.
    private func saveAutomaticState(of session: EmulatorSession) {
        guard let stateStore, let saved = try? session.saveState(),
              (try? stateStore.save(saved.state, thumbnail: Self.thumbnail(saved.pixels), to: .auto)) != nil
        else { return }
        resumableFingerprints.insert(session.info.fingerprint)
    }

    func canResume(_ entry: RomEntry) -> Bool {
        #if DEBUG
        if DebugArguments.screen != nil { return libraryPrefs.lastPlayed(entry) != nil }
        #endif
        guard let fingerprint = libraryPrefs.fingerprint(of: entry) else { return false }
        return resumableFingerprints.contains(fingerprint)
    }

    /// Revisa fuera del actor principal la fecha y la cabecera de 4 bytes del estado `.auto`
    /// (sin leer miniaturas); el contenido se valida al reanudar, en `EmulatorSession`.
    func refreshContinuations() {
        let fingerprints = Set(libraryPrefs.data.fingerprints.values)
        Task.detached(priority: .utility) { [weak self] in
            guard let statesRoot = try? StateStore.defaultRoot(),
                  let savesDirectory = try? SaveStore.defaultDirectory() else { return }
            let valid = Set(fingerprints.filter { fingerprint in
                let states = StateStore(root: statesRoot, fingerprint: fingerprint)
                let saves = SaveStore(directory: savesDirectory, fingerprint: fingerprint)
                return states.automaticEntry(newerThan: saves.modificationDate) != nil
            })
            await MainActor.run { self?.resumableFingerprints = valid }
        }
    }

    func didRestoreSave(fingerprint: String) {
        resumableFingerprints.remove(fingerprint)
        refreshContinuations()
    }

    /// El último frame de la sesión como portada (SPEC §11). El hilo de emulación ya está
    /// parado y el render corre en el hilo principal, así que el frame no cambia al copiarlo;
    /// la codificación PNG va en la cola del almacén de portadas.
    private func saveArtwork(_ session: EmulatorSession) {
        let frame = session.frames.latest()
        let pixels = Array(UnsafeBufferPointer(start: frame, count: FrameBuffers.pixelCount))
        artwork.save(fingerprint: session.info.fingerprint, pixels: pixels)
    }

    /// `.inactive`/`.background`: pausar, flush síncrono de la SRAM y después el estado
    /// automático, todo dentro de la tarea de fondo (iOS puede matar la app sin más aviso).
    func enterBackground() {
        guard let session else { return }
        if paused {
            // Sheet de pausa abierta: la sesión ya está aparcada y su SRAM volcada, pero el
            // .auto puede ser anterior a la posición actual (D81V2-H3).
            if !editingControls { saveAutomaticState(of: session) }
            return
        }
        let backgroundTask = LocalSaveBackgroundTask()
        backgroundTask.begin()
        session.pause()
        if !editingControls { saveAutomaticState(of: session) }
        session.whenMirrorIdle(LocalSaveBackgroundTask.makeEndHandler(backgroundTask))
        paused = true
    }

    /// `didReceiveMemoryWarning`: se guarda y se sigue (docs/04 §Ciclo de vida).
    func memoryWarning() {
        session?.requestFlush()
    }

    func resume() {
        session?.resume()
        paused = false
        pausePath = []
    }

    /// Cierra la sheet de pausa para abrir el editor: la emulación sigue parada (el
    /// editor la reanuda al terminar).
    func resumeKeepingEditorPaused() {
        paused = false
        pausePath = []
    }

    /// Carpetas que mide Ajustes › Almacenamiento (en DEBUG con demo, temporales).
    var storageDirectories: (saves: URL?, states: URL?, artwork: URL?) {
        #if DEBUG
        if let demo = DebugScreenRouter.demoStorage { return demo }
        #endif
        return (try? SaveStore.defaultDirectory(), try? StateStore.defaultRoot(), artwork.directoryURL)
    }

    var audioPreferences: AudioPreferences {
        AudioPreferences(volume: Float(gameplay.data.volume), playsInSilentMode: gameplay.data.playsInSilentMode)
    }

    /// Avance rápido: ×1 → ×2 → ×4 → ×1 (SPEC §7.6: háptica rígida al cambiar).
    func cycleSpeed() {
        guard let session else { return }
        let next = FastForward.next(gameSpeed)
        session.setSpeed(next)
        gameSpeed = next
        if gameplay.data.haptics { UIImpactFeedbackGenerator(style: .rigid).impactOccurred() }
    }

    /// Pausa desde el HUD: la sheet de pausa aparece con la emulación ya parada.
    func pauseGame() {
        guard let session, !paused else { return }
        if !editingControls { session.pause() }
        paused = true
    }

    // MARK: - Save states (D5)

    func reloadStates() {
        stateEntries = stateStore?.entries() ?? [:]
    }

    /// Guarda en una ranura (con la sesión en pausa). Reemplazar ya se confirmó antes.
    func saveState(to slot: StateSlot) {
        guard let session, let stateStore else { return }
        do {
            let saved = try session.saveState()
            try stateStore.save(saved.state, thumbnail: Self.thumbnail(saved.pixels), to: slot)
            showGameToast("Estado guardado en \(slot.title.lowercased())")
        } catch {
            showAlert("No se pudo guardar el estado", Self.describe(error))
        }
        reloadStates()
    }

    /// Carga una ranura. Con `saveCurrentFirst`, antes guarda la partida actual en la
    /// ranura automática para poder volver a ella.
    func loadState(from slot: StateSlot, saveCurrentFirst: Bool) {
        guard let session, let stateStore else { return }
        do {
            if saveCurrentFirst && slot != .auto {
                let current = try session.saveState()
                try stateStore.save(current.state, thumbnail: Self.thumbnail(current.pixels), to: .auto)
            }
            let data = try stateStore.load(slot)
            try session.loadState(data)
            showGameToast("Estado cargado: \(slot.title.lowercased())")
        } catch {
            showAlert("No se pudo cargar el estado",
                      Self.describe(error) + " La partida actual no ha cambiado.")
        }
        reloadStates()
    }

    func deleteState(_ slot: StateSlot) {
        try? stateStore?.delete(slot)
        reloadStates()
    }

    private func showGameToast(_ text: String) {
        gameToast = text
        Task { @MainActor [weak self] in
            try? await Task.sleep(for: PocketMotion.toastVisible)
            if self?.gameToast == text { self?.gameToast = nil }
        }
    }

    nonisolated static func thumbnail(_ pixels: [UInt32]) -> Data? {
        GameArtworkStore.makeImage(pixels).flatMap(GameArtworkStore.pngData)
    }

    private static func describe(_ error: Error) -> String {
        if let core = error as? CoreError { return core.description }
        if error as? EmulatorSession.StateError == .notPaused { return "Pausa el juego antes." }
        if error as? EmulatorSession.StateError == .saveFailed {
            return "No se pudo guardar la partida del estado en este iPhone, así que no se ha cargado."
        }
        return error.localizedDescription
    }
}

/// Velocidades del avance rápido (D-README §8).
enum FastForward {
    static let speeds = [1, 2, 4]

    static func next(_ speed: Int) -> Int {
        guard let i = speeds.firstIndex(of: speed) else { return 1 }
        return speeds[(i + 1) % speeds.count]
    }
}

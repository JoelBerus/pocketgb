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

/// N6 · aviso de la pantalla de Momentos.
struct MomentNotice: Identifiable, Equatable {
    let id = UUID()
    let title: String
    let message: String
    init(_ title: String, _ message: String) {
        self.title = title
        self.message = message
    }
}

/// Pantallas de la sheet de pausa.
enum PauseRoute: Hashable {
    /// N6 · Momentos del juego abierto (sustituye a las ranuras de estados).
    case moments
}

/// Pantallas empujadas en la pila de Ajustes.
enum SettingsRoute: Hashable {
    case appearance, about, licenses, saves, library, controls, display, emulation, audio, storage
    /// N4 · Ajustes › Biblioteca › Inicio.
    case libraryHome
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
    /// Huella calculada de los bytes leídos (la misma que dará el núcleo): los ajustes del juego
    /// se encuentran aunque el archivo se acabe de mover y aún no tenga huella en la biblioteca.
    let fingerprint: String?
}

/// Estado global de la app: shell de tabs, biblioteca y el juego abierto.
@MainActor @Observable
final class AppState {
    var selectedTab: AppTab = .library
    var settingsPath: [SettingsRoute] = []
    let library: LibraryStore
    /// Favoritos, recientes, ocultos, alias, ajustes por juego, vista y orden (D3, N1a), por huella.
    let libraryPrefs: LibraryPreferences
    /// Portadas locales: el último frame de cada juego (SPEC §11).
    let artwork: GameArtworkStore
    /// N5: todas las fuentes de portada (importada, de la carpeta, captura fijada) y la elección.
    let covers: CoverStore
    var libraryPath: [LibraryRoute] = []
    var favoritesPath: [LibraryRoute] = []
    var libraryFilter: LibraryFilter = .all
    /// N4 · etiqueta elegida en Filtros (nil = todas). Las categorías ya no filtran en el sitio: abren
    /// su pantalla (`openCategory`, N4A-1).
    var libraryTag: String?
    /// Grupo flotante de la biblioteca en horizontal (N3b).
    let libraryTools = LibraryToolsState()
    var librarySearch = ""
    var librarySearchPresented = false
    /// Controles y pantalla del gameplay (D4).
    let gameplay: GameplaySettings
    /// Editor de la disposición de los controles, sobre el juego en pausa.
    var editingControls = false {
        didSet {
            editorSelection = nil
            updatePlayClock()
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
    /// Estado automático del juego abierto (las ranuras manuales pasan a momentos en N6).
    private(set) var stateStore: StateStore?
    /// N6 · momentos del juego abierto (nil con el cable o sin juego).
    private(set) var momentStore: MomentStore?
    /// Cambia con cada escritura de momentos: las vistas recargan su lista.
    private(set) var momentsRevision = 0
    /// N6 · exclusión por huella: la sesión abierta (o aparcada, o aún guardando) es dueña de su partida.
    let ownership = FingerprintOwnership()
    @ObservationIgnored private var sessionLeases: [FingerprintOwnership.Lease] = []
    /// N6 · progreso por juego (tiempo, hitos, porcentaje) y su contador de tiempo de juego.
    let progress = ProgressLibrary()
    @ObservationIgnored private var playClock: PlayTimeTracker?
    @ObservationIgnored private var playCheckpoint: Task<Void, Never>?
    /// Opciones de emulación con las que se abrió el juego (configuración de un momento).
    @ObservationIgnored private var sessionEmulation: EmulationOptions?
    /// N6 · resultado o error de una acción de momentos: lo muestra la pantalla de Momentos (también sobre la pausa).
    var momentNotice: MomentNotice?
    /// N7a · divergencia al abrir: la partida de este iPhone y la de junto al juego avanzaron por separado.
    var divergencePrompt: DivergencePrompt?
    /// N7a · la elección de Joel para la próxima apertura (se consume en `start`).
    @ObservationIgnored private var divergenceChoice: DivergenceChoice?
    @ObservationIgnored private var lastOpenRequest: (entry: RomEntry, mode: GameLaunchMode)?
    /// N7b · importación pendiente de la elección de Joel (divergencia, paquete más viejo o `.sav` crudo).
    var importPrompt: ImportPrompt?
    /// N7c · estado de la partida de cada huella («Partida: Pixel · hace 2 h») y continuación exacta de otro equipo.
    var saveStatuses: [String: SaveStatus] = [:]
    /// N7b · importando o preparando una exportación.
    var travelBusy = false
    /// N6 · cargar un momento al abrir el juego desde el detalle (se carga en pausa).
    @ObservationIgnored private var momentAfterOpen: (fingerprint: String, id: String)?
    /// Aviso breve sobre el juego ("Estado guardado").
    private(set) var gameToast: String?
    /// Juego cuyos ajustes se están editando (sheet).
    var gameSettingsEntry: RomEntry?
    /// Pantalla con la que se abre el centro de ajustes del juego (N6: «Momentos» o «Progreso» desde el detalle).
    var gameCenterStart: [GameCenterRoute] = []
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

    private(set) var session: EmulatorSession? {
        // Con un juego abierto, el cálculo de huellas en segundo plano se pausa (auditoría N1, H7).
        didSet { library.setHashingPaused(session != nil) }
    }
    /// Cable link virtual (M9): con un cable abierto, `session` es la de `link` y no hay estados.
    private(set) var link: LinkSession?
    /// Juego desde cuyo detalle se pide conectar (sheet del selector de pareja).
    var linkPartnerSource: RomEntry?
    /// Cable pedido en el selector: se abre al cerrarse la sheet (`startPendingLink`).
    var pendingLinkRequest: LinkRequest?
    /// Alerta de continuación: algún juego tiene estado automático válido que el cable no carga.
    var linkContinueRequest: LinkRequest?
    /// Pausa por ciclo de vida: se sale solo con "Continuar" (docs/04 §Ciclo de vida).
    private(set) var paused = false {
        didSet { updatePlayClock() }
    }
    /// Abriendo un juego (lectura coordinada, quizá esperando a iCloud).
    private(set) var opening = false
    var alertTitle: String?
    var alertMessage: String?
    /// Permite recuperarse explícitamente de un autoestado ausente, dañado o ajeno.
    var resumeFallbackEntry: RomEntry?
    private(set) var resumableFingerprints: Set<String> = []
    /// Huellas cuya continuación ya se comprobó (una huella nueva vuelve a comprobar).
    @ObservationIgnored private var checkedFingerprints: Set<String> = []
    /// Las comprobaciones de continuación van en serie: una lenta no pisa a una posterior.
    @ObservationIgnored private var continuationTask: Task<Void, Never>?

    /// BIOS de Game Boy Advance en la carpeta de juegos (Ajustes › Emulación). `nil` hasta
    /// que se comprueba.
    private(set) var gbaBIOSStatus: BIOSFile.Status?

    init() {
        #if DEBUG
        // Capturas y tests de UI: preferencias y portadas solo en memoria.
        let inMemory = DebugScreenRouter.overridesLibrary
        library = LibraryStore(cacheURL: inMemory ? nil : FingerprintCacheData.defaultURL())
        libraryPrefs = LibraryPreferences(fileURL: inMemory ? nil : LibraryPreferences.defaultFileURL())
        artwork = GameArtworkStore(directory: inMemory ? nil : GameArtworkStore.defaultDirectory())
        covers = CoverStore.standard(captures: artwork, inMemory: inMemory)
        // Con argumentos de controles o `-screen`, ajustes solo en memoria (no persistentes).
        let fixedControls = inMemory || DebugArguments.value("-controlOpacity") != nil
            || DebugArguments.value("-controlsVisibility") != nil
            || DebugArguments.value("-dpadStyle") != nil
            || DebugArguments.value("-dpadDiagonals") != nil || DebugArguments.value("-arrowSpacing") != nil
        gameplay = GameplaySettings(defaults: fixedControls ? nil : .standard)
        // Con ajustes solo en memoria no hay copia antigua que importar (y no se marca como hecho).
        if !fixedControls { libraryPrefs.importLegacyGameSettings(gameplay.data.perGame) }
        gameplay.applyDebugArguments()
        let catalogRun = DebugArguments.screen != nil || DebugArguments.arguments.contains("-rom")
        gamepad = GamepadInput(observesHardware: !catalogRun)
        if DebugArguments.value("-demoController") == "connected" { gamepad.applyDemo() }
        #else
        library = LibraryStore()
        libraryPrefs = LibraryPreferences(fileURL: LibraryPreferences.defaultFileURL())
        artwork = GameArtworkStore(directory: GameArtworkStore.defaultDirectory())
        covers = CoverStore.standard(captures: artwork, inMemory: false)
        gameplay = GameplaySettings(defaults: .standard)
        // N1a: los ajustes por juego antiguos (por ruta, en UserDefaults) pasan a la huella.
        libraryPrefs.importLegacyGameSettings(gameplay.data.perGame)
        gamepad = GamepadInput()
        #endif
        wireLibraryIdentity()
        library.onScanCompleted = { [weak self] entries in self?.covers.pruneFolderCache(entries) }
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

    /// N1a: las huellas que llegan del escaneo se llevan los metadatos provisionales por ruta, y
    /// los problemas del archivo de preferencias se avisan (nunca se pierden en silencio). Solo las
    /// huellas nuevas vuelven a mirar su estado automático (auditoría N1, H7).
    private func wireLibraryIdentity() {
        LibraryIdentityWiring.connect(
            library: library, prefs: libraryPrefs,
            alert: { [weak self] title, message in self?.showAlert(title, message) },
            fingerprintsResolved: { [weak self] fingerprints in
                guard let self else { return }
                let added = fingerprints.subtracting(checkedFingerprints)
                if !added.isEmpty { refreshContinuations(adding: added) }
            })
    }

    /// El juego con su huella actual (una vista puede guardar una copia anterior al cálculo).
    private func current(_ entry: RomEntry) -> RomEntry {
        library.entries.first { $0.id == entry.id } ?? entry
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

    /// N4 · abre la pantalla de una categoría en la pestaña Biblioteca (`[]` = «Sin categoría»).
    func openCategory(_ path: [String]) {
        selectedTab = .library
        libraryPath.append(.category(path: path))
    }

    /// N4 · migas: vuelve a un nivel ya abierto en la pila o, si no está, lo abre. `nil` = la biblioteca.
    func showCrumb(_ path: [String]?) {
        guard let path else {
            libraryPath = []
            return
        }
        if let index = libraryPath.lastIndex(of: .category(path: path)) {
            libraryPath = Array(libraryPath.prefix(index + 1))
        } else {
            libraryPath.append(.category(path: path))
        }
    }

    /// Oculta el juego (solo preferencias: ROM, partida y copias intactos) y cierra su detalle.
    func hide(_ entry: RomEntry) {
        let entry = current(entry)
        libraryPrefs.hide(entry)
        hideCandidate = nil
        // Ocultar va por huella: sus duplicados también se ocultan.
        let ids = Set([entry.id] + entry.duplicatePaths)
        libraryPath.removeAll { $0.entryID.map(ids.contains) ?? false }
        favoritesPath.removeAll { $0.entryID.map(ids.contains) ?? false }
    }

    func explainProblem(_ entry: RomEntry) {
        let folders = entry.folderPath.joined(separator: " › ")
        let place = folders.isEmpty ? "en la carpeta de juegos" : "en la subcarpeta “\(folders)”"
        showAlert("No se puede abrir “\(entry.fileName)”",
                  "\(entry.problem?.message ?? "") Está \(place). PocketGB no lo abrirá ni lo modificará.")
    }

    /// Abre un juego de la biblioteca: lectura coordinada fuera del hilo principal,
    /// y la partida con su espejo junto al ROM.
    func open(entry: RomEntry, mode: GameLaunchMode = .fresh) {
        let entry = current(entry)
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
        let console = Console(fileName: entry.fileName)
        let root = library.rootURL
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
                let rom = try LibraryScanner.readROM(url, limit: LibraryScanner.romLimit(for: console))
                let romFingerprint = RomFingerprint.compute(data: rom, console: console)
                guard mode == .resumeAutomatic else {
                    return GameOpeningPayload(rom: rom, automaticState: nil, fingerprint: romFingerprint)
                }
                // El estado automático se busca con la huella de los bytes leídos (la del núcleo), no
                // con la pista de la caché, que puede ser de otro archivo (auditoría N1, H5).
                guard let fingerprint = romFingerprint ?? fingerprint else { throw ContinuationOpenError.unavailable }
                let states = StateStore(root: try StateStore.defaultRoot(), fingerprint: fingerprint)
                let saves = SaveStore(directory: try SaveStore.defaultDirectory(), fingerprint: fingerprint)
                guard states.automaticEntry(newerThan: saves.modificationDate) != nil else {
                    throw ContinuationOpenError.unavailable
                }
                return GameOpeningPayload(rom: rom, automaticState: try states.load(.auto),
                                          fingerprint: romFingerprint)
            }
            let snapshot: SaveMirror.Snapshot = shared ? .absent : mirror.snapshot()
            let bios: (data: Data?, status: BIOSFile.Status) =
                console == .gameBoyAdvance ? BIOSFile.read(folder: root) : (data: nil, status: .absent)
            await self?.finishOpening(entry: entry, result: result, mirror: shared ? nil : mirror,
                                      snapshot: snapshot, shared: shared, mode: mode,
                                      console: console, bios: bios)
        }
    }

    private func finishOpening(entry: RomEntry, result: Result<GameOpeningPayload, Error>, mirror: SaveMirror?,
                               snapshot: SaveMirror.Snapshot, shared: Bool, mode: GameLaunchMode,
                               console: Console, bios: (data: Data?, status: BIOSFile.Status)) {
        opening = false
        if case .failure = result { momentAfterOpen = nil }   // H4
        if console == .gameBoyAdvance { gbaBIOSStatus = bios.status }
        switch result {
        case .success(let payload):
            lastOpenRequest = (entry, mode)
            start(romData: payload.rom, mirror: mirror, snapshot: snapshot, fileName: entry.fileName,
                  entryID: entry.id,
                  overrides: libraryPrefs.overrides(fingerprint: payload.fingerprint, path: entry.id),
                  restoring: payload.automaticState,
                  resumeFallback: mode == .resumeAutomatic ? entry : nil,
                  extraWarning: shared ? .mirrorShared : nil,
                  console: console, bios: bios.data)
        case .failure(let error as CocoaError) where error.code == .fileReadTooLarge:
            showAlert("No se puede abrir “\(entry.fileName)”",
                      (console == .gameBoyAdvance ? RomEntry.Problem.tooLargeGBA : .tooLarge).message)
        case .failure(let error):
            if mode == .resumeAutomatic {
                showResumeFailure(entry, message: error.localizedDescription)
            } else {
                showAlert("No se puede abrir “\(entry.fileName)”", error.localizedDescription)
            }
        }
    }

    /// N7a · Joel eligió qué partida seguir ante una divergencia: se vuelve a abrir el juego con esa elección.
    func resolveDivergence(_ choice: DivergenceChoice) {
        guard let prompt = divergencePrompt else { return }
        divergencePrompt = nil
        divergenceChoice = choice
        open(entry: prompt.entry, mode: choice == .useOther ? .fresh : prompt.mode)
    }

    // MARK: - Cable link (M9)

    /// Al cerrarse la sheet del selector: abre el cable elegido (no antes, para no chocar con
    /// las alertas ni con otras presentaciones).
    func startPendingLink() {
        guard let request = pendingLinkRequest else { return }
        pendingLinkRequest = nil
        openLink(request)
    }

    /// Abre dos juegos unidos por el cable virtual. Mismo patrón que `open(entry:)`: las dos
    /// lecturas coordinadas y los espejos van fuera del hilo principal.
    /// - Parameter confirmedContinuation: ya se avisó de que algún juego tiene continuación.
    func openLink(_ request: LinkRequest, confirmedContinuation: Bool = false) {
        let entries = [current(request.first), current(request.second)]
        for entry in entries {
            guard entry.problem == nil else {
                showAlert("No se puede abrir “\(entry.fileName)”", entry.problem?.message ?? "")
                return
            }
            guard entry.console == .gameBoy else {
                let refusal = LinkSession.Refusal.notGameBoy(title: libraryPrefs.displayTitle(entry))
                showAlert(refusal.title, refusal.message)
                return
            }
            guard entry.cloud == .current else {
                library.download(entry)
                return
            }
        }
        guard !opening else { return }
        if !confirmedContinuation && entries.contains(where: { canResume($0) }) {
            linkContinueRequest = request
            return
        }
        opening = true
        let sources = entries.map { entry in
            // Igual que `open(entry:)`: con otro ROM de la carpeta con el mismo nombre base el .sav
            // junto al ROM no se usa como espejo (auditoría D2, H5).
            let mirror = SaveMirror(romURL: entry.url)
            let key = mirror.url.path.lowercased()
            let shared = library.entries.contains {
                $0.id != entry.id && SaveMirror(romURL: $0.url).url.path.lowercased() == key
            }
            return (entry: entry, mirror: shared ? nil : mirror, shared: shared)
        }
        let games = sources.map {
            LinkSession.Game(fileName: $0.entry.fileName, title: libraryPrefs.displayTitle($0.entry), rom: Data(),
                             console: .gameBoy, mirror: $0.mirror,
                             emulation: gameplay.data.emulation(with: libraryPrefs.overrides(for: $0.entry)))
        }
        let ids = entries.map(\.id)
        let urls = entries.map(\.url)
        let shared = sources.map(\.shared)
        let metadata = entries.map { (id: $0.id, fileName: $0.fileName) }
        Task.detached(priority: .userInitiated) { [weak self] in
            var loaded = games
            var fingerprints: [String?] = Array(repeating: nil, count: games.count)
            var failure: (index: Int, error: Error)?
            for i in loaded.indices {
                do {
                    loaded[i].rom = try LibraryScanner.readROM(urls[i], limit: LibraryScanner.romLimit(for: .gameBoy))
                    fingerprints[i] = RomFingerprint.compute(data: loaded[i].rom, console: .gameBoy)
                    loaded[i].mirrorSnapshot = loaded[i].mirror?.snapshot() ?? .absent
                } catch {
                    failure = (i, error)
                    break
                }
            }
            let result: Result<[LinkSession.Game], Error> =
                failure.map { .failure($0.error) } ?? .success(loaded)
            let failedIndex = failure?.index ?? 0
            let finalFingerprints = fingerprints
            await self?.finishOpeningLink(result: result, ids: ids, fingerprints: finalFingerprints,
                                          fileNames: metadata.map(\.fileName), shared: shared,
                                          failedIndex: failedIndex)
        }
    }

    private func finishOpeningLink(result: Result<[LinkSession.Game], Error>, ids: [String], fingerprints: [String?],
                                   fileNames: [String], shared: [Bool], failedIndex: Int) {
        opening = false
        switch result {
        case .success(var games):
            // Ajustes de cada juego por la huella de los bytes leídos (auditoría N1, H5).
            for i in games.indices where i < ids.count {
                let overrides = libraryPrefs.overrides(fingerprint: fingerprints[i], path: ids[i])
                games[i].emulation = gameplay.data.emulation(with: overrides)
            }
            startLink(games: games, entryIDs: ids, sharedMirrors: shared)
        case .failure(let error as CocoaError) where error.code == .fileReadTooLarge:
            showAlert("No se puede abrir “\(fileNames[failedIndex])”", RomEntry.Problem.tooLarge.message)
        case .failure(let error):
            showAlert("No se puede abrir “\(fileNames[failedIndex])”", error.localizedDescription)
        }
    }

    /// Crea el cable y arranca la emulación (patrón de `start`).
    private func startLink(games: [LinkSession.Game], entryIDs: [String?], sharedMirrors: [Bool] = [false, false]) {
        closeGame()
        do {
            let savesDirectory = try SaveStore.defaultDirectory()
            let link = try LinkSession(games: games, savesDirectory: savesDirectory) { [weak self] in
                self?.enterBackground()
            }
            link.session.applyAudioPreferences(audioPreferences)
            link.session.start()
            for (i, info) in link.infos.enumerated() {
                SavesIndex(directory: savesDirectory).record(fingerprint: info.fingerprint,
                                                            title: link.indexTitles[i], fileName: games[i].fileName)
                if let id = entryIDs[i] {
                    library.learnFingerprint(info.fingerprint, forPath: id)
                    libraryPrefs.recordPlayed(id: id, fingerprint: info.fingerprint, at: Date())
                }
            }
            self.link = link
            session = link.session
            gamepad.target = link.session.padButtons
            gameSpeed = 1
            stateStore = nil   // sin estados con el cable (M9 §1.6)
            momentStore = nil
            sessionLeases = link.infos.map { ownership.takeForSession($0.fingerprint) }
            paused = false
            var notices = link.notice.map { [$0] } ?? []
            if let i = sharedMirrors.firstIndex(of: true), link.infos[i].hasBattery {
                let warning = SaveLoadWarning.mirrorShared
                notices.append(.init(title: warning.title, message: "“\(link.titles[i])”: \(warning.message)"))
            }
            if notices.count == 1 {
                showAlert(notices[0].title, notices[0].message)
            } else if notices.count > 1 {
                showAlert("Avisos al conectar", notices.map(\.message).joined(separator: "\n\n"))
            }
        } catch let refusal as LinkSession.Refusal {
            showAlert(refusal.title, refusal.message)
        } catch let e as CoreError {
            showAlert("No se puede conectar el cable", e.description)
        } catch {
            showAlert("No se puede conectar el cable", error.localizedDescription)
        }
    }

    /// Cambia el juego activo del cable (botón del HUD o de la pausa).
    func switchLinkSide() {
        guard let link else { return }
        link.switchSide()
        if gameplay.data.haptics { UISelectionFeedbackGenerator().selectionChanged() }
        showGameToast("Ahora juegas con \(link.activeTitle)")
    }

    /// Vuelve a comprobar `gba_bios.bin` (al abrir Ajustes › Emulación).
    func refreshBIOSStatus() {
        let root = library.rootURL
        Task.detached(priority: .utility) { [weak self] in
            let status = BIOSFile.read(folder: root).status
            await self?.setBIOSStatus(status)
        }
    }

    private func setBIOSStatus(_ status: BIOSFile.Status) {
        gbaBIOSStatus = status
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
                       fileName: String, entryID: String? = nil, overrides: GameOverrides? = nil,
                       restoring automaticState: Data? = nil,
                       resumeFallback: RomEntry? = nil, extraWarning: SaveLoadWarning? = nil,
                       console: Console = .gameBoy, bios: Data? = nil) {
        // H4: el momento pendiente es de esta apertura; closeGame lo limpia y cualquier fallo lo descarta.
        let pendingMoment = momentAfterOpen
        closeGame()
        defer { momentAfterOpen = nil }
        do {
            let savesDirectory = try SaveStore.defaultDirectory()
            let emulation = gameplay.data.emulation(with: overrides)
            let choice = divergenceChoice
            divergenceChoice = nil
            let conflictMoments = RomFingerprint.compute(data: romData, console: console).flatMap(momentStoreFor)
            let session = try EmulatorSession(romData: romData, savesDirectory: savesDirectory,
                                              mirror: mirror, mirrorSnapshot: snapshot,
                                              emulation: emulation,
                                              console: console, bios: bios,
                                              lineage: .init(divergence: choice, conflictMoments: conflictMoments)) { [weak self] in
                self?.enterBackground()
            }
            session.applyAudioPreferences(audioPreferences)
            try session.start(restoring: automaticState)
            // Game Boy Advance sin tipo ni reloj forzados: lo detectado se recuerda para los ajustes.
            let forced = emulation.gbaSaveType != 0 || emulation.gbaRTC != 0
            let detected = console == .gameBoyAdvance && !forced
                ? (media: session.info.gbaMediaDescription, hasRTC: session.info.hasRTC) : nil
            SavesIndex(directory: savesDirectory).record(fingerprint: session.info.fingerprint,
                                                        title: session.info.title, fileName: fileName,
                                                        detected: detected)
            if let entryID {
                library.learnFingerprint(session.info.fingerprint, forPath: entryID)
                libraryPrefs.recordPlayed(id: entryID, fingerprint: session.info.fingerprint, at: Date())
            }
            sessionLeases = [ownership.takeForSession(session.info.fingerprint)]
            sessionEmulation = emulation
            self.session = session
            gamepad.target = session.padButtons
            gameSpeed = 1
            stateStore = (try? StateStore.defaultRoot()).map { StateStore(root: $0, fingerprint: session.info.fingerprint) }
            #if DEBUG
            if let demo = DebugScreenRouter.demoStateStore() { stateStore = demo }
            #endif
            openMoments(for: session, romData: romData)
            paused = false
            startPlayClock(fingerprint: session.info.fingerprint)
            loadMomentAfterOpening(session, pendingMoment)
            if let forced = session.gameSettingsWarning {
                showAlert(forced.title, forced.message)
            } else if let warning = session.loadWarning ?? (session.info.hasBattery ? extraWarning : nil) {
                showAlert(warning.title, warning.message)
            } else if !session.info.headerChecksumOK {
                showAlert("Cabecera dañada", "La cabecera del ROM no coincide con su checksum. Puede ser un volcado dañado.")
            }
        } catch let SaveOpening.Refusal.divergence(localDate, otherDate) {
            if let request = lastOpenRequest {
                divergencePrompt = DivergencePrompt(entry: request.entry, mode: request.mode,
                                                    localDate: localDate, otherDate: otherDate)
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
        let console = Console(fileName: url.lastPathComponent)
        guard let data = try? Data(contentsOf: url), data.count <= LibraryScanner.romLimit(for: console) else {
            showAlert("No se puede abrir “\(url.lastPathComponent)”", "No se pudo leer el archivo.")
            return
        }
        start(romData: data, mirror: nil, fileName: url.lastPathComponent, console: console)
    }
    #endif

    #if DEBUG
    /// Solo pruebas en el simulador: abre dos ROMs unidos por el cable, sin biblioteca ni espejo.
    func openLink(romURL: URL, linkURL: URL) {
        var games: [LinkSession.Game] = []
        for url in [romURL, linkURL] {
            guard let data = try? Data(contentsOf: url), data.count <= LibraryScanner.romLimit(for: .gameBoy) else {
                showAlert("No se puede abrir “\(url.lastPathComponent)”", "No se pudo leer el archivo.")
                return
            }
            games.append(.init(fileName: url.lastPathComponent, title: nil, rom: data,
                               console: Console(fileName: url.lastPathComponent)))
        }
        startLink(games: games, entryIDs: [nil, nil])
    }
    #endif

    #if DEBUG
    /// Solo pruebas en el simulador: `-rom <ruta>` abre ese archivo al arrancar.
    func openFromLaunchArguments() {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-rom"), i + 1 < args.count else { return }
        if let link = DebugArguments.linkROM {
            openLink(romURL: URL(fileURLWithPath: args[i + 1]), linkURL: URL(fileURLWithPath: link))
        } else {
            open(url: URL(fileURLWithPath: args[i + 1]))
        }
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
        if let session, let link {
            // Cable link: pausa (flush de las dos SRAM) → stop (último flush y desconexión) →
            // portadas de los dos juegos → continuaciones invalidadas. Sin estado automático.
            if !paused && !editingControls { session.pause() }
            session.stop()
            saveArtwork(link)
            for info in link.infos { didRestoreSave(fingerprint: info.fingerprint) }
            releaseLeases(after: session)
            self.link = nil
        } else if let session {
            // Estado automático al salir (SPEC §12). La pausa hace antes el flush síncrono
            // de la SRAM; un fallo del estado nunca impide salir ni guardar la partida.
            if !paused && !editingControls { session.pause() }
            saveAutomaticState(of: session)
            session.stop()
            saveArtwork(session)
            releaseLeases(after: session)
        }
        stopPlayClock()
        stateStore = nil
        momentAfterOpen = nil
        momentStore = nil
        sessionEmulation = nil
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
        var known = Set(libraryPrefs.data.fingerprints.values)
        known.formUnion(libraryPrefs.data.games.keys)
        known.formUnion(library.entries.compactMap(\.fingerprint))
        checkedFingerprints = known
        checkContinuations(known, replacing: true)
    }

    /// Solo las huellas nuevas (p. ej. un juego movido que se acaba de reconocer).
    private func refreshContinuations(adding added: Set<String>) {
        checkedFingerprints.formUnion(added)
        checkContinuations(added, replacing: false)
    }

    private func checkContinuations(_ fingerprints: Set<String>, replacing: Bool) {
        let previous = continuationTask
        continuationTask = Task.detached(priority: .utility) { [weak self] in
            await previous?.value
            guard let statesRoot = try? StateStore.defaultRoot(),
                  let savesDirectory = try? SaveStore.defaultDirectory() else { return }
            let valid = Set(fingerprints.filter { fingerprint in
                let states = StateStore(root: statesRoot, fingerprint: fingerprint)
                let saves = SaveStore(directory: savesDirectory, fingerprint: fingerprint)
                return states.automaticEntry(newerThan: saves.modificationDate) != nil
            })
            let statuses = Dictionary(uniqueKeysWithValues: fingerprints.compactMap { fingerprint -> (String, SaveStatus)? in
                SaveStatus.read(SaveStore(directory: savesDirectory, fingerprint: fingerprint),
                                resumable: valid.contains(fingerprint)).map { (fingerprint, $0) }
            })
            await MainActor.run {
                guard let self else { return }
                if replacing {
                    self.resumableFingerprints = valid
                } else {
                    self.resumableFingerprints.formUnion(valid)
                }
                if replacing { self.saveStatuses = statuses } else { self.saveStatuses.merge(statuses) { $1 } }
            }
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
        let pixels = Array(UnsafeBufferPointer(start: frame, count: session.frames.size.pixelCount))
        artwork.save(fingerprint: session.info.fingerprint, pixels: pixels)
    }

    /// Cable link: la portada de cada juego es su último frame (el activo en `session.frames`, el otro
    /// en `peerFrames`). Con el hilo de emulación ya parado.
    private func saveArtwork(_ link: LinkSession) {
        let frames = [link.session.frames, link.peerFrames]
        let sides = [link.activeSide, link.activeSide.other]
        for (buffers, side) in zip(frames, sides) {
            let pixels = Array(UnsafeBufferPointer(start: buffers.latest(), count: buffers.size.pixelCount))
            artwork.save(fingerprint: link.infos[side.rawValue].fingerprint, pixels: pixels)
        }
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
    var storageDirectories: (saves: URL?, states: URL?, artwork: [URL]) {
        #if DEBUG
        if let demo = DebugScreenRouter.demoStorage { return (demo.saves, demo.states, [demo.artwork].compactMap { $0 }) }
        if let saves = DebugScreenRouter.demoN6Saves { return (saves, nil, []) }
        #endif
        return (try? SaveStore.defaultDirectory(), try? StateStore.defaultRoot(), covers.directories)
    }

    /// N5 · «Usar como portada» desde la pausa: el fotograma en pantalla (la sesión está parada) queda fijado
    /// y el juego pasa a «Captura». No toca la partida ni los estados. `false` si la escena no vale (lisa).
    func pinCurrentFrameAsCover() -> Bool {
        guard let session, link == nil else { return false }
        let frame = session.frames.latest()
        let pixels = Array(UnsafeBufferPointer(start: frame, count: session.frames.size.pixelCount))
        return covers.pinCapture(fingerprint: session.info.fingerprint, pixels: pixels)
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

    // MARK: - Momentos (N6)

    /// Al abrir un juego: momentos de su huella (temporales e índice al día y ranuras 1–4 migradas sin pérdida) y la
    /// cabecera del ROM para el lector de progreso.
    private func openMoments(for session: EmulatorSession, romData: Data) {
        let fingerprint = session.info.fingerprint
        progress.recordHeader(Data(romData.prefix(Int(ProgressLibrary.headerBytes))), for: fingerprint)
        guard let store = momentStoreFor(fingerprint) else { momentStore = nil; return }
        do {
            try store.recoverOrphans()
            if let stateStore { try store.migrateSlots(from: stateStore) }
        } catch {
            momentNotice = MomentNotice("Momentos", "No se pudieron poner al día los momentos de este juego (\(error.localizedDescription)). No se ha borrado nada.")
        }
        momentStore = store
        momentsRevision += 1
    }

    /// La carpeta de momentos de una huella (en DEBUG, la de demostración si la hay).
    func momentStoreFor(_ fingerprint: String) -> MomentStore? {
        #if DEBUG
        if let demo = DebugScreenRouter.demoMomentStore(fingerprint: fingerprint) { return demo }
        #endif
        return (try? MomentStore.defaultRoot()).map { MomentStore(root: $0, fingerprint: fingerprint) }
    }

    private func saveStoreFor(_ fingerprint: String) -> SaveStore? {
        (storageDirectories.saves ?? (try? SaveStore.defaultDirectory())).map { SaveStore(directory: $0, fingerprint: fingerprint) }
    }

    /// Configuración actual del juego abierto (para guardarla en un momento y avisar al cargar).
    var currentMomentConfig: [String: String] {
        guard let session, let sessionEmulation else { return [:] }
        return MomentConfig.make(sessionEmulation, console: session.info.console)
    }

    /// Configuración con la que se abriría un juego ahora (detalle, sin sesión).
    func momentConfig(for entry: RomEntry, fingerprint: String) -> [String: String] {
        MomentConfig.make(gameplay.data.emulation(with: libraryPrefs.overrides(fingerprint: fingerprint, path: entry.id)),
                          console: entry.console)
    }

    func suggestedMomentName() -> String {
        let count = (try? momentStore?.snapshot().moments.count) ?? nil
        return "Momento \((count ?? 0) + 1)"
    }

    /// Crea un momento con la sesión en pausa. No cambia la partida ni el estado automático.
    func createMoment(name: String) {
        guard let session, let momentStore, let saves = saveStoreFor(session.info.fingerprint) else { return }
        do {
            try MomentActions(moments: momentStore, saves: saves)
                .create(from: session, name: name, config: currentMomentConfig, playTime: playClock?.total)
            showGameToast("Momento guardado")
        } catch {
            momentNotice = MomentNotice("No se pudo guardar el momento", Self.describe(error))
        }
        momentsRevision += 1
    }

    /// Carga un momento (o recupera una entrada de «Antes de cargar») en la sesión en pausa. Ya se confirmó.
    func loadMoment(_ kind: MomentStore.Kind, _ moment: MomentStore.Moment) {
        guard let session, let momentStore, let saves = saveStoreFor(session.info.fingerprint) else { return }
        do {
            try MomentActions(moments: momentStore, saves: saves)
                .load(kind, moment, into: session, config: currentMomentConfig, playTime: playClock?.total)
            showGameToast(kind == .moment ? "Momento cargado: \(moment.name)" : "Recuperado lo de antes de cargar")
        } catch {
            var text = Self.describe(error) + " La partida actual no ha cambiado."
            if kind == .moment && moment.hasSRAM {
                text += " Si el momento ya no carga, puedes recuperar su partida desde el detalle del juego › Momentos."
            }
            momentNotice = MomentNotice(kind == .moment ? "No se pudo cargar el momento" : "No se pudo recuperar", text)
        }
        momentsRevision += 1
    }

    func updateMoment(_ store: MomentStore, _ id: String, name: String, tags: [String], collection: String?, note: String) {
        do { try store.update(id, name: name, tags: tags, collection: collection, note: note) } catch {
            momentNotice = MomentNotice("No se pudo guardar el cambio", error.localizedDescription)
        }
        momentsRevision += 1
    }

    func deleteMoment(_ store: MomentStore, _ kind: MomentStore.Kind, _ id: String) {
        do { try store.delete(kind, id) } catch {
            momentNotice = MomentNotice("No se pudo borrar", error.localizedDescription)
        }
        momentsRevision += 1
    }

    /// Detalle (sin sesión): instala la partida de un momento o de «Antes de cargar». Lo actual queda en «Antes de
    /// cargar» y en las copias. Se rechaza con el juego abierto o aún guardando (exclusión por huella).
    func installMomentSave(fingerprint: String, _ kind: MomentStore.Kind, _ moment: MomentStore.Moment) {
        guard let store = momentStoreFor(fingerprint), let saves = saveStoreFor(fingerprint) else { return }
        // H5: mientras se abre un juego aún no se sabe su huella ni tiene dueño: no se toca ninguna partida.
        guard !opening else {
            momentNotice = MomentNotice("No se pudo recuperar la partida", FingerprintOwnership.Busy(owner: "apertura").localizedDescription)
            return
        }
        do {
            try MomentActions(moments: store, saves: saves).installSRAM(kind, moment, ownership: ownership)
            didRestoreSave(fingerprint: fingerprint)
            momentNotice = MomentNotice("Partida recuperada", "Se ha instalado la partida de «\(moment.name)». La de antes quedó en «Antes de cargar» y en las copias de seguridad.")
        } catch {
            momentNotice = MomentNotice("No se pudo recuperar la partida", error.localizedDescription)
        }
        momentsRevision += 1
    }

    /// Detalle o menú: el centro de ajustes del juego abierto directamente en Momentos o Progreso.
    func showGameCenter(_ entry: RomEntry, at route: GameCenterRoute) {
        gameCenterStart = [route]
        gameSettingsEntry = entry
    }

    /// Detalle: abre el juego y, ya en pausa, carga el momento (confirmado en el detalle).
    func openAndLoadMoment(_ entry: RomEntry, fingerprint: String, momentID: String) {
        gameSettingsEntry = nil
        momentAfterOpen = (fingerprint, momentID)
        open(entry: entry, mode: .fresh)
    }

    private func loadMomentAfterOpening(_ session: EmulatorSession, _ pending: (fingerprint: String, id: String)?) {
        guard let pending else { return }
        guard pending.fingerprint == session.info.fingerprint,
              let moment = try? momentStore?.snapshot().find(.moment, pending.id) else { return }
        pauseGame()
        pausePath = [.moments]
        loadMoment(.moment, moment)
    }

    /// Suelta la partida cuando la sesión ya paró y terminó de escribir el espejo.
    private func releaseLeases(after session: EmulatorSession) {
        let leases = sessionLeases
        sessionLeases = []
        session.whenMirrorIdle { for lease in leases { lease.release() } }
    }

    // MARK: - Tiempo de juego (N6)

    private func startPlayClock(fingerprint: String) {
        guard let store = progress.store else { return }
        playClock = PlayTimeTracker(store: store, fingerprint: fingerprint)
        updatePlayClock()
        playCheckpoint?.cancel()
        playCheckpoint = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(PlayTimeTracker.checkpointSeconds))
                self?.playClock?.checkpoint()
            }
        }
    }

    /// Solo cuenta con el juego corriendo: ni en pausa, ni en el editor, ni en segundo plano (que pausa).
    private func updatePlayClock() {
        playClock?.setRunning(session != nil && link == nil && !paused && !editingControls)
    }

    private func stopPlayClock() {
        playCheckpoint?.cancel()
        playCheckpoint = nil
        guard let clock = playClock else { return }
        clock.setRunning(false)
        playClock = nil
        progress.reload()
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

/// N7a · pregunta de divergencia al abrir un juego.
struct DivergencePrompt: Equatable {
    let entry: RomEntry
    let mode: GameLaunchMode
    let localDate: Date?
    let otherDate: Date?

    var message: String {
        func when(_ date: Date?) -> String { date.map { $0.formatted(date: .abbreviated, time: .shortened) } ?? "fecha desconocida" }
        return "La partida de este iPhone (\(when(localDate))) y la del archivo .sav junto al juego (\(when(otherDate))) avanzaron por separado. Elige con cuál seguir: la otra no se borra, queda en Momentos como «Conflicto» y en las copias apartadas."
    }
}

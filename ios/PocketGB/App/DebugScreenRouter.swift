#if DEBUG
import Foundation

/// Pantallas del catálogo que la app sabe abrir directamente (SPEC §9). Cada hito
/// de diseño añade aquí las suyas y sus líneas en ios/PocketGBUITests/screens.txt.
enum DebugScreen: String, CaseIterable {
    // D1
    case launch
    case libraryNoFolder = "library-no-folder"
    case libraryEmpty = "library-empty"
    case settingsMain = "settings-main"
    case settingsAppearance = "settings-appearance"
    case settingsAbout = "settings-about"
    // D2
    case libraryFolderUnavailable = "library-folder-unavailable"
    case libraryCloudPending = "library-cloud-pending"
    case libraryCloudDownloading = "library-cloud-downloading"
    case libraryScanProgress = "library-scan-progress"
    case libraryScanSummary = "library-scan-summary"
    case libraryRomError = "library-rom-error"
    case saveDataError = "save-data-error"
    // D3
    case libraryGrid = "library-grid"
    case libraryList = "library-list"
    case libraryContinue = "library-continue"
    case favorites
    case searchActive = "search-active"
    case searchResults = "search-results"
    case searchNoResults = "search-no-results"
    case gameDetails = "game-details"
    case gameContextMenu = "game-context-menu"
    case removeGameConfirm = "remove-game-confirm"
    case settingsLibrary = "settings-library"
    // D4 (las de gameplay abren además `-rom`)
    case gameplayPortrait = "gameplay-portrait"
    case gameplayLandscape = "gameplay-landscape"
    case gameplayLandscapeClear = "gameplay-landscape-clear"
    case gameplayLandscapeHidden = "gameplay-landscape-hidden"
    case gameplayReduceTransparency = "gameplay-reduce-transparency"
    case customizeControlsPortrait = "customize-controls-portrait"
    case customizeControlsLandscape = "customize-controls-landscape"
    case settingsControls = "settings-controls"
    case settingsDisplay = "settings-display"
    // D5 (abren además `-rom`)
    case gameplayPause = "gameplay-pause"
    case saveStates = "save-states"
    case loadStateConfirm = "load-state-confirm"
    case replaceStateConfirm = "replace-state-confirm"
    case customizeControlsSize = "customize-controls-size"
    case gameplayController = "gameplay-controller"
    case gameplayFastForward = "gameplay-fast-forward"
    case gameplayPortraitArrows = "gameplay-portrait-arrows"
    case gameplayLandscapeArrows = "gameplay-landscape-arrows"
}

/// Traduce `-screen <id>` y los `-demo*` a estado de la app, sin tocar disco ni red.
/// Un id desconocido se muestra como error visible (ScreenshotTests lo detecta).
@MainActor
enum DebugScreenRouter {
    /// Los argumentos fijan la biblioteca: no se resuelve el bookmark real.
    static var overridesLibrary: Bool {
        DebugArguments.demoFolderState != nil || DebugArguments.demoLibrary != nil || DebugArguments.screen != nil
    }

    static func apply(to state: AppState) {
        applyDemoLibrary(to: state.library)
        applyDemoPreferences(to: state)
        guard let raw = DebugArguments.screen else { return }
        guard let screen = DebugScreen(rawValue: raw) else {
            state.debugUnknownScreen = raw
            return
        }
        switch screen {
        case .launch:
            state.debugShowsLaunch = true
        case .settingsMain:
            state.selectedTab = .settings
        case .settingsAppearance:
            state.selectedTab = .settings
            state.settingsPath = [.appearance]
        case .settingsAbout:
            state.selectedTab = .settings
            state.settingsPath = [.about]
        case .saveDataError:
            state.selectedTab = .library
            if DebugArguments.value("-demoSaveError") == "wrong-size" {
                // El mismo aviso que muestra la app al abrir el juego (auditoría D2, H8).
                state.alertTitle = SaveLoadWarning.mirrorIgnored.title
                state.alertMessage = SaveLoadWarning.mirrorIgnored.message
            }
        case .favorites:
            state.selectedTab = .favorites
        case .searchActive:
            presentSearch(state)
        case .searchResults:
            presentSearch(state)
            state.librarySearch = "acid"
        case .searchNoResults:
            // En GB no hay "CGB…": la pantalla ofrece "Buscar en todos".
            presentSearch(state)
            state.libraryFilter = .gb
            state.librarySearch = "CGB"
        case .gameDetails:
            state.libraryPath = [.details(id: demoWithArtwork, source: demoWithArtwork)]
        case .removeGameConfirm:
            state.libraryPath = [.details(id: demoHideable, source: demoHideable)]
            state.hideCandidate = standard.first { $0.id == demoHideable }
        case .settingsLibrary:
            state.selectedTab = .settings
            state.settingsPath = [.library]
        case .settingsControls:
            state.selectedTab = .settings
            state.settingsPath = [.controls]
        case .settingsDisplay:
            state.selectedTab = .settings
            state.settingsPath = [.display]
        case .gameplayPortraitArrows, .gameplayLandscapeArrows, .gameplayController, .gameplayFastForward:
            break
        case .gameplayPause, .saveStates, .loadStateConfirm, .replaceStateConfirm:
            break   // se aplican al abrir el juego (`afterGameOpened`)
        case .customizeControlsPortrait, .customizeControlsLandscape, .customizeControlsSize:
            // El editor se abre cuando `-rom` ya abrió el juego (openFromLaunchArguments).
            state.debugOpensControlsEditor = true
        default:
            state.selectedTab = .library
        }
    }

    /// La búsqueda minimizada solo se expande con la vista ya en pantalla.
    private static func presentSearch(_ state: AppState) {
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(1))
            state.librarySearchPresented = true
        }
    }

    /// D5: estado de la pantalla una vez abierto el juego con `-rom`.
    static func afterGameOpened(_ state: AppState) {
        guard let screen = DebugArguments.screen.flatMap(DebugScreen.init(rawValue:)) else { return }
        switch screen {
        case .customizeControlsSize:
            state.editorSelection = .a
        case .gameplayFastForward:
            Task { @MainActor in
                try? await Task.sleep(for: .seconds(1))
                state.cycleSpeed()
                state.cycleSpeed()   // ×4
            }
        case .saveStates, .loadStateConfirm, .replaceStateConfirm:
            Task { @MainActor in
                try? await Task.sleep(for: .seconds(1))
                state.pauseGame()
                state.pausePath = [.states]
                try? await Task.sleep(for: .seconds(1))
                if screen == .loadStateConfirm { state.pendingStateLoad = .manual1 }
                if screen == .replaceStateConfirm { state.pendingStateReplace = .manual2 }
            }
        default:
            break
        }
    }

    /// `-demoSaveState slots`: estados de demostración en un directorio temporal (automático
    /// y tres manuales con fechas fijas; la ranura 4 vacía). Arte generado, no del juego.
    static func demoStateStore() -> StateStore? {
        guard DebugArguments.value("-demoSaveState") == "slots" else { return nil }
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("demo-states-\(UUID().uuidString)", isDirectory: true)
        let store = StateStore(directory: dir)
        let base = GameArtworkStore.demoPixels()
        let slots: [(StateSlot, TimeInterval, Int)] = [
            (.auto, 1_790_700_000, 0), (.manual1, 1_790_600_000, 40), (.manual2, 1_790_400_000, 80),
            (.manual3, 1_790_100_000, 120),
        ]
        for (slot, time, shift) in slots {
            // La captura de cada ranura desplaza el dibujo para distinguirlas.
            var pixels = base
            for y in 0..<FrameBuffers.height {
                for x in 0..<FrameBuffers.width {
                    pixels[y * FrameBuffers.width + x] = base[y * FrameBuffers.width + (x + shift) % FrameBuffers.width]
                }
            }
            try? store.save(Data("PGBS-demo".utf8), thumbnail: AppState.thumbnail(pixels), to: slot)
            try? FileManager.default.setAttributes([.modificationDate: Date(timeIntervalSince1970: time)],
                                                   ofItemAtPath: store.stateURL(slot).path)
        }
        return store
    }

    nonisolated private static let demoWithArtwork = "dmg-acid2.gb"
    nonisolated private static let demoFavorite = "cgb-acid2.gbc"
    nonisolated private static let demoHideable = "Pruebas/rtc3test.gb"

    /// Favorito, recientes con fechas fijas, una captura (arte abstracto generado) y la
    /// vista de cada pantalla. Solo en memoria (AppState no persiste en modo demo).
    private static func applyDemoPreferences(to state: AppState) {
        guard DebugArguments.demoLibrary != nil else { return }
        let screen = DebugArguments.screen.flatMap(DebugScreen.init(rawValue:))
        var prefs = LibraryPreferencesData()
        prefs.favorites = [demoFavorite]
        prefs.fingerprints = [demoWithArtwork: "demo-dmg-acid2", demoFavorite: "demo-cgb-acid2"]
        prefs.lastPlayed = [demoWithArtwork: Date(timeIntervalSince1970: 1_790_500_000),
                            demoFavorite: Date(timeIntervalSince1970: 1_790_300_000)]
        switch screen {
        case .libraryList, .libraryScanProgress, .libraryScanSummary, .libraryRomError, .saveDataError:
            prefs.layout = .list
        case .settingsLibrary:
            prefs.hiddenPaths = [demoHideable]
        default:
            prefs.layout = .grid
        }
        state.libraryPrefs.applyDemo(prefs)
        state.artwork.applyDemo(fingerprint: "demo-dmg-acid2")
    }

    nonisolated private static let folder = "Juegos Game Boy"

    /// Biblioteca de demostración en memoria, con fechas y progreso fijos.
    private static func applyDemoLibrary(to library: LibraryStore) {
        let screen = DebugArguments.screen.flatMap(DebugScreen.init(rawValue:))
        switch DebugArguments.demoFolderState {
        case "none":
            library.applyDemo(phase: .noFolder, entries: [])
            return
        case "stale", "denied":
            library.applyDemo(phase: .unavailable(folderName: folder), entries: [])
            return
        default:
            break
        }
        switch DebugArguments.demoLibrary {
        case "empty":
            library.applyDemo(phase: .ready(folderName: folder), entries: [])
        case "standard":
            switch screen {
            case .libraryScanProgress:
                library.applyDemo(phase: .ready(folderName: folder), entries: Array(standard.prefix(2)),
                                  progress: .init(done: 2, total: 5))
            case .libraryScanSummary:
                var entries = standard
                entries[1].isNew = true
                entries[3].isNew = true
                library.applyDemo(phase: .ready(folderName: folder), entries: entries,
                                  summary: "2 juegos nuevos")
            default:
                library.applyDemo(phase: .ready(folderName: folder), entries: standard)
            }
        case "cloud":
            let state: RomEntry.CloudState =
                DebugArguments.value("-demoCloudState") == "downloading" ? .downloading : .notDownloaded
            var entries = standard
            entries[2].cloud = state
            entries[3].cloud = state
            library.applyDemo(phase: .ready(folderName: folder), entries: entries)
        case "errors":
            library.applyDemo(phase: .ready(folderName: folder), entries: Array(standard.prefix(2)) + errors)
        default:
            break
        }
    }

    nonisolated private static func entry(_ file: String, _ title: String, color: Bool, sub: String = "",
                              problem: RomEntry.Problem? = nil, saved: Bool = false) -> RomEntry {
        let id = sub.isEmpty ? file : "\(sub)/\(file)"
        return RomEntry(id: id, url: URL(fileURLWithPath: "/demo/\(id)"), fileName: file, title: title,
                        isColor: color, sizeBytes: 32_768, headerChecksumOK: true, cloud: .current,
                        problem: problem,
                        mirrorSaveDate: saved ? Date(timeIntervalSince1970: 1_790_000_000) : nil)
    }

    /// Juegos libres de las ROMs de prueba (nunca títulos comerciales en las capturas).
    nonisolated private static let standard: [RomEntry] = [
        entry("cgb-acid2.gbc", "CGB-ACID2", color: true),
        entry("dmg-acid2.gb", "DMG-ACID2", color: false, saved: true),
        entry("rtc3test.gb", "RTC3TEST", color: false, sub: "Pruebas"),
        entry("homebrew-con-un-titulo-muy-largo.gbc", "Un homebrew con un título muy largo para probar el truncado",
              color: true, sub: "Pruebas"),
    ]

    nonisolated private static let errors: [RomEntry] = [
        entry("copia-de-seguridad.gb", "copia-de-seguridad", color: false, problem: .tooLarge),
        entry("notas.gbc", "notas", color: true, problem: .invalidHeader),
    ]
}
#endif

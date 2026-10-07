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
    case libraryContinueReduceMotion = "library-continue-reduce-motion"
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
    case libraryReduceTransparency = "library-reduce-transparency"
    case libraryAX5 = "library-ax5"
    case settingsAudio = "settings-audio"
    case settingsEmulation = "settings-emulation"
    case settingsStorage = "settings-storage"
    case settingsSaves = "settings-saves"
    case gameSettings = "game-settings"
    case gameSettingsGBA = "game-settings-gba"
    case customizeControlsGBAPortrait = "customize-controls-gba-portrait"
    case customizeControlsGBALandscape = "customize-controls-gba-landscape"
    // G8-H4: las mismas pantallas con Dynamic Type accessibility5.
    case gameSettingsGBAAX5 = "game-settings-gba-ax5"
    case customizeControlsGBAPortraitAX5 = "customize-controls-gba-portrait-ax5"
    case gameplayController = "gameplay-controller"
    case gameplayFastForward = "gameplay-fast-forward"
    case gameplayPortraitArrows = "gameplay-portrait-arrows"
    case gameplayLandscapeArrows = "gameplay-landscape-arrows"
    // M9: cable link virtual (las de gameplay abren además `-rom` y `-linkROM`)
    case gameplayLinkPortrait = "gameplay-link-portrait"
    case gameplayLinkLandscape = "gameplay-link-landscape"
    case gameplayLinkSwitched = "gameplay-link-switched"
    case gameplayLinkPause = "gameplay-link-pause"
    case gameplayLinkReduceTransparency = "gameplay-link-reduce-transparency"
    case linkPartnerPicker = "link-partner-picker"
    case linkPartnerPickerAX5 = "link-partner-picker-ax5"
    case linkOpenRefused = "link-open-refused"
    case linkContinueWarning = "link-continue-warning"
    // N1 (iOS): carpetas anidadas, duplicados y preferencias apartadas (`-demoLibrary folders`)
    case libraryFolders = "library-folders"
    case libraryFoldersList = "library-folders-list"
    case gameDetailsFolders = "game-details-folders"
    case gameDetailsFoldersAX5 = "game-details-folders-ax5"
    case libraryPreferencesQuarantined = "library-preferences-quarantined"
    // Auditoría N1, H1: copia apartada (no rota) en Ajustes › Partidas › juego (`-demoSaveState slots`)
    case settingsSaveBackups = "settings-save-backups"
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
        case .settingsAudio:
            state.selectedTab = .settings
            state.settingsPath = [.audio]
        case .settingsEmulation:
            state.selectedTab = .settings
            state.settingsPath = [.emulation]
        case .settingsStorage:
            state.selectedTab = .settings
            state.settingsPath = [.storage]
        case .settingsSaves:
            state.selectedTab = .settings
            state.settingsPath = [.saves]
        case .settingsSaveBackups:
            state.selectedTab = .settings
            state.settingsPath = [.saves, .backups(fingerprint: "demo-dmg-acid2")]
        case .gameSettings:
            state.libraryPath = [.details(id: demoFavorite, source: demoFavorite)]
            // Un juego de Game Boy con paleta personalizada (Global/Personalizado visibles).
            if let dmg = standard.first(where: { $0.id == demoWithArtwork }) {
                state.libraryPrefs.setOverrides(GameOverrides(colorForGameBoy: true, compatPalette: 5), for: dmg)
                state.libraryPath = [.details(id: dmg.id, source: dmg.id)]
                state.gameSettingsEntry = dmg
            }
        case .gameSettingsGBA, .gameSettingsGBAAX5:
            // Un juego de Game Boy Advance con el tipo de partida forzado.
            if let gba = standard.first(where: { $0.badge == .gba }) {
                state.libraryPrefs.setOverrides(GameOverrides(gbaSaveType: 3), for: gba)
                state.libraryPath = [.details(id: gba.id, source: gba.id)]
                state.gameSettingsEntry = gba
            }
        case .settingsControls:
            state.selectedTab = .settings
            state.settingsPath = [.controls]
        case .settingsDisplay:
            state.selectedTab = .settings
            state.settingsPath = [.display]
        case .gameplayPortraitArrows, .gameplayLandscapeArrows, .gameplayController, .gameplayFastForward:
            break
        case .gameplayLinkPortrait, .gameplayLinkLandscape, .gameplayLinkSwitched, .gameplayLinkPause,
             .gameplayLinkReduceTransparency:
            break   // se aplican al abrir el cable (`afterGameOpened`)
        case .linkPartnerPicker, .linkPartnerPickerAX5:
            // Detalle de dmg-acid2 y, encima, el selector de pareja (patrón de `gameSettings`).
            state.libraryPath = [.details(id: demoWithArtwork, source: demoWithArtwork)]
            state.linkPartnerSource = standard.first { $0.id == demoWithArtwork }
        case .linkOpenRefused:
            state.selectedTab = .library
            if DebugArguments.demoLinkError == "same-game" {
                // El mismo texto que muestra la app al rechazar el cable (patrón de `saveDataError`).
                let refusal = LinkSession.Refusal.sameGame(title: "DMG-ACID2")
                state.alertTitle = refusal.title
                state.alertMessage = refusal.message
            }
        case .linkContinueWarning:
            state.libraryPath = [.details(id: demoWithArtwork, source: demoWithArtwork)]
            if let first = standard.first(where: { $0.id == demoWithArtwork }),
               let second = standard.first(where: { $0.id == demoFavorite }) {
                state.linkContinueRequest = LinkRequest(first: first, second: second)
            }
        case .gameplayPause, .saveStates, .loadStateConfirm, .replaceStateConfirm:
            break   // se aplican al abrir el juego (`afterGameOpened`)
        case .libraryFolders:
            // Filtro GB: sin el carril «Continuar», las dos copias duplicadas quedan arriba.
            state.selectedTab = .library
            state.libraryFilter = .gb
        case .gameDetailsFolders, .gameDetailsFoldersAX5:
            state.libraryPath = [.details(id: demoDeep, source: demoDeep)]
        case .libraryPreferencesQuarantined:
            // El mismo aviso que da la app al apartar un preferences.json dañado (N1a).
            state.selectedTab = .library
            let issue = LibraryPreferences.Issue.quarantined(fileName: "preferences.corrupt-20261006-101500.json")
            state.alertTitle = issue.title
            state.alertMessage = issue.message
        case .customizeControlsPortrait, .customizeControlsLandscape, .customizeControlsSize,
             .customizeControlsGBAPortrait, .customizeControlsGBALandscape, .customizeControlsGBAPortraitAX5:
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
        case .gameplayLinkSwitched:
            Task { @MainActor in
                // El toast dura 2,5 s y la captura llega ~3 s tras el arranque: se cambia a los 2 s.
                try? await Task.sleep(for: .seconds(2))
                state.switchLinkSide()
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

    /// `-demoSaveState slots` en Ajustes: carpetas temporales con partidas, copias, estados y
    /// portadas de tamaño fijo, para Almacenamiento y Partidas. Se crean una vez por arranque.
    static let demoStorage: (saves: URL?, states: URL?, artwork: URL?)? = {
        guard DebugArguments.value("-demoSaveState") == "slots" else { return nil }
        let fm = FileManager.default
        let root = fm.temporaryDirectory.appendingPathComponent("demo-storage-\(UUID().uuidString)", isDirectory: true)
        let saves = root.appendingPathComponent("Saves", isDirectory: true)
        let states = root.appendingPathComponent("States", isDirectory: true)
        let artwork = root.appendingPathComponent("Artwork", isDirectory: true)
        for dir in [saves, states, artwork] { try? fm.createDirectory(at: dir, withIntermediateDirectories: true) }
        let games = [("demo-dmg-acid2", "DMG-ACID2", "dmg-acid2.gb"), ("demo-cgb-acid2", "CGB-ACID2", "cgb-acid2.gbc")]
        for (fp, title, file) in games {
            let store = SaveStore(directory: saves, fingerprint: fp)
            for byte in [UInt8(1), 2, 3] { try? store.save(Data(repeating: byte, count: 8_192)) }
            SavesIndex(directory: saves).record(fingerprint: fp, title: title, fileName: file)
            try? StateStore(root: states, fingerprint: fp).save(Data(repeating: 7, count: 40_000), thumbnail: nil, to: .auto)
        }
        try? Data(repeating: 0, count: 23_000).write(to: artwork.appendingPathComponent("demo.png"))
        // Fechas fijas (capturas deterministas) y, solo en su captura, una copia apartada (H1).
        let dmg = SaveStore(directory: saves, fingerprint: "demo-dmg-acid2")
        if DebugArguments.screen == DebugScreen.settingsSaveBackups.rawValue {
            try? dmg.keepMirrorLoser(Data(repeating: 9, count: 8_192), now: Date(timeIntervalSince1970: 1_790_100_000))
        }
        let fixed: [(URL, TimeInterval)] = [(dmg.saveURL, 1_790_600_000), (dmg.backupURL(1), 1_790_500_000),
                                            (dmg.backupURL(2), 1_790_400_000)]
            + dmg.keptCopies().map { ($0.url, 1_790_100_000) }
        for (url, time) in fixed {
            try? fm.setAttributes([.modificationDate: Date(timeIntervalSince1970: time)], ofItemAtPath: url.path)
        }
        return (saves, states, artwork)
    }()

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
        // Huellas conocidas por ruta (como tras abrir los juegos) y metadatos por huella (N1a).
        prefs.fingerprints = [demoWithArtwork: "demo-dmg-acid2", demoFavorite: "demo-cgb-acid2"]
        var dmg = GameMetadata()
        dmg.lastPlayed = Date(timeIntervalSince1970: 1_790_500_000)
        dmg.lastPlayedPath = DebugArguments.demoLibrary == "folders" ? demoDeep : demoWithArtwork
        var cgb = GameMetadata()
        cgb.favorite = true
        cgb.lastPlayed = Date(timeIntervalSince1970: 1_790_300_000)
        cgb.lastPlayedPath = demoFavorite
        prefs.games = ["demo-dmg-acid2": dmg, "demo-cgb-acid2": cgb]
        switch screen {
        case .libraryList, .libraryScanProgress, .libraryScanSummary, .libraryRomError, .saveDataError,
             .libraryFoldersList:
            prefs.layout = .list
        case .settingsLibrary:
            var hidden = GameMetadata()
            hidden.hidden = true
            prefs.pendingByPath = [demoHideable: hidden]
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
        case "folders":
            library.applyDemo(phase: .ready(folderName: folder), entries: folders)
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
        entry("arm.gba", "jsmolka ARM", color: false, sub: "Pruebas"),
    ]

    nonisolated private static let demoDeep = "Homebrew/Pruebas de vídeo/Acid/Game Boy/Clásicos/dmg-acid2.gb"

    /// N1: árbol de carpetas de 5 niveles y una copia duplicada (misma huella) en `Copias/`.
    /// Huellas de demostración (no son las reales de los ROMs de prueba).
    nonisolated private static let folders: [RomEntry] = {
        func make(_ path: String, _ title: String, color: Bool, fingerprint: String?, saved: Bool = false) -> RomEntry {
            let file = (path as NSString).lastPathComponent
            var e = RomEntry(id: path, url: URL(fileURLWithPath: "/demo/\(path)"), fileName: file, title: title,
                             isColor: color, sizeBytes: 32_768, headerChecksumOK: true, cloud: .current,
                             problem: nil,
                             mirrorSaveDate: saved ? Date(timeIntervalSince1970: 1_790_000_000) : nil)
            e.fingerprint = fingerprint
            return e
        }
        var entries = [
            make("Homebrew/Pruebas de vídeo/Acid/Game Boy Color/cgb-acid2.gbc", "CGB-ACID2", color: true,
                 fingerprint: "demo-cgb-acid2"),
            make(demoDeep, "DMG-ACID2", color: false, fingerprint: "demo-dmg-acid2", saved: true),
            make("Copias/dmg-acid2.gb", "DMG-ACID2", color: false, fingerprint: "demo-dmg-acid2"),
            make("Homebrew/Pruebas de reloj/rtc3test.gb", "RTC3TEST", color: false, fingerprint: "demo-rtc3test"),
            make("Game Boy Advance/Pruebas/arm.gba", "jsmolka ARM", color: false, fingerprint: "demo-arm"),
        ]
        LibraryIdentity.markDuplicates(&entries)
        return entries
    }()

    nonisolated private static let errors: [RomEntry] = [
        entry("copia-de-seguridad.gb", "copia-de-seguridad", color: false, problem: .tooLarge),
        entry("notas.gbc", "notas", color: true, problem: .invalidHeader),
    ]
}
#endif

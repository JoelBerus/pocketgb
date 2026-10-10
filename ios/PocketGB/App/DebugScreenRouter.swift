#if DEBUG
import Foundation
import UIKit

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
    // N2: cruceta que responde por dirección (abren además `-rom`; `-uiPressedDpad` simula el dedo)
    case gameplayDpadUp = "gameplay-dpad-up"
    case gameplayDpadUpright = "gameplay-dpad-upright"
    case gameplayDpadUpClear = "gameplay-dpad-up-clear"
    case gameplayDpadUpReduceTransparency = "gameplay-dpad-up-reduce-transparency"
    case gameplayArrowsUp = "gameplay-arrows-up"
    case gameplayArrowsUpright = "gameplay-arrows-upright"
    case gameplayArrowsUpReduceTransparency = "gameplay-arrows-up-reduce-transparency"
    case gameplayArrowsSpacing70 = "gameplay-arrows-spacing-70"
    case gameplayArrowsSpacing150 = "gameplay-arrows-spacing-150"
    case gameplayGBADpadUp = "gameplay-gba-dpad-up"
    case gameplayGBAArrowsUp = "gameplay-gba-arrows-up"
    case customizeControlsDpad = "customize-controls-dpad"
    case customizeControlsArrows = "customize-controls-arrows"
    case settingsControlsAX5 = "settings-controls-ax5"
    // N1 (iOS): carpetas anidadas, duplicados y preferencias apartadas (`-demoLibrary folders`)
    case libraryFolders = "library-folders"
    case libraryFoldersList = "library-folders-list"
    case gameDetailsFolders = "game-details-folders"
    case gameDetailsFoldersAX5 = "game-details-folders-ax5"
    case libraryPreferencesQuarantined = "library-preferences-quarantined"
    // Auditoría N1, H1: copia apartada (no rota) en Ajustes › Partidas › juego (`-demoSaveState slots`)
    case settingsSaveBackups = "settings-save-backups"
    // N3 (iOS): biblioteca y detalle adaptables (`-demoLibrary adaptive`; ver `applyAdaptive`)
    case libraryLandscape = "library-landscape"
    case libraryLandscapeScrolled = "library-landscape-scrolled"
    case libraryLandscapeSearch = "library-landscape-search"
    case libraryLandscapeFilters = "library-landscape-filters"
    case libraryLandscapeCategories = "library-landscape-categories"
    case libraryLandscapeView = "library-landscape-view"
    case libraryLandscapeCategory = "library-landscape-category"
    case libraryLandscapeWhite = "library-landscape-white"
    case libraryLandscapeAX5 = "library-landscape-ax5"
    case libraryWhite = "library-white"
    case favoritesLandscape = "favorites-landscape"
    case gameDetailsGB = "game-details-gb"
    case gameDetailsGBA = "game-details-gba"
    case gameDetailsTechnical = "game-details-technical"
    case gameDetailsGBATechnical = "game-details-gba-technical"
    case gameDetailsAX5 = "game-details-ax5"
    case saveStatesGBA = "save-states-gba"
    // N3 (auditoría): herramientas en la barra en reposo y paneles con AX5 y sin transparencia
    case libraryLandscapeBarFilters = "library-landscape-bar-filters"
    case libraryLandscapeBarCategories = "library-landscape-bar-categories"
    case libraryLandscapeBarView = "library-landscape-bar-view"
    case libraryLandscapePanelAX5 = "library-landscape-panel-ax5"
    case libraryLandscapePanelReduceTransparency = "library-landscape-panel-reduce-transparency"
    // N4 (iOS): inicio con estanterías, categorías anidadas, etiquetas y centro de ajustes (`-demoLibrary n4`)
    case n4Home = "n4-home"
    case n4HomeScrolled = "n4-home-scrolled"
    case n4HomeLandscape = "n4-home-landscape"
    case n4HomeLandscapeScrolled = "n4-home-landscape-scrolled"
    case n4HomeAX5 = "n4-home-ax5"
    case n4HomeCustomized = "n4-home-customized"
    case n4Category = "n4-category"
    case n4CategoryNested = "n4-category-nested"
    case n4CategoryNestedList = "n4-category-nested-list"
    case n4CategoryVirtual = "n4-category-virtual"
    case n4CategoryAX5 = "n4-category-ax5"
    case n4GameCenter = "n4-game-center"
    case n4GameCenterGBA = "n4-game-center-gba"
    case n4GameCenterAX5 = "n4-game-center-ax5"
    case n4TagEditor = "n4-tag-editor"
    case n4MoveCategory = "n4-move-category"
    case n4MoveCategoryAX5 = "n4-move-category-ax5"
    case n4DetailsMoved = "n4-details-moved"
    case n4FilterTag = "n4-filter-tag"
    case n4LandscapeFilters = "n4-landscape-filters"
    case n4LandscapeCategories = "n4-landscape-categories"
    case n4SettingsHome = "n4-settings-home"
    case n4SettingsHomeAX5 = "n4-settings-home-ax5"
    // N5 (iOS): portadas (`-demoLibrary n5`; imágenes pintadas en código, ver `applyN5`)
    case n5Library = "n5-library"
    case n5LibraryList = "n5-library-list"
    case n5LibraryPreferCaptures = "n5-library-prefer-captures"
    case n5LibraryAX5 = "n5-library-ax5"
    case n5LibraryLandscape = "n5-library-landscape"
    case n5DetailsImported = "n5-details-imported"
    case n5DetailsFolder = "n5-details-folder"
    case n5DetailsCapture = "n5-details-capture"
    case n5DetailsGenerated = "n5-details-generated"
    case n5DetailsGBA = "n5-details-gba"
    case n5GameCenter = "n5-game-center"
    case n5Cover = "n5-cover"
    case n5CoverCapture = "n5-cover-capture"
    case n5CoverFailed = "n5-cover-failed"
    case n5CoverAX5 = "n5-cover-ax5"
    case n5Pause = "n5-pause"
    case n5SettingsLibrary = "n5-settings-library"
    case n5SettingsStorage = "n5-settings-storage"
    // N6 (iOS): momentos y progreso (`-demoMoments rich`, `-demoLibrary n5`; ver `applyN6`)
    case n6MomentsPause = "n6-moments-pause"
    case n6MomentsDetail = "n6-moments-detail"
    case n6MomentsPauseLoad = "n6-moments-pause-load"
    case n6MomentsPauseRecover = "n6-moments-pause-recover"
    case n6MomentsPauseNew = "n6-moments-pause-new"
    case n6MomentsDetailFilter = "n6-moments-detail-filter"
    case n6MomentsDetailInstall = "n6-moments-detail-install"
    case n6MomentsDetailLoad = "n6-moments-detail-load"
    case n6MomentsAX5 = "n6-moments-ax5"
    case n6MomentEdit = "n6-moment-edit"
    case n6ProgressDetails = "n6-progress-details"
    case n6ProgressEditor = "n6-progress-editor"
    case n6ProgressAX5 = "n6-progress-ax5"
    case n6LibraryPercent = "n6-library-percent"
    // N7 (iOS): partidas que viajan
    case n7DetailSave = "n7-detail-save"
    case n7DetailSaveAX5 = "n7-detail-save-ax5"
    case n7Divergence = "n7-divergence"
    case n7ImportPrompt = "n7-import-prompt"
    case n7ImportDone = "n7-import-done"
    case n7SettingsConflicts = "n7-settings-conflicts"
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
        guard let raw = DebugArguments.screen else {
            applyN4(nil, to: state)
            return
        }
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
        case .gameplayDpadUp, .gameplayDpadUpright, .gameplayDpadUpClear, .gameplayDpadUpReduceTransparency,
             .gameplayArrowsUp, .gameplayArrowsUpright, .gameplayArrowsUpReduceTransparency, .gameplayArrowsSpacing70,
             .gameplayArrowsSpacing150, .gameplayGBADpadUp, .gameplayGBAArrowsUp:
            break   // los fijan `-dpadStyle`, `-arrowSpacing` y `-uiPressedDpad` (ControlsOverlayView)
        case .settingsControlsAX5:
            state.selectedTab = .settings
            state.settingsPath = [.controls]
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
        case .gameplayPause, .saveStates, .loadStateConfirm, .replaceStateConfirm, .n5Pause:
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
             .customizeControlsGBAPortrait, .customizeControlsGBALandscape, .customizeControlsGBAPortraitAX5,
             .customizeControlsDpad, .customizeControlsArrows:
            // El editor se abre cuando `-rom` ya abrió el juego (openFromLaunchArguments).
            state.debugOpensControlsEditor = true
        default:
            state.selectedTab = .library
        }
        applyAdaptive(screen, to: state)
        applyN4(screen, to: state)
        applyN5(screen, to: state)
        applyN6(screen, to: state)
        applyN7(screen, to: state)
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
        case .customizeControlsDpad, .customizeControlsArrows:
            state.editorSelection = .dpad
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
        case .n6MomentsPause, .n6MomentsPauseLoad, .n6MomentsPauseRecover, .n6MomentsPauseNew:
            Task { @MainActor in
                try? await Task.sleep(for: .seconds(1))
                state.pauseGame()
                state.pausePath = [.moments]
            }
        case .saveStates, .loadStateConfirm, .replaceStateConfirm:
            // N6: las ranuras pasan a momentos; estas capturas abren la pantalla de Momentos (con las ranuras de
            // demostración ya migradas). `replace-state-confirm` ya no existe como tal: muestra la misma pantalla.
            Task { @MainActor in
                try? await Task.sleep(for: .seconds(1))
                state.pauseGame()
                state.pausePath = [.moments]
            }
        default:
            break
        }
        afterGameOpenedAdaptive(screen, state)
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

// MARK: - N3 (iOS): biblioteca y detalle adaptables

extension DebugScreenRouter {
    /// `-demoLibrary adaptive`: la biblioteca estándar y más juegos libres (ROMs de prueba) en
    /// varias carpetas de primer nivel (categorías), para desplazar en horizontal. CGB-ACID2 lleva
    /// una captura casi blanca (título legible encima) y arm.gba una de 240×160 (sin estirar).
    /// `-demoROMDir $FIXTURES` hace que el detalle lea la información técnica de los ROMs reales.
    static func applyAdaptive(_ screen: DebugScreen, to state: AppState) {
        guard DebugArguments.demoLibrary == "adaptive" else { return }
        state.library.applyDemo(phase: .ready(folderName: "Juegos Game Boy"), entries: adaptive)
        var prefs = state.libraryPrefs.data
        prefs.fingerprints[adaptiveGBA] = "demo-arm"
        prefs.layout = .grid
        state.libraryPrefs.applyDemo(prefs)
        state.artwork.applyDemo(fingerprint: "demo-cgb-acid2", pixels: GameArtworkStore.demoWhitePixels())
        state.artwork.applyDemo(fingerprint: "demo-arm", pixels: GameArtworkStore.demoGBAPixels())
        switch screen {
        case .libraryLandscapeCategory:
            // N4 (N4A-1): una categoría ya no filtra en el sitio; se abre su pantalla.
            state.libraryPath = [.category(path: ["Blargg"])]
        case .favoritesLandscape:
            state.selectedTab = .favorites
        case .gameDetailsGB, .gameDetailsTechnical, .gameDetailsAX5:
            state.libraryPath = [.details(id: "dmg-acid2.gb", source: "dmg-acid2.gb")]
        case .gameDetailsGBA, .gameDetailsGBATechnical:
            state.libraryPath = [.details(id: adaptiveGBA, source: adaptiveGBA)]
        default:
            break
        }
    }

    /// `save-states-gba`: con `-rom arm.gba`, guarda un estado real (miniatura de 240×160) y abre
    /// la pantalla de estados: la miniatura y las ranuras vacías van en 3:2, sin estirar.
    static func afterGameOpenedAdaptive(_ screen: DebugScreen, _ state: AppState) {
        guard screen == .saveStatesGBA else { return }
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(1))
            state.pauseGame()
            state.createMoment(name: "Momento 1")
            state.pausePath = [.moments]
        }
    }

    nonisolated private static let adaptiveGBA = "Pruebas/arm.gba"

    /// Juegos libres de las ROMs de prueba (blargg, mooneye y jsmolka; nunca títulos comerciales).
    nonisolated private static let adaptive: [RomEntry] = {
        func make(_ path: String, _ title: String, color: Bool) -> RomEntry {
            let file = (path as NSString).lastPathComponent
            return RomEntry(id: path, url: URL(fileURLWithPath: "/demo/\(path)"), fileName: file, title: title,
                            isColor: color, sizeBytes: 65_536, headerChecksumOK: true, cloud: .current,
                            problem: nil, mirrorSaveDate: nil)
        }
        return standard + [
            make("Blargg/cpu_instrs.gb", "CPU_INSTRS", color: false),
            make("Blargg/instr_timing.gb", "INSTR_TIMING", color: false),
            make("Blargg/mem_timing.gb", "MEM_TIMING", color: false),
            make("Blargg/dmg_sound.gb", "DMG_SOUND", color: false),
            make("Mooneye/halt_ime1_timing.gb", "HALT_IME1", color: false),
            make("Mooneye/boot_regs-cgb.gbc", "BOOT_REGS", color: true),
            make("Game Boy Advance/thumb.gba", "jsmolka THUMB", color: false),
            make("Game Boy Advance/memory.gba", "jsmolka MEMORY", color: false),
        ]
    }()
}

// MARK: - N4 (iOS): categorías, etiquetas, inicio y centro de ajustes

extension DebugScreenRouter {
    nonisolated private static let n4Moved = "Reloj/rtc3test.gb"
    nonisolated private static let n4GBA = "Game Boy Advance/arm.gba"

    /// `-demoLibrary n4`: árbol de carpetas de varios niveles con juegos libres de las ROMs de prueba
    /// (nunca títulos comerciales), huellas confirmadas, etiquetas, tres favoritos, juegos jugados y una
    /// categoría virtual (ND3):
    /// ```
    /// Juegos Game Boy/
    ///   Acid/{dmg-acid2.gb, cgb-acid2.gbc}
    ///   Pruebas/Blargg/{cpu_instrs.gb, instr_timing.gb}
    ///   Pruebas/Blargg/Sonido/dmg_sound.gb
    ///   Pruebas/Mooneye/{halt_ime1_timing.gb, boot_regs-cgb.gbc}
    ///   Game Boy Advance/{arm.gba, thumb.gba}
    ///   Reloj/rtc3test.gb        ← se ve en «Para jugar» (movido en la app)
    ///   homebrew-con-un-titulo-muy-largo.gbc   ← sin categoría
    /// ```
    /// Sin `-demoLibrary n4`: `-demoHome off` quita Favoritos y las estanterías (las pruebas de N3 y de
    /// la cuadrícula miden «Todos los juegos»; el inicio se prueba aparte, como N4A-11 en Android) y las
    /// demás bibliotecas de demostración tienen sus huellas conocidas confirmadas (el centro de ajustes
    /// de `game-settings` no se queda en «Leyendo el juego…»).
    static func applyN4(_ screen: DebugScreen?, to state: AppState) {
        if DebugArguments.demoLibrary == "n4" {
            applyN4Library(to: state)
        } else if DebugArguments.demoLibrary != nil {
            confirmDemoFingerprints(state)
        }
        // N4: el menú contextual de la captura `game-context-menu` es el de la tarjeta de «Todos los
        // juegos», que con el inicio queda debajo de las estanterías.
        if DebugArguments.value("-demoHome") == "off" || screen == .gameContextMenu {
            let keys = LibraryHome.keys(state.library.entries, prefs: state.libraryPrefs.data)
            var prefs = state.libraryPrefs.data
            prefs.home.showFavorites = false
            prefs.home.hidden = keys
            state.libraryPrefs.applyDemo(prefs)
        }
        guard DebugArguments.demoLibrary == "n4", let screen else { return }
        let entries = state.library.entries
        let moved = entries.first { $0.id == n4Moved }
        switch screen {
        case .n4HomeCustomized:
            var prefs = state.libraryPrefs.data
            prefs.home = HomeSettings().pinning("Pruebas", true).hiding("Game Boy Advance", true)
            prefs.home.showFavorites = false
            state.libraryPrefs.applyDemo(prefs)
        case .n4Category, .n4CategoryAX5:
            state.libraryPath = [.category(path: ["Pruebas"])]
        case .n4CategoryNested, .n4CategoryNestedList:
            state.libraryPath = [.category(path: ["Pruebas"]), .category(path: ["Pruebas", "Blargg"])]
            if screen == .n4CategoryNestedList {
                var prefs = state.libraryPrefs.data
                prefs.categoryLayouts["Pruebas/Blargg"] = .list
                state.libraryPrefs.applyDemo(prefs)
            }
        case .n4CategoryVirtual:
            state.libraryPath = [.category(path: ["Para jugar"])]
        case .n4GameCenter, .n4GameCenterAX5, .n4TagEditor, .n4MoveCategory, .n4MoveCategoryAX5:
            state.libraryPath = [.details(id: n4Moved, source: n4Moved)]
            state.gameSettingsEntry = moved
        case .n4GameCenterGBA:
            state.libraryPath = [.details(id: n4GBA, source: n4GBA)]
            state.gameSettingsEntry = entries.first { $0.id == n4GBA }
        case .n4DetailsMoved:
            state.libraryPath = [.details(id: n4Moved, source: n4Moved)]
        case .n4FilterTag:
            state.libraryTag = "pendiente"
        case .n4SettingsHome, .n4SettingsHomeAX5:
            state.selectedTab = .settings
            state.settingsPath = [.library, .libraryHome]
            var prefs = state.libraryPrefs.data
            prefs.home = HomeSettings().pinning("Pruebas", true).hiding("Game Boy Advance", true)
            state.libraryPrefs.applyDemo(prefs)
        default:
            break
        }
    }

    /// Las huellas que la demostración da por conocidas (por ruta o en el escaneo) pasan a confirmadas.
    private static func confirmDemoFingerprints(_ state: AppState) {
        let hints = state.libraryPrefs.data.fingerprints
        var entries = state.library.entries
        guard !entries.isEmpty else { return }
        for i in entries.indices {
            if entries[i].fingerprint == nil { entries[i].fingerprint = hints[entries[i].id] }
            entries[i].fingerprintVerified = entries[i].fingerprint != nil
        }
        LibraryIdentity.markDuplicates(&entries)
        state.library.applyDemo(phase: state.library.phase, entries: entries)
    }

    private static func applyN4Library(to state: AppState) {
        func make(_ path: String, _ title: String, color: Bool, fingerprint: String, size: Int = 65_536,
                  saved: Bool = false) -> RomEntry {
            let file = (path as NSString).lastPathComponent
            var e = RomEntry(id: path, url: URL(fileURLWithPath: "/demo/\(path)"), fileName: file, title: title,
                             isColor: color, sizeBytes: size, headerChecksumOK: true, cloud: .current, problem: nil,
                             mirrorSaveDate: saved ? Date(timeIntervalSince1970: 1_790_000_000) : nil)
            e.fingerprint = fingerprint
            e.fingerprintVerified = true
            return e
        }
        let entries = [
            make("Acid/dmg-acid2.gb", "DMG-ACID2", color: false, fingerprint: "demo-dmg-acid2", size: 32_768, saved: true),
            make("Acid/cgb-acid2.gbc", "CGB-ACID2", color: true, fingerprint: "demo-cgb-acid2", size: 32_768),
            make("Pruebas/Blargg/cpu_instrs.gb", "CPU_INSTRS", color: false, fingerprint: "demo-cpu"),
            make("Pruebas/Blargg/instr_timing.gb", "INSTR_TIMING", color: false, fingerprint: "demo-instr"),
            make("Pruebas/Blargg/Sonido/dmg_sound.gb", "DMG_SOUND", color: false, fingerprint: "demo-sound"),
            make("Pruebas/Mooneye/halt_ime1_timing.gb", "HALT_IME1", color: false, fingerprint: "demo-halt"),
            make("Pruebas/Mooneye/boot_regs-cgb.gbc", "BOOT_REGS", color: true, fingerprint: "demo-boot"),
            make(n4GBA, "jsmolka ARM", color: false, fingerprint: "demo-arm", size: 8_806),
            make("Game Boy Advance/thumb.gba", "jsmolka THUMB", color: false, fingerprint: "demo-thumb", size: 8_806),
            make(n4Moved, "RTC3TEST", color: false, fingerprint: "demo-rtc", size: 32_768),
            make("homebrew-con-un-titulo-muy-largo.gbc", "Un homebrew con un título muy largo para probar el truncado",
                 color: true, fingerprint: "demo-homebrew"),
        ]
        state.library.applyDemo(phase: .ready(folderName: "Juegos Game Boy"), entries: entries)
        var prefs = LibraryPreferencesData()
        func meta(favorite: Bool = false, played: TimeInterval? = nil, path: String? = nil, tags: [String] = [],
                  virtual: [String]? = nil) -> GameMetadata {
            var m = GameMetadata()
            m.favorite = favorite
            m.lastPlayed = played.map { Date(timeIntervalSince1970: $0) }
            m.lastPlayedPath = played == nil ? nil : path
            m.tags = Tags.sanitized(tags)
            m.virtualFolder = virtual
            return m
        }
        prefs.games = [
            "demo-dmg-acid2": meta(played: 1_790_500_000, path: "Acid/dmg-acid2.gb", tags: ["vídeo"]),
            "demo-cgb-acid2": meta(favorite: true, tags: ["vídeo", "color"]),
            "demo-cpu": meta(played: 1_790_300_000, path: "Pruebas/Blargg/cpu_instrs.gb", tags: ["cpu", "pendiente"]),
            "demo-sound": meta(tags: ["sonido"]),
            "demo-arm": meta(favorite: true, played: 1_790_100_000, path: n4GBA),
            "demo-rtc": meta(favorite: true, tags: ["reloj", "pendiente"], virtual: ["Para jugar"]),
        ]
        prefs.layout = .grid
        state.libraryPrefs.applyDemo(prefs)
        state.artwork.applyDemo(fingerprint: "demo-dmg-acid2")
        state.artwork.applyDemo(fingerprint: "demo-arm", pixels: GameArtworkStore.demoGBAPixels())
    }
}
// MARK: - N6 (iOS): momentos y progreso

extension DebugScreenRouter {
    nonisolated static let n6Pokemon = "Homebrew/lector-pokemon-sintetico.gb"
    nonisolated(unsafe) private static var demoMomentRoot: URL?

    /// `-momentsOpen load|new|edit|install|filter`: lo que la pantalla de Momentos abre al aparecer (capturas).
    static var momentsOpen: String? { DebugArguments.value("-momentsOpen") }

    /// `-demoMoments rich`: momentos de demostración en una carpeta temporal por arranque (cualquier huella):
    /// tres momentos en dos colecciones con etiquetas y nota, uno sin colección, una ranura migrada y dos entradas
    /// en «Antes de cargar». Estados de mentira («PGBS-demo»: no cargan) y miniaturas pintadas en código.
    static func demoMomentStore(fingerprint: String) -> MomentStore? {
        guard DebugArguments.value("-demoMoments") == "rich" else { return nil }
        if demoMomentRoot == nil {
            demoMomentRoot = FileManager.default.temporaryDirectory
                .appendingPathComponent("demo-moments-\(UUID().uuidString)", isDirectory: true)
        }
        guard let root = demoMomentRoot else { return nil }
        var store = MomentStore(root: root, fingerprint: fingerprint)
        if FileManager.default.fileExists(atPath: store.directory.path) { return store }
        let base = GameArtworkStore.demoPixels()
        func thumb(_ shift: Int) -> Data? {
            var pixels = base
            for y in 0..<FrameBuffers.height {
                for x in 0..<FrameBuffers.width {
                    pixels[y * FrameBuffers.width + x] = base[y * FrameBuffers.width + (x + shift) % FrameBuffers.width]
                }
            }
            return AppState.thumbnail(pixels)
        }
        let state = Data("PGBS-demo".utf8)
        let sram = Data(repeating: 0x5A, count: 8_192)
        let gb = ["console": "gb", "model": "dmg", "palette": "0"]
        let items: [(String, String, [String], String?, String, TimeInterval, TimeInterval, Int, [String: String])] = [
            ("m1", "Antes del jefe", ["jefe"], "Principal", "Con 3 pociones y el nivel justo.", 1_790_600_000, 4 * 3600 + 300, 20, gb),
            ("m2", "Probar otro camino", ["experimento", "jefe"], "Experimentos", "", 1_790_500_000, 3 * 3600, 60, gb),
            ("m3", "Con la otra paleta", ["experimento"], "Experimentos", "", 1_790_400_000, 2 * 3600 + 900, 100,
             ["console": "gb", "model": "cgb", "palette": "3"]),
            ("m4", "Entrada a la cueva", [], nil, "", 1_790_300_000, 3600, 140, gb),
        ]
        for item in items {
            store.newID = { item.0 }
            _ = try? store.create(.init(state: state, sram: sram, thumbnail: thumb(item.7)), name: item.1, config: item.8,
                                  playTime: item.6, tags: item.2, collection: item.3, note: item.4,
                                  created: Date(timeIntervalSince1970: item.5))
        }
        let ring: [(String, String, TimeInterval, Bool)] = [("b1", "Antes de cargar «Antes del jefe»", 1_790_700_000, true),
                                                           ("b2", "Antes de recuperar", 1_790_650_000, false)]
        for (i, entry) in ring.enumerated() {
            store.newID = { entry.0 }
            store.now = { Date(timeIntervalSince1970: entry.2) }
            _ = try? store.pushBeforeLoad(.init(state: entry.3 ? state : nil, sram: sram, thumbnail: entry.3 ? thumb(180 + i) : nil),
                                          label: entry.1, config: gb)
        }
        store.newID = { "m5" }
        _ = try? store.create(.init(state: state, sram: nil, thumbnail: nil), name: "Ranura 1",
                              created: Date(timeIntervalSince1970: 1_790_100_000))
        return MomentStore(root: root, fingerprint: fingerprint)
    }

    /// Partida sintética de 1.ª generación construida byte a byte (nunca una real): nombre «PRUEBA», 3 medallas,
    /// 24 capturados, 40 vistos, 12 h 34 min, 3.456 ₽. Offsets de docs/03-core-spec.md §Lector de progreso Pokémon.
    static func syntheticGen1Save() -> Data {
        var sav = Data(repeating: 0, count: 0x8000)
        let name: [UInt8] = [0x8F, 0x91, 0x94, 0x84, 0x81, 0x80, 0x50]   // PRUEBA + fin
        sav.replaceSubrange(0x2598..<(0x2598 + name.count), with: name)
        func bits(_ at: Int, _ n: Int) { for i in 0..<n { sav[at + i / 8] |= UInt8(1 << (i % 8)) } }
        bits(0x25A3, 24)
        bits(0x25B6, 40)
        sav[0x25F3] = 0x00; sav[0x25F4] = 0x34; sav[0x25F5] = 0x56
        sav[0x2602] = 0b0000_0111
        sav[0x2CED] = 12; sav[0x2CEF] = 34; sav[0x2CF0] = 5
        var sum: UInt8 = 0
        for i in 0x2598..<0x3523 { sum &+= sav[i] }
        sav[0x3523] = ~sum
        return sav
    }

    static func syntheticGen1Header() -> Data {
        var h = Data(repeating: 0, count: ProgressLibrary.headerBytes)
        let title = Array("POKEMON RED".utf8)
        h.replaceSubrange(0x134..<(0x134 + title.count), with: title)
        h[0x14A] = 1
        return h
    }

    /// `-demoMoments rich` en el detalle: partidas de demostración (la del lector, sintética) en una carpeta temporal.
    static let demoN6Saves: URL? = {
        guard DebugArguments.value("-demoMoments") == "rich", DebugArguments.demoLibrary == "n5" else { return nil }
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("demo-n6-saves-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try? SaveStore(directory: dir, fingerprint: "demo-pokemon").save(syntheticGen1Save())
        try? SaveStore(directory: dir, fingerprint: "demo-dmg-acid2").save(Data(repeating: 0x5A, count: 8_192))
        return dir
    }()

    /// N7a · `-demoConflicts`: copias en conflicto de mentira en Ajustes › Partidas (no hay archivos).
    static let demoConflictCopies: [(title: String, url: URL)]? = {
        guard DebugArguments.arguments.contains("-demoConflicts") else { return nil }
        return [("Pokémon Rojo", URL(fileURLWithPath: "/demo/Pokemon Rojo 2.sav")),
                ("Pokémon Rojo", URL(fileURLWithPath: "/demo/Pokemon Rojo.sync-conflict-20261008-101010-ABCDEFG.sav")),
                ("Tetris", URL(fileURLWithPath: "/demo/Tetris (1).sav"))]
    }()

    static func applyN6(_ screen: DebugScreen, to state: AppState) {
        guard DebugArguments.demoLibrary == "n5", DebugArguments.value("-demoMoments") == "rich" else { return }
        var entries = state.library.entries
        var e = RomEntry(id: n6Pokemon, url: URL(fileURLWithPath: "/demo/\(n6Pokemon)"), fileName: "lector-pokemon-sintetico.gb",
                         title: "LECTOR (SINTÉTICO)", isColor: false, sizeBytes: 32_768, headerChecksumOK: true,
                         cloud: .current, problem: nil, mirrorSaveDate: Date(timeIntervalSince1970: 1_790_000_000))
        e.fingerprint = "demo-pokemon"
        e.fingerprintVerified = true
        entries.append(e)
        state.library.applyDemo(phase: state.library.phase, entries: entries)
        var dmg = GameProgress()
        dmg.playTime = 4 * 3600 + 25 * 60
        dmg.sessions = 7
        dmg.firstPlayed = Date(timeIntervalSince1970: 1_789_000_000)
        dmg.lastPlayed = Date(timeIntervalSince1970: 1_790_600_000)
        dmg.template = .free
        dmg.milestones = [Milestone(id: "h1", title: "Primer jefe", done: true),
                          Milestone(id: "h2", title: "Segundo mundo", done: true),
                          Milestone(id: "h3", title: "Final"), Milestone(id: "h4", title: "Todo al 100 %")]
        dmg.showPercent = true
        state.progress.seed("demo-dmg-acid2", dmg)
        var poke = GameProgress()
        poke.playTime = 12 * 3600 + 40 * 60
        poke.sessions = 15
        poke.firstPlayed = Date(timeIntervalSince1970: 1_788_000_000)
        poke.lastPlayed = Date(timeIntervalSince1970: 1_790_650_000)
        poke.template = .pokemon
        poke.milestones = MilestoneTemplate.pokemon.titles.enumerated().map {
            Milestone(id: "p\($0.offset)", title: $0.element, done: $0.offset == 0)
        }
        poke.romHeader = syntheticGen1Header()
        state.progress.seed("demo-pokemon", poke)

        let imported = state.library.entries.first { $0.id == "Acid/dmg-acid2.gb" }
        let pokemon = state.library.entries.first { $0.id == n6Pokemon }
        switch screen {
        case .n6MomentsDetail, .n6MomentsAX5, .n6MomentEdit, .n6MomentsDetailFilter, .n6MomentsDetailInstall,
             .n6MomentsDetailLoad:
            state.libraryPath = [.details(id: "Acid/dmg-acid2.gb", source: "Acid/dmg-acid2.gb")]
            if let imported { state.showGameCenter(imported, at: .moments(fingerprint: "demo-dmg-acid2")) }
        case .n6LibraryPercent:
            state.libraryFilter = .gb   // cuadrícula con tarjetas (el porcentaje va en su línea de metadatos)
        case .n6ProgressDetails:
            state.libraryPath = [.details(id: n6Pokemon, source: n6Pokemon)]
        case .n6ProgressEditor, .n6ProgressAX5:
            state.libraryPath = [.details(id: n6Pokemon, source: n6Pokemon)]
            if let pokemon { state.showGameCenter(pokemon, at: .progress(fingerprint: "demo-pokemon")) }
        default:
            break
        }
    }
}

// MARK: - N7 (iOS): partidas que viajan

extension DebugScreenRouter {
    /// Pantallas de N7 sobre la biblioteca `-demoLibrary n5` (juego «Acid/dmg-acid2.gb», huella de demostración). Sin
    /// archivos de partida reales: el estado y las preguntas se siembran en memoria.
    static func applyN7(_ screen: DebugScreen, to state: AppState) {
        let id = "Acid/dmg-acid2.gb", fp = "demo-dmg-acid2"
        let entry = state.library.entries.first { $0.id == id }
        let plan = SaveImport.Plan(fingerprint: fp, save: Data(count: 8_192), incoming: Data(count: 8_192),
                                   relation: .divergent(baseKnown: true), continuation: nil, thumbnail: nil,
                                   meta: PackageMeta(romSHA256: String(repeating: "0", count: 64),
                                                     savSHA256: String(repeating: "0", count: 64), baseSavSHA256: nil,
                                                     platform: "android", deviceName: "Pixel 8", createdMs: 0,
                                                     coreName: "gb", coreVersion: nil, stateOfSavSHA256: nil),
                                   raw: false, batteryless: false)
        switch screen {
        case .n7DetailSave, .n7DetailSaveAX5:
            state.libraryPath = [.details(id: id, source: id)]
            state.saveStatuses[fp] = SaveStatus(device: "Pixel 8", date: Date().addingTimeInterval(-2 * 3600),
                                                continuesFromOtherDevice: true)
        case .n7Divergence:
            state.libraryPath = [.details(id: id, source: id)]
            if let entry {
                state.divergencePrompt = DivergencePrompt(entry: entry, mode: .resumeAutomatic,
                                                          localDate: Date(timeIntervalSince1970: 1_790_600_000),
                                                          otherDate: Date(timeIntervalSince1970: 1_790_650_000))
            }
        case .n7ImportPrompt:
            state.libraryPath = [.details(id: id, source: id)]
            if let entry { state.importPrompt = ImportPrompt(entry: entry, plan: plan) }
        case .n7ImportDone:
            state.libraryPath = [.details(id: id, source: id)]
            state.notify("Partida importada", "Se ha instalado la partida de Pixel 8. La anterior quedó en «Antes de cargar» y en las copias de seguridad. Puedes continuar justo donde lo dejaste.")
        case .n7SettingsConflicts:
            state.selectedTab = .settings
            state.settingsPath = [.saves]
        default:
            break
        }
    }
}

// MARK: - N5 (iOS): portadas

extension DebugScreenRouter {
    nonisolated private static let n5Imported = "Acid/dmg-acid2.gb"
    nonisolated private static let n5Capture = "Acid/cgb-acid2.gbc"
    nonisolated private static let n5Folder = "Reloj/rtc3test.gb"
    nonisolated private static let n5None = "Pruebas/cpu_instrs.gb"
    nonisolated private static let n5Chosen = "Pruebas/instr_timing.gb"
    nonisolated private static let n5GBA = "Game Boy Advance/arm.gba"

    /// `-demoLibrary n5`: juegos libres de las ROMs de prueba con cada fuente de portada. Las imágenes se
    /// pintan en código (nada con copyright y ningún binario en el repo):
    /// - dmg-acid2: imagen importada vertical (3:4) y elección «Imagen»; jugado.
    /// - cgb-acid2: captura (y, en `n5-cover-capture`, fijada); jugado.
    /// - rtc3test: imagen de la carpeta apaisada (2:1), único juego de `Reloj/`.
    /// - cpu_instrs: sin ninguna fuente; jugado y reanudable: sale en «Continuar jugando» con la generada
    ///   (decisión de Joel 2026-10-07, sustituye a K10).
    /// - instr_timing: tiene imagen de la carpeta pero eligió «Generada».
    /// - arm.gba: imagen de la carpeta y captura de 240×160 en «Automática» (con «Preferir capturas» se ve
    ///   la captura); jugado.
    static func applyN5(_ screen: DebugScreen, to state: AppState) {
        guard DebugArguments.demoLibrary == "n5" else { return }
        func make(_ path: String, _ title: String, color: Bool, fingerprint: String, cover: String? = nil,
                  saved: Bool = false) -> RomEntry {
            let file = (path as NSString).lastPathComponent
            var e = RomEntry(id: path, url: URL(fileURLWithPath: "/demo/\(path)"), fileName: file, title: title,
                             isColor: color, sizeBytes: 32_768, headerChecksumOK: true, cloud: .current, problem: nil,
                             mirrorSaveDate: saved ? Date(timeIntervalSince1970: 1_790_000_000) : nil)
            e.fingerprint = fingerprint
            e.fingerprintVerified = true
            if let cover {
                e.coverURL = URL(fileURLWithPath: "/demo/\(cover)")
                e.coverStamp = "\(cover)|1|0"
            }
            return e
        }
        let entries = [
            make(n5Imported, "DMG-ACID2", color: false, fingerprint: "demo-dmg-acid2", saved: true),
            make(n5Capture, "CGB-ACID2", color: true, fingerprint: "demo-cgb-acid2"),
            make(n5Folder, "RTC3TEST", color: false, fingerprint: "demo-rtc", cover: "Reloj/portada.png"),
            make(n5None, "CPU_INSTRS", color: false, fingerprint: "demo-cpu"),
            make(n5Chosen, "INSTR_TIMING", color: false, fingerprint: "demo-instr", cover: "Pruebas/instr_timing.jpg"),
            make(n5GBA, "jsmolka ARM", color: false, fingerprint: "demo-arm", cover: "Game Boy Advance/arm.webp"),
            make("homebrew-con-un-titulo-muy-largo.gbc", "Un homebrew con un título muy largo para probar el truncado",
                 color: true, fingerprint: "demo-homebrew"),
        ]
        state.library.applyDemo(phase: .ready(folderName: "Juegos Game Boy"), entries: entries)
        var prefs = LibraryPreferencesData()
        func played(_ time: TimeInterval, _ path: String, favorite: Bool = false) -> GameMetadata {
            var m = GameMetadata()
            m.lastPlayed = Date(timeIntervalSince1970: time)
            m.lastPlayedPath = path
            m.favorite = favorite
            return m
        }
        var favorite = GameMetadata()
        favorite.favorite = true
        prefs.games = [
            "demo-dmg-acid2": played(1_790_500_000, n5Imported, favorite: true),
            "demo-cgb-acid2": played(1_790_400_000, n5Capture, favorite: true),
            "demo-cpu": played(1_790_300_000, n5None, favorite: true),
            "demo-arm": played(1_790_200_000, n5GBA),
            "demo-instr": favorite,
            "demo-rtc": favorite,
        ]
        prefs.layout = screen == .n5LibraryList ? .list : .grid
        if screen == .n5LibraryList { prefs.home.showFavorites = false }
        state.libraryPrefs.applyDemo(prefs)
        if screen == .n5LibraryList { state.libraryFilter = .favorites }

        let covers = state.covers
        covers.applyDemo(imported: demoPoster(), fingerprint: "demo-dmg-acid2")
        state.artwork.applyDemo(fingerprint: "demo-cgb-acid2")
        state.artwork.applyDemo(fingerprint: "demo-arm", pixels: GameArtworkStore.demoGBAPixels())
        for entry in entries where entry.coverURL != nil {
            covers.applyDemo(folder: demoLandscape(seed: entry.id == n5GBA ? 1 : 0), entry: entry)
        }
        var settings = CoverSettings()
        settings = settings.with(.image, for: "demo-dmg-acid2").with(.generated, for: "demo-instr")
        if screen == .n5LibraryPreferCaptures { settings.preference = .captures }
        if screen == .n5CoverCapture {
            state.covers.pinned.applyDemo(fingerprint: "demo-cgb-acid2", pixels: GameArtworkStore.demoWhitePixels())
            settings = settings.with(.capture, for: "demo-cgb-acid2")
        }
        covers.applyDemo(settings: settings)

        func details(_ id: String) { state.libraryPath = [.details(id: id, source: id)] }
        switch screen {
        case .n5DetailsImported: details(n5Imported)
        case .n5DetailsFolder: details(n5Folder)
        case .n5DetailsCapture: details(n5Capture)
        case .n5DetailsGenerated: details(n5None)
        case .n5DetailsGBA: details(n5GBA)
        case .n5GameCenter, .n5Cover, .n5CoverFailed, .n5CoverAX5:
            details(n5Imported)
            state.gameSettingsEntry = entries.first { $0.id == n5Imported }
        case .n5CoverCapture:
            details(n5Capture)
            state.gameSettingsEntry = entries.first { $0.id == n5Capture }
        case .n5SettingsLibrary:
            state.selectedTab = .settings
            state.settingsPath = [.library]
        case .n5SettingsStorage:
            state.selectedTab = .settings
            state.settingsPath = [.storage]
        default:
            break
        }
    }

    /// Póster vertical 3:4 pintado en código: degradado, sol y montañas (arte abstracto propio).
    static func demoPoster() -> UIImage {
        let size = CGSize(width: 600, height: 800)
        return UIGraphicsImageRenderer(size: size).image { ctx in
            let cg = ctx.cgContext
            let colors = [UIColor(red: 0.18, green: 0.10, blue: 0.42, alpha: 1).cgColor,
                          UIColor(red: 0.95, green: 0.42, blue: 0.36, alpha: 1).cgColor] as CFArray
            if let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors, locations: [0, 1]) {
                cg.drawLinearGradient(gradient, start: .zero, end: CGPoint(x: 0, y: size.height), options: [])
            }
            UIColor(red: 1, green: 0.85, blue: 0.4, alpha: 1).setFill()
            cg.fillEllipse(in: CGRect(x: 180, y: 220, width: 240, height: 240))
            UIColor(red: 0.12, green: 0.08, blue: 0.25, alpha: 1).setFill()
            let hills = UIBezierPath()
            hills.move(to: CGPoint(x: 0, y: 800))
            hills.addLine(to: CGPoint(x: 0, y: 560))
            hills.addLine(to: CGPoint(x: 160, y: 430))
            hills.addLine(to: CGPoint(x: 300, y: 580))
            hills.addLine(to: CGPoint(x: 450, y: 400))
            hills.addLine(to: CGPoint(x: 600, y: 540))
            hills.addLine(to: CGPoint(x: 600, y: 800))
            hills.fill()
            UIColor.white.withAlphaComponent(0.9).setFill()
            cg.fill(CGRect(x: 60, y: 60, width: 480, height: 14))
            cg.fill(CGRect(x: 60, y: 90, width: 300, height: 14))
        }
    }

    /// Imagen apaisada 2:1 pintada en código: franjas y círculos (arte abstracto propio).
    static func demoLandscape(seed: Int) -> UIImage {
        let size = CGSize(width: 1200, height: 600)
        let palettes: [[UIColor]] = [
            [.systemTeal, .systemBlue, .systemIndigo, .systemPurple],
            [.systemYellow, .systemOrange, .systemRed, .systemPink],
        ]
        let palette = palettes[seed % palettes.count]
        return UIGraphicsImageRenderer(size: size).image { ctx in
            let cg = ctx.cgContext
            for (i, color) in palette.enumerated() {
                color.setFill()
                cg.fill(CGRect(x: 0, y: CGFloat(i) * 150, width: size.width, height: 150))
            }
            UIColor.white.withAlphaComponent(0.85).setFill()
            for i in 0..<5 {
                cg.fillEllipse(in: CGRect(x: 80 + CGFloat(i) * 220, y: 220, width: 160, height: 160))
            }
            UIColor.black.withAlphaComponent(0.6).setFill()
            cg.fill(CGRect(x: 0, y: 0, width: 24, height: size.height))
            cg.fill(CGRect(x: size.width - 24, y: 0, width: 24, height: size.height))
        }
    }
}
#endif

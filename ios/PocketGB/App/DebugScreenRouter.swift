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
        default:
            state.selectedTab = .library
        }
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

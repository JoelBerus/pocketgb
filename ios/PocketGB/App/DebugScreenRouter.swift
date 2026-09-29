#if DEBUG
import Foundation

/// Pantallas del catálogo que la app sabe abrir directamente (SPEC §9). Cada hito
/// de diseño añade aquí las suyas y sus líneas en ios/PocketGBUITests/screens.txt.
enum DebugScreen: String, CaseIterable {
    case launch
    case libraryNoFolder = "library-no-folder"
    case libraryEmpty = "library-empty"
    case settingsMain = "settings-main"
    case settingsAppearance = "settings-appearance"
    case settingsAbout = "settings-about"
}

/// Traduce `-screen <id>` y los `-demo*` a estado de la app, sin tocar disco ni red.
/// Un id desconocido se muestra como error visible (ScreenshotTests lo detecta).
@MainActor
enum DebugScreenRouter {
    static func apply(to state: AppState) {
        applyDemoData(to: state)
        guard let raw = DebugArguments.screen else { return }
        guard let screen = DebugScreen(rawValue: raw) else {
            state.debugUnknownScreen = raw
            return
        }
        switch screen {
        case .launch:
            state.debugShowsLaunch = true
        case .libraryNoFolder, .libraryEmpty:
            state.selectedTab = .library
        case .settingsMain:
            state.selectedTab = .settings
        case .settingsAppearance:
            state.selectedTab = .settings
            state.settingsPath = [.appearance]
        case .settingsAbout:
            state.selectedTab = .settings
            state.settingsPath = [.about]
        }
    }

    /// Biblioteca de demostración en memoria (D1: sin carpeta o carpeta vacía).
    private static func applyDemoData(to state: AppState) {
        if DebugArguments.demoFolderState == "none" {
            state.library = .noFolder
        }
        if DebugArguments.demoLibrary == "empty" {
            state.library = .empty(folderName: "Juegos Game Boy")
        }
    }
}
#endif

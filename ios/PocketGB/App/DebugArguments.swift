#if DEBUG
import SwiftUI

/// Argumentos de arranque solo para pruebas y capturas (ios/README.md,
/// docs/hitos/D-README.md §2.5). No existen en Release y no escriben UserDefaults.
enum DebugArguments {
    static var arguments: [String] { ProcessInfo.processInfo.arguments }

    /// Valor que sigue a `flag` (`-screen launch` → "launch").
    static func value(_ flag: String) -> String? {
        let args = arguments
        guard let i = args.firstIndex(of: flag), i + 1 < args.count else { return nil }
        return args[i + 1]
    }

    static var debugHUD: Bool { arguments.contains("-debugHUD") }

    /// `-uiStyle light|dark` fuerza la apariencia.
    static var colorScheme: ColorScheme? {
        switch value("-uiStyle") {
        case "light": return .light
        case "dark": return .dark
        default: return nil
        }
    }

    /// `-screen <id>`: pantalla del catálogo (docs/diseno/SPEC.md §9).
    static var screen: String? { value("-screen") }
    /// `-demoFolderState none|available|stale|denied`.
    static var demoFolderState: String? { value("-demoFolderState") }
    /// `-demoLibrary empty|standard|cloud|errors`.
    static var demoLibrary: String? { value("-demoLibrary") }
}
#endif

import SwiftUI

/// Card de la cuadrícula (SPEC §8, `GameCard`): portada, título y una línea de metadatos.
/// Contenido L1: sin vidrio ni contenedor detrás de la portada.
struct GameCard: View {
    @Environment(AppState.self) private var state
    @Environment(\.dynamicTypeSize) private var typeSize
    let entry: RomEntry
    let zoom: Namespace.ID

    var body: some View {
        VStack(alignment: .leading, spacing: PocketSpacing.xs) {
            GameArtworkView(entry: entry)
                .opacity(entry.problem == nil ? 1 : 0.45)
                .overlay(alignment: .topTrailing) { statusBadge }
                .matchedTransitionSource(id: entry.id, in: zoom)
            Text(state.libraryPrefs.displayTitle(entry))
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(entry.problem == nil ? .primary : .secondary)
                .lineLimit(typeSize.isAccessibilitySize ? nil : 2)
                .multilineTextAlignment(.leading)
                .frame(maxWidth: .infinity, alignment: .leading)
            // Chip, favorito y "Nuevo" en su propia línea: un título largo no los solapa.
            GameMetaLine(entry: entry)
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(GameAccessibility.label(entry, prefs: state.libraryPrefs))
        .accessibilityHint(GameAccessibility.hint(entry))
    }

    /// Estado que impide jugar ya: nube o error. Símbolo sobre fondo oscuro fijo para
    /// que se lea sobre cualquier portada.
    @ViewBuilder private var statusBadge: some View {
        if let symbol = GameStatus.symbol(entry) {
            Group {
                if entry.cloud == .downloading && entry.problem == nil {
                    ProgressView().tint(.white)
                } else {
                    Image(systemName: symbol).font(.subheadline.weight(.semibold))
                }
            }
            .foregroundStyle(.white)
            .frame(width: 32, height: 32)
            .background(PocketColor.controlScrim.opacity(0.55), in: Circle())
            .padding(PocketSpacing.xs)
        }
    }
}

/// Chip GB/GBC, favorito, "Nuevo" y la última partida o el estado.
struct GameMetaLine: View {
    @Environment(AppState.self) private var state
    let entry: RomEntry

    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        // Con tamaños de accesibilidad, en columna: nada se trunca (SPEC §13).
        let layout = typeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: PocketSpacing.xxs))
            : AnyLayout(HStackLayout(spacing: PocketSpacing.xs))
        layout {
            ConsoleChip(isColor: entry.isColor)
            if state.libraryPrefs.isFavorite(entry) {
                Image(systemName: "star.fill")
                    .font(.caption)
                    .foregroundStyle(PocketColor.controlAWarm)
                    .accessibilityLabel("Favorito")
            }
            if entry.isNew {
                Text("Nuevo")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(PocketColor.accent)
            }
            Text(GameStatus.detail(entry, lastPlayed: state.libraryPrefs.lastPlayed(entry)))
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(typeSize.isAccessibilitySize ? 3 : 1)
        }
    }
}

/// Textos de estado comunes a card, fila y detalle.
enum GameStatus {
    static func symbol(_ entry: RomEntry) -> String? {
        if entry.problem != nil { return "exclamationmark.triangle.fill" }
        switch entry.cloud {
        case .notDownloaded: return "icloud.and.arrow.down"
        case .downloading: return "arrow.down.circle"
        case .current: return nil
        }
    }

    /// Una línea corta: motivo, estado de iCloud o cuándo se jugó.
    static func detail(_ entry: RomEntry, lastPlayed: Date?) -> String {
        if entry.problem != nil { return "No se puede abrir" }
        switch entry.cloud {
        case .notDownloaded: return "En iCloud"
        case .downloading: return "Descargando…"
        case .current:
            if let lastPlayed { return relative(lastPlayed) }
            if let saved = entry.mirrorSaveDate { return "Partida \(relative(saved))" }
            return "Sin jugar"
        }
    }

    /// Fecha y hora de un save state ("30 sept, 14:05"; fija en las capturas).
    static func stateDate(_ date: Date) -> String {
        var style = Date.FormatStyle.dateTime.day().month(.abbreviated).hour().minute()
        #if DEBUG
        if DebugArguments.screen != nil {
            style = style.locale(Locale(identifier: "es_ES"))
            style.timeZone = TimeZone(identifier: "UTC") ?? .current
        }
        #endif
        return date.formatted(style)
    }

    static func relative(_ date: Date) -> String {
        #if DEBUG
        // Capturas deterministas: fecha absoluta en lugar de "hace 2 h".
        if DebugArguments.screen != nil {
            return date.formatted(.dateTime.day().month(.abbreviated).locale(Locale(identifier: "es_ES")))
        }
        #endif
        return date.formatted(.relative(presentation: .named))
    }
}

/// VoiceOver: título, sistema, favorito, estado de iCloud y última partida en un solo
/// elemento (SPEC §13).
enum GameAccessibility {
    @MainActor
    static func label(_ entry: RomEntry, prefs: LibraryPreferences) -> String {
        var parts = [prefs.displayTitle(entry), entry.isColor ? "Game Boy Color" : "Game Boy"]
        if prefs.isFavorite(entry) { parts.append("Favorito") }
        if entry.isNew { parts.append("Nuevo") }
        if let problem = entry.problem {
            parts.append(problem.message)
        } else {
            switch entry.cloud {
            case .notDownloaded: parts.append("Solo en iCloud")
            case .downloading: parts.append("Descargando")
            case .current:
                if let date = prefs.lastPlayed(entry) { parts.append("Jugado \(GameStatus.relative(date))") }
            }
        }
        return parts.joined(separator: ", ")
    }

    static func hint(_ entry: RomEntry) -> String {
        if entry.problem != nil { return "Muestra por qué no se puede abrir" }
        switch entry.cloud {
        case .notDownloaded: return "Descarga el juego de iCloud"
        case .downloading: return "Espera a que termine la descarga"
        case .current: return "Muestra el detalle del juego"
        }
    }
}

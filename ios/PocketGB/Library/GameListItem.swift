import SwiftUI

/// Fila de la lista (SPEC §8, `GameListItem`): miniatura, título, sistema y última
/// partida o estado. Contenido L1: sin vidrio.
struct GameListItem: View {
    @Environment(AppState.self) private var state
    let entry: RomEntry
    var zoom: Namespace.ID?

    var body: some View {
        HStack(spacing: PocketSpacing.sm) {
            thumbnail
            VStack(alignment: .leading, spacing: PocketSpacing.xxs) {
                Text(state.libraryPrefs.displayTitle(entry))
                    .font(.body.weight(.semibold))
                    .foregroundStyle(entry.problem == nil ? .primary : .secondary)
                    .lineLimit(2)
                if let problem = entry.problem {
                    // El motivo completo en la fila: el error se entiende sin tocarlo.
                    Text(problem.message)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                } else {
                    GameMetaLine(entry: entry)
                }
            }
            Spacer(minLength: PocketSpacing.xs)
            trailing
        }
        .padding(.vertical, PocketSpacing.xs)
        .frame(minHeight: PocketSpacing.minTouch)
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(GameAccessibility.label(entry, prefs: state.libraryPrefs))
        .accessibilityHint(GameAccessibility.hint(entry))
    }

    @ViewBuilder private var thumbnail: some View {
        let art = GameArtworkView(entry: entry, cornerRadius: PocketRadius.thumbnail, compact: true)
            .frame(width: 56)
            .opacity(entry.problem == nil ? 1 : 0.45)
        if let zoom {
            art.matchedTransitionSource(id: entry.id, in: zoom)
        } else {
            art
        }
    }

    @ViewBuilder private var trailing: some View {
        if entry.problem != nil {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(PocketColor.danger)
        } else {
            switch entry.cloud {
            case .notDownloaded:
                Image(systemName: "icloud.and.arrow.down")
                    .foregroundStyle(PocketColor.accent)
            case .downloading:
                ProgressView()
            case .current:
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
        }
    }
}

/// Chip GB/GBC/GBA (SPEC §8, `ConsoleChip`): texto, nunca solo color.
struct ConsoleChip: View {
    let badge: ConsoleBadge

    var body: some View {
        Text(badge.label)
            .font(.caption2.monospaced().weight(.semibold))
            .frame(minWidth: 34)
            .padding(.vertical, 2)
            .overlay(Capsule().strokeBorder(.secondary.opacity(0.6), lineWidth: 1))
            .accessibilityLabel(badge.name)
    }
}

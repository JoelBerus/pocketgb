import SwiftUI
import UIKit

/// Tarjeta de una ranura (SPEC §8, `SaveStateCard`): captura, nombre, fecha y selección.
/// Contenido L1: sin vidrio. Un estado dañado se ve como error hasta que se borre.
struct SaveStateCard: View {
    let slot: StateSlot
    let entry: StateStore.Entry?
    let selected: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: PocketSpacing.xxs) {
            preview
                .aspectRatio(10.0 / 9.0, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: PocketRadius.saveStatePreview, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: PocketRadius.saveStatePreview, style: .continuous)
                        .strokeBorder(selected ? PocketColor.accent : .white.opacity(0.12),
                                      lineWidth: selected ? 3 : 1)
                }
                .overlay(alignment: .topTrailing) {
                    if selected {
                        Image(systemName: "checkmark.circle.fill")
                            .font(.title3)
                            .foregroundStyle(.white, PocketColor.accent)
                            .padding(PocketSpacing.xs)
                            .accessibilityHidden(true)
                    }
                }
            HStack(spacing: PocketSpacing.xxs) {
                if slot == .auto {
                    Image(systemName: "clock.arrow.circlepath").font(.caption)
                }
                Text(slot.title).font(.subheadline.weight(.semibold))
            }
            Text(subtitle)
                .font(.caption)
                .foregroundStyle(entry?.corrupt == true ? PocketColor.danger : .secondary)
                .lineLimit(1)
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(slot.title), \(slot == .auto ? "automático" : "manual"), \(subtitle)")
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
    }

    private var subtitle: String {
        guard let entry else { return "Vacía" }
        if entry.corrupt { return "Dañado" }
        return GameStatus.stateDate(entry.date)
    }

    @ViewBuilder private var preview: some View {
        if let data = entry?.thumbnail, entry?.corrupt == false, let image = UIImage(data: data) {
            Image(uiImage: image).resizable().interpolation(.none)
        } else {
            ZStack {
                PocketColor.backgroundElevated
                Image(systemName: entry?.corrupt == true ? "exclamationmark.triangle" : "plus.square.dashed")
                    .font(.title2)
                    .foregroundStyle(entry?.corrupt == true ? PocketColor.danger : .secondary)
            }
        }
    }
}

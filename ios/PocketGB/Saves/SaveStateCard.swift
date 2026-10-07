import SwiftUI
import UIKit

/// Tarjeta de una ranura (SPEC §8, `SaveStateCard`): captura, nombre, fecha y selección.
/// Contenido L1: sin vidrio. Un estado dañado se ve como error hasta que se borre.
struct SaveStateCard: View {
    let slot: StateSlot
    let entry: StateStore.Entry?
    let selected: Bool
    /// Consola del juego abierto: da la proporción de una ranura vacía (N3a; 3:2 en GBA).
    var console: Console = .gameBoy

    var body: some View {
        let image = thumbnail
        VStack(alignment: .leading, spacing: PocketSpacing.xxs) {
            Color.clear
                .aspectRatio(ArtworkStyle.aspectRatio(of: image?.size, fallback: console), contentMode: .fit)
                .overlay { preview(image) }
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

    /// La miniatura guardada (240×160 en GBA), salvo en un estado dañado.
    private var thumbnail: UIImage? {
        guard let data = entry?.thumbnail, entry?.corrupt == false else { return nil }
        return UIImage(data: data)
    }

    /// La miniatura con su propia proporción: nunca se estira a 10:9 (N3a).
    @ViewBuilder private func preview(_ image: UIImage?) -> some View {
        if let image {
            Image(uiImage: image).resizable().interpolation(.none).aspectRatio(contentMode: .fill)
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

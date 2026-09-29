import SwiftUI

/// Aviso breve flotante (SPEC §8, `ToastView`): vidrio regular, texto y símbolo.
struct ToastView: View {
    let text: String
    var systemImage = "checkmark.circle"

    var body: some View {
        Label(text, systemImage: systemImage)
            .font(.subheadline.weight(.semibold))
            .padding(.horizontal, PocketSpacing.md)
            .padding(.vertical, PocketSpacing.sm)
            .glassEffect(.regular, in: Capsule())
            .accessibilityElement(children: .combine)
    }
}

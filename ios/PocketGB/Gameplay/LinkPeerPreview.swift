import SwiftUI

/// Miniatura en vivo del otro juego del cable (M9 §2.3). Contenido L1: sin vidrio, borde fino y
/// sin recibir toques. Va en los huecos de L/R, que Game Boy no usa.
struct LinkPeerPreview: View {
    let link: LinkSession
    let width: CGFloat

    var body: some View {
        GameMetalView(frames: link.peerFrames, integerScale: false)
            .aspectRatio(link.peerFrames.size.aspectRatio, contentMode: .fit)
            .frame(width: width)
            .clipShape(RoundedRectangle(cornerRadius: PocketRadius.thumbnail, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: PocketRadius.thumbnail, style: .continuous)
                .strokeBorder(.white.opacity(0.45), lineWidth: 1))
            .allowsHitTesting(false)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Otro juego del cable: \(link.peerTitle)")
            .accessibilityIdentifier("link-peer-preview")
    }
}

import SwiftUI

/// El mismo asset sin texto que usa `UILaunchScreen`; la preview valida el launch real.
struct LaunchGlyphView: View {
    var size: CGFloat = 160

    var body: some View {
        Image("LaunchGlyph")
            .resizable()
            .interpolation(.high)
            .aspectRatio(contentMode: .fit)
            .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

#if DEBUG
/// Composición del launch para capturas (SPEC §9, `launch`): glifo centrado sobre fondo oscuro.
struct LaunchPreviewView: View {
    var body: some View {
        ZStack {
            PocketColor.gameplayBackground.ignoresSafeArea()
            LaunchGlyphView()
        }
    }
}
#endif

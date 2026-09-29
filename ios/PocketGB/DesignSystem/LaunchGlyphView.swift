import SwiftUI

/// Glifo de la app: una pantalla y una cruceta, dibujados con formas (sin texto).
/// Lo usa la preview del launch (`-screen launch`); el launch real es el de Info.plist.
struct LaunchGlyphView: View {
    var size: CGFloat = 96

    var body: some View {
        VStack(spacing: size * 0.12) {
            RoundedRectangle(cornerRadius: size * 0.08, style: .continuous)
                .fill(.white.opacity(0.9))
                .frame(width: size * 0.78, height: size * 0.7)
            CrossShape()
                .fill(.white.opacity(0.9))
                .frame(width: size * 0.34, height: size * 0.34)
        }
        .accessibilityHidden(true)
    }
}

/// Cruz de la cruceta: dos barras de un tercio del lado.
private struct CrossShape: Shape {
    func path(in rect: CGRect) -> Path {
        let t = rect.width / 3
        var p = Path()
        p.addRoundedRect(in: CGRect(x: rect.minX + t, y: rect.minY, width: t, height: rect.height),
                         cornerSize: CGSize(width: t * 0.2, height: t * 0.2))
        p.addRoundedRect(in: CGRect(x: rect.minX, y: rect.minY + t, width: rect.width, height: t),
                         cornerSize: CGSize(width: t * 0.2, height: t * 0.2))
        return p
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

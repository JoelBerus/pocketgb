import SwiftUI
import UIKit

/// Ajustes › Pantalla (SPEC §9, `settings-display`): vista previa 10:9 con píxeles
/// nítidos y escala entera en horizontal. Sin filtros que cambien el núcleo.
struct DisplaySettingsView: View {
    @Environment(AppState.self) private var state

    var body: some View {
        Form {
            Section {
                DisplayPreview()
                    .listRowInsets(EdgeInsets())
            } footer: {
                Text("Game Boy: 160 × 144 píxeles (10:9). La imagen nunca se estira.")
            }
            Section {
                Toggle("Escala entera en horizontal",
                       isOn: Binding(get: { state.gameplay.data.integerScaleLandscape },
                                     set: { value in state.gameplay.update { $0.integerScaleLandscape = value } }))
                LabeledContent("Filtro", value: "Píxeles nítidos")
            } footer: {
                Text("Con escala entera todos los píxeles tienen el mismo tamaño; puede quedar un borde negro. En vertical la imagen ocupa todo el ancho.")
            }
        }
        .scrollContentBackground(.hidden)
        .background(PocketColor.backgroundBase.ignoresSafeArea())
        .navigationTitle("Pantalla")
    }
}

/// Vista previa determinista: arte abstracto generado (nunca material de un juego),
/// escalado con muestreo nearest.
private struct DisplayPreview: View {
    var body: some View {
        ZStack {
            PocketColor.gameplayBackground
            if let image = GameArtworkStore.makeImage(Self.pixels) {
                Image(decorative: image, scale: 1)
                    .resizable()
                    .interpolation(.none)
                    .aspectRatio(10.0 / 9.0, contentMode: .fit)
                    .padding(PocketSpacing.md)
            }
        }
        .frame(height: 260)
        .accessibilityLabel("Vista previa de la pantalla, proporción 10:9")
    }

    /// Tablero y degradado de 4 tonos: los bordes nítidos muestran el muestreo nearest.
    private static let pixels: [UInt32] = {
        let shades: [UInt32] = [0xFF0F_BC9B, 0xFF0F_AC8B, 0xFF30_6230, 0xFF0F_380F]
        var p = [UInt32](repeating: 0, count: FrameBuffers.pixelCount)
        for y in 0..<FrameBuffers.height {
            for x in 0..<FrameBuffers.width {
                let checker = ((x / 16) + (y / 16)) % 2
                p[y * FrameBuffers.width + x] = shades[y < 72 ? (x * 4 / FrameBuffers.width) : 2 + checker]
            }
        }
        return p
    }()
}

import MetalKit
import SwiftUI

/// `MTKView` a 60 Hz con fondo negro que dibuja el último frame de la sesión.
struct GameMetalView: UIViewRepresentable {
    let frames: FrameBuffers
    let integerScale: Bool

    final class Coordinator {
        var renderer: Renderer?
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> MTKView {
        let view = MTKView(frame: .zero, device: MTLCreateSystemDefaultDevice())
        view.colorPixelFormat = .bgra8Unorm
        view.clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 1)
        view.backgroundColor = .black
        view.preferredFramesPerSecond = 60
        view.framebufferOnly = true
        view.isUserInteractionEnabled = false
        let renderer = Renderer(view: view, frames: frames)
        renderer?.integerScale = integerScale
        view.delegate = renderer
        context.coordinator.renderer = renderer
        return view
    }

    func updateUIView(_ view: MTKView, context: Context) {
        context.coordinator.renderer?.integerScale = integerScale
    }
}

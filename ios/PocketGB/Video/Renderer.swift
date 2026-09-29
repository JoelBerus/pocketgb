import MetalKit

/// Sube el último frame a una textura 160×144 y la dibuja con sampler nearest
/// (docs/04 §Pantalla de juego).
@MainActor
final class Renderer: NSObject, MTKViewDelegate {
    /// Horizontal: escala entera máxima. Vertical: ancho completo (la vista ya es 10:9).
    var integerScale = true

    private let frames: FrameBuffers
    private let queue: MTLCommandQueue
    private let pipeline: MTLRenderPipelineState
    private let sampler: MTLSamplerState
    private let texture: MTLTexture

    init?(view: MTKView, frames: FrameBuffers) {
        guard let device = view.device,
              let queue = device.makeCommandQueue(),
              let library = try? device.makeLibrary(source: Shaders.source, options: nil) else { return nil }

        let pd = MTLRenderPipelineDescriptor()
        pd.vertexFunction = library.makeFunction(name: "gb_vertex")
        pd.fragmentFunction = library.makeFunction(name: "gb_fragment")
        pd.colorAttachments[0].pixelFormat = view.colorPixelFormat
        guard let pipeline = try? device.makeRenderPipelineState(descriptor: pd) else { return nil }

        let sd = MTLSamplerDescriptor()
        sd.minFilter = .nearest
        sd.magFilter = .nearest
        sd.sAddressMode = .clampToEdge
        sd.tAddressMode = .clampToEdge
        guard let sampler = device.makeSamplerState(descriptor: sd) else { return nil }

        let td = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .rgba8Unorm,
                                                          width: FrameBuffers.width,
                                                          height: FrameBuffers.height,
                                                          mipmapped: false)
        td.usage = .shaderRead
        guard let texture = device.makeTexture(descriptor: td) else { return nil }

        self.frames = frames
        self.queue = queue
        self.pipeline = pipeline
        self.sampler = sampler
        self.texture = texture
        super.init()
    }

    nonisolated func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {}

    nonisolated func draw(in view: MTKView) {
        MainActor.assumeIsolated { render(in: view) }
    }

    private func render(in view: MTKView) {
        let pixels = frames.latest()
        texture.replace(region: MTLRegionMake2D(0, 0, FrameBuffers.width, FrameBuffers.height),
                        mipmapLevel: 0, withBytes: pixels, bytesPerRow: FrameBuffers.width * 4)

        guard let pass = view.currentRenderPassDescriptor,
              let drawable = view.currentDrawable,
              let buffer = queue.makeCommandBuffer(),
              let encoder = buffer.makeRenderCommandEncoder(descriptor: pass) else { return }

        let size = view.drawableSize
        var scale = min(size.width / Double(FrameBuffers.width), size.height / Double(FrameBuffers.height))
        if integerScale && scale >= 1 { scale = scale.rounded(.down) }
        let w = Double(FrameBuffers.width) * scale
        let h = Double(FrameBuffers.height) * scale
        encoder.setViewport(MTLViewport(originX: ((size.width - w) / 2).rounded(.down),
                                        originY: ((size.height - h) / 2).rounded(.down),
                                        width: w, height: h, znear: 0, zfar: 1))
        encoder.setRenderPipelineState(pipeline)
        encoder.setFragmentTexture(texture, index: 0)
        encoder.setFragmentSamplerState(sampler, index: 0)
        encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
        encoder.endEncoding()
        buffer.present(drawable)
        buffer.commit()
    }
}

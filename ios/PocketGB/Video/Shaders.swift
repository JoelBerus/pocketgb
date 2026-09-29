import Foundation

/// Shaders en fuente: se compilan en tiempo de ejecución con `makeLibrary(source:)`.
/// Así el proyecto no depende del Metal Toolchain (descarga aparte desde Xcode 26).
enum Shaders {
    static let source = """
    #include <metal_stdlib>
    using namespace metal;

    struct VertexOut {
        float4 position [[position]];
        float2 uv;
    };

    // Cuadrado a pantalla completa (triangle strip de 4 vértices, sin buffer).
    // El viewport del encoder coloca y escala la imagen.
    vertex VertexOut gb_vertex(uint vid [[vertex_id]]) {
        float2 uv = float2(vid & 1, (vid >> 1) & 1);
        VertexOut out;
        out.position = float4(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0, 0.0, 1.0);
        out.uv = uv;
        return out;
    }

    fragment float4 gb_fragment(VertexOut in [[stage_in]],
                                texture2d<float> frame [[texture(0)]],
                                sampler nearest [[sampler(0)]]) {
        return frame.sample(nearest, in.uv);
    }
    """
}

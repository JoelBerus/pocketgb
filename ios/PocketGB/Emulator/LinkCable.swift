import Foundation
import PocketGBCore
import Synchronization

/// Lado activo del cable (0 o 1): lo escribe el hilo principal y lo lee `LinkedPair` una vez por frame.
final class LinkSideSelector: Sendable {
    private let value = Atomic<Int>(0)

    var side: Int {
        get { value.load(ordering: .acquiring) == 1 ? 1 : 0 }
        set { value.store(newValue == 1 ? 1 : 0, ordering: .releasing) }
    }
}

/// Envoltorio de `gb_link*` (M9, docs/03 §Cable link virtual). No es `Sendable`: un solo
/// hilo a la vez, el de emulación.
///
/// **Retiene** los dos `CoreBridge` mientras están conectados: ningún `gb_destroy` puede
/// ejecutarse con la instancia conectada, porque `gb_link_detach` lee la instancia
/// (destruirla antes sería un use-after-free). Swift suelta las propiedades después del cuerpo
/// de `deinit`, así que `gb_link_destroy` (que desconecta) siempre va antes.
final class LinkCable {
    private let link: OpaquePointer
    private var cores: [CoreBridge] = []

    init() throws(CoreError) {
        guard let link = gb_link_create() else { throw .outOfMemory }
        self.link = link
    }

    deinit { gb_link_destroy(link) }

    var isAttached: Bool { cores.count == 2 }

    /// Conecta `a` (lado 0) y `b` (lado 1). Si el núcleo rechaza el par (misma instancia o ya
    /// conectada a otro cable) desconecta y no retiene nada.
    func attach(_ a: CoreBridge, _ b: CoreBridge) -> Bool {
        detach()
        guard gb_link_attach(link, a.linkHandle, b.linkHandle) else {
            gb_link_detach(link)
            return false
        }
        cores = [a, b]
        return true
    }

    func detach() {
        gb_link_detach(link)
        cores.removeAll()
    }

    func runFrame() { gb_link_run_frame(link) }

    func copyFramebuffer(side: Int, to dst: UnsafeMutablePointer<UInt32>) {
        guard let src = gb_link_framebuffer(link, UInt32(side)) else { return }
        dst.update(from: src, count: ScreenSize.gameBoy.pixelCount)
    }
}

import os

/// Triple buffer de frames RGBA8888 entre el hilo de emulación (escritor) y el
/// render (lector). El escritor solo toca `back`, el lector solo `front`; el
/// intercambio con `middle` va bajo un unfair lock de pocas instrucciones.
/// (`Synchronization.Atomic` exige iOS 18 y el deployment target es 17.0.)
final class FrameBuffers: Sendable {
    static let width = 160
    static let height = 144
    static let pixelCount = width * height

    private struct Slots: Sendable {
        var back = 0, middle = 1, front = 2
        var fresh = false
    }

    // Memoria propia de la clase, sin copias: 3 frames contiguos.
    nonisolated(unsafe) private let storage: UnsafeMutablePointer<UInt32>
    private let slots = OSAllocatedUnfairLock(initialState: Slots())

    init() {
        storage = .allocate(capacity: 3 * Self.pixelCount)
        storage.initialize(repeating: 0xFF00_0000, count: 3 * Self.pixelCount)
    }

    deinit { storage.deallocate() }

    private func buffer(_ i: Int) -> UnsafeMutablePointer<UInt32> { storage + i * Self.pixelCount }

    /// Solo el hilo de emulación: escribe en el buffer trasero y lo publica.
    func publish(_ write: (UnsafeMutablePointer<UInt32>) -> Void) {
        let back = slots.withLock { $0.back }
        write(buffer(back))
        slots.withLock { s in
            swap(&s.back, &s.middle)
            s.fresh = true
        }
    }

    /// Solo el render: el último frame completo. El puntero es válido hasta la
    /// siguiente llamada (el escritor nunca toca `front`).
    func latest() -> UnsafePointer<UInt32> {
        let front = slots.withLock { s in
            if s.fresh {
                swap(&s.front, &s.middle)
                s.fresh = false
            }
            return s.front
        }
        return UnsafePointer(buffer(front))
    }
}

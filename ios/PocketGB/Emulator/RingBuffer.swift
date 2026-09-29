import Synchronization

/// Ring SPSC de frames estéreo intercalados. El productor escribe PCM `Int16`
/// y el callback de audio consume `Float32` no intercalado sin reservar ni bloquear.
final class AudioRingBuffer: Sendable {
    let capacityFrames: Int

    nonisolated(unsafe) private let storage: UnsafeMutablePointer<Int16>
    private let readIndex = Atomic<Int>(0)
    private let writeIndex = Atomic<Int>(0)
    private let underrunCount = Atomic<Int>(0)

    // Solo los toca el consumidor. `clear` se llama con el motor detenido.
    nonisolated(unsafe) private var lastLeft: Float = 0
    nonisolated(unsafe) private var lastRight: Float = 0

    init(capacityFrames: Int = 8_192) {
        precondition(capacityFrames > 0 && capacityFrames.isPowerOfTwo)
        self.capacityFrames = capacityFrames
        storage = .allocate(capacity: capacityFrames * 2)
        storage.initialize(repeating: 0, count: capacityFrames * 2)
    }

    deinit { storage.deallocate() }

    var availableFrames: Int {
        let written = writeIndex.load(ordering: .acquiring)
        let read = readIndex.load(ordering: .acquiring)
        return written - read
    }

    var underruns: Int { underrunCount.load(ordering: .relaxed) }

    /// Solo el hilo de emulación. Publica los datos con release tras copiarlos.
    func write(from source: UnsafePointer<Int16>, frames: Int) -> Int {
        guard frames > 0 else { return 0 }
        let written = writeIndex.load(ordering: .relaxed)
        let read = readIndex.load(ordering: .acquiring)
        let count = min(frames, capacityFrames - (written - read))
        let mask = capacityFrames - 1
        for frame in 0..<count {
            let destination = ((written + frame) & mask) * 2
            storage[destination] = source[frame * 2]
            storage[destination + 1] = source[frame * 2 + 1]
        }
        writeIndex.store(written + count, ordering: .releasing)
        return count
    }

    /// Solo el callback de audio. Un underrun conserva el último nivel de cada
    /// canal para evitar el clic que produciría saltar bruscamente a cero.
    func read(intoLeft left: UnsafeMutablePointer<Float>,
              right: UnsafeMutablePointer<Float>, frames: Int) {
        guard frames > 0 else { return }
        let read = readIndex.load(ordering: .relaxed)
        let written = writeIndex.load(ordering: .acquiring)
        let count = min(frames, written - read)
        let mask = capacityFrames - 1
        let scale = Float(1.0 / 32768.0)

        // Locales en el bucle: una sola escritura a las propiedades al final.
        var l = lastLeft, r = lastRight
        for frame in 0..<count {
            let source = ((read + frame) & mask) * 2
            l = Float(storage[source]) * scale
            r = Float(storage[source + 1]) * scale
            left[frame] = l
            right[frame] = r
        }
        readIndex.store(read + count, ordering: .releasing)

        if count < frames {
            for frame in count..<frames {
                left[frame] = l
                right[frame] = r
            }
            underrunCount.wrappingAdd(1, ordering: .relaxed)
        }
        lastLeft = l
        lastRight = r
    }

    /// Se usa al reanudar, siempre con el callback detenido y el productor aparcado.
    func clear() {
        let written = writeIndex.load(ordering: .acquiring)
        readIndex.store(written, ordering: .releasing)
        lastLeft = 0
        lastRight = 0
    }
}

private extension Int {
    var isPowerOfTwo: Bool { self & (self - 1) == 0 }
}

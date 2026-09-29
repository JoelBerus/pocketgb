import AVFAudio
import Foundation
import os
import Synchronization

/// Semáforo binario: el callback puede publicar como máximo un wake pendiente.
/// Evita acumular señales si el pacing cambia temporalmente al reloj monotónico.
final class AudioWakeSignal: Sendable {
    private let semaphore = DispatchSemaphore(value: 0)
    private let pending = Atomic<Bool>(false)

    func signal() {
        if pending.compareExchange(expected: false, desired: true,
                                   ordering: .acquiringAndReleasing).exchanged {
            semaphore.signal()
        }
    }

    func wait(timeout: DispatchTime) -> DispatchTimeoutResult {
        pending.store(false, ordering: .releasing)
        return semaphore.wait(timeout: timeout)
    }

    /// Solo con el motor detenido y el hilo de emulación aparcado.
    func reset() {
        pending.store(false, ordering: .releasing)
        while semaphore.wait(timeout: .now()) == .success {}
    }
}

/// Salida de audio de 48 kHz. El bloque de render solo toca memoria
/// preasignada, atómicos del ring y el semáforo de pacing.
@MainActor
final class AudioOutput {
    private let ring: AudioRingBuffer
    private let consumed: AudioWakeSignal
    private let onPause: @MainActor @Sendable () -> Void
    private let log = Logger(subsystem: "com.joelbermudez.pocketgb", category: "audio")

    private var engine: AVAudioEngine?
    nonisolated(unsafe) private var observers: [NSObjectProtocol] = []

    init(ring: AudioRingBuffer, consumed: AudioWakeSignal,
         onPause: @escaping @MainActor @Sendable () -> Void) {
        self.ring = ring
        self.consumed = consumed
        self.onPause = onPause

        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: AVAudioSession.interruptionNotification,
                                             object: nil, queue: nil) { [weak self] notification in
            let raw = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
            Task { @MainActor in self?.handleInterruption(raw) }
        })
        // Cambio de salida (auriculares, Bluetooth, AirPlay, otra frecuencia): el motor
        // se detiene solo. Se pausa el juego como en una interrupción; "Continuar"
        // rearranca el motor con cebado (auditoría M5 iOS, H1).
        observers.append(center.addObserver(forName: .AVAudioEngineConfigurationChange,
                                             object: nil, queue: nil) { [weak self] notification in
            let changed = (notification.object as AnyObject?).map(ObjectIdentifier.init)
            Task { @MainActor in
                guard let self, let engine = self.engine, changed == ObjectIdentifier(engine) else { return }
                self.stop()
                self.onPause()
            }
        })
        observers.append(center.addObserver(forName: AVAudioSession.routeChangeNotification,
                                             object: nil, queue: nil) { [weak self] notification in
            let raw = notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt
            Task { @MainActor in
                guard let self, self.engine != nil,
                      raw == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue else { return }
                self.stop()
                self.onPause()
            }
        })
        observers.append(center.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification,
                                             object: nil, queue: nil) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                self.stop()
                self.onPause()
            }
        })
    }

    deinit {
        for observer in observers { NotificationCenter.default.removeObserver(observer) }
    }

    /// Devuelve `false` si iOS no ofrece una salida; la sesión usará reloj monotónico.
    func start() -> Bool {
        stop()
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.ambient, mode: .default)
            try session.setPreferredSampleRate(48_000)
            try session.setPreferredIOBufferDuration(0.010)
            try session.setActive(true)

            guard let format = AVAudioFormat(commonFormat: .pcmFormatFloat32,
                                             sampleRate: 48_000,
                                             channels: 2,
                                             interleaved: false) else {
                throw AudioOutputError.invalidFormat
            }
            let source = Self.makeSourceNode(format: format, ring: ring, consumed: consumed)

            let engine = AVAudioEngine()
            engine.attach(source)
            engine.connect(source, to: engine.mainMixerNode, format: format)
            engine.prepare()
            try engine.start()
            self.engine = engine
            return true
        } catch {
            log.error("No se pudo iniciar el audio; se usará pacing por reloj: \(error.localizedDescription, privacy: .public)")
            engine?.stop()
            engine = nil
            try? session.setActive(false)
            return false
        }
    }

    /// El bloque de render se crea fuera del `@MainActor`: un closure creado dentro de
    /// un método aislado hereda ese aislamiento y Swift 6 aborta al ejecutarlo en el
    /// hilo de audio en tiempo real (`dispatch_assert_queue`).
    nonisolated private static func makeSourceNode(format: AVAudioFormat, ring: AudioRingBuffer,
                                                   consumed: AudioWakeSignal) -> AVAudioSourceNode {
        AVAudioSourceNode(format: format) { _, _, frameCount, audioBufferList in
            let buffers = UnsafeMutableAudioBufferListPointer(audioBufferList)
            guard buffers.count >= 2,
                  let left = buffers[0].mData?.assumingMemoryBound(to: Float.self),
                  let right = buffers[1].mData?.assumingMemoryBound(to: Float.self) else {
                return kAudio_ParamError
            }
            ring.read(intoLeft: left, right: right, frames: Int(frameCount))
            consumed.signal()
            return noErr
        }
    }

    func stop() {
        engine?.stop()
        engine = nil
        try? AVAudioSession.sharedInstance().setActive(false)
    }

    private func handleInterruption(_ raw: UInt?) {
        guard let raw,
              let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
        if type == .began {
            stop()
            onPause()
        }
        // Al terminar no se reanuda: el juego queda en pausa hasta "Continuar".
    }
}

private enum AudioOutputError: Error {
    case invalidFormat
}

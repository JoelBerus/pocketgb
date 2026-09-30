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

/// Preferencias de audio (Ajustes › Audio). `.ambient` respeta el interruptor de
/// silencio y se mezcla con otras apps; `.playback` suena también en silencio. Nunca hay
/// audio en segundo plano (sin `UIBackgroundModes`).
struct AudioPreferences: Equatable, Sendable {
    var volume: Float = 1
    var playsInSilentMode = false
}

/// Salida de audio de 48 kHz. El bloque de render solo toca memoria
/// preasignada, atómicos del ring y el semáforo de pacing.
///
/// Todo el estado (sesión y motor) vive en `queue`, una cola serie propia:
/// `AVAudioSession.setActive` puede bloquear y no debe llamarse en el hilo
/// principal (aviso de iOS visto en el iPhone de Joel). Al ser serie, un `stop`
/// pedido después de un `start` siempre se ejecuta después.
final class AudioOutput: @unchecked Sendable {
    private let ring: AudioRingBuffer
    private let consumed: AudioWakeSignal
    private let onPause: @MainActor @Sendable () -> Void
    private let queue = DispatchQueue(label: "PocketGB.audio", qos: .userInitiated)
    private let log = Logger(subsystem: "com.joelbermudez.pocketgb", category: "audio")

    private var engine: AVAudioEngine?     // solo en `queue`
    private var preferences = AudioPreferences()   // solo en `queue`
    private var observers: [NSObjectProtocol] = []

    init(ring: AudioRingBuffer, consumed: AudioWakeSignal,
         onPause: @escaping @MainActor @Sendable () -> Void) {
        self.ring = ring
        self.consumed = consumed
        self.onPause = onPause

        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: AVAudioSession.interruptionNotification,
                                             object: nil, queue: nil) { [weak self] notification in
            let raw = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
            guard let raw, AVAudioSession.InterruptionType(rawValue: raw) == .began else { return }
            // Al terminar la interrupción no se reanuda: el juego queda en pausa hasta "Continuar".
            self?.stopAndPause(onlyIf: nil)
        })
        // Cambio de salida (auriculares, Bluetooth, AirPlay, otra frecuencia): el motor
        // se detiene solo. Se pausa el juego como en una interrupción; "Continuar"
        // rearranca el motor con cebado (auditoría M5 iOS, H1).
        observers.append(center.addObserver(forName: .AVAudioEngineConfigurationChange,
                                             object: nil, queue: nil) { [weak self] notification in
            let changed = (notification.object as AnyObject?).map(ObjectIdentifier.init)
            self?.stopAndPause(onlyIf: changed)
        })
        observers.append(center.addObserver(forName: AVAudioSession.routeChangeNotification,
                                             object: nil, queue: nil) { [weak self] notification in
            let raw = notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt
            guard raw == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue else { return }
            self?.stopAndPause(onlyIf: nil)
        })
        observers.append(center.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification,
                                             object: nil, queue: nil) { [weak self] _ in
            self?.stopAndPause(onlyIf: nil)
        })
    }

    deinit {
        for observer in observers { NotificationCenter.default.removeObserver(observer) }
    }

    /// Arranca en la cola de audio y avisa con `true`, o `false` si iOS no ofrece
    /// salida (la sesión usará el reloj monotónico). `completion` corre en esa cola.
    func start(_ completion: @escaping @Sendable (Bool) -> Void) {
        queue.async { [self] in completion(startOnQueue()) }
    }

    /// Para el motor, vacía el ring (con el callback ya parado, único momento seguro
    /// para tocar el índice de lectura desde fuera) y desactiva la sesión.
    func stop() {
        queue.async { [self] in
            stopEngineOnQueue()
            ring.clear() // sin callback activo, también si el motor nunca arrancó
        }
    }

    func apply(_ preferences: AudioPreferences) {
        queue.async { [self] in
            self.preferences = preferences
            engine?.mainMixerNode.outputVolume = preferences.volume
        }
    }

    private func startOnQueue() -> Bool {
        stopEngineOnQueue() // sin vaciar: el ring ya trae el audio cebado
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(preferences.playsInSilentMode ? .playback : .ambient,
                                    mode: .default, options: preferences.playsInSilentMode ? [.mixWithOthers] : [])
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
            engine.mainMixerNode.outputVolume = preferences.volume
            engine.prepare()
            try engine.start()
            self.engine = engine
            return true
        } catch {
            log.error("No se pudo iniciar el audio; se usará pacing por reloj: \(error.localizedDescription, privacy: .public)")
            stopEngineOnQueue()
            return false
        }
    }

    private func stopEngineOnQueue() {
        guard let engine else { return }
        engine.stop() // síncrono: al volver ya no hay callbacks
        self.engine = nil
        try? AVAudioSession.sharedInstance().setActive(false)
    }

    /// `onlyIf`: solo si el aviso es del motor actual (cambio de configuración).
    private func stopAndPause(onlyIf changed: ObjectIdentifier?) {
        queue.async { [self] in
            guard let engine else { return }
            if let changed, changed != ObjectIdentifier(engine) { return }
            stopEngineOnQueue()
            let onPause = onPause
            Task { @MainActor in onPause() }
        }
    }

    /// El bloque de render se crea fuera de cualquier actor: un closure creado dentro de
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
}

private enum AudioOutputError: Error {
    case invalidFormat
}

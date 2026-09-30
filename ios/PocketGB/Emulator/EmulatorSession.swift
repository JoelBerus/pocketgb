import Foundation
import os
import Synchronization

/// Una partida en marcha: dueña del núcleo y de su hilo de emulación.
/// Todo acceso a `core` ocurre en ese hilo (docs/02 §Hilos); el resto de hilos
/// se comunica con `control` (NSCondition), `buttons` y `frames`.
final class EmulatorSession: @unchecked Sendable {
    let info: RomInfo
    let frames = FrameBuffers()
    let buttons = ButtonMask()
    /// Máscara del mando físico: se combina por OR con la táctil (`buttons`).
    let padButtons = ButtonMask()
    let audioRing = AudioRingBuffer()
    /// Aviso para mostrar al abrir (p. ej. .sav con tamaño inesperado).
    let loadWarning: SaveLoadWarning?

    private let core: CoreBridge
    private let audioOutput: AudioOutput
    private let audioConsumed = AudioWakeSignal()
    private let audioMode = Atomic<Int>(0)
    private let averageFrameMicros = Atomic<Int>(0)
    /// Avance rápido: 1, 2 o 4 frames por frame real. Con > 1, pacing por reloj y sin audio.
    private let speedFactor = Atomic<Int>(1)
    private let audioFallbackCount = Atomic<Int>(0)
    /// Invalida el resultado de un arranque de audio que llegue tras pausar o parar.
    private let audioGeneration = Atomic<Int>(0)
    nonisolated(unsafe) private let audioScratch: UnsafeMutablePointer<Int16>
    private let saves: SaveTarget?
    private let localSaveQueue = DispatchQueue(label: "PocketGB.saves-local", qos: .utility)
    private let log = Logger(subsystem: "com.joelbermudez.pocketgb", category: "session")

    // Estado de control compartido, protegido por `control`.
    private let control = NSCondition()
    private var pauseRequested = false
    private var stopRequested = false
    private var parked = false
    private var finished = false
    private var saveFailed = false
    private var flushRequested = false
    private var audioPrimed = false
    private var confirmed: Data?           // último contenido que está en disco (o se cargó)

    // Solo del hilo de emulación.
    private var ticksPerSecond = 1.0
    private var dirtyLast: UInt64?         // último "juego guardó" aún sin escribir
    private var lastCheck: UInt64 = 0      // último flush (para la red de seguridad)
    private var lastQueued: Data?          // último contenido encolado
    #if DEBUG
    private var failAsyncWrites = false    // `-failAsyncSaves`: toda escritura asíncrona falla (prueba H6)
    #endif

    private static let debounceSeconds = 1.0
    private static let safetyNetSeconds = 60.0
    private static let frameSeconds = Double(70224) / Double(4_194_304) // 59,7275 Hz
    // 2 frames son 1 607 muestras; 2 048 deja margen para un callback grande
    // sin elevar la latencia objetivo por encima de ≈43 ms.
    private static let audioTargetFrames = 2_048
    private static let audioScratchFrames = 1_024
    private static let audioWait: DispatchTimeInterval = .milliseconds(25)
    private static let audioTimeoutLimit = 4 // ≈100 ms sin callbacks antes del fallback
    private static let audioClock = 0
    private static let audioPriming = 1
    private static let audioLive = 2

    /// - Parameters:
    ///   - mirror: `<rom>.sav` junto al ROM en la carpeta de la biblioteca (nil para un
    ///     ROM suelto). La copia local sigue siendo la autoritativa.
    ///   - mirrorSnapshot: el espejo ya leído fuera del hilo principal (`SaveMirror.snapshot()`).
    /// - Throws: `SaveOpening.Refusal` si la única partida está en iCloud sin descargar.
    @MainActor
    init(romData: Data, savesDirectory: URL, mirror: SaveMirror? = nil,
         mirrorSnapshot: SaveMirror.Snapshot = .absent,
         mirrorWriter: (@Sendable (Data) throws -> Void)? = nil,
         onAudioInterrupted: @escaping @MainActor @Sendable () -> Void) throws {
        let core = try CoreBridge()
        let now = Int64(Date().timeIntervalSince1970)
        info = try core.loadROM(romData, unixTime: now, sampleRate: 48_000)
        self.core = core
        audioScratch = .allocate(capacity: Self.audioScratchFrames * 2)
        audioOutput = AudioOutput(ring: audioRing, consumed: audioConsumed,
                                  onPause: onAudioInterrupted)

        var warning: SaveLoadWarning?
        var saves: SaveTarget?
        if info.hasBattery, core.sramSaveSize > 0 {
            let store = SaveStore(directory: savesDirectory, fingerprint: info.fingerprint)
            do {
                try store.recoverOrphans(expectedSize: core.sramSaveSize)
                let outcome = try SaveOpening.prepare(store: store, mirror: mirror, snapshot: mirrorSnapshot,
                                                      validSizes: Self.validSaveSizes(info),
                                                      mirrorWriter: mirrorWriter)
                if let data = outcome.data { try core.sramLoad(data) }
                saves = outcome.target
                warning = outcome.warning
            } catch let refusal as SaveOpening.Refusal {
                // `saves` y `loadWarning` aún no están inicializados, así que `deinit` no se
                // ejecutará: el búfer se libera aquí. Si se inicializan antes, quitar esto.
                audioScratch.deallocate()
                throw refusal
            } catch let e as CoreError where e == .sramSize {
                // No se sobrescribe un .sav que no entendemos (no debería llegar: se valida el tamaño antes).
                warning = .localWrongSize
            } catch {
                warning = .unreadable("No se pudo leer la partida (\(error.localizedDescription)).")
            }
        }
        self.saves = saves
        loadWarning = warning
    }

    deinit { audioScratch.deallocate() }

    /// Tamaños que acepta `gb_sram_load`: la RAM, y con RTC también +48 o +44 bytes.
    static func validSaveSizes(_ info: RomInfo) -> Set<Int> {
        info.hasRTC ? [info.sramBytes, info.sramBytes + 48, info.sramBytes + 44] : [info.sramBytes]
    }

    @MainActor
    func start() {
        audioConsumed.reset()
        control.lock()
        audioPrimed = false
        audioMode.store(Self.audioPriming, ordering: .releasing)
        let thread = Thread { [self] in run() }
        thread.name = "PocketGB.emulation"
        thread.qualityOfService = .userInteractive
        thread.start()
        while !audioPrimed && !finished { control.wait() }
        control.unlock()
        startAudio()
    }

    /// Pausa y espera a que el hilo guarde la SRAM pendiente y quede parado.
    @MainActor
    func pause() {
        audioGeneration.wrappingAdd(1, ordering: .relaxed)
        audioOutput.stop() // vacía el ring al parar el motor
        audioMode.store(Self.audioClock, ordering: .releasing)
        audioConsumed.signal()
        control.lock()
        pauseRequested = true
        control.broadcast()
        while !parked && !finished { control.wait() }
        control.unlock()
    }

    @MainActor
    func resume() {
        if speedFactor.load(ordering: .relaxed) > 1 {
            // Avance rápido: sin audio (el ring nunca se llenaría); sigue el reloj.
            audioMode.store(Self.audioClock, ordering: .releasing)
            control.lock()
            pauseRequested = false
            control.broadcast()
            control.unlock()
            return
        }
        audioConsumed.reset()
        control.lock()
        audioPrimed = false
        audioMode.store(Self.audioPriming, ordering: .releasing)
        pauseRequested = false
        control.broadcast()
        while !audioPrimed && !finished { control.wait() }
        control.unlock()
        startAudio()
    }

    /// Arranca el audio en su cola sin bloquear el hilo principal. Mientras tanto el
    /// hilo de emulación espera en modo cebado (ring lleno, sin contar timeouts).
    private func startAudio() {
        let generation = audioGeneration.wrappingAdd(1, ordering: .relaxed).newValue
        audioOutput.start { [weak self] running in
            guard let self, self.audioGeneration.load(ordering: .relaxed) == generation else { return }
            self.audioMode.store(running ? Self.audioLive : Self.audioClock, ordering: .releasing)
            self.audioConsumed.signal()
        }
    }

    /// Pide guardar la SRAM en el próximo frame sin pausar (aviso de memoria baja).
    func requestFlush() {
        control.lock()
        flushRequested = true
        control.unlock()
    }

    /// Llama a `callback` al terminar la escritura de espejo en vuelo. La copia local
    /// ya está cubierta por la barrera síncrona de `pause()`/`stop()`.
    func whenMirrorIdle(_ callback: @escaping @Sendable () -> Void) {
        guard let saves else {
            callback()
            return
        }
        saves.whenMirrorIdle(callback)
    }

    /// Detiene el hilo; al volver, la SRAM ya está escrita en disco.
    @MainActor
    func stop() {
        audioGeneration.wrappingAdd(1, ordering: .relaxed)
        audioOutput.stop()
        audioMode.store(Self.audioClock, ordering: .releasing)
        audioConsumed.signal()
        control.lock()
        stopRequested = true
        control.broadcast()
        while !finished { control.wait() }
        control.unlock()
    }

    // MARK: - Avance rápido (D6)

    var speed: Int { speedFactor.load(ordering: .relaxed) }

    /// ×2/×4: para el audio (que vacía el ring, interfaz de M5) y pasa al reloj. De vuelta
    /// a ×1, se ceba y arranca el audio igual que al reanudar. En pausa solo se guarda el
    /// valor: `resume()` hará el cebado.
    @MainActor
    func setSpeed(_ factor: Int) {
        let factor = [1, 2, 4].contains(factor) ? factor : 1
        let old = speedFactor.exchange(factor, ordering: .relaxed)
        guard factor != old else { return }
        control.lock()
        let isPaused = pauseRequested || finished
        control.unlock()
        if factor > 1 {
            audioGeneration.wrappingAdd(1, ordering: .relaxed)
            audioOutput.stop()
            audioMode.store(Self.audioClock, ordering: .releasing)
            audioConsumed.signal()
        } else if old > 1 && !isPaused {
            audioConsumed.reset()
            control.lock()
            audioPrimed = false
            audioMode.store(Self.audioPriming, ordering: .releasing)
            control.broadcast()
            while !audioPrimed && !finished && !pauseRequested { control.wait() }
            control.unlock()
            startAudio()
        }
    }

    // MARK: - Save states (D5)

    enum StateError: Error, Equatable {
        /// Los estados solo se guardan o cargan con el hilo de emulación aparcado.
        case notPaused
    }

    /// Guarda un estado con la sesión en pausa. Devuelve el estado y el frame actual
    /// (RGBA, 160×144) para la captura de la ranura.
    @MainActor
    func saveState() throws -> (state: Data, pixels: [UInt32]) {
        try withParkedCore { core in
            let state = try core.stateSave()
            var pixels = [UInt32](repeating: 0, count: FrameBuffers.pixelCount)
            pixels.withUnsafeMutableBufferPointer { core.copyFramebuffer(to: $0.baseAddress!) }
            return (state: state, pixels: pixels)
        }
    }

    /// Carga un estado con la sesión en pausa. Si el núcleo lo rechaza (dañado, de otro
    /// juego o de otra versión) no cambia nada, SRAM incluida. Si lo acepta, la SRAM del
    /// estado se guarda en el acto por la ruta normal: `AtomicFile` deja la partida
    /// anterior como backup `.1` antes de sustituirla (SPEC §12).
    @MainActor
    func loadState(_ data: Data) throws {
        try withParkedCore { core in
            try core.stateLoad(data)
            frames.publish { core.copyFramebuffer(to: $0) }
            flushSRAM(sync: true)
        }
    }

    /// Ejecuta `body` en el hilo principal con el núcleo mientras el hilo de emulación está
    /// aparcado en `control.wait()`: nadie más lo toca hasta que el propio hilo principal
    /// llame a `resume()` o `stop()`. El lock da la visibilidad de memoria entre hilos.
    @MainActor
    private func withParkedCore<T>(_ body: (CoreBridge) throws -> T) throws -> T {
        control.lock()
        let isParked = parked && !finished
        control.unlock()
        guard isParked else { throw StateError.notPaused }
        return try body(core)
    }

    /// Para el HUD de depuración: "audio", "cebado" o "reloj" (sin sonido).
    var pacingDescription: String {
        switch audioMode.load(ordering: .relaxed) {
        case Self.audioLive: "audio"
        case Self.audioPriming: "cebado"
        default: "reloj"
        }
    }

    /// Veces que el pacing cayó a reloj por falta de callbacks.
    var audioFallbacks: Int { audioFallbackCount.load(ordering: .relaxed) }

    var averageFrameMilliseconds: Double {
        Double(averageFrameMicros.load(ordering: .relaxed)) / 1_000
    }

    // MARK: - Hilo de emulación

    private func run() {
        var timebase = mach_timebase_info_data_t()
        mach_timebase_info(&timebase)
        ticksPerSecond = 1e9 * Double(timebase.denom) / Double(timebase.numer)
        let frameTicks = Self.frameSeconds * ticksPerSecond
        var deadline = Double(mach_absolute_time())
        var audioTimeouts = 0
        if saves != nil {
            // Lo que ya hay en disco (o la RAM inicial si no había .sav).
            lastQueued = try? core.sramSave()
            control.lock(); confirmed = lastQueued; control.unlock()
            // Espejo ausente o desfasado al abrir: se pone al día en su propia cola.
            if let initial = lastQueued, let saves {
                saves.retryMirrorIfNeeded(initial)
            }
            lastCheck = mach_absolute_time()
            #if DEBUG
            let args = ProcessInfo.processInfo.arguments
            failAsyncWrites = args.contains("-failAsyncSaves")
            #endif
        }

        while true {
            control.lock()
            if pauseRequested && !stopRequested {
                control.unlock()
                flushSRAM(sync: true)
                control.lock()
                parked = true
                control.broadcast()
                while pauseRequested && !stopRequested { control.wait() }
                parked = false
                control.unlock()
                // De vuelta: el RTC del MBC3 sigue la hora real y el pacing se reinicia.
                core.setRTCTime(Int64(Date().timeIntervalSince1970))
                deadline = Double(mach_absolute_time())
                continue
            }
            if stopRequested {
                control.unlock()
                break
            }
            let flushNow = flushRequested
            flushRequested = false
            if saveFailed {
                saveFailed = false
                lastQueued = nil // forzar la reescritura
                dirtyLast = mach_absolute_time() // reintento tras el debounce
            }
            control.unlock()
            if flushNow { flushSRAM(sync: true) }

            let mode = audioMode.load(ordering: .acquiring)
            if mode != Self.audioClock && audioRing.availableFrames >= Self.audioTargetFrames {
                if mode == Self.audioPriming { markAudioPrimed() }
                let waitResult = audioConsumed.wait(timeout: .now() + Self.audioWait)
                if mode == Self.audioLive {
                    if waitResult == .success {
                        audioTimeouts = 0
                    } else {
                        audioTimeouts += 1
                        if audioTimeouts >= Self.audioTimeoutLimit {
                            // El timeout acumulado evita congelar el juego si muere el callback.
                            audioMode.store(Self.audioClock, ordering: .releasing)
                            audioFallbackCount.wrappingAdd(1, ordering: .relaxed)
                            deadline = Double(mach_absolute_time())
                            audioOutput.stop() // en su cola: antes que cualquier arranque posterior
                        }
                    }
                } else {
                    audioTimeouts = 0
                }
                continue
            }

            let frameStarted = mach_absolute_time()
            core.setButtons(buttons.value | padButtons.value)
            let speed = speedFactor.load(ordering: .relaxed)
            core.runFrame()
            drainAudio(discard: speed > 1)
            frames.publish { core.copyFramebuffer(to: $0) }
            checkSRAM()
            recordFrameDuration(from: frameStarted, to: mach_absolute_time())

            if mode == Self.audioPriming && audioRing.availableFrames >= Self.audioTargetFrames {
                markAudioPrimed()
            }

            if mode == Self.audioClock {
                // Fallback si iOS no ofrece salida o el motor no pudo arrancar.
                deadline += frameTicks / Double(speedFactor.load(ordering: .relaxed))
                let now = Double(mach_absolute_time())
                if now - deadline > 5 * frameTicks {
                    deadline = now // demasiado atrasados: no intentar recuperar
                } else if deadline > now {
                    mach_wait_until(UInt64(deadline))
                }
            }
        }

        flushSRAM(sync: true)
        control.lock()
        finished = true
        control.broadcast()
        control.unlock()
    }

    /// Con avance rápido el audio del núcleo se lee y se descarta: no se acumula ni llega
    /// desincronizado al ring.
    private func drainAudio(discard: Bool = false) {
        while true {
            let count = core.readAudio(into: audioScratch, maxFrames: Self.audioScratchFrames)
            guard count > 0 else { return }
            if !discard { _ = audioRing.write(from: audioScratch, frames: count) }
        }
    }

    private func markAudioPrimed() {
        control.lock()
        if !audioPrimed {
            audioPrimed = true
            control.broadcast()
        }
        control.unlock()
    }

    private func recordFrameDuration(from start: UInt64, to end: UInt64) {
        let sample = Int(seconds(end - start) * 1_000_000)
        let previous = averageFrameMicros.load(ordering: .relaxed)
        averageFrameMicros.store(previous == 0 ? sample : (previous * 9 + sample) / 10,
                                 ordering: .relaxed)
    }

    /// Debounce de 1,0 s tras cada "juego guardó" y red de seguridad de 60 s
    /// (docs/04 §Saves). La red no depende del flanco: un juego puede escribir la
    /// SRAM y dejarla habilitada; `flushSRAM` compara y solo escribe si cambió.
    private func checkSRAM() {
        guard saves != nil else { return }
        let now = mach_absolute_time()
        if core.sramDirty {
            core.clearSRAMDirty()
            dirtyLast = now
        }
        if let last = dirtyLast, seconds(now - last) >= Self.debounceSeconds {
            flushSRAM(sync: false)
        } else if seconds(now - lastCheck) >= Self.safetyNetSeconds {
            flushSRAM(sync: false)
        }
    }

    private func seconds(_ ticks: UInt64) -> Double { Double(ticks) / ticksPerSecond }

    /// Copia la SRAM aquí (hilo de emulación) y la escribe en la cola de guardado.
    /// - Asíncrono: se compara con lo último encolado.
    /// - Síncrono (pausa, background, salida): primero se vacía la cola y se compara
    ///   con lo último **confirmado en disco**, así una escritura asíncrona que falló
    ///   justo antes se reintenta aquí y no se pierde (auditoría M4, H6).
    private func flushSRAM(sync: Bool) {
        guard let saves else { return }
        if core.sramDirty { core.clearSRAMDirty() }
        dirtyLast = nil
        lastCheck = mach_absolute_time()
        let data: Data
        do {
            data = try core.sramSave()
        } catch {
            log.error("gb_sram_save falló: \(String(describing: error), privacy: .public)")
            return
        }
        if sync {
            localSaveQueue.sync {}
            control.lock()
            let onDisk = confirmed
            control.unlock()
            guard data != onDisk else {
                saves.retryMirrorIfNeeded(data)
                return
            }
        } else {
            guard data != lastQueued else { return }
        }
        lastQueued = data
        #if DEBUG
        let injectFailure = failAsyncWrites && !sync
        #else
        let injectFailure = false
        #endif
        let write: @Sendable () -> Void = { [self] in
            do {
                if injectFailure { throw CocoaError(.fileWriteUnknown) }
                try saves.persistLocal(data)
                control.lock()
                confirmed = data
                control.unlock()
            } catch {
                log.error("No se pudo guardar la partida: \(error.localizedDescription, privacy: .public)")
                control.lock()
                saveFailed = true // se reintenta en el próximo ciclo o en el próximo flush síncrono
                control.unlock()
            }
        }
        if sync { localSaveQueue.sync(execute: write) } else { localSaveQueue.async(execute: write) }
    }
}

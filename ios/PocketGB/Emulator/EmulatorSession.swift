import Foundation
import os
import Synchronization

/// Una partida en marcha: dueña del núcleo y de su hilo de emulación.
/// Todo acceso a `core` ocurre en ese hilo (docs/02 §Hilos); el resto de hilos
/// se comunica con `control` (NSCondition), `buttons` y `frames`.
final class EmulatorSession: @unchecked Sendable {
    let info: RomInfo
    let frames: FrameBuffers
    let buttons = ButtonMask()
    /// Máscara del mando físico: se combina por OR con la táctil (`buttons`).
    let padButtons = ButtonMask()
    let audioRing = AudioRingBuffer()
    /// Aviso para mostrar al abrir (p. ej. .sav con tamaño inesperado).
    let loadWarning: SaveLoadWarning?
    /// Game Boy Advance: el `.sav` existente no coincide con el tipo o el reloj forzados por juego.
    let gameSettingsWarning: GameSettingsSaveWarning?

    private let core: any ConsoleCore
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
    /// Una por cada partida que se guarda: ninguna (cartucho sin batería), una (juego suelto)
    /// o dos (cable link, M9).
    private let persisters: [SRAMPersistence]

    // Estado de control compartido, protegido por `control`.
    private let control = NSCondition()
    private var pauseRequested = false
    private var stopRequested = false
    private var parked = false
    private var finished = false
    private var flushRequested = false
    private var audioPrimed = false

    // Solo del hilo de emulación.
    private var ticksPerSecond = 1.0

    // 59,7275 Hz en las dos consolas: 70 224 / 4 194 304 = 280 896 / 16 777 216.
    private static let frameSeconds = Double(70224) / Double(4_194_304)
    // 2 frames son 1 607 muestras; 2 048 deja margen para un callback grande
    // sin elevar la latencia objetivo por encima de ≈43 ms.
    private static let audioTargetFrames = 2_048
    private static let audioScratchFrames = 1_024
    private static let audioWait: DispatchTimeInterval = .milliseconds(25)
    private static let audioTimeoutLimit = 4 // ≈100 ms sin callbacks antes del fallback
    private static let audioClock = 0
    private static let audioPriming = 1
    private static let audioLive = 2

    /// Init designado: el núcleo ya tiene el ROM cargado y la SRAM de cada persistencia ya está
    /// en él (`SRAMPersistence.open`). Lo usan el cable link y las pruebas con un núcleo falso.
    @MainActor
    init(core: any ConsoleCore, info: RomInfo, persisters: [SRAMPersistence],
         loadWarning: SaveLoadWarning?, gameSettingsWarning: GameSettingsSaveWarning? = nil,
         onAudioInterrupted: @escaping @MainActor @Sendable () -> Void) {
        self.core = core
        self.info = info
        self.persisters = persisters
        self.loadWarning = loadWarning
        self.gameSettingsWarning = gameSettingsWarning
        frames = FrameBuffers(size: core.console.screen)
        audioScratch = .allocate(capacity: Self.audioScratchFrames * 2)
        audioOutput = AudioOutput(ring: audioRing, consumed: audioConsumed,
                                  onPause: onAudioInterrupted)
    }

    /// - Parameters:
    ///   - mirror: `<rom>.sav` junto al ROM en la carpeta de la biblioteca (nil para un
    ///     ROM suelto). La copia local sigue siendo la autoritativa.
    ///   - mirrorSnapshot: el espejo ya leído fuera del hilo principal (`SaveMirror.snapshot()`).
    ///   - console: núcleo que ejecuta el ROM (por la extensión del archivo).
    ///   - bios: Game Boy Advance, BIOS del usuario ya validada; `nil` = BIOS HLE.
    /// - Throws: `SaveOpening.Refusal` si la única partida está en iCloud sin descargar.
    @MainActor
    convenience init(romData: Data, savesDirectory: URL, mirror: SaveMirror? = nil,
                     mirrorSnapshot: SaveMirror.Snapshot = .absent,
                     mirrorWriter: (@Sendable (Data) throws -> Void)? = nil,
                     emulation: EmulationOptions = EmulationOptions(colorForGameBoy: false, compatPalette: 0),
                     console: Console = .gameBoy, bios: Data? = nil,
                     onAudioInterrupted: @escaping @MainActor @Sendable () -> Void) throws {
        let now = Int64(Date().timeIntervalSince1970)
        let core: any ConsoleCore
        let info: RomInfo
        switch console {
        case .gameBoy:
            let gb = try CoreBridge()
            info = try gb.loadROM(romData, unixTime: now, sampleRate: 48_000,
                                  colorForGameBoy: emulation.colorForGameBoy,
                                  compatPalette: emulation.compatPalette)
            core = gb
        case .gameBoyAdvance:
            let gba = try GBACoreBridge()
            info = try gba.loadROM(romData, bios: emulation.gbaUseBIOS ? bios : nil, unixTime: now, sampleRate: 48_000,
                                   saveType: emulation.gbaSaveType, rtc: emulation.gbaRTC)
            core = gba
        }

        var forcedWarning: GameSettingsSaveWarning?
        if console == .gameBoyAdvance {
            var sizes: [Int] = []
            if let local = try? SaveStore(directory: savesDirectory, fingerprint: info.fingerprint).load() {
                sizes.append(local.count)
            }
            if case let .read(data, _) = mirrorSnapshot, mirror != nil { sizes.append(data.count) }
            forcedWarning = GameSettingsSaveWarning.check(
                forced: emulation.gbaSaveType != 0 || emulation.gbaRTC != 0,
                validSizes: Self.validSaveSizes(info), existingSizes: sizes)
        }
        let opened = try SRAMPersistence.open(core: core, info: info, savesDirectory: savesDirectory,
                                              mirror: mirror, snapshot: mirrorSnapshot, mirrorWriter: mirrorWriter)
        self.init(core: core, info: info, persisters: opened.persister.map { [$0] } ?? [],
                  loadWarning: opened.warning, gameSettingsWarning: forcedWarning,
                  onAudioInterrupted: onAudioInterrupted)
    }

    deinit { audioScratch.deallocate() }

    /// Tamaños que acepta `gb_sram_load`: la RAM, y con RTC también +48 o +44 bytes.
    /// Game Boy Advance: `GBACoreBridge.validSaveSizes`.
    static func validSaveSizes(_ info: RomInfo) -> Set<Int> {
        if info.console == .gameBoyAdvance { return GBACoreBridge.validSaveSizes(info) }
        return info.hasRTC ? [info.sramBytes, info.sramBytes + 48, info.sramBytes + 44] : [info.sramBytes]
    }

    /// Botones que recibe el núcleo cada frame: táctiles OR mando, sin direcciones opuestas
    /// (el núcleo no las filtra; N2-H3).
    static func combinedButtons(touch: UInt16, pad: UInt16) -> UInt16 {
        DpadDirection.withoutOpposites(touch | pad)
    }

    @MainActor
    func start() {
        startThreadAndAudio()
    }

    /// Restaura el estado antes de crear el hilo y arrancar el audio.
    ///
    /// La vigencia se decide aquí, ya con la partida abierta (`SaveOpening` puede haber
    /// instalado un espejo más nuevo) y por contenido (D81-H1): un estado automático
    /// legítimo lleva la misma SRAM que la partida, porque `closeGame` y `enterBackground`
    /// la vacían antes de guardarlo. Si difiere, se revierte el núcleo y se lanza
    /// `StateError.notCurrent` sin escribir ninguna copia de la partida.
    @MainActor
    func start(restoring state: Data?) throws {
        if let state {
            // Invariante (M9L2-H3): con varias persistencias (cable link) no hay estados; la vigencia
            // por contenido de abajo solo vale con un núcleo y una SRAM.
            guard persisters.count <= 1 else { throw CoreError.linkUnsupported }
            let previous = try core.stateSave()
            let sramBefore = try ramBytes()
            do { try core.stateLoad(state) } catch CoreError.stateConfig {
                // Otra configuración (tipo de partida, reloj, BIOS): no es vigente (INT-H2).
                throw StateError.notCurrent
            }
            guard try ramBytes() == sramBefore else {
                try? core.stateLoad(previous)
                frames.publish { core.copyFramebuffer(to: $0) }
                throw StateError.notCurrent
            }
            // La RAM ya es la de disco: si solo cambia el pie del RTC no se persiste nada
            // (no rota backups ni reescribe el espejo, D81V2-H4). Sin copia confirmada
            // (lectura fallida al abrir) sí se pasa por el flush.
            let confirmed = persisters.count == 1 ? persisters[0].confirmedSRAM : nil
            let ramOnDisk = confirmed.map { $0.dropLast(core.sramFooterBytes) == sramBefore }
            if ramOnDisk != true {
                guard flushAll(sync: true) else {
                    try? core.stateLoad(previous)
                    frames.publish { core.copyFramebuffer(to: $0) }
                    throw StateError.saveFailed
                }
            }
            // El RTC del MBC3 vuelve a la hora real (el estado trae la del momento de guardarlo).
            core.setRTCTime(Int64(Date().timeIntervalSince1970))
            frames.publish { core.copyFramebuffer(to: $0) }
        }
        startThreadAndAudio()
    }

    /// El medio real completo (tras `sramLoad`, no el tamaño provisional de `info`), sin el
    /// pie del RTC (que sí cambia con un estado) (INT-H1).
    private func ramBytes() throws -> Data {
        try core.sramSave().dropLast(core.sramFooterBytes)
    }

    @MainActor
    private func startThreadAndAudio() {
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
        switch persisters.count {
        case 0:
            callback()
        case 1:
            persisters[0].whenMirrorIdle(callback)
        default:
            let group = DispatchGroup()
            for persister in persisters {
                group.enter()
                persister.whenMirrorIdle { group.leave() }
            }
            group.notify(queue: .global(qos: .utility), execute: callback)
        }
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

    /// Volumen y modo silencio (Ajustes › Audio). El volumen se aplica en el acto; la
    /// categoría de la sesión, al próximo arranque del motor.
    func applyAudioPreferences(_ preferences: AudioPreferences) {
        audioOutput.apply(preferences)
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

    enum StateError: Error, Equatable, LocalizedError {
        /// Los estados solo se guardan o cargan con el hilo de emulación aparcado.
        case notPaused
        /// La partida del estado no se pudo guardar: se volvió al estado anterior.
        case saveFailed
        /// El estado automático no corresponde a la partida vigente (la SRAM difiere):
        /// se revirtió el núcleo y no se tocó ninguna copia de la partida.
        case notCurrent

        var errorDescription: String? {
            switch self {
            case .notPaused: "La partida debe estar en pausa."
            case .saveFailed: "No se pudo guardar la partida del estado."
            case .notCurrent: "El estado guardado ya no corresponde a tu partida actual."
            }
        }
    }

    /// Guarda un estado con la sesión en pausa. Devuelve el estado y el frame actual
    /// (RGBA, 160×144 o 240×160) para la captura de la ranura.
    @MainActor
    func saveState() throws -> (state: Data, pixels: [UInt32]) {
        try withParkedCore { core in
            let state = try core.stateSave()
            var pixels = [UInt32](repeating: 0, count: frames.size.pixelCount)
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
        // Invariante (M9L2-H3): con varias persistencias (cable link) no hay estados.
        guard persisters.count <= 1 else { throw CoreError.linkUnsupported }
        try withParkedCore { core in
            // Si la partida del estado no se puede guardar en el acto, el núcleo vuelve a
            // como estaba: nunca queda en marcha una partida que no está en disco
            // (auditoría D2-D5 Codex, H2).
            let previous = try core.stateSave()
            try core.stateLoad(data)
            guard flushAll(sync: true) else {
                try? core.stateLoad(previous)
                frames.publish { core.copyFramebuffer(to: $0) }
                throw StateError.saveFailed
            }
            frames.publish { core.copyFramebuffer(to: $0) }
        }
    }

    /// Ejecuta `body` en el hilo principal con el núcleo mientras el hilo de emulación está
    /// aparcado en `control.wait()`: nadie más lo toca hasta que el propio hilo principal
    /// llame a `resume()` o `stop()`. El lock da la visibilidad de memoria entre hilos.
    @MainActor
    private func withParkedCore<T>(_ body: (any ConsoleCore) throws -> T) throws -> T {
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
        persisters.forEach { $0.prime() }

        while true {
            control.lock()
            if pauseRequested && !stopRequested {
                control.unlock()
                flushAll(sync: true)
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
            control.unlock()
            persisters.forEach { $0.retryIfFailed() }
            if flushNow { flushAll(sync: true) }

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
            core.setButtons(Self.combinedButtons(touch: buttons.value, pad: padButtons.value))
            let speed = speedFactor.load(ordering: .relaxed)
            core.runFrame()
            drainAudio(discard: speed > 1)
            frames.publish { core.copyFramebuffer(to: $0) }
            persisters.forEach { $0.check() }
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

        flushAll(sync: true)
        core.shutdown()
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

    private func seconds(_ ticks: UInt64) -> Double { Double(ticks) / ticksPerSecond }

    /// Guarda todas las partidas. **No cortocircuita**: si falla una, las demás se guardan igual
    /// (primero el flush, luego `&&`). Con `sync`, devuelve si todas quedaron en disco.
    @discardableResult
    private func flushAll(sync: Bool) -> Bool {
        var ok = true
        for persister in persisters {
            let flushed = persister.flush(sync: sync)
            ok = ok && flushed
        }
        return ok
    }
}

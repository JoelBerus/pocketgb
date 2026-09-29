import Foundation
import os

/// Una partida en marcha: dueña del núcleo y de su hilo de emulación.
/// Todo acceso a `core` ocurre en ese hilo (docs/02 §Hilos); el resto de hilos
/// se comunica con `control` (NSCondition), `buttons` y `frames`.
final class EmulatorSession: @unchecked Sendable {
    let info: RomInfo
    let frames = FrameBuffers()
    let buttons = ButtonMask()
    /// Aviso para mostrar al abrir (p. ej. .sav con tamaño inesperado).
    let loadWarning: String?

    private let core: CoreBridge
    private let saves: SaveStore?
    private let saveQueue = DispatchQueue(label: "PocketGB.saves", qos: .utility)
    private let log = Logger(subsystem: "com.joelbermudez.pocketgb", category: "session")

    // Estado de control compartido, protegido por `control`.
    private let control = NSCondition()
    private var pauseRequested = false
    private var stopRequested = false
    private var parked = false
    private var finished = false
    private var saveFailed = false
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

    init(romData: Data, savesDirectory: URL) throws {
        let core = try CoreBridge()
        let now = Int64(Date().timeIntervalSince1970)
        info = try core.loadROM(romData, unixTime: now)
        self.core = core

        var warning: String?
        var saves: SaveStore?
        if info.hasBattery, core.sramSaveSize > 0 {
            let store = SaveStore(directory: savesDirectory, fingerprint: info.fingerprint)
            do {
                try store.recoverOrphans(expectedSize: core.sramSaveSize)
                if let data = try store.load() {
                    try core.sramLoad(data)
                }
                saves = store
            } catch let e as CoreError where e == .sramSize {
                // No se sobrescribe un .sav que no entendemos (ESTADO §Pendiente M6).
                warning = "La partida guardada tiene un tamaño inesperado. No se tocará y esta sesión no guardará."
            } catch {
                warning = "No se pudo leer la partida (\(error.localizedDescription)). Esta sesión no guardará."
            }
        }
        self.saves = saves
        loadWarning = warning
    }

    func start() {
        let thread = Thread { [self] in run() }
        thread.name = "PocketGB.emulation"
        thread.qualityOfService = .userInteractive
        thread.start()
    }

    /// Pausa y espera a que el hilo guarde la SRAM pendiente y quede parado.
    func pause() {
        control.lock()
        pauseRequested = true
        control.broadcast()
        while !parked && !finished { control.wait() }
        control.unlock()
    }

    func resume() {
        control.lock()
        pauseRequested = false
        control.broadcast()
        control.unlock()
    }

    /// Detiene el hilo; al volver, la SRAM ya está escrita en disco.
    func stop() {
        control.lock()
        stopRequested = true
        control.broadcast()
        while !finished { control.wait() }
        control.unlock()
    }

    // MARK: - Hilo de emulación

    private func run() {
        var timebase = mach_timebase_info_data_t()
        mach_timebase_info(&timebase)
        ticksPerSecond = 1e9 * Double(timebase.denom) / Double(timebase.numer)
        let frameTicks = Self.frameSeconds * ticksPerSecond
        var deadline = Double(mach_absolute_time())
        if saves != nil {
            // Lo que ya hay en disco (o la RAM inicial si no había .sav).
            lastQueued = try? core.sramSave()
            control.lock(); confirmed = lastQueued; control.unlock()
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
            if saveFailed {
                saveFailed = false
                lastQueued = nil // forzar la reescritura
                dirtyLast = mach_absolute_time() // reintento tras el debounce
            }
            control.unlock()

            core.setButtons(buttons.value)
            core.runFrame()
            frames.publish { core.copyFramebuffer(to: $0) }
            checkSRAM()

            // Pacing por reloj hasta que llegue el audio (M5).
            deadline += frameTicks
            let now = Double(mach_absolute_time())
            if now - deadline > 5 * frameTicks {
                deadline = now // demasiado atrasados: no intentar recuperar
            } else if deadline > now {
                mach_wait_until(UInt64(deadline))
            }
        }

        flushSRAM(sync: true)
        control.lock()
        finished = true
        control.broadcast()
        control.unlock()
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
            saveQueue.sync {}
            control.lock()
            let onDisk = confirmed
            control.unlock()
            guard data != onDisk else { return }
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
                try saves.save(data)
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
        if sync { saveQueue.sync(execute: write) } else { saveQueue.async(execute: write) }
    }
}

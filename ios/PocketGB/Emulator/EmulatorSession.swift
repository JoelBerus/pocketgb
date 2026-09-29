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

    // Solo del hilo de emulación.
    private var dirtySinceFirst: UInt64?   // ticks del primer cambio sin guardar
    private var dirtyLast: UInt64 = 0      // ticks del último "juego guardó"
    private var lastSaved: Data?           // contenido del último .sav escrito o cargado

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
        let ticksPerSecond = 1e9 * Double(timebase.denom) / Double(timebase.numer)
        let frameTicks = Self.frameSeconds * ticksPerSecond
        var deadline = Double(mach_absolute_time())
        if saves != nil { lastSaved = try? core.sramSave() } // lo que ya hay en disco (o la RAM inicial)

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
                lastSaved = nil // forzar la reescritura
                if dirtySinceFirst == nil { dirtySinceFirst = mach_absolute_time() }
            }
            control.unlock()

            core.setButtons(buttons.value)
            core.runFrame()
            frames.publish { core.copyFramebuffer(to: $0) }
            checkSRAM(ticksPerSecond: ticksPerSecond)

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

    /// Debounce de 1,0 s tras cada "juego guardó" y red de seguridad de 60 s (docs/04 §Saves).
    private func checkSRAM(ticksPerSecond: Double) {
        guard saves != nil else { return }
        let now = mach_absolute_time()
        if core.sramDirty {
            core.clearSRAMDirty()
            dirtyLast = now
            if dirtySinceFirst == nil { dirtySinceFirst = now }
        }
        guard let first = dirtySinceFirst else { return }
        let sinceLast = Double(now - dirtyLast) / ticksPerSecond
        let sinceFirst = Double(now - first) / ticksPerSecond
        if sinceLast >= Self.debounceSeconds || sinceFirst >= Self.safetyNetSeconds {
            flushSRAM(sync: false)
        }
    }

    /// Copia la SRAM aquí (hilo de emulación) y la escribe en la cola de guardado.
    /// El flush síncrono (pausa, background, salida) no depende del flanco "el juego
    /// guardó": un juego puede escribir la SRAM y no deshabilitarla todavía. Por eso
    /// compara siempre con lo último escrito y guarda si difiere (incluye el RTC).
    private func flushSRAM(sync: Bool) {
        guard let saves else { return }
        if core.sramDirty { core.clearSRAMDirty() }
        dirtySinceFirst = nil
        let data: Data
        do {
            data = try core.sramSave()
        } catch {
            log.error("gb_sram_save falló: \(String(describing: error), privacy: .public)")
            return
        }
        guard data != lastSaved else {
            if sync { saveQueue.sync {} } // espera a escrituras ya encoladas
            return
        }
        lastSaved = data
        let write: @Sendable () -> Void = { [self] in
            do {
                try saves.save(data)
            } catch {
                log.error("No se pudo guardar la partida: \(error.localizedDescription, privacy: .public)")
                control.lock()
                saveFailed = true // se reintenta en el próximo ciclo
                control.unlock()
            }
        }
        if sync { saveQueue.sync(execute: write) } else { saveQueue.async(execute: write) }
    }
}

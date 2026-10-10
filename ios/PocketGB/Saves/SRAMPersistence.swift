import Foundation
import os

/// La ruta de guardado de la SRAM de un núcleo: debounce de 1 s, red de seguridad de 60 s,
/// flush síncrono en pausa/background/salida y reintento tras un fallo (docs/04 §Saves,
/// regla dura 6). Es el código de `EmulatorSession` (M4, D2, D5, D8.1) movido tal cual para que
/// un juego suelto use una instancia y el cable link (M9) use una por lado.
///
/// `check`, `prime`, `retryIfFailed` y el flush asíncrono se llaman solo desde el hilo de
/// emulación; `flush(sync: true)` también desde el hilo principal con la sesión aparcada.
/// Nonisolated: el closure de escritura corre en la cola de guardado (H0 de M5).
final class SRAMPersistence: @unchecked Sendable {
    private let core: any ConsoleCore
    private let target: SaveTarget
    private let clock: @Sendable () -> UInt64
    private let localSaveQueue = DispatchQueue(label: "PocketGB.saves-local", qos: .utility)
    private let log = Logger(subsystem: "com.joelbermudez.pocketgb", category: "session")

    // Estado compartido con la cola de guardado, protegido por `lock`.
    private let lock = NSLock()
    private var saveFailed = false
    private var confirmed: Data?           // último contenido que está en disco (o se cargó)

    // Solo del hilo de emulación.
    private var dirtyLast: UInt64?         // último "juego guardó" aún sin escribir
    private var lastCheck: UInt64 = 0      // último flush (para la red de seguridad)
    private var lastQueued: Data?          // último contenido encolado
    #if DEBUG
    private var failAsyncWrites = false    // `-failAsyncSaves`: toda escritura asíncrona falla (prueba H6)
    #endif

    static let debounceSeconds = 1.0
    static let safetyNetSeconds = 60.0

    /// Ticks de `mach_absolute_time()` por segundo.
    static let ticksPerSecond: Double = {
        var timebase = mach_timebase_info_data_t()
        mach_timebase_info(&timebase)
        return 1e9 * Double(timebase.denom) / Double(timebase.numer)
    }()

    /// - Parameter clock: reloj en ticks (`ticksPerSecond` por segundo); inyectable en las pruebas.
    ///   Se asume que la SRAM del núcleo ya se cargó: lo que hay ahora es lo que está en disco
    ///   (o la RAM inicial), así que una reanudación con la misma SRAM no reescribe nada (D81-H7).
    init(core: any ConsoleCore, target: SaveTarget, clock: @escaping @Sendable () -> UInt64 = { mach_absolute_time() }) {
        self.core = core
        self.target = target
        self.clock = clock
        let initial = try? core.sramSave()
        confirmed = initial
        lastQueued = initial
    }

    /// Abre la partida de un núcleo ya con el ROM cargado: recupera huérfanos, resuelve local/espejo,
    /// carga la SRAM en el núcleo y devuelve su persistencia. `persister == nil` si el cartucho no
    /// guarda o si esta sesión no debe guardar (`warning` lo explica).
    /// - Throws: `SaveOpening.Refusal` si la única partida está en iCloud sin descargar.
    static func open(core: any ConsoleCore, info: RomInfo, savesDirectory: URL, mirror: SaveMirror?,
                     snapshot: SaveMirror.Snapshot, mirrorWriter: (@Sendable (Data) throws -> Void)?,
                     lineage: SaveOpening.LineageOptions = .init())
        throws -> (persister: SRAMPersistence?, warning: SaveLoadWarning?) {
        guard info.hasBattery, core.sramSaveSize > 0 else { return (nil, nil) }
        let store = SaveStore(directory: savesDirectory, fingerprint: info.fingerprint)
        do {
            if info.console == .gameBoyAdvance {
                try store.recoverOrphans(validSizes: EmulatorSession.validSaveSizes(info))
            } else {
                try store.recoverOrphans(expectedSize: core.sramSaveSize)
            }
            let outcome = try SaveOpening.prepare(store: store, mirror: mirror, snapshot: snapshot,
                                                  validSizes: EmulatorSession.validSaveSizes(info),
                                                  mirrorWriter: mirrorWriter, lineage: lineage)
            if let data = outcome.data { try core.sramLoad(data) }
            return (outcome.target.map { SRAMPersistence(core: core, target: $0) }, outcome.warning)
        } catch let refusal as SaveOpening.Refusal {
            throw refusal
        } catch let e as CoreError where e == .sramSize {
            // No se sobrescribe un .sav que no entendemos (no debería llegar: se valida el tamaño antes).
            return (nil, .localWrongSize)
        } catch {
            return (nil, .unreadable("No se pudo leer la partida (\(error.localizedDescription))."))
        }
    }

    /// Último contenido que está en disco (o se cargó).
    var confirmedSRAM: Data? {
        lock.lock(); defer { lock.unlock() }
        return confirmed
    }

    /// Al arrancar el hilo de emulación: lo que ya hay en disco (o la RAM inicial si no había .sav).
    func prime() {
        lastQueued = try? core.sramSave()
        lock.lock(); confirmed = lastQueued; lock.unlock()
        // Espejo ausente o desfasado al abrir: se pone al día en su propia cola.
        if let initial = lastQueued { target.retryMirrorIfNeeded(initial) }
        lastCheck = clock()
        #if DEBUG
        failAsyncWrites = ProcessInfo.processInfo.arguments.contains("-failAsyncSaves")
        #endif
    }

    /// Tras una escritura asíncrona fallida: fuerza la reescritura tras el debounce.
    func retryIfFailed() {
        lock.lock()
        let failed = saveFailed
        saveFailed = false
        lock.unlock()
        if failed {
            lastQueued = nil // forzar la reescritura
            dirtyLast = clock() // reintento tras el debounce
        }
    }

    /// Debounce de 1,0 s tras cada "juego guardó" y red de seguridad de 60 s
    /// (docs/04 §Saves). La red no depende del flanco: un juego puede escribir la
    /// SRAM y dejarla habilitada; `flush` compara y solo escribe si cambió.
    func check() {
        let now = clock()
        if core.sramDirty {
            core.clearSRAMDirty()
            dirtyLast = now
        }
        if let last = dirtyLast, seconds(now &- last) >= Self.debounceSeconds {
            flush(sync: false)
        } else if seconds(now &- lastCheck) >= Self.safetyNetSeconds {
            flush(sync: false)
        }
    }

    private func seconds(_ ticks: UInt64) -> Double { Double(ticks) / Self.ticksPerSecond }

    /// Copia la SRAM aquí (hilo de emulación) y la escribe en la cola de guardado.
    /// - Asíncrono: se compara con lo último encolado.
    /// - Síncrono (pausa, background, salida): primero se vacía la cola y se compara
    ///   con lo último **confirmado en disco**, así una escritura asíncrona que falló
    ///   justo antes se reintenta aquí y no se pierde (auditoría M4, H6).
    /// - Returns: con `sync`, si la copia local quedó en disco (o ya lo estaba).
    @discardableResult
    func flush(sync: Bool) -> Bool {
        if core.sramDirty { core.clearSRAMDirty() }
        dirtyLast = nil
        lastCheck = clock()
        let data: Data
        do {
            data = try core.sramSave()
        } catch {
            log.error("gb_sram_save falló: \(String(describing: error), privacy: .public)")
            return false
        }
        if sync {
            localSaveQueue.sync {}
            let onDisk = confirmedSRAM
            guard data != onDisk else {
                target.retryMirrorIfNeeded(data)
                return true
            }
        } else {
            guard data != lastQueued else { return true }
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
                try target.persistLocal(data)
                lock.lock()
                confirmed = data
                lock.unlock()
            } catch {
                log.error("No se pudo guardar la partida: \(error.localizedDescription, privacy: .public)")
                lock.lock()
                saveFailed = true // se reintenta en el próximo ciclo o en el próximo flush síncrono
                lock.unlock()
            }
        }
        guard sync else {
            localSaveQueue.async(execute: write)
            return true
        }
        localSaveQueue.sync(execute: write)
        return confirmedSRAM == data
    }

    /// Llama a `callback` al terminar la escritura de espejo en vuelo. La copia local ya está
    /// cubierta por la barrera síncrona de `flush(sync: true)`.
    func whenMirrorIdle(_ callback: @escaping @Sendable () -> Void) {
        target.whenMirrorIdle(callback)
    }

    /// Para pruebas: espera a que terminen las escrituras locales encoladas.
    func waitForPendingWrites() { localSaveQueue.sync {} }
}

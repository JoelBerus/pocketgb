import Foundation
import os

/// Copia de la partida junto al ROM (`<rom>.sav`, en la carpeta de iCloud Drive o
/// de Archivos). Se lee y escribe con `NSFileCoordinator`. La copia local es la
/// autoritativa: un fallo del espejo nunca invalida el guardado (docs/04 §Saves).
struct SaveMirror: Sendable {
    let url: URL

    init(romURL: URL) {
        url = romURL.deletingPathExtension().appendingPathExtension("sav")
    }

    init(url: URL) {
        self.url = url
    }

    var exists: Bool { FileManager.default.fileExists(atPath: url.path) }
    var modificationDate: Date? { SaveStore.modificationDate(url) }

    /// Placeholder clásico de iCloud para un `.sav` sin descargar: `.<nombre>.sav.icloud`.
    var placeholderURL: URL {
        url.deletingLastPathComponent().appendingPathComponent(".\(url.lastPathComponent).icloud")
    }

    /// Estado del espejo al abrir un juego (auditoría D2, H1/H2). Se calcula fuera del
    /// hilo principal: si el `.sav` solo está en iCloud, se pide la descarga y la
    /// lectura coordinada espera a que termine.
    enum Snapshot: Sendable, Equatable {
        /// No hay `.sav` junto al ROM.
        case absent
        /// Leído: contenido y fecha.
        case read(Data, Date?)
        /// Existe (o existe en iCloud) pero no se pudo leer: no se debe tocar.
        case unavailable
    }

    func snapshot(polls: Int = downloadPolls) -> Snapshot {
        let fm = FileManager.default
        let inCloudOnly = fm.fileExists(atPath: placeholderURL.path)
        if !exists && !inCloudOnly { return .absent }
        if inCloudOnly || Self.isDataless(url) {
            try? fm.startDownloadingUbiquitousItem(at: url)
            // Se espera un poco a que llegue (auditoría D2, N2); fuera del hilo principal.
            for _ in 0..<polls where !exists || Self.isDataless(url) {
                Thread.sleep(forTimeInterval: 0.5)
            }
        }
        do {
            guard let data = try read() else { return .unavailable }
            return .read(data, modificationDate)
        } catch {
            return .unavailable
        }
    }

    /// Espera máxima a la descarga del `.sav` al abrir: 20 × 0,5 s.
    static let downloadPolls = 20

    /// En iCloud y sin datos locales (`.notDownloaded`). `.downloaded` sí tiene datos.
    static func isDataless(_ url: URL) -> Bool {
        let values = try? url.resourceValues(forKeys: [.isUbiquitousItemKey, .ubiquitousItemDownloadingStatusKey])
        return values?.isUbiquitousItem == true && values?.ubiquitousItemDownloadingStatus == .notDownloaded
    }

    func read() throws -> Data? {
        guard exists else { return nil }
        var result: Result<Data, Error> = .failure(CocoaError(.fileReadUnknown))
        var coordError: NSError?
        NSFileCoordinator(filePresenter: nil).coordinate(readingItemAt: url, options: [], error: &coordError) { u in
            result = Result { try Data(contentsOf: u) }
        }
        if let coordError { throw coordError }
        return try result.get()
    }

    func write(_ data: Data) throws {
        var result: Result<Void, Error> = .success(())
        var coordError: NSError?
        NSFileCoordinator(filePresenter: nil).coordinate(writingItemAt: url, options: .forReplacing,
                                                         error: &coordError) { u in
            result = Result { try data.write(to: u, options: .atomic) }
        }
        if let coordError { throw coordError }
        try result.get()
    }
}

/// Qué partida cargar al abrir un ROM (docs/04 §Saves, "Carga al abrir un ROM").
/// Es una decisión pura, sin E/S, para poder probar cada caso.
enum SaveResolution: Equatable {
    /// No hay partida: el juego empieza de cero.
    case none
    /// Cargar `data`.
    /// - `backupOther`: el espejo, cuando pierde frente a la local; se guarda como backup.
    /// - `installLocal`: la partida viene del espejo y se instala en local con
    ///   `AtomicFile`, que deja la local anterior en `.1` (así también queda respaldada).
    /// - `updateMirror`: el espejo falta o está desfasado y se reescribe con `data`.
    /// - `mirrorIgnored`: el espejo tiene un tamaño que el núcleo no acepta; no se
    ///   toca en toda la sesión y se avisa a Joel.
    /// - `quarantineLocal`: la local tiene un tamaño incorrecto; se aparta (fuera de la
    ///   rotación de backups) antes de instalar el espejo, y se avisa.
    case load(data: Data, backupOther: Data?, installLocal: Bool, updateMirror: Bool,
              mirrorIgnored: Bool, quarantineLocal: Bool)
    /// La única partida que hay tiene un tamaño incorrecto: no se carga ni se toca,
    /// y la sesión no guarda (para no sobrescribirla).
    case wrongSize(fromMirror: Bool)
    /// N7a · las dos partidas avanzaron por separado (linaje): hay que preguntar antes de cargar nada.
    case divergent

    struct Candidate: Equatable {
        let data: Data
        let date: Date?
    }

    /// - Parameters:
    ///   - local: `Saves/<huella>.sav`.
    ///   - mirror: `<rom>.sav` junto al ROM (nil si no hay o no hay carpeta).
    ///   - isValidSize: tamaños que acepta `gb_sram_load`.
    ///   - lineage: N7a, relación del espejo con la local según el historial (nil = sin calcular: regla por fecha).
    ///   - divergence: lo que eligió Joel si ya se le preguntó por una divergencia.
    static func resolve(local: Candidate?, mirror: Candidate?, mirrorIsOwned: Bool = false,
                        lineage: SaveLineage.MirrorRelation? = nil, divergence: DivergenceChoice? = nil,
                        isValidSize: (Int) -> Bool) -> SaveResolution {
        let localOK = local.map { isValidSize($0.data.count) } ?? false
        let mirrorOK = mirror.map { isValidSize($0.data.count) } ?? false
        switch (local, mirror) {
        case (nil, nil):
            return .none
        case let (local?, nil):
            guard localOK else { return .wrongSize(fromMirror: false) }
            return .load(data: local.data, backupOther: nil, installLocal: false,
                         updateMirror: true, mirrorIgnored: false, quarantineLocal: false)
        case let (nil, mirror?):
            guard mirrorOK else { return .wrongSize(fromMirror: true) }
            return .load(data: mirror.data, backupOther: nil, installLocal: true,
                         updateMirror: false, mirrorIgnored: false, quarantineLocal: false)
        case let (local?, mirror?):
            if !mirrorOK {
                // El espejo no se entiende: se usa la local (si vale) y el espejo no se toca.
                guard localOK else { return .wrongSize(fromMirror: false) }
                return .load(data: local.data, backupOther: nil, installLocal: false,
                             updateMirror: false, mirrorIgnored: true, quarantineLocal: false)
            }
            guard localOK else {
                // Local con tamaño incorrecto: se aparta intacta y se usa el espejo (auditoría D2, H3).
                return .load(data: mirror.data, backupOther: nil, installLocal: true,
                             updateMirror: false, mirrorIgnored: false, quarantineLocal: true)
            }
            if local.data == mirror.data {
                return .load(data: local.data, backupOther: nil, installLocal: false,
                             updateMirror: false, mirrorIgnored: false, quarantineLocal: false)
            }
            if mirrorIsOwned {
                // Contenido y fecha coinciden con una escritura de PocketGB que nadie tocó
                // después (`SaveStore.recognizesOwnedMirror`): una fecha posterior solo
                // significa que una escritura asíncrona vieja terminó tarde. La local gana
                // sin respaldar el espejo obsoleto.
                return .load(data: local.data, backupOther: nil, installLocal: false,
                             updateMirror: true, mirrorIgnored: false, quarantineLocal: false)
            }
            // N7a · linaje (N-README §3.4): con historial no se mira la fecha (el reloj puede ir desfasado).
            let useLocal = SaveResolution.load(data: local.data, backupOther: nil, installLocal: false, updateMirror: true,
                                               mirrorIgnored: false, quarantineLocal: false)
            let useMirror = SaveResolution.load(data: mirror.data, backupOther: nil, installLocal: true,
                                                updateMirror: false, mirrorIgnored: false, quarantineLocal: false)
            switch lineage {
            case .ownEarlier?: return useLocal
            case .external?: return useMirror
            case .divergent?:
                switch divergence {
                case nil: return .divergent
                case .keepLocal?: return useLocal
                case .useOther?: return useMirror
                }
            case .same?, .noHistory?, nil: break
            }
            // Difieren: gana la más reciente; ante la duda (sin fecha o empate), la local.
            let mirrorNewer = (mirror.date ?? .distantPast) > (local.date ?? .distantPast)
            return mirrorNewer
                ? .load(data: mirror.data, backupOther: nil, installLocal: true,
                        updateMirror: false, mirrorIgnored: false, quarantineLocal: false)
                : .load(data: local.data, backupOther: mirror.data, installLocal: false,
                        updateMirror: true, mirrorIgnored: false, quarantineLocal: false)
        }
    }
}

/// Aviso al abrir un juego sobre su partida (título y texto para Joel).
enum SaveLoadWarning: Equatable, Sendable {
    /// La partida de este iPhone tiene un tamaño incorrecto y no hay otra: no se toca y no se guarda.
    case localWrongSize
    /// El `.sav` junto al juego tiene un tamaño incorrecto y no hay otra: no se toca y no se guarda.
    case mirrorWrongSizeOnly
    /// El `.sav` junto al juego tiene un tamaño incorrecto; se usa la de este iPhone.
    case mirrorIgnored
    /// La de este iPhone tenía un tamaño incorrecto: se apartó y se usa la de junto al juego.
    case localQuarantined
    /// El `.sav` de iCloud no se pudo leer (sin descargar): se usa la local y no se toca.
    case mirrorUnavailable
    /// Otro ROM de la carpeta comparte el nombre del `.sav`: no se copia junto al juego.
    case mirrorShared
    /// Error al leer la partida local.
    case unreadable(String)
    /// N7a · el `.sav` junto al juego cambió fuera de PocketGB y la de este iPhone no: se instaló con copia.
    case externalChange
    /// N7a · divergencia resuelta con la elección de Joel: la otra quedó como momento «Conflicto» y apartada.
    case divergenceResolved

    var title: String {
        switch self {
        case .localWrongSize, .mirrorWrongSizeOnly, .mirrorIgnored, .localQuarantined:
            "Partida con tamaño inesperado"
        case .mirrorUnavailable: "Partida de iCloud sin descargar"
        case .mirrorShared: "Partida sin copia junto al juego"
        case .unreadable: "No se pudo leer la partida"
        case .externalChange: "Partida actualizada desde la carpeta"
        case .divergenceResolved: "Partida en conflicto guardada"
        }
    }

    var message: String {
        switch self {
        case .localWrongSize:
            "La partida guardada en este iPhone tiene un tamaño inesperado. No se tocará y esta sesión no guardará."
        case .mirrorWrongSizeOnly:
            "El archivo .sav junto al juego tiene un tamaño inesperado. No se tocará y esta sesión no guardará."
        case .mirrorIgnored:
            "El archivo .sav junto al juego tiene un tamaño inesperado. No se tocará; se usa la partida guardada en este iPhone."
        case .localQuarantined:
            "La partida de este iPhone tenía un tamaño inesperado: se apartó sin borrarla y se usa la del archivo .sav junto al juego."
        case .mirrorUnavailable:
            "El archivo .sav junto al juego está en iCloud y no se pudo descargar. Se usa la partida de este iPhone y el de iCloud no se tocará."
        case .mirrorShared:
            "Otro juego de la carpeta tiene el mismo nombre, así que la partida solo se guarda en este iPhone."
        case .externalChange:
            "El archivo .sav junto al juego cambió fuera de PocketGB y se ha instalado. La partida anterior de este iPhone quedó en las copias de seguridad."
        case .divergenceResolved:
            "La otra partida no se ha perdido: está en Momentos como «Conflicto» y en Ajustes › Partidas › Copias apartadas."
        case .unreadable(let detail):
            "\(detail) Esta sesión no guardará."
        }
    }
}

/// Aplica la decisión de carga con E/S real (auditoría D2, H9): backups, cuarentena e
/// instalación en local. Se prueba sobre directorios temporales.
enum SaveOpening {
    struct Outcome {
        /// Partida a cargar en el núcleo (nil = empezar de cero).
        let data: Data?
        /// Destino de los guardados (nil = esta sesión no guarda).
        let target: SaveTarget?
        let warning: SaveLoadWarning?
    }

    enum Refusal: Error, Equatable {
        /// No hay partida local y la de iCloud no se pudo leer: abrir empezaría de cero
        /// y el primer guardado podría pisarla. Mejor no abrir (auditoría D2, H1).
        case mirrorNotDownloaded
        /// N7a · divergencia: la partida de este iPhone y la de junto al juego avanzaron por separado. No se carga
        /// nada hasta que Joel elija (`DivergenceChoice`).
        case divergence(localDate: Date?, otherDate: Date?)
    }

    /// N7a · opciones de linaje de una apertura.
    struct LineageOptions {
        var divergence: DivergenceChoice?
        /// Donde queda la perdedora de una divergencia como momento «Conflicto …» (nil = solo copia apartada).
        var conflictMoments: MomentStore?

        init(divergence: DivergenceChoice? = nil, conflictMoments: MomentStore? = nil) {
            self.divergence = divergence
            self.conflictMoments = conflictMoments
        }
    }

    /// Nombre del momento con la perdedora de una divergencia.
    static func conflictName(_ date: Date = Date()) -> String {
        "Conflicto \(date.formatted(date: .abbreviated, time: .shortened))"
    }

    static func prepare(store: SaveStore, mirror: SaveMirror?, snapshot: SaveMirror.Snapshot,
                        validSizes: Set<Int>,
                        mirrorWriter: (@Sendable (Data) throws -> Void)? = nil,
                        lineage options: LineageOptions = LineageOptions()) throws -> Outcome {
        func makeTarget(_ mirror: SaveMirror?, pending: Bool = false) -> SaveTarget {
            if let mirrorWriter {
                return SaveTarget(local: store, mirror: mirror, mirrorPending: pending,
                                  mirrorWriter: mirrorWriter)
            }
            return SaveTarget(local: store, mirror: mirror, mirrorPending: pending)
        }
        let local = try store.load().map { SaveResolution.Candidate(data: $0, date: store.modificationDate) }
        var usableMirror = mirror
        var mirrorCandidate: SaveResolution.Candidate?
        var unavailable = false
        if mirror != nil {
            switch snapshot {
            case .absent:
                break
            case let .read(data, date):
                mirrorCandidate = .init(data: data, date: date)
            case .unavailable:
                guard local != nil else { throw Refusal.mirrorNotDownloaded }
                usableMirror = nil
                unavailable = true
            }
        }
        let mirrorIsOwned = mirrorCandidate.map { store.recognizesOwnedMirror($0.data, date: $0.date) } ?? false
        let relation: SaveLineage.MirrorRelation? = (local != nil && mirrorCandidate != nil)
            ? SaveLineage.relation(local: SaveLineage.sha256(local!.data), mirror: SaveLineage.sha256(mirrorCandidate!.data),
                                   history: store.lineageHistory())
            : nil
        switch SaveResolution.resolve(local: local, mirror: mirrorCandidate, mirrorIsOwned: mirrorIsOwned,
                                      lineage: relation, divergence: options.divergence,
                                      isValidSize: { validSizes.contains($0) }) {
        case .divergent:
            throw Refusal.divergence(localDate: local?.date, otherDate: mirrorCandidate?.date)
        case .none:
            return Outcome(data: nil, target: makeTarget(usableMirror), warning: nil)
        case let .load(data, backupOther, installLocal, updateMirror, mirrorIgnored, quarantineLocal):
            // Auditoría N1, H1 (regla 6): un espejo que PocketGB no escribió y que difiere de la
            // local se resuelve por fecha; el perdedor, sea cual sea, va además a una copia apartada
            // que no rota (el anillo de 5 backups lo desplazaría en 5 guardados).
            if let local, let mirrorCandidate, !mirrorIsOwned, !mirrorIgnored, !quarantineLocal,
               local.data != mirrorCandidate.data {
                try store.keepMirrorLoser(data == mirrorCandidate.data ? local.data : mirrorCandidate.data)
            }
            // N7a · divergencia resuelta: la que pierde queda además como momento «Conflicto …» (solo partida).
            if relation == .divergent, let local, let mirrorCandidate, let moments = options.conflictMoments {
                let loser = data == mirrorCandidate.data ? local.data : mirrorCandidate.data
                _ = try? moments.create(.init(state: nil, sram: loser, thumbnail: nil), name: conflictName())
            }
            if let backupOther { try store.addBackup(backupOther) }
            if quarantineLocal { try store.quarantineCurrent() }
            if installLocal { try store.save(data) }
            let target = makeTarget(mirrorIgnored ? nil : usableMirror, pending: updateMirror)
            let warning: SaveLoadWarning? = mirrorIgnored ? .mirrorIgnored
                : relation == .external && data != local?.data ? .externalChange
                : relation == .divergent ? .divergenceResolved
                : quarantineLocal ? .localQuarantined
                : unavailable ? .mirrorUnavailable : nil
            return Outcome(data: data, target: target, warning: warning)
        case let .wrongSize(fromMirror):
            return Outcome(data: nil, target: nil, warning: fromMirror ? .mirrorWrongSizeOnly : .localWrongSize)
        }
    }
}

/// Destino de los guardados de una sesión: la copia local (obligatoria) y el espejo
/// (opcional). La local se escribe desde la cola de guardado de `EmulatorSession`;
/// el espejo tiene su propia cola serie y nunca bloquea un flush local.
private final class MirrorChannelRegistry: @unchecked Sendable {
    static let shared = MirrorChannelRegistry()
    private let lock = NSLock()
    private var channels: [String: MirrorChannel] = [:]

    func channel(for fingerprint: String) -> MirrorChannel {
        lock.lock()
        defer { lock.unlock() }
        if let existing = channels[fingerprint] { return existing }
        let channel = MirrorChannel(fingerprint: fingerprint)
        channels[fingerprint] = channel
        return channel
    }
}

private final class MirrorChannel: @unchecked Sendable {
    struct Request: Sendable {
        let data: Data
        let store: SaveStore
        let writer: @Sendable (Data) throws -> Void
        /// Espejo en disco, para anotar su fecha tras escribirlo.
        let mirrorURL: URL?
    }

    private let queue: DispatchQueue
    private let lock = NSLock()
    private var pendingRequest: Request?
    /// Contenido que se está escribiendo ahora (fuera del lock).
    private var inFlight: Data?
    private var workerRunning = false
    private var needsRetry = false
    private var idleCallbacks: [@Sendable () -> Void] = []
    private let log = Logger(subsystem: "com.joelbermudez.pocketgb", category: "saves")

    init(fingerprint: String) {
        queue = DispatchQueue(label: "PocketGB.save-mirror.\(fingerprint)", qos: .utility)
    }

    var pending: Bool {
        lock.lock()
        defer { lock.unlock() }
        return needsRetry || workerRunning || pendingRequest != nil
    }

    func markNeedsRetry() {
        lock.lock()
        needsRetry = true
        lock.unlock()
    }

    func enqueue(_ request: Request) {
        lock.lock()
        // El mismo contenido que ya se está escribiendo no se repite: si esa escritura
        // falla, ella misma se reencola (ver `drain`).
        if pendingRequest == nil, inFlight == request.data {
            lock.unlock()
            return
        }
        pendingRequest = request
        needsRetry = true
        let shouldStart = !workerRunning
        if shouldStart { workerRunning = true }
        lock.unlock()
        if shouldStart { queue.async { [self] in drain() } }
    }

    func retryIfNeeded(_ request: Request) {
        lock.lock()
        guard needsRetry, !(pendingRequest == nil && inFlight == request.data) else {
            lock.unlock()
            return
        }
        pendingRequest = request
        let shouldStart = !workerRunning
        if shouldStart { workerRunning = true }
        lock.unlock()
        if shouldStart { queue.async { [self] in drain() } }
    }

    func whenIdle(_ callback: @escaping @Sendable () -> Void) {
        lock.lock()
        if workerRunning {
            idleCallbacks.append(callback)
            lock.unlock()
        } else {
            lock.unlock()
            callback()
        }
    }

    private func drain() {
        while true {
            lock.lock()
            guard let request = pendingRequest else {
                needsRetry = false
                workerRunning = false
                let callbacks = idleCallbacks
                idleCallbacks.removeAll()
                lock.unlock()
                callbacks.forEach { $0() }
                return
            }
            pendingRequest = nil
            inFlight = request.data
            lock.unlock()

            do {
                // Write-ahead local: si el proceso muere después del replace remoto y
                // antes de confirmarlo, la próxima apertura aún reconoce el contenido.
                try request.store.recordMirrorAttempt(request.data)
                try request.writer(request.data)
                try request.store.recordSuccessfulMirror(
                    request.data, observedDate: request.mirrorURL.flatMap(SaveStore.modificationDate))
                lock.lock()
                inFlight = nil
                lock.unlock()
            } catch {
                lock.lock()
                inFlight = nil
                let newerRequestExists = pendingRequest != nil
                if !newerRequestExists { pendingRequest = request }
                needsRetry = true
                if !newerRequestExists { workerRunning = false }
                let callbacks = newerRequestExists ? [] : idleCallbacks
                if !newerRequestExists { idleCallbacks.removeAll() }
                lock.unlock()
                log.error("Espejo de la partida sin escribir (se reintentará): \(error.localizedDescription, privacy: .public)")
                callbacks.forEach { $0() }
                if !newerRequestExists { return }
            }
        }
    }
}

final class SaveTarget: @unchecked Sendable {
    let local: SaveStore
    let mirror: SaveMirror?
    private let mirrorWriter: (@Sendable (Data) throws -> Void)?
    private let mirrorChannel: MirrorChannel?

    init(local: SaveStore, mirror: SaveMirror?, mirrorPending: Bool = false) {
        self.local = local
        self.mirror = mirror
        if let mirror {
            mirrorWriter = { @Sendable data in try mirror.write(data) }
        } else {
            mirrorWriter = nil
        }
        mirrorChannel = mirror == nil ? nil : MirrorChannelRegistry.shared.channel(for: local.fingerprint)
        if mirrorPending { mirrorChannel?.markNeedsRetry() }
    }

    /// Inyección para probar un proveedor de archivos lento o bloqueado sin sustituir
    /// la escritura local real.
    init(local: SaveStore, mirror: SaveMirror?, mirrorPending: Bool = false,
         mirrorWriter: @escaping @Sendable (Data) throws -> Void) {
        self.local = local
        self.mirror = mirror
        self.mirrorWriter = mirrorWriter
        // Sin espejo no hay canal, igual que en producción: un escritor inyectado nunca
        // escribe un espejo que la sesión decidió no tocar (ignorado o sin descargar).
        mirrorChannel = mirror == nil ? nil : MirrorChannelRegistry.shared.channel(for: local.fingerprint)
        if mirrorPending { mirrorChannel?.markNeedsRetry() }
    }

    /// Verdadero mientras hay una copia en curso, pendiente o fallida.
    var mirrorPending: Bool {
        mirrorChannel?.pending ?? false
    }

    /// Guarda primero la copia local atómica. El espejo solo se encola y queda fuera
    /// de la barrera que usa pausa/background/salida/memoria baja.
    func persistLocal(_ data: Data) throws {
        try local.save(data)
        enqueueMirror(data)
    }

    /// Reintenta en la cola del espejo, también al abrir un juego. Si ya hay una
    /// escritura remota en curso, esa escritura drenará el último valor pendiente.
    func retryMirrorIfNeeded(_ data: Data) {
        guard let mirrorWriter, let mirrorChannel else { return }
        mirrorChannel.retryIfNeeded(.init(data: data, store: local, writer: mirrorWriter, mirrorURL: mirror?.url))
    }

    /// Se invoca cuando no queda una escritura de espejo en vuelo. Un fallo deja el
    /// contenido marcado para reintento, pero también libera la tarea de fondo.
    func whenMirrorIdle(_ callback: @escaping @Sendable () -> Void) {
        guard let mirrorChannel else {
            callback()
            return
        }
        mirrorChannel.whenIdle(callback)
    }

    /// Coalescer: una escritura que ya empezó no se puede cancelar, pero mientras está
    /// bloqueada solo se conserva el contenido más reciente recibido.
    private func enqueueMirror(_ data: Data) {
        guard let mirrorWriter, let mirrorChannel else { return }
        mirrorChannel.enqueue(.init(data: data, store: local, writer: mirrorWriter, mirrorURL: mirror?.url))
    }
}

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

    struct Candidate: Equatable {
        let data: Data
        let date: Date?
    }

    /// - Parameters:
    ///   - local: `Saves/<huella>.sav`.
    ///   - mirror: `<rom>.sav` junto al ROM (nil si no hay o no hay carpeta).
    ///   - isValidSize: tamaños que acepta `gb_sram_load`.
    static func resolve(local: Candidate?, mirror: Candidate?, isValidSize: (Int) -> Bool) -> SaveResolution {
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

    var title: String {
        switch self {
        case .localWrongSize, .mirrorWrongSizeOnly, .mirrorIgnored, .localQuarantined:
            "Partida con tamaño inesperado"
        case .mirrorUnavailable: "Partida de iCloud sin descargar"
        case .mirrorShared: "Partida sin copia junto al juego"
        case .unreadable: "No se pudo leer la partida"
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
    }

    static func prepare(store: SaveStore, mirror: SaveMirror?, snapshot: SaveMirror.Snapshot,
                        validSizes: Set<Int>) throws -> Outcome {
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
        switch SaveResolution.resolve(local: local, mirror: mirrorCandidate,
                                      isValidSize: { validSizes.contains($0) }) {
        case .none:
            return Outcome(data: nil, target: SaveTarget(local: store, mirror: usableMirror), warning: nil)
        case let .load(data, backupOther, installLocal, updateMirror, mirrorIgnored, quarantineLocal):
            if let backupOther { try store.addBackup(backupOther) }
            if quarantineLocal { try store.quarantineCurrent() }
            if installLocal { try store.save(data) }
            let target = SaveTarget(local: store, mirror: mirrorIgnored ? nil : usableMirror,
                                    mirrorPending: updateMirror)
            let warning: SaveLoadWarning? = mirrorIgnored ? .mirrorIgnored
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
final class SaveTarget: @unchecked Sendable {
    let local: SaveStore
    let mirror: SaveMirror?
    private let mirrorWriter: (@Sendable (Data) throws -> Void)?
    private let mirrorQueue = DispatchQueue(label: "PocketGB.save-mirror", qos: .utility)
    private let mirrorLock = NSLock()
    private var pendingMirrorData: Data?
    private var mirrorWorkerRunning = false
    private var mirrorNeedsRetry = false
    private let log = Logger(subsystem: "com.joelbermudez.pocketgb", category: "saves")

    init(local: SaveStore, mirror: SaveMirror?, mirrorPending: Bool = false) {
        self.local = local
        self.mirror = mirror
        if let mirror {
            mirrorWriter = { @Sendable data in try mirror.write(data) }
        } else {
            mirrorWriter = nil
        }
        mirrorNeedsRetry = mirrorPending && mirror != nil
    }

    /// Inyección para probar un proveedor de archivos lento o bloqueado sin sustituir
    /// la escritura local real.
    init(local: SaveStore, mirror: SaveMirror?, mirrorPending: Bool = false,
         mirrorWriter: @escaping @Sendable (Data) throws -> Void) {
        self.local = local
        self.mirror = mirror
        self.mirrorWriter = mirrorWriter
        mirrorNeedsRetry = mirrorPending
    }

    /// Verdadero mientras hay una copia en curso, pendiente o fallida.
    var mirrorPending: Bool {
        mirrorLock.lock()
        defer { mirrorLock.unlock() }
        return mirrorNeedsRetry || mirrorWorkerRunning || pendingMirrorData != nil
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
        guard mirrorWriter != nil else { return }
        mirrorLock.lock()
        guard mirrorNeedsRetry else {
            mirrorLock.unlock()
            return
        }
        pendingMirrorData = data
        if mirrorWorkerRunning {
            mirrorLock.unlock()
            return
        }
        mirrorWorkerRunning = true
        mirrorLock.unlock()
        mirrorQueue.async { [self] in drainMirror() }
    }

    /// Coalescer: una escritura que ya empezó no se puede cancelar, pero mientras está
    /// bloqueada solo se conserva el contenido más reciente recibido.
    private func enqueueMirror(_ data: Data) {
        guard mirrorWriter != nil else { return }
        mirrorLock.lock()
        pendingMirrorData = data
        mirrorNeedsRetry = true
        if mirrorWorkerRunning {
            mirrorLock.unlock()
            return
        }
        mirrorWorkerRunning = true
        mirrorLock.unlock()
        mirrorQueue.async { [self] in drainMirror() }
    }

    private func drainMirror() {
        guard let mirrorWriter else { return }
        while true {
            mirrorLock.lock()
            guard let data = pendingMirrorData else {
                mirrorNeedsRetry = false
                mirrorWorkerRunning = false
                mirrorLock.unlock()
                return
            }
            pendingMirrorData = nil
            mirrorLock.unlock()

            do {
                try mirrorWriter(data)
            } catch {
                mirrorLock.lock()
                let retryWasQueuedWhileWriting = pendingMirrorData != nil
                if !retryWasQueuedWhileWriting { pendingMirrorData = data }
                mirrorNeedsRetry = true
                if !retryWasQueuedWhileWriting { mirrorWorkerRunning = false }
                mirrorLock.unlock()
                log.error("Espejo de la partida sin escribir (se reintentará): \(error.localizedDescription, privacy: .public)")
                if !retryWasQueuedWhileWriting { return }
            }
        }
    }
}

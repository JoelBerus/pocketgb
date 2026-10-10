import Foundation

/// N7c · estado de la partida de un juego para el detalle.
struct SaveStatus: Equatable, Sendable {
    /// Equipo de donde vino la partida actual (nil = este iPhone).
    let device: String?
    let date: Date
    /// «Continuar donde lo dejaste en <equipo>»: el estado automático llegó con la partida y sigue vigente.
    let continuesFromOtherDevice: Bool

    /// - Parameter resumable: hay un estado automático vigente.
    static func read(_ store: SaveStore, resumable: Bool) -> SaveStatus? {
        guard let date = store.modificationDate else { return nil }
        if let origin = SaveImport.Origin.load(store), let local = try? store.load(),
           SaveLineage.sha256(local) == origin.savSHA256 {
            return SaveStatus(device: origin.deviceName, date: origin.importedAt,
                              continuesFromOtherDevice: origin.continuation && resumable)
        }
        return SaveStatus(device: nil, date: date, continuesFromOtherDevice: false)
    }

    func text(now: Date = Date()) -> String {
        let when = date.formatted(.relative(presentation: .named, unitsStyle: .wide))
        return "Partida: \(device ?? "este iPhone") · \(when)"
    }
}

/// N7b · importación que espera la elección de Joel.
struct ImportPrompt: Identifiable {
    let id = UUID()
    let entry: RomEntry
    let plan: SaveImport.Plan
    /// Título del juego tal como lo muestra la biblioteca (la pregunta siempre lo nombra; auditoría N7 iOS, H3).
    let gameTitle: String
    /// La partida ya es la misma; solo cambiaría el punto para continuar (auditoría N7 iOS, H1).
    var stateOnly = false

    var title: String {
        if stateOnly { return "¿Cambiar dónde continúas «\(gameTitle)»?" }
        if plan.raw { return "¿Usar este .sav en «\(gameTitle)»?" }
        switch plan.relation {
        case .older: return "Paquete más antiguo de «\(gameTitle)»"
        default: return "Dos partidas distintas de «\(gameTitle)»"
        }
    }

    var message: String {
        let from = plan.origin.map { " de \($0)" } ?? ""
        let keep = "La que no elijas no se borra: queda en Momentos como «Conflicto» y en las copias apartadas."
        if stateOnly {
            return "La partida\(from) es la misma que ya tienes, pero el punto para continuar es otro. Si lo cambias, el de este iPhone queda en Momentos › «Antes de cargar»; si no, el del paquete queda como «Conflicto»."
        }
        if plan.raw {
            return "Sustituirá la partida de «\(gameTitle)». La actual (si la hay) se guarda antes en «Antes de cargar» y en las copias de seguridad."
        }
        switch plan.relation {
        case .older: return "La partida\(from) es una versión que este iPhone ya tuvo y que después avanzó. \(keep)"
        case .divergent(baseKnown: false):
            return "La partida\(from) parte de una versión que este iPhone no tiene (quizá aún no ha llegado). \(keep)"
        default: return "La partida de este iPhone y la\(from) avanzaron por separado. \(keep)"
        }
    }
}

/// N7b · un `.sav` abierto con PocketGB cuyo nombre encaja con varios juegos: Joel elige (auditoría N7 iOS, H3).
struct SaveTargetChoice: Identifiable {
    let id = UUID()
    let fileName: String
    let data: Data
    let candidates: [RomEntry]
}

extension AppState {
    /// El juego de la biblioteca con esta huella (clave de 32 hex).
    func entry(fingerprint: String) -> RomEntry? {
        library.entries.first { libraryPrefs.fingerprint(of: $0) == fingerprint || $0.fingerprint == fingerprint }
    }

    private func savesStore(_ fingerprint: String) -> SaveStore? {
        (storageDirectories.saves ?? (try? SaveStore.defaultDirectory())).map { SaveStore(directory: $0, fingerprint: fingerprint) }
    }

    /// Lee el ROM (fuera del hilo principal) y lo carga en un núcleo para saber huella, batería y tamaños válidos con los
    /// ajustes del juego.
    nonisolated static func readCartridge(url: URL, console: Console, emulation: EmulationOptions) throws
        -> (cartridge: SaveImport.Cartridge, digest: Data, info: RomInfo) {
        let rom = try LibraryScanner.readROM(url, limit: LibraryScanner.romLimit(for: console))
        guard let digest = RomFingerprint.fullDigest(data: rom, console: console) else { throw CocoaError(.fileReadCorruptFile) }
        let info: RomInfo
        switch console {
        case .gameBoy:
            info = try CoreBridge().loadROM(rom, unixTime: 0, colorForGameBoy: emulation.colorForGameBoy,
                                            compatPalette: emulation.compatPalette)
        case .gameBoyAdvance:
            info = try GBACoreBridge().loadROM(rom, bios: nil, unixTime: 0, saveType: emulation.gbaSaveType, rtc: emulation.gbaRTC)
        }
        return (SaveImport.Cartridge(info: info, validSizes: EmulatorSession.validSaveSizes(info)), digest, info)
    }

    private func emulationOptions(_ entry: RomEntry) -> EmulationOptions {
        gameplay.data.emulation(with: libraryPrefs.overrides(fingerprint: libraryPrefs.fingerprint(of: entry), path: entry.id))
    }

    /// N7c · «Continuar donde lo dejaste en <equipo>» si el estado automático llegó de otro equipo con la partida.
    func continueTitle(_ entry: RomEntry) -> String {
        guard let fp = libraryPrefs.fingerprint(of: entry), let status = saveStatuses[fp],
              status.continuesFromOtherDevice, let device = status.device else { return "Continuar" }
        return "Continuar donde lo dejaste en \(device)"
    }

    // MARK: Importar

    /// «Abrir con PocketGB» desde Archivos, Drive o la hoja de compartir.
    func handleIncomingFile(_ url: URL) {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let data: Data
        do {
            let size = (try url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            guard size <= SaveImport.maxFileBytes else { throw SaveImport.Rejection.tooLarge }
            data = try Data(contentsOf: url)
        } catch {
            return notify("No se pudo importar", error.localizedDescription)
        }
        if PGBMPackage.looksLikePackage(data) {
            guard let package = try? PGBMPackage.parse(data) else {
                // El error exacto (versión más nueva, dañado…) lo da la validación completa.
                return notify("No se pudo importar", Self.containerMessage(data) + " No se ha cambiado nada.")
            }
            let fingerprint = package.romFingerprint.prefix(16).map { String(format: "%02x", $0) }.joined()
            guard let entry = entry(fingerprint: fingerprint) else {
                return notify("Juego no encontrado",
                              "El paquete es de un juego que no está en tu biblioteca (o aún no se ha leído). Añade el juego a la carpeta y vuelve a abrir el paquete. No se ha cambiado nada.")
            }
            importSave(data, into: entry)
        } else {
            // Una copia en conflicto («Juego 2.sav», «Juego (1).sav»…) se busca por su nombre original (H8).
            // Primero el nombre exacto («Juego 2.sav» puede ser la partida de «Juego 2.gb»).
            let raw = url.deletingPathExtension().lastPathComponent
            func matching(_ stem: String) -> [RomEntry] {
                library.entries.filter {
                    $0.url.deletingPathExtension().lastPathComponent.lowercased() == stem.lowercased() && !libraryPrefs.isHidden($0)
                }
            }
            var candidates = matching(raw)
            if candidates.isEmpty { candidates = matching(ConflictCopies.originalStem(raw)) }
            switch candidates.count {
            case 0:
                notify("¿De qué juego es?",
                       "No hay un juego con el mismo nombre que «\(url.lastPathComponent)». Ábrelo desde el detalle del juego › Importar partida. No se ha cambiado nada.")
            case 1:
                importSave(data, into: candidates[0])
            default:
                saveTargetChoice = SaveTargetChoice(fileName: url.lastPathComponent, data: data, candidates: candidates)
            }
        }
    }

    nonisolated static func containerMessage(_ data: Data) -> String {
        do { _ = try PGBMPackage.parse(data); return "" } catch { return error.localizedDescription }
    }

    /// Valida y aplica una importación en `entry`. Nada se toca hasta que la validación termina.
    func importSave(_ data: Data, into entry: RomEntry, choice: DivergenceChoice? = nil, plan existing: SaveImport.Plan? = nil) {
        guard !opening, !travelBusy else {
            return notify("No se pudo importar", FingerprintOwnership.Busy(owner: "apertura").localizedDescription)
        }
        travelBusy = true
        let url = entry.url, console = entry.console, emulation = emulationOptions(entry)
        let savesDirectory = storageDirectories.saves
        Task.detached(priority: .userInitiated) { [weak self] in
            let result = Result<SaveImport.Plan, Error> {
                if let existing { return existing }
                let cart = try Self.readCartridge(url: url, console: console, emulation: emulation).cartridge
                let store = SaveStore(directory: try savesDirectory ?? SaveStore.defaultDirectory(), fingerprint: cart.fingerprint)
                return try SaveImport.evaluate(data, cartridge: cart, local: try store.load(), known: store.knownHashes())
            }
            await self?.finishImport(result, entry: entry, choice: choice)
        }
    }

    private func finishImport(_ result: Result<SaveImport.Plan, Error>, entry: RomEntry, choice: DivergenceChoice?) {
        travelBusy = false
        let plan: SaveImport.Plan
        switch result {
        case .success(let p): plan = p
        case .failure(let error):
            let newer = (error as? SaveImport.Rejection)?.needsNewerApp == true
            return notify(newer ? "Actualiza PocketGB" : "No se pudo importar", error.localizedDescription)
        }
        guard let store = savesStore(plan.fingerprint) else { return }
        let states = (try? StateStore.defaultRoot()).map { StateStore(root: $0, fingerprint: plan.fingerprint) }
        do {
            let outcome = try SaveImport.apply(plan, choice: choice, store: store, states: states,
                                               moments: momentStoreFor(plan.fingerprint), ownership: ownership)
            let from = plan.origin.map { " de \($0)" } ?? ""
            switch outcome {
            case .needsChoice:
                importPrompt = ImportPrompt(entry: entry, plan: plan, gameTitle: libraryPrefs.displayTitle(entry))
                return
            case .needsStateChoice:
                importPrompt = ImportPrompt(entry: entry, plan: plan, gameTitle: libraryPrefs.displayTitle(entry),
                                            stateOnly: true)
                return
            case .installed(let c):
                notify("Partida importada", "Se ha instalado la partida\(from). La anterior quedó en «Antes de cargar» y en las copias de seguridad."
                       + (c ? " Puedes continuar justo donde lo dejaste." : ""))
            case .alreadyCurrent(let c):
                notify(c ? "Listo para continuar" : "Ya tienes esta partida",
                       c ? "La partida ya era la misma; ahora puedes continuar justo donde lo dejaste\(from.isEmpty ? "" : " en \(plan.origin!)")."
                         : "La partida del paquete es la misma que ya tienes. No se ha cambiado nada.")
            case .keptLocal:
                notify("Se mantiene tu partida", "La partida\(from) no se ha perdido: está en Momentos como «Conflicto» y en las copias apartadas.")
            case .saveNotTouched:
                notify("Partida no sustituida", "La partida del paquete está vacía o no tiene el tamaño de la de este juego, así que no se ha tocado. La actual quedó además en las copias de seguridad.")
            }
            didRestoreSave(fingerprint: plan.fingerprint)
        } catch {
            notify("No se pudo importar", error.localizedDescription)
        }
    }

    func resolveImport(_ choice: DivergenceChoice) {
        guard let prompt = importPrompt else { return }
        importPrompt = nil
        importSave(Data(), into: prompt.entry, choice: choice, plan: prompt.plan)
    }

    // MARK: Exportar

    /// El `.pgbm` de la partida actual de `entry` (para «Enviar a otro dispositivo» y «Guardar en Archivos»).
    func exportPackage(_ entry: RomEntry) async throws -> Data {
        let url = entry.url, console = entry.console, emulation = emulationOptions(entry)
        let savesDirectory = storageDirectories.saves
        let meta = libraryPrefs.data.metadata(entry)
        let title = entry.title
        let fingerprint = libraryPrefs.fingerprint(of: entry)
        let progress = progress.progress(fingerprint)
        let device = SaveExport.deviceName
        return try await Task.detached(priority: .userInitiated) {
            let read = try Self.readCartridge(url: url, console: console, emulation: emulation)
            let fp = read.cartridge.fingerprint
            let store = SaveStore(directory: try savesDirectory ?? SaveStore.defaultDirectory(), fingerprint: fp)
            let states = StateStore(root: try StateStore.defaultRoot(), fingerprint: fp)
            let game = SaveExport.Game(fingerprint: fp, romDigest: read.digest, console: console, title: title,
                                       alias: meta.alias, tags: meta.tags,
                                       playTime: progress.playTime > 0 ? progress.playTime : nil,
                                       milestones: progress.milestones.map { .init(id: $0.id, title: $0.title, done: $0.done) })
            return try SaveExport.package(game, store: store, states: states, deviceName: device)
        }.value
    }

    /// El `.sav` crudo de `entry`.
    func exportRawSave(_ entry: RomEntry) throws -> Data {
        guard let fp = libraryPrefs.fingerprint(of: entry), let data = try savesStore(fp)?.load() else {
            throw SaveExport.ExportError.noSave
        }
        return data
    }

    func notify(_ title: String, _ message: String) {
        alertTitle = title
        alertMessage = message
    }
}

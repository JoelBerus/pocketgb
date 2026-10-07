import Foundation

/// «Información técnica» del detalle (N3a, paridad con Android `GameDetailsScreen`): lo que el
/// núcleo lee de la cabecera al cargar el ROM, sin abrir una sesión de juego (sin hilo de
/// emulación, sin partida y sin audio). Solo para mostrar.
struct GameTechnicalInfo: Equatable, Sendable {
    let console: Console
    /// Título de la cabecera (el alias puede ser otro).
    let headerTitle: String
    /// Game Boy: byte 0x147 (MBC y extras).
    let cartType: UInt8
    /// Game Boy Advance: medio de guardado detectado (`GBA_SAVE_*`, nunca AUTO).
    let gbaSaveType: UInt8?
    /// Game Boy: lo que declara la cabecera; Game Boy Advance: el archivo.
    let romBytes: Int
    /// Tamaño de la partida sin el reloj (0 = sin RAM).
    let saveBytes: Int
    let hasBattery: Bool
    let hasRTC: Bool
    let headerChecksumOK: Bool
    /// Solo Game Boy (la consola real no lo comprueba).
    let globalChecksumOK: Bool?
    /// SHA-256 completo del ROM (64 hex). La huella de la app son sus 32 primeros.
    let sha256: String
    /// Game Boy Advance: código del juego, fabricante y versión de la cabecera.
    let gameCode: String?

    init(console: Console, headerTitle: String, cartType: UInt8, gbaSaveType: UInt8?, romBytes: Int,
         saveBytes: Int, hasBattery: Bool, hasRTC: Bool, headerChecksumOK: Bool, globalChecksumOK: Bool?,
         sha256: String, gameCode: String?) {
        self.console = console
        self.headerTitle = headerTitle
        self.cartType = cartType
        self.gbaSaveType = gbaSaveType
        self.romBytes = romBytes
        self.saveBytes = saveBytes
        self.hasBattery = hasBattery
        self.hasRTC = hasRTC
        self.headerChecksumOK = headerChecksumOK
        self.globalChecksumOK = globalChecksumOK
        self.sha256 = sha256
        self.gameCode = gameCode
    }

    /// Desde lo que devuelve `CoreBridge`/`GBACoreBridge.loadROM`.
    init(_ info: RomInfo) {
        let gba = info.console == .gameBoyAdvance
        let code = [info.gameCode, info.makerCode].filter { !$0.isEmpty }
        self.init(console: info.console, headerTitle: info.title, cartType: info.cartType,
                  gbaSaveType: gba ? info.gbaSaveType : nil, romBytes: info.romBytes, saveBytes: info.sramBytes,
                  hasBattery: info.hasBattery, hasRTC: info.hasRTC, headerChecksumOK: info.headerChecksumOK,
                  globalChecksumOK: gba ? nil : info.globalChecksumOK, sha256: info.sha256,
                  gameCode: gba && !code.isEmpty ? (code + ["v\(info.version)"]).joined(separator: " · ") : nil)
    }

    struct Row: Equatable, Sendable, Identifiable {
        let label: String
        let value: String
        /// Advertencia (checksum que no cuadra): símbolo y texto, nunca solo color.
        var warning = false
        var id: String { label }
    }

    /// Filas en el orden de Android: cartucho (o tipo de partida GBA), ROM, partida, checksums.
    /// El SHA-256 va aparte (monoespaciado y copiable).
    var rows: [Row] {
        var rows: [Row] = []
        if !headerTitle.isEmpty { rows.append(Row(label: "Título de cabecera", value: headerTitle)) }
        if console == .gameBoyAdvance {
            rows.append(Row(label: "Tipo de partida", value: "\(Self.gbaSaveName(gbaSaveType)) (detectado)"))
            if let gameCode { rows.append(Row(label: "Código del juego", value: gameCode)) }
        } else {
            rows.append(Row(label: "Cartucho", value: Self.cartridgeName(cartType)))
        }
        rows.append(Row(label: "ROM", value: ByteFormat.format(romBytes)))
        rows.append(Row(label: "Partida guardada", value: saveDescription))
        rows.append(Row(label: "Checksum de cabecera", value: headerChecksumOK ? "Correcto" : "Incorrecto",
                        warning: !headerChecksumOK))
        if let globalChecksumOK {
            rows.append(Row(label: "Checksum global",
                            value: globalChecksumOK ? "Correcto" : "No coincide (la consola real lo ignora)",
                            warning: !globalChecksumOK))
        }
        return rows
    }

    /// «32 KiB · batería · reloj», «Sin RAM» (GB) o «Sin partida» (GBA). En GBA, la EEPROM
    /// detectada mide 512 B u 8 KiB según el juego: se sabe al guardar.
    var saveDescription: String {
        var text: String
        if console == .gameBoyAdvance {
            if gbaSaveType == 5 || gbaSaveType == 6 {
                text = "EEPROM (512 B u 8 KiB)"
            } else {
                text = saveBytes == 0 ? "Sin partida" : ByteFormat.format(saveBytes)
            }
        } else {
            text = saveBytes == 0 ? "Sin RAM" : ByteFormat.format(saveBytes)
            if hasBattery { text += " · batería" }
        }
        if hasRTC { text += " · reloj" }
        return text
    }

    /// `GBA_SAVE_*` con los mismos nombres que «Ajustes del juego».
    static func gbaSaveName(_ type: UInt8?) -> String {
        switch type {
        case 1: "Sin partida"
        case 2: "SRAM 32 KiB"
        case 3: "Flash 64 KiB"
        case 4: "Flash 128 KiB"
        case 5, 6: "EEPROM"
        default: "Desconocido"
        }
    }

    /// Nombre del tipo de cartucho (byte 0x147), igual que Android `CartridgeNames`.
    static func cartridgeName(_ code: UInt8) -> String {
        switch code {
        case 0x00: "Solo ROM"
        case 0x01: "MBC1"
        case 0x02: "MBC1 + RAM"
        case 0x03: "MBC1 + RAM + batería"
        case 0x05: "MBC2"
        case 0x06: "MBC2 + batería"
        case 0x08: "ROM + RAM"
        case 0x09: "ROM + RAM + batería"
        case 0x0B: "MMM01"
        case 0x0C: "MMM01 + RAM"
        case 0x0D: "MMM01 + RAM + batería"
        case 0x0F: "MBC3 + reloj + batería"
        case 0x10: "MBC3 + reloj + RAM + batería"
        case 0x11: "MBC3"
        case 0x12: "MBC3 + RAM"
        case 0x13: "MBC3 + RAM + batería"
        case 0x19: "MBC5"
        case 0x1A: "MBC5 + RAM"
        case 0x1B: "MBC5 + RAM + batería"
        case 0x1C: "MBC5 + vibración"
        case 0x1D: "MBC5 + vibración + RAM"
        case 0x1E: "MBC5 + vibración + RAM + batería"
        case 0x20: "MBC6"
        case 0x22: "MBC7 + sensor + vibración + RAM + batería"
        case 0xFC: "Pocket Camera"
        case 0xFD: "Bandai TAMA5"
        case 0xFE: "HuC3"
        case 0xFF: "HuC1 + RAM + batería"
        default: String(format: "Desconocido (0x%02X)", code)
        }
    }
}

/// Tamaños con unidades binarias y coma decimal, igual en cualquier idioma (como Android `ByteFormat`).
enum ByteFormat {
    static func format(_ bytes: Int) -> String {
        if bytes < 1024 { return "\(bytes) B" }
        if bytes < 1024 * 1024 { return trim(Double(bytes) / 1024) + " KiB" }
        return trim(Double(bytes) / (1024 * 1024)) + " MiB"
    }

    private static func trim(_ value: Double) -> String {
        var text = String(format: "%.1f", locale: Locale(identifier: "en_US_POSIX"), value)
        if text.hasSuffix(".0") { text.removeLast(2) }
        return text.replacingOccurrences(of: ".", with: ",")
    }
}

/// Lee la información técnica fuera del hilo principal. Nunca fuerza una descarga de iCloud:
/// un archivo sin descargar no se lee (la lectura coordinada lo descargaría).
enum GameTechnicalInfoLoader {
    enum Failure: Error, Equatable, Sendable {
        /// Solo en iCloud: se lee al descargarlo.
        case notDownloaded
        case unreadable
        /// El núcleo no lo acepta (con el mismo texto que al abrirlo).
        case rejected(String)

        var message: String {
            switch self {
            case .notDownloaded: "Se podrá ver cuando el juego esté descargado de iCloud."
            case .unreadable: "No se pudo leer el archivo."
            case .rejected(let reason): reason
            }
        }
    }

    /// Lectura coordinada y acotada del ROM (`LibraryScanner.readROM`) y carga en una instancia
    /// propia del núcleo sin audio, que se destruye al terminar.
    static func load(url: URL, console: Console) -> Result<GameTechnicalInfo, Failure> {
        guard RomFingerprint.isLocallyAvailable(url) else { return .failure(.notDownloaded) }
        guard let data = try? LibraryScanner.readROM(url, limit: LibraryScanner.romLimit(for: console)) else {
            return .failure(.unreadable)
        }
        return inspect(data, console: console)
    }

    /// Desde los bytes ya leídos (tests).
    static func inspect(_ data: Data, console: Console) -> Result<GameTechnicalInfo, Failure> {
        do throws(CoreError) {
            let info: RomInfo
            switch console {
            case .gameBoy:
                info = try CoreBridge().loadROM(data, unixTime: 0)
            case .gameBoyAdvance:
                info = try GBACoreBridge().loadROM(data, bios: nil, unixTime: 0)
            }
            return .success(GameTechnicalInfo(info))
        } catch {
            return .failure(.rejected(error.description))
        }
    }
}

import CryptoKit
import Foundation
import PocketGBCore

/// N7b · contenedor `.pgbm` (docs/12-formato-pgbm.md) a través del parser C del núcleo (`pocketgb_pgbm.h`). Swift no
/// interpreta la estructura binaria: todo pasa por `pgbm_parse` / `pgbm_encode`, que acotan cada longitud y comprueban
/// el CRC. Aquí solo se copian los trozos a `Data` para que no dependan del buffer original.
struct PGBMPackage: Equatable, Sendable {
    /// SHA-256 completo del ROM (32 bytes, sección `ROMF`).
    var romFingerprint: Data
    var meta: Data?
    var save: Data
    var state: Data?
    var thumbnail: Data?

    /// Error del contenedor: el valor numérico es el de `pgbm_result` (contrato estable).
    struct ContainerError: Error, Equatable, LocalizedError {
        let code: Int

        static let magic = ContainerError(code: 1), version = ContainerError(code: 2)
        static let critical = ContainerError(code: 13)

        /// Versión desconocida o sección crítica: el paquete viene de una versión más nueva de PocketGB.
        var needsNewerApp: Bool { code == 2 || code == 13 }
        var name: String { String(cString: pgbm_result_name(pgbm_result(rawValue: UInt32(code)))) }

        var errorDescription: String? {
            switch code {
            case 1: "El archivo no es un paquete de PocketGB."
            case 2, 13: "El paquete viene de una versión más nueva de PocketGB. Actualiza la app para importarlo."
            case 3: "El paquete está incompleto (¿descarga a medias?)."
            case 5: "El paquete está dañado."
            default: "El paquete no es válido (\(name))."
            }
        }
    }

    static let magic = Data("PGBM".utf8)

    static func looksLikePackage(_ data: Data) -> Bool { data.prefix(4) == magic }

    /// Lee y valida con `pgbm_parse`. Nunca toca el disco.
    static func parse(_ data: Data) throws -> PGBMPackage {
        try data.withUnsafeBytes { raw -> PGBMPackage in
            var view = pgbm_view()
            let base = raw.bindMemory(to: UInt8.self).baseAddress
            let result = pgbm_parse(base, raw.count, &view)
            guard result == PGBM_OK else { throw ContainerError(code: Int(result.rawValue)) }
            func copy(_ span: pgbm_span) -> Data? {
                guard let p = span.data, span.len > 0 else { return nil }
                return Data(bytes: p, count: Int(span.len))
            }
            let romf = withUnsafeBytes(of: view.rom_fp) { Data($0) }
            return PGBMPackage(romFingerprint: romf, meta: copy(view.meta), save: copy(view.sav) ?? Data(),
                               state: copy(view.state), thumbnail: copy(view.thumb))
        }
    }

    /// Escribe el paquete canónico con `pgbm_encode` (determinista).
    func encoded() throws -> Data {
        guard romFingerprint.count == Int(PGBM_ROMF_BYTES) else { throw ContainerError(code: 11) }
        let empty = Data()
        let meta = self.meta ?? empty, state = self.state ?? empty, thumb = self.thumbnail ?? empty
        return try meta.withUnsafeBytes { m in
            try save.withUnsafeBytes { s in
                try state.withUnsafeBytes { st in
                    try thumb.withUnsafeBytes { t in
                        var view = pgbm_view()
                        func span(_ b: UnsafeRawBufferPointer) -> pgbm_span {
                            pgbm_span(data: b.count > 0 ? b.bindMemory(to: UInt8.self).baseAddress : nil, len: UInt32(b.count))
                        }
                        view.meta = span(m)
                        view.sav = span(s)
                        view.state = span(st)
                        view.thumb = span(t)
                        view.version = UInt16(PGBM_VERSION)
                        withUnsafeMutableBytes(of: &view.rom_fp) { $0.copyBytes(from: romFingerprint) }
                        let size = pgbm_encoded_size(&view)
                        guard size > 0 else { throw ContainerError(code: 10) }
                        var out = Data(count: size)
                        var written = 0
                        let result = out.withUnsafeMutableBytes { o in
                            pgbm_encode(&view, o.bindMemory(to: UInt8.self).baseAddress, size, &written)
                        }
                        guard result == PGBM_OK else { throw ContainerError(code: Int(result.rawValue)) }
                        return out.prefix(written)
                    }
                }
            }
        }
    }
}

/// N7b · `META v1` (docs/12-formato-pgbm.md §META v1, normativa). Se valida a mano sobre `JSONSerialization` para
/// aplicar exactamente las reglas del esquema (tipos, enteros sin fracción, longitudes, hex de 64).
struct PackageMeta: Equatable, Sendable {
    struct Milestone: Equatable, Sendable { var id: String; var title: String; var done: Bool }
    struct Moment: Equatable, Sendable { var name: String; var collection: String?; var note: String?; var createdMs: Int? }

    var romSHA256: String
    var savSHA256: String
    var baseSavSHA256: String?
    var platform: String
    var deviceName: String
    var createdMs: Int
    var coreName: String
    var coreVersion: String?
    var stateOfSavSHA256: String?
    var config: PackageConfig?
    var playTimeMs: Int?
    var title: String?
    var alias: String?
    var tags: [String]?
    var milestones: [Milestone]?
    var moment: Moment?

    enum Invalid: Error, Equatable, LocalizedError {
        case notJSON, newerFormat, field(String)
        var errorDescription: String? {
            switch self {
            case .notJSON: "Los datos del paquete no son válidos."
            case .newerFormat: "El paquete viene de una versión más nueva de PocketGB. Actualiza la app para importarlo."
            case .field(let key): "Los datos del paquete no son válidos («\(key)»)."
            }
        }
    }

    static let maxInt = (1 << 53) - 1

    static func parse(_ json: Data) throws -> PackageMeta {
        // ND20 (h): antes de `JSONSerialization` (que se queda con una de las claves repetidas y acepta 1.0 o 1e3), un
        // recorrido de tokens rechaza claves repetidas y números con fracción o exponente.
        // Las fracciones solo cuentan en las claves enteras del esquema: una clave desconocida se ignora aunque lleve
        // `1.5` (docs/12 «Claves desconocidas se ignoran»; auditoría N-final H13).
        guard json.count <= 65_536, json.prefix(3) != Data([0xEF, 0xBB, 0xBF]),
              let fractional = StrictJSON.scan(json),
              let object = try? JSONSerialization.jsonObject(with: json), let root = object as? [String: Any]
        else { throw Invalid.notJSON }
        // `null` solo vale en `base_sav_sha256` (docs/12): en cualquier otra clave conocida es un tipo erróneo. Las
        // desconocidas no se leen, así que su `null` se ignora (auditoría N-final H13).
        func int(_ value: Any?, _ key: String) throws -> Int? {
            guard let value else { return nil }
            guard !fractional.contains(key), let n = value as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID()
            else { throw Invalid.field(key) }
            let d = n.doubleValue
            guard d.rounded() == d, d >= 0, d <= Double(maxInt)
            else { throw Invalid.field(key) }
            return n.intValue
        }
        func string(_ value: Any?, _ key: String, max: Int) throws -> String? {
            guard let value else { return nil }
            // Longitudes en code points de Unicode (ND20 h), no en grafemas.
            guard let s = value as? String, s.unicodeScalars.count <= max else { throw Invalid.field(key) }
            return s
        }
        func hex(_ value: Any?, _ key: String) throws -> String? {
            guard let s = try string(value, key, max: 64) else { return nil }
            // Solo hex ASCII (ND20 h): `isHexDigit` aceptaría dígitos de ancho completo.
            guard s.utf8.count == 64, s.utf8.allSatisfy({ (0x30...0x39).contains($0) || (0x41...0x46).contains($0)
                                                          || (0x61...0x66).contains($0) }) else { throw Invalid.field(key) }
            return s.lowercased()
        }
        func bool(_ value: Any?, _ key: String) throws -> Bool {
            guard let n = value as? NSNumber, CFGetTypeID(n) == CFBooleanGetTypeID() else { throw Invalid.field(key) }
            return n.boolValue
        }
        func required<T>(_ v: T?, _ key: String) throws -> T {
            guard let v else { throw Invalid.field(key) }
            return v
        }

        // `format` primero: otra versión puede tener otras claves.
        let format = try required(try int(root["format"], "format"), "format")
        guard format == 1 else { throw Invalid.newerFormat }

        guard let device = root["device"] as? [String: Any] else { throw Invalid.field("device") }
        let platform = try required(try string(device["platform"], "device.platform", max: 16), "device.platform")
        guard ["ios", "android"].contains(platform) else { throw Invalid.field("device.platform") }
        guard let core = root["core"] as? [String: Any] else { throw Invalid.field("core") }
        let coreName = try required(try string(core["name"], "core.name", max: 8), "core.name")
        guard ["gb", "gba"].contains(coreName) else { throw Invalid.field("core.name") }

        var meta = PackageMeta(
            romSHA256: try required(try hex(root["rom_sha256"], "rom_sha256"), "rom_sha256"),
            savSHA256: try required(try hex(root["sav_sha256"], "sav_sha256"), "sav_sha256"),
            baseSavSHA256: root["base_sav_sha256"] is NSNull ? nil : try hex(root["base_sav_sha256"], "base_sav_sha256"),
            platform: platform,
            deviceName: try required(try string(device["name"], "device.name", max: 128), "device.name"),
            createdMs: try required(try int(root["created_ms"], "created_ms"), "created_ms"),
            coreName: coreName,
            coreVersion: try string(core["version"], "core.version", max: 32),
            stateOfSavSHA256: try hex(root["state_of_sav_sha256"], "state_of_sav_sha256"))
        if let config = root["config"] {
            guard let c = config as? [String: Any] else { throw Invalid.field("config") }
            // Tipos del esquema (ND20 h): un tipo o valor erróneo (también `null`) hace la META inválida; las claves
            // desconocidas se ignoran.
            func choice(_ key: String, _ allowed: [String]) throws -> String? {
                guard let v = try string(c[key], "config.\(key)", max: 16) else { return nil }
                guard allowed.contains(v) else { throw Invalid.field("config.\(key)") }
                return v
            }
            var cfg = PackageConfig()
            cfg.model = try choice("model", ["auto", "dmg", "cgb"])
            cfg.compatPalette = try string(c["compat_palette"], "config.compat_palette", max: 64)
            cfg.gbaSaveType = try choice("gba_save_type", PackageConfig.gbaSaveTypes)
            cfg.gbaRTC = try choice("gba_rtc", ["auto", "on", "off"])
            if let b = c["gba_bios"] { cfg.gbaBIOS = try bool(b, "config.gba_bios") }
            meta.config = cfg
        }
        meta.playTimeMs = try int(root["play_time_ms"], "play_time_ms")
        meta.title = try string(root["title"], "title", max: 256)
        meta.alias = try string(root["alias"], "alias", max: 256)
        if let tags = root["tags"] {
            guard let list = tags as? [Any], list.count <= 64 else { throw Invalid.field("tags") }
            meta.tags = try list.map { try required(try string($0, "tags", max: 64), "tags") }
        }
        if let list = root["milestones"] {
            guard let list = list as? [Any], list.count <= 256 else { throw Invalid.field("milestones") }
            meta.milestones = try list.map { item in
                guard let m = item as? [String: Any] else { throw Invalid.field("milestones") }
                return Milestone(id: try required(try string(m["id"], "milestones.id", max: 64), "milestones.id"),
                                 title: try required(try string(m["title"], "milestones.title", max: 256), "milestones.title"),
                                 done: try bool(m["done"], "milestones.done"))
            }
        }
        if let m = root["moment"] {
            guard let m = m as? [String: Any] else { throw Invalid.field("moment") }
            meta.moment = Moment(name: try required(try string(m["name"], "moment.name", max: 256), "moment.name"),
                                 collection: try string(m["collection"], "moment.collection", max: 256),
                                 note: try string(m["note"], "moment.note", max: 4096),
                                 createdMs: try int(m["created_ms"], "moment.created_ms"))
        }
        return meta
    }

    /// JSON de `META v1` (claves en orden estable, sin espacios, `/` sin escapar).
    func encoded() throws -> Data {
        var root: [String: Any] = [
            "format": 1, "rom_sha256": romSHA256, "sav_sha256": savSHA256,
            "base_sav_sha256": baseSavSHA256 ?? NSNull(),
            "device": ["platform": platform, "name": deviceName], "created_ms": createdMs,
            "core": ["name": coreName, "version": coreVersion ?? ""] as [String: Any],
        ]
        if let config, !config.dictionary.isEmpty { root["config"] = config.dictionary }
        if let stateOfSavSHA256 { root["state_of_sav_sha256"] = stateOfSavSHA256 }
        if let playTimeMs { root["play_time_ms"] = playTimeMs }
        if let title { root["title"] = String(title.prefix(256)) }
        if let alias { root["alias"] = String(alias.prefix(256)) }
        if let tags { root["tags"] = Array(tags.prefix(64).map { String($0.prefix(64)) }) }
        if let milestones {
            root["milestones"] = milestones.prefix(256).map {
                ["id": String($0.id.prefix(64)), "title": String($0.title.prefix(256)), "done": $0.done] as [String: Any]
            }
        }
        if let moment {
            var m: [String: Any] = ["name": String(moment.name.prefix(256))]
            if let c = moment.collection { m["collection"] = String(c.prefix(256)) }
            if let n = moment.note { m["note"] = String(n.prefix(4096)) }
            if let d = moment.createdMs { m["created_ms"] = d }
            root["moment"] = m
        }
        return try JSONSerialization.data(withJSONObject: root, options: [.sortedKeys, .withoutEscapingSlashes])
    }

    static func == (a: PackageMeta, b: PackageMeta) -> Bool {
        (try? a.encoded()) == (try? b.encoded())
    }
}

extension PackageMeta {
    init(romSHA256: String, savSHA256: String, baseSavSHA256: String?, platform: String, deviceName: String,
         createdMs: Int, coreName: String, coreVersion: String?, stateOfSavSHA256: String?) {
        self.romSHA256 = romSHA256
        self.savSHA256 = savSHA256
        self.baseSavSHA256 = baseSavSHA256
        self.platform = platform
        self.deviceName = deviceName
        self.createdMs = createdMs
        self.coreName = coreName
        self.coreVersion = coreVersion
        self.stateOfSavSHA256 = stateOfSavSHA256
    }
}

/// `config` de META v1 (docs/12): la configuración con la que se jugó. Todo opcional.
struct PackageConfig: Equatable, Sendable {
    var model: String?
    var compatPalette: String?
    var gbaSaveType: String?
    var gbaRTC: String?
    var gbaBIOS: Bool?

    /// Orden de `gba_save_type` (`GBA_SAVE_AUTO`… `GBA_SAVE_EEPROM8K` en `gba/include`).
    static let gbaSaveTypes = ["auto", "none", "sram", "flash64", "flash128", "eeprom512", "eeprom8k"]
    static let gbaRTCs = ["auto", "on", "off"]

    init() {}

    /// La configuración efectiva de un juego en este iPhone, en los términos del esquema.
    /// - Parameter isColorROM: ROM de Game Boy Color (bit 7 de 0x143). Con él, o con «Color en juegos de Game Boy»,
    ///   el modelo es `"cgb"`; si no, `"dmg"` (docs/12 «Valores de config»; nunca `"auto"`).
    init(_ options: EmulationOptions, console: Console, isColorROM: Bool = false) {
        switch console {
        case .gameBoy:
            model = isColorROM || options.colorForGameBoy ? "cgb" : "dmg"
            compatPalette = String(options.compatPalette)
        case .gameBoyAdvance:
            gbaSaveType = Self.gbaSaveTypes[safe: Int(options.gbaSaveType)] ?? "auto"
            gbaRTC = Self.gbaRTCs[safe: Int(options.gbaRTC)] ?? "auto"
            gbaBIOS = options.gbaUseBIOS
        }
    }

    var dictionary: [String: Any] {
        var d: [String: Any] = [:]
        if let model { d["model"] = model }
        if let compatPalette { d["compat_palette"] = compatPalette }
        if let gbaSaveType { d["gba_save_type"] = gbaSaveType }
        if let gbaRTC { d["gba_rtc"] = gbaRTC }
        if let gbaBIOS { d["gba_bios"] = gbaBIOS }
        return d
    }

    /// Claves que el paquete trae y que difieren de `local` (vacío = coinciden o no se sabe). ND20 (h): con un estado
    /// de otra configuración se avisa antes de que el estado deje de servir.
    func differences(from local: PackageConfig) -> [String] {
        var out: [String] = []
        if let model, model != "auto", let l = local.model, l != model { out.append("el color de Game Boy") }
        // La paleta no cambia la máquina: no cuenta (decisión Android (3), docs/12 «Valores de config»).
        if let gbaSaveType, gbaSaveType != "auto", let l = local.gbaSaveType, l != gbaSaveType { out.append("el tipo de partida") }
        if let gbaRTC, gbaRTC != "auto", let l = local.gbaRTC, l != gbaRTC { out.append("el reloj") }
        if let gbaBIOS, let l = local.gbaBIOS, l != gbaBIOS { out.append("la BIOS") }
        return out
    }
}

private extension Array {
    subscript(safe i: Int) -> Element? { indices.contains(i) ? self[i] : nil }
}

/// ND20 (h): recorrido de tokens JSON (RFC 8259) que solo comprueba lo que `JSONSerialization` deja pasar: claves
/// repetidas en un objeto (comparadas ya decodificadas) y números con fracción o exponente. `scan` devuelve `nil` si el
/// JSON no vale (o repite claves) y, si vale, las rutas (`clave.subclave`, `lista[]`) cuyos números llevan fracción o
/// exponente: solo hacen inválida la META si la ruta es una clave entera del esquema (auditoría N-final H13).
enum StrictJSON {
    static func scan(_ data: Data) -> Set<String>? {
        let b = [UInt8](data)
        var i = 0
        var fractional = Set<String>()
        func ws() { while i < b.count, [0x20, 0x09, 0x0A, 0x0D].contains(b[i]) { i += 1 } }
        func string() -> String? {
            guard i < b.count, b[i] == 0x22 else { return nil }
            let start = i
            i += 1
            while i < b.count, b[i] != 0x22 {
                if b[i] == 0x5C { i += 1 }
                i += 1
            }
            guard i < b.count else { return nil }
            i += 1
            return (try? JSONSerialization.jsonObject(with: Data(b[start..<i]), options: .fragmentsAllowed)) as? String
        }
        func value(_ depth: Int, _ path: String) -> Bool {
            guard depth < 64 else { return false }
            ws()
            guard i < b.count else { return false }
            switch b[i] {
            case 0x7B: // {
                i += 1; ws()
                var keys = Set<String>()
                if i < b.count, b[i] == 0x7D { i += 1; return true }
                while true {
                    ws()
                    guard let key = string(), keys.insert(key).inserted else { return false }
                    ws()
                    guard i < b.count, b[i] == 0x3A else { return false }
                    i += 1
                    guard value(depth + 1, path.isEmpty ? key : "\(path).\(key)") else { return false }
                    ws()
                    guard i < b.count else { return false }
                    if b[i] == 0x2C { i += 1; continue }
                    if b[i] == 0x7D { i += 1; return true }
                    return false
                }
            case 0x5B: // [
                i += 1; ws()
                if i < b.count, b[i] == 0x5D { i += 1; return true }
                while true {
                    guard value(depth + 1, "\(path)[]") else { return false }
                    ws()
                    guard i < b.count else { return false }
                    if b[i] == 0x2C { i += 1; continue }
                    if b[i] == 0x5D { i += 1; return true }
                    return false
                }
            case 0x22:
                return string() != nil
            case 0x2D, 0x30...0x39:
                while i < b.count, (0x30...0x39).contains(b[i]) || [0x2D, 0x2B, 0x2E, 0x65, 0x45].contains(b[i]) {
                    if [0x2E, 0x65, 0x45].contains(b[i]) { fractional.insert(path) }   // fracción o exponente
                    i += 1
                }
                return true
            default:
                for lit in ["true", "false", "null"] where b[i...].starts(with: Array(lit.utf8)) {
                    i += lit.utf8.count
                    return true
                }
                return false
            }
        }
        guard value(0, "") else { return nil }
        ws()
        return i == b.count ? fractional : nil
    }
}

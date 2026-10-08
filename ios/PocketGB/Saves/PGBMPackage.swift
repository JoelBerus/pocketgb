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
    var configModel: String?
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
        guard json.count <= 65_536, json.prefix(3) != Data([0xEF, 0xBB, 0xBF]),
              let object = try? JSONSerialization.jsonObject(with: json), let root = object as? [String: Any]
        else { throw Invalid.notJSON }
        func int(_ value: Any?, _ key: String) throws -> Int? {
            guard let value, !(value is NSNull) else { return nil }
            guard let n = value as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() else { throw Invalid.field(key) }
            let d = n.doubleValue
            guard d.rounded() == d, d >= 0, d <= Double(maxInt)
            else { throw Invalid.field(key) }
            return n.intValue
        }
        func string(_ value: Any?, _ key: String, max: Int) throws -> String? {
            guard let value, !(value is NSNull) else { return nil }
            guard let s = value as? String, s.count <= max else { throw Invalid.field(key) }
            return s
        }
        func hex(_ value: Any?, _ key: String) throws -> String? {
            guard let s = try string(value, key, max: 64) else { return nil }
            guard s.count == 64, s.allSatisfy(\.isHexDigit) else { throw Invalid.field(key) }
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
            baseSavSHA256: try hex(root["base_sav_sha256"], "base_sav_sha256"),
            platform: platform,
            deviceName: try required(try string(device["name"], "device.name", max: 128), "device.name"),
            createdMs: try required(try int(root["created_ms"], "created_ms"), "created_ms"),
            coreName: coreName,
            coreVersion: try string(core["version"], "core.version", max: 32),
            stateOfSavSHA256: try hex(root["state_of_sav_sha256"], "state_of_sav_sha256"))
        if let config = root["config"] {
            guard let config = config as? [String: Any] else { throw Invalid.field("config") }
            meta.configModel = try string(config["model"], "config.model", max: 8)
        }
        meta.playTimeMs = try int(root["play_time_ms"], "play_time_ms")
        meta.title = try string(root["title"], "title", max: 256)
        meta.alias = try string(root["alias"], "alias", max: 256)
        if let tags = root["tags"], !(tags is NSNull) {
            guard let list = tags as? [Any], list.count <= 64 else { throw Invalid.field("tags") }
            meta.tags = try list.map { try required(try string($0, "tags", max: 64), "tags") }
        }
        if let list = root["milestones"], !(list is NSNull) {
            guard let list = list as? [Any], list.count <= 256 else { throw Invalid.field("milestones") }
            meta.milestones = try list.map { item in
                guard let m = item as? [String: Any] else { throw Invalid.field("milestones") }
                return Milestone(id: try required(try string(m["id"], "milestones.id", max: 64), "milestones.id"),
                                 title: try required(try string(m["title"], "milestones.title", max: 256), "milestones.title"),
                                 done: try bool(m["done"], "milestones.done"))
            }
        }
        if let m = root["moment"], !(m is NSNull) {
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
        if let configModel { root["config"] = ["model": configModel] }
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

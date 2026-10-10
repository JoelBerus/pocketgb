import Foundation
import SwiftUI
import UIKit
import UniformTypeIdentifiers

extension UTType {
    /// N7b · paquete de PocketGB (`.pgbm`), tipo exportado en Info.plist.
    static let pocketGBPackage = UTType(exportedAs: "com.joelbermudez.pocketgb.package", conformingTo: .data)
    /// `.sav` crudo (tipo importado: no hay uno del sistema).
    static let gameBoySave = UTType(importedAs: "com.joelbermudez.pocketgb.save", conformingTo: .data)
}

/// N7b · exportar la partida de un juego: el `.sav` crudo (sirve en otros emuladores) o un `.pgbm` con la partida, el
/// estado automático si corresponde a ella y los metadatos (ND12). Solo lee: nunca escribe en la partida.
enum SaveExport {
    struct Game: Sendable {
        var fingerprint: String
        /// SHA-256 completo del ROM (32 bytes).
        var romDigest: Data
        var console: Console
        var title: String?
        var alias: String?
        var tags: [String] = []
        var playTime: TimeInterval?
        var milestones: [PackageMeta.Milestone] = []
        /// ND20 (h): la configuración con la que se juega ahora.
        var config: PackageConfig?
    }

    enum ExportError: Error, LocalizedError {
        case noSave
        var errorDescription: String? { "Este juego todavía no tiene partida guardada en este iPhone." }
    }

    /// Nombre del equipo (en iOS 16+ es el modelo, «iPhone», sin pedir permisos).
    @MainActor static var deviceName: String { UIDevice.current.name }

    static var coreVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1"
    }

    /// El `.pgbm` de la partida actual. `base_sav_sha256` es la última partida que llegó de otro equipo (si la hay).
    static func package(_ game: Game, store: SaveStore, states: StateStore?, deviceName: String,
                        now: Date = Date()) throws -> Data {
        let stored = try store.load()
        // Sin `.sav` (juego sin batería) solo se exporta si hay un estado automático que llevar.
        if stored == nil, states?.automaticEntry(newerThan: nil) == nil { throw ExportError.noSave }
        let save = stored ?? Data()
        let savSHA = SaveLineage.sha256(save)
        // El estado automático vale si es posterior a la partida (lleva su misma RAM: `closeGame` vacía antes).
        let auto = states?.automaticEntry(newerThan: store.modificationDate).flatMap { _ in try? states?.load(.auto) }
        let thumb = auto == nil ? nil : (try? Data(contentsOf: states!.thumbnailURL(.auto))).flatMap {
            $0.count <= 262_144 && $0.prefix(8) == Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]) ? $0 : nil
        }
        // ND20 (g): la última partida recibida de fuera (paquete, cambio externo o primera instalación desde el espejo).
        let base = store.lastReceived
        var meta = PackageMeta(romSHA256: game.romDigest.map { String(format: "%02x", $0) }.joined(),
                               savSHA256: savSHA, baseSavSHA256: base,
                               platform: "ios", deviceName: String(deviceName.prefix(128)),
                               createdMs: Int(now.timeIntervalSince1970 * 1000),
                               coreName: game.console == .gameBoyAdvance ? "gba" : "gb",
                               coreVersion: String(coreVersion.prefix(32)),
                               stateOfSavSHA256: auto == nil ? nil : savSHA)
        meta.title = game.title
        meta.alias = game.alias
        meta.tags = game.tags.isEmpty ? nil : game.tags
        meta.playTimeMs = game.playTime.map { Int($0 * 1000) }
        meta.milestones = game.milestones.isEmpty ? nil : game.milestones
        meta.config = game.config
        return try PGBMPackage(romFingerprint: game.romDigest, meta: try meta.encoded(), save: save,
                               state: auto, thumbnail: thumb).encoded()
    }

    /// Nombre de archivo seguro a partir del título.
    static func fileName(_ title: String, ext: String) -> String {
        let bad = CharacterSet(charactersIn: "/\\:?%*|\"<>").union(.newlines)
        let clean = title.components(separatedBy: bad).joined(separator: " ").trimmingCharacters(in: .whitespaces)
        return "\(clean.isEmpty ? "Partida" : String(clean.prefix(80))).\(ext)"
    }

    /// Escribe `data` en un temporal propio con el nombre dado (para la hoja de compartir).
    static func temporaryFile(_ data: Data, name: String) throws -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("export-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent(name)
        try data.write(to: url, options: .atomic)
        return url
    }
}

/// Archivo que se comparte con `ShareLink`: se genera al compartir, no antes.
struct ExportedSaveFile: Transferable {
    let name: String
    let contentType: UTType
    let make: @Sendable () async throws -> Data

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(exportedContentType: .pocketGBPackage) { file in
            SentTransferredFile(try SaveExport.temporaryFile(try await file.make(), name: file.name))
        }
        .exportingCondition { $0.contentType == .pocketGBPackage }
        FileRepresentation(exportedContentType: .gameBoySave) { file in
            SentTransferredFile(try SaveExport.temporaryFile(try await file.make(), name: file.name))
        }
        .exportingCondition { $0.contentType == .gameBoySave }
    }
}

/// Documento para `fileExporter` («Guardar en Archivos…»).
struct SaveFileDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.pocketGBPackage, .gameBoySave, .data] }
    let data: Data

    init(data: Data) { self.data = data }
    init(configuration: ReadConfiguration) throws { data = configuration.file.regularFileContents ?? Data() }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}

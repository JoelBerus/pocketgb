import CryptoKit
import Foundation
import Testing
@testable import PocketGB

/// Relleno determinista de docs/12-formato-pgbm.md: pat(semilla, i) = (semilla + 37·i + 11·(i >> 8)) mod 256.
func pat(_ seed: Int, _ n: Int) -> Data {
    Data((0..<n).map { UInt8((seed + 37 * $0 + 11 * ($0 >> 8)) & 0xFF) })
}

private func sha(_ d: Data) -> String { SaveLineage.sha256(d) }

/// N7b · vectores dorados (G1–G4) y cruzados (X1–X7), generados aquí con las llamadas Swift a `pgbm_encode` /
/// `pgbm_parse`: ningún `.pgbm` vive en el repo. Después, el importador real con el cartucho de referencia.
struct PGBMTests {
    let rom = pat(0x10, 32)
    var R: String { rom.map { String(format: "%02x", $0) }.joined() }
    let dir: URL

    init() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("pgbm-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    /// Cartucho de referencia de los vectores cruzados: GB con batería, 32 768 B sin RTC.
    var cartridge: SaveImport.Cartridge {
        SaveImport.Cartridge(fingerprint: String(R.prefix(32)), romSHA256: R, console: .gameBoy, hasBattery: true,
                             validSizes: [32_768])
    }

    private func package(meta: String?, save: Data, state: Data? = nil, thumb: Data? = nil) throws -> Data {
        try PGBMPackage(romFingerprint: rom, meta: meta.map { Data($0.utf8) }, save: save, state: state, thumbnail: thumb).encoded()
    }

    private func expect(_ data: Data, length: Int, sha256: String, _ name: String) {
        #expect(data.count == length, "\(name): longitud")
        #expect(sha(data) == sha256, "\(name): SHA-256")
    }

    // MARK: G1–G4 (docs/12 §Vectores dorados)

    static let g1Meta = #"{"format":1,"rom_sha256":"10355a7fa4c9ee13385d82a7ccf1163b6085aacff4193e6388add2f71c41668b","sav_sha256":"d3c5532fe0534370189004c7704430c1470f5c0f0be7b9e522c3799517ac28ef","base_sav_sha256":null,"device":{"platform":"ios","name":"iPhone de prueba"},"created_ms":1790000000000,"core":{"name":"gb","version":"1.0.0"},"config":{"model":"cgb","compat_palette":"default"},"state_of_sav_sha256":"d3c5532fe0534370189004c7704430c1470f5c0f0be7b9e522c3799517ac28ef","play_time_ms":3723000,"title":"Pok\u00e9mon Rojo \u2013 prueba","alias":"Mi partida","tags":["rpg","prueba"],"milestones":[{"id":"badge1","title":"Medalla Roca","done":true},{"id":"badge2","title":"Medalla Cascada","done":false}],"moment":{"name":"Antes del gimnasio","collection":"Principal","note":"Nota de prueba","created_ms":1789999000000}}"#

    @Test func goldenG1AndG2AreReproducedAndRead() throws {
        #expect(Self.g1Meta.utf8.count == 802)
        let png = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]) + pat(0x63, 24)
        let g1 = try package(meta: Self.g1Meta, save: pat(0x21, 8192), state: pat(0x42, 1500), thumb: png)
        expect(g1, length: 10614, sha256: "e83ee087bd870c6b395bca974654ce25f799839b3e77f6865b01f142bc16ab4d", "G1")
        let g2 = try package(meta: nil, save: pat(0x21, 16))
        expect(g2, length: 80, sha256: "5548b8cac9de16d52d17aec2907fd61832443bdebb4dc747499f726da9ef41c9", "G2")

        let read = try PGBMPackage.parse(g1)
        #expect(read.romFingerprint == rom)
        #expect(read.save == pat(0x21, 8192))
        #expect(read.state == pat(0x42, 1500))
        #expect(read.thumbnail == png)
        let meta = try PackageMeta.parse(try #require(read.meta))
        #expect(meta.title == "Pokémon Rojo – prueba")
        #expect(meta.romSHA256 == R)
        #expect(meta.milestones?.count == 2)
        #expect(try read.encoded() == g1)   // leer y volver a escribir da los mismos bytes
        #expect(try PGBMPackage.parse(g2).meta == nil)
    }

    /// G3 y G4 los escribe a mano el test (el codificador no produce secciones desconocidas).
    private func handBuilt(_ type: String) -> Data {
        var body = Data()
        func section(_ t: String, _ d: Data) {
            body += Data(t.utf8)
            body += withUnsafeBytes(of: UInt32(d.count).littleEndian) { Data($0) }
            body += d
        }
        section("ROMF", rom); section(type, Data("hola!".utf8)); section("SAVE", pat(0x21, 16))
        var pre = Data("PGBM".utf8)
        pre += withUnsafeBytes(of: UInt16(1).littleEndian) { Data($0) }
        pre += withUnsafeBytes(of: UInt16(3).littleEndian) { Data($0) }
        pre += withUnsafeBytes(of: UInt32(12 + body.count + 4).littleEndian) { Data($0) }
        pre += body
        return pre + withUnsafeBytes(of: crc32(pre).littleEndian) { Data($0) }
    }

    private func crc32(_ d: Data) -> UInt32 {
        var c: UInt32 = 0xFFFF_FFFF
        for b in d {
            c ^= UInt32(b)
            for _ in 0..<8 { c = (c >> 1) ^ (0xEDB8_8320 & (0 &- (c & 1))) }
        }
        return ~c
    }

    @Test func goldenG3IsReadAndG4IsCritical() throws {
        let g3 = handBuilt("xtra"), g4 = handBuilt("XTRA")
        expect(g3, length: 93, sha256: "1d61e5c030c7855a197e5b4b05e7417d7c452d4f2accf5441d494e84cb96a261", "G3")
        expect(g4, length: 93, sha256: "b6b3f3e96df34995d4bdf171e368acae20f5b72bb512b824fae509d3979a4a53", "G4")
        #expect(try PGBMPackage.parse(g3).save == pat(0x21, 16))
        #expect(throws: PGBMPackage.ContainerError.critical) { try PGBMPackage.parse(g4) }
    }

    // MARK: X1–X7 (docs/12 §Vectores cruzados de las apps)

    var S1: Data { pat(0x21, 32_768) }
    var B: Data { pat(0x22, 32_768) }
    var S2: Data { pat(0x23, 32_768) }

    func meta(_ m: String) -> String {
        m.replacingOccurrences(of: "\"R\"", with: "\"\(R)\"")
            .replacingOccurrences(of: "\"S1\"", with: "\"\(sha(S1))\"")
            .replacingOccurrences(of: "\"S2\"", with: "\"\(sha(S2))\"")
            .replacingOccurrences(of: "\"B\"", with: "\"\(sha(B))\"")
            .replacingOccurrences(of: "\"S4\"", with: "\"\(sha(pat(0x21, 8000)))\"")
            .replacingOccurrences(of: "\"<pat(0x24, 32768)>\"", with: "\"\(sha(pat(0x24, 32_768)))\"")
    }

    var x1: Data { get throws { try package(meta: meta(#"{"format":1,"rom_sha256":"R","sav_sha256":"S1","base_sav_sha256":"B","device":{"platform":"android","name":"Pixel de prueba"},"created_ms":1790003600000,"core":{"name":"gb","version":"1.0.0"},"state_of_sav_sha256":"S1","play_time_ms":7200000,"title":"Prueba cruzada","tags":["cruzado"]}"#), save: S1, state: pat(0x42, 4096)) } }
    var x2: Data { get throws { try package(meta: meta(#"{"format":1,"rom_sha256":"R","sav_sha256":"S2","base_sav_sha256":"S1","device":{"platform":"ios","name":"iPhone de prueba"},"created_ms":1790007200000,"core":{"name":"gb","version":"1.0.0"},"state_of_sav_sha256":"<pat(0x24, 32768)>"}"#), save: S2, state: pat(0x43, 4096)) } }
    var x3: Data { get throws { try package(meta: meta(#"{"format":1,"rom_sha256":"R","sav_sha256":"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855","base_sav_sha256":null,"device":{"platform":"android","name":"Pixel de prueba"},"created_ms":1790000000000,"core":{"name":"gb","version":"1.0.0"}}"#), save: Data()) } }
    var x4: Data { get throws { try package(meta: meta(#"{"format":1,"rom_sha256":"R","sav_sha256":"S4","base_sav_sha256":null,"device":{"platform":"ios","name":"iPhone de prueba"},"created_ms":1790000000000,"core":{"name":"gb","version":"1.0.0"}}"#), save: pat(0x21, 8000)) } }
    var x5: Data { get throws { try package(meta: meta(#"{"format":2,"rom_sha256":"R","sav_sha256":"S1","base_sav_sha256":null,"device":{"platform":"android","name":"Pixel de prueba"},"created_ms":1790000000000,"core":{"name":"gb","version":"1.0.0"}}"#), save: S1) } }
    var x6: Data { get throws { try package(meta: nil, save: S1) } }
    var x7: Data { get throws { try package(meta: meta(#"{"format":1,"rom_sha256":"R","sav_sha256":"B","base_sav_sha256":null,"device":{"platform":"android","name":"Pixel de prueba"},"created_ms":1790000000000,"core":{"name":"gb","version":"1.0.0"}}"#), save: S1) } }

    @Test func crossVectorsMatchTheReferenceBytes() throws {
        #expect(sha(S1) == "5e08944e1a748c003e29abf76d1930cb1ed35933e7741e52a9ce055f3fa77a76")
        #expect(sha(B) == "b28ee9fab76c8a575342631959fca77e58ef6885e554111f35e76195dc16322b")
        #expect(sha(S2) == "cbab8faef55e97b329a4bcffbe8cc683be5aabc75970565eca6008250e3f6b39")
        expect(try x1, length: 37480, sha256: "b7d163cf9fda7b6ccbcd240b1a25b97518caccdda45b5a38e37a021e713bf796", "X1")
        expect(try x2, length: 37410, sha256: "e79d7993c7cd380ac7496f792766362e0689fe285ff2c06a9424508b40db5f2f", "X2")
        expect(try x3, length: 390, sha256: "c1f033894a9d9f7bc15cfcc353b776caf6303fadb51f7168829045cfbb430e17", "X3")
        expect(try x4, length: 8387, sha256: "336401621230102beadbadbdba1643acdc58da343fe36e2105d9e07966933771", "X4")
        expect(try x5, length: 33158, sha256: "8b1fb567aa21ffd5c907b59111c93eb4f67d7dba91bb57aff5e4b5804f30718a", "X5")
        expect(try x6, length: 32832, sha256: "c7f5650966a804b620f8d29a63766b2583d53a2a30384b030ceafe224b236a2e", "X6")
        expect(try x7, length: 33158, sha256: "6d8b3af30e6fe76adf7fef36fc21f80a3db0a86d4df1a8faa00d573b07a39880", "X7")
    }

    // MARK: Importador real con el cartucho de referencia

    private struct Env {
        let store: SaveStore
        let states: StateStore
        let moments: MomentStore
        let ownership = FingerprintOwnership()
    }

    private func env() -> Env {
        let fp = cartridge.fingerprint
        let root = dir.appendingPathComponent(UUID().uuidString)
        try? FileManager.default.createDirectory(at: root.appendingPathComponent("Saves"), withIntermediateDirectories: true)
        return Env(store: SaveStore(directory: root.appendingPathComponent("Saves"), fingerprint: fp),
                   states: StateStore(root: root.appendingPathComponent("States"), fingerprint: fp),
                   moments: MomentStore(root: root.appendingPathComponent("Moments"), fingerprint: fp))
    }

    private func run(_ data: Data, _ e: Env, choice: DivergenceChoice? = nil) throws -> SaveImport.Outcome {
        let plan = try SaveImport.evaluate(data, cartridge: cartridge, local: try e.store.load(), known: e.store.knownHashes())
        return try SaveImport.apply(plan, choice: choice, store: e.store, states: e.states, moments: e.moments, ownership: e.ownership)
    }

    /// Todo lo de disco del juego, para comprobar «sin tocar nada».
    private func snapshot(_ e: Env) -> [String: Data] {
        var out: [String: Data] = [:]
        for base in [e.store.directory, e.states.directory] {
            guard let it = FileManager.default.enumerator(at: base, includingPropertiesForKeys: nil) else { continue }
            for case let url as URL in it { if let d = try? Data(contentsOf: url) { out[url.path] = d } }
        }
        return out
    }

    @Test func x1OffersExactContinuationFromAndroid() throws {
        let e = env()
        #expect(try run(try x1, e) == .installed(continuation: true))
        #expect(try e.store.load() == S1)
        #expect(try e.states.load(.auto) == pat(0x42, 4096))
        // (T1 es una carga sintética sin la firma «PGBS»: al continuar la valida `gb_state_load`.)
        #expect(try #require(e.states.entries()[.auto]).date >= (e.store.modificationDate ?? .distantFuture))
        let origin = try #require(SaveImport.Origin.load(e.store))
        #expect(origin.deviceName == "Pixel de prueba" && origin.platform == "android" && origin.continuation)
        // Volver a importarlo con la misma partida: no se reescribe nada, sigue ofreciendo continuar.
        #expect(try run(try x1, e) == .alreadyCurrent(continuation: true))
        #expect(e.store.backups().isEmpty)
    }

    @Test func x2AdvancesFromS1WithoutStateAndDivergesFromB() throws {
        let e = env()
        try e.store.save(S1)
        #expect(try run(try x2, e) == .installed(continuation: false))
        #expect(try e.store.load() == S2)
        #expect(try Data(contentsOf: e.store.backupURL(1)) == S1)
        #expect((try? e.states.load(.auto)) == nil)
        #expect(try e.moments.snapshot().beforeLoad.first?.name.hasPrefix("Antes de importar") == true)

        let d = env()
        try d.store.save(B)
        let before = snapshot(d)
        #expect(try run(try x2, d) == .needsChoice)
        #expect(snapshot(d) == before)   // preguntar no toca nada
        #expect(try run(try x2, d, choice: .keepLocal) == .keptLocal)
        #expect(try d.store.load() == B)
        #expect(d.store.keptCopies().contains { (try? Data(contentsOf: $0.url)) == S2 })
        #expect(try d.moments.snapshot().moments.first?.name.hasPrefix("Conflicto") == true)
    }

    @Test func x3AndX4NeverTouchTheSaveAndLeaveABackup() throws {
        for vector in [try x3, try x4] {
            let e = env()
            try e.store.save(S1)
            let mtime = e.store.modificationDate
            #expect(try run(vector, e) == .saveNotTouched)
            #expect(try e.store.load() == S1)
            #expect(e.store.modificationDate == mtime)
            #expect(try Data(contentsOf: e.store.backupURL(1)) == S1)
        }
    }

    @Test func x5X6X7AndG4AreRejectedWithoutTouchingAnything() throws {
        for (name, vector) in [("X5", try x5), ("X6", try x6), ("X7", try x7), ("G4", handBuilt("XTRA"))] {
            let e = env()
            try e.store.save(B)
            let before = snapshot(e)
            #expect(throws: SaveImport.Rejection.self, "\(name)") { try run(vector, e) }
            #expect(snapshot(e) == before, "\(name)")
        }
        #expect(throws: SaveImport.Rejection.meta(.newerFormat)) { try run(try x5, env()) }
        #expect(throws: SaveImport.Rejection.missingMeta) { try run(try x6, env()) }
        #expect(throws: SaveImport.Rejection.saveMismatch) { try run(try x7, env()) }
        #expect(throws: SaveImport.Rejection.container(.critical)) { try run(handBuilt("XTRA"), env()) }
    }

    @Test func importRespectsFingerprintOwnership() throws {
        let e = env()
        try e.store.save(S1)
        let lease = try #require(e.ownership.tryAcquire(cartridge.fingerprint, owner: "sesión"))
        #expect(throws: FingerprintOwnership.Busy.self) { try run(try x2, e) }
        #expect(try e.store.load() == S1)
        lease.release()
        #expect(try run(try x2, e) == .installed(continuation: false))
    }

    @Test func otherGameAndHostileBytesAreRejected() throws {
        var other = cartridge
        other = SaveImport.Cartridge(fingerprint: String(repeating: "0", count: 32), romSHA256: nil, console: .gameBoy,
                                     hasBattery: true, validSizes: [32_768])
        #expect(throws: SaveImport.Rejection.otherGame) {
            try SaveImport.evaluate(try x1, cartridge: other, local: nil, known: [])
        }
        var corrupt = try x1
        corrupt[100] ^= 0xFF
        #expect(throws: SaveImport.Rejection.container(.init(code: 5))) {
            try SaveImport.evaluate(corrupt, cartridge: cartridge, local: nil, known: [])
        }
        for cut in [0, 4, 11, 12, 100, 37_479] {
            #expect(throws: SaveImport.Rejection.self) {
                try SaveImport.evaluate((try x1).prefix(cut), cartridge: cartridge, local: nil, known: [])
            }
        }
    }

    @Test func rawSaveImportValidatesExactSizeAndBacksUp() throws {
        let e = env()
        try e.store.save(S1)
        #expect(throws: SaveImport.Rejection.rawWrongSize) { try run(pat(0x21, 32_767), e) }
        #expect(try run(B, e) == .needsChoice)   // un .sav crudo siempre se confirma
        #expect(try run(B, e, choice: .useOther) == .installed(continuation: false))
        #expect(try e.store.load() == B)
        #expect(try Data(contentsOf: e.store.backupURL(1)) == S1)
    }

    /// Ida y vuelta en la misma plataforma: exportar → importar en otro «iPhone» da la misma partida y continuación.
    @Test func exportThenImportRoundTrip() throws {
        let a = env()
        try a.store.save(S1)
        let auto = Data("PGBS".utf8) + pat(0x42, 4092)   // con la firma del núcleo, para que cuente como automático
        try a.states.save(auto, thumbnail: nil, to: .auto)
        let game = SaveExport.Game(fingerprint: cartridge.fingerprint, romDigest: rom, console: .gameBoy, title: "Prueba",
                                   alias: "Mía", tags: ["rpg"], playTime: 60,
                                   milestones: [.init(id: "m1", title: "Uno", done: true)])
        let data = try SaveExport.package(game, store: a.store, states: a.states, deviceName: "iPhone A")
        let meta = try PackageMeta.parse(try #require(try PGBMPackage.parse(data).meta))
        #expect(meta.platform == "ios" && meta.alias == "Mía" && meta.playTimeMs == 60_000 && meta.baseSavSHA256 == nil)
        let b = env()
        #expect(try run(data, b) == .installed(continuation: true))
        #expect(try b.store.load() == S1)
        #expect(try b.states.load(.auto) == auto)
        // B avanza y lo devuelve: el paquete de B lleva base = S1 y A lo recibe como avance.
        try b.store.save(S2)
        let back = try SaveExport.package(game, store: b.store, states: nil, deviceName: "iPhone B")
        #expect(try PackageMeta.parse(try #require(try PGBMPackage.parse(back).meta)).baseSavSHA256 == sha(S1))
        #expect(try run(back, a) == .installed(continuation: false))
        #expect(try a.store.load() == S2)
    }

    @Test func metaSchemaIsStrict() throws {
        let ok = meta(#"{"format":1,"rom_sha256":"R","sav_sha256":"S1","device":{"platform":"ios","name":"x"},"created_ms":1,"core":{"name":"gb"}}"#)
        #expect(throws: Never.self) { try PackageMeta.parse(Data(ok.utf8)) }
        let bad = [
            ok.replacingOccurrences(of: #""created_ms":1"#, with: #""created_ms":1.5"#),
            ok.replacingOccurrences(of: #""created_ms":1"#, with: #""created_ms":-1"#),
            ok.replacingOccurrences(of: #""created_ms":1"#, with: #""created_ms":true"#),
            ok.replacingOccurrences(of: #""platform":"ios""#, with: #""platform":"web""#),
            ok.replacingOccurrences(of: #""name":"gb""#, with: #""name":"nes""#),
            ok.replacingOccurrences(of: #","created_ms":1"#, with: ""),
            ok.replacingOccurrences(of: sha(S1), with: "xyz"),
            "[1]", "no es json",
        ]
        for json in bad { #expect(throws: PackageMeta.Invalid.self, "\(json)") { try PackageMeta.parse(Data(json.utf8)) } }
    }

    // MARK: Auditoría N7 iOS

    private let ownAuto = Data("PGBS".utf8) + pat(0x55, 4092)

    /// H1: la partida ya es la misma (X1 con local = S1) pero el estado propio es otro: se pregunta sin tocar nada; al
    /// aceptar, el propio queda en «Antes de cargar» con su estado; al rechazar, el del paquete queda como «Conflicto».
    @Test func sameSaveWithDifferentOwnAutoAsksAndKeepsIt() throws {
        let e = env()
        try e.store.save(S1)
        try e.states.save(ownAuto, thumbnail: nil, to: .auto)
        let before = snapshot(e)
        #expect(try run(try x1, e) == .needsStateChoice)
        #expect(snapshot(e) == before)

        #expect(try run(try x1, e, choice: .keepLocal) == .keptLocal)
        #expect(try e.states.load(.auto) == ownAuto)
        let conflict = try #require(try e.moments.snapshot().moments.first)
        #expect(try e.moments.loadState(.moment, conflict.id) == pat(0x42, 4096))

        #expect(try run(try x1, e, choice: .useOther) == .alreadyCurrent(continuation: true))
        #expect(try e.states.load(.auto) == pat(0x42, 4096))
        let kept = try #require(try e.moments.snapshot().beforeLoad.first)
        #expect(try e.moments.loadState(.beforeLoad, kept.id) == ownAuto)
        #expect(try e.store.load() == S1)
    }

    /// H1: juego sin batería (SAVE vacía) con estado propio distinto: igual, se pregunta y se conserva.
    @Test func batterylessGameKeepsOwnAutoBeforeReplacing() throws {
        let e = env()
        try e.states.save(ownAuto, thumbnail: nil, to: .auto)
        let empty = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        let pkg = try package(meta: meta(#"{"format":1,"rom_sha256":"R","sav_sha256":"\#(empty)","device":{"platform":"android","name":"Pixel"},"created_ms":1,"core":{"name":"gb"},"state_of_sav_sha256":"\#(empty)"}"#),
                              save: Data(), state: pat(0x42, 64))
        let cart = SaveImport.Cartridge(fingerprint: cartridge.fingerprint, romSHA256: R, console: .gameBoy,
                                        hasBattery: false, validSizes: [])
        let plan = try SaveImport.evaluate(pkg, cartridge: cart, local: nil, known: [])
        func apply(_ c: DivergenceChoice?) throws -> SaveImport.Outcome {
            try SaveImport.apply(plan, choice: c, store: e.store, states: e.states, moments: e.moments, ownership: e.ownership)
        }
        #expect(try apply(nil) == .needsStateChoice)
        #expect(try e.states.load(.auto) == ownAuto)
        #expect(try apply(.useOther) == .alreadyCurrent(continuation: true))
        #expect(try e.states.load(.auto) == pat(0x42, 64))
        let kept = try #require(try e.moments.snapshot().beforeLoad.first)
        #expect(try e.moments.loadState(.beforeLoad, kept.id) == ownAuto)
    }

    /// H1: sin partida local pero con estado propio, instalar un paquete con continuación guarda antes ese estado.
    @Test func noLocalSaveStillKeepsOwnAuto() throws {
        let e = env()
        try FileManager.default.createDirectory(at: e.states.directory, withIntermediateDirectories: true)
        try e.states.save(ownAuto, thumbnail: nil, to: .auto)
        #expect(try run(try x1, e) == .installed(continuation: true))
        let kept = try #require(try e.moments.snapshot().beforeLoad.first)
        #expect(try e.moments.loadState(.beforeLoad, kept.id) == ownAuto)
    }

    /// N1: importar deja el anillo en 3 y conserva la entrada nueva.
    @Test func importTrimsTheRingKeepingTheNewEntry() throws {
        let e = env()
        for i in 0..<3 { _ = try e.moments.appendBeforeLoad(.init(state: nil, sram: pat(i, 4), thumbnail: nil), label: "v\(i)") }
        try e.store.save(S1)
        #expect(try run(try x2, e) == .installed(continuation: false))
        let ring = try e.moments.snapshot().beforeLoad
        #expect(ring.count == 3)
        #expect(ring.first?.name.hasPrefix("Antes de importar") == true)
        #expect(try e.moments.loadSRAM(.beforeLoad, try #require(ring.first).id) == S1)
    }

    /// H3: un `.sav` crudo se confirma también sin partida previa, y la pregunta nombra el juego.
    @Test func rawSaveIsConfirmedEvenWithoutLocalAndNamesTheGame() throws {
        let e = env()
        #expect(try run(S1, e) == .needsChoice)
        #expect(try e.store.load() == nil)
        let plan = try SaveImport.evaluate(S1, cartridge: cartridge, local: nil, known: [])
        let entry = RomEntry(id: "x.gb", url: URL(fileURLWithPath: "/x.gb"), fileName: "x.gb", title: "X", isColor: false,
                             sizeBytes: 0, headerChecksumOK: true, cloud: .current, problem: nil, mirrorSaveDate: nil)
        #expect(ImportPrompt(entry: entry, plan: plan, gameTitle: "Pokémon Rojo").title.contains("Pokémon Rojo"))
    }
}

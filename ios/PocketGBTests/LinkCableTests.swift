import Foundation
import Testing
@testable import PocketGB

/// M9 lote 1: el cable link en Swift, sin hilos (se llama a `runFrame` a mano).
struct LinkCableTests {
    static let keys: [UInt8] = [0x5A, 0xA5]

    static func core(_ rom: Data, color: Bool = false) throws -> CoreBridge {
        let core = try CoreBridge()
        _ = try core.loadROM(rom, unixTime: 0, sampleRate: 48_000, colorForGameBoy: color)
        return core
    }

    static func pair(_ a: Data, _ b: Data, colorA: Bool = false, colorB: Bool = false,
                     selector: LinkSideSelector = LinkSideSelector()) throws -> (LinkedPair, FrameBuffers) {
        let peer = FrameBuffers()
        let pair = try LinkedPair(cores: [core(a, color: colorA), core(b, color: colorB)],
                                  selector: selector, peerFrames: peer)
        return (pair, peer)
    }

    /// Avanza hasta que los dos lados escriben 1 en $A100 (≤ 30 frames) y comprueba los 16 bytes.
    static func runExchange(_ pair: LinkedPair, sourceLine: Int = #line) throws {
        var frames = 0
        func done() throws -> Bool {
            try pair.cores.allSatisfy { try $0.sramSave()[0x100] == 1 }
        }
        while frames < 30, try !done() {
            pair.runFrame()
            frames += 1
        }
        #expect(try done(), "el intercambio no terminó en 30 frames")
        for s in 0..<2 {
            let sram = try pair.cores[s].sramSave()
            for i in 0..<16 { #expect(sram[i] == UInt8(i) ^ keys[1 - s], "lado \(s) byte \(i)") }
        }
    }

    @Test func exchangeWithMasterOnSideZero() throws {
        let (pair, _) = try Self.pair(LinkTestROMs.exchange(title: "MAESTRO", key: Self.keys[0], sc: 0x81),
                                      LinkTestROMs.exchange(title: "ESCLAVO", key: Self.keys[1], sc: 0x80))
        try Self.runExchange(pair)
    }

    @Test func exchangeWithMasterOnSideOne() throws {
        let (pair, _) = try Self.pair(LinkTestROMs.exchange(title: "ESCLAVO", key: Self.keys[0], sc: 0x80),
                                      LinkTestROMs.exchange(title: "MAESTRO", key: Self.keys[1], sc: 0x81))
        try Self.runExchange(pair)
    }

    /// Rojo ↔ Amarillo: un juego nativo de Color con otro de Game Boy en compatibilidad (CGB).
    @Test func exchangeBetweenNativeColorAndCompatibility() throws {
        let (pair, _) = try Self.pair(LinkTestROMs.exchange(title: "COLOR", key: Self.keys[0], sc: 0x81, color: true),
                                      LinkTestROMs.exchange(title: "COMPAT", key: Self.keys[1], sc: 0x80),
                                      colorA: false, colorB: true)
        try Self.runExchange(pair)
    }

    @Test func masterWithoutCableReceivesFF() throws {
        let core = try Self.core(LinkTestROMs.exchange(title: "SOLO", key: 0, sc: 0x81))
        for _ in 0..<30 { core.runFrame() }
        let sram = try core.sramSave()
        #expect(sram[0x100] == 1)
        for i in 0..<16 { #expect(sram[i] == 0xFF, "byte \(i)") }
    }

    @Test func cableRetainsItsCoresUntilDetached() throws {
        weak var a: CoreBridge?
        weak var b: CoreBridge?
        let cable = try LinkCable()
        do {
            let ca = try Self.core(LinkTestROMs.romOnly(title: "A")), cb = try Self.core(LinkTestROMs.romOnly(title: "B"))
            a = ca; b = cb
            #expect(cable.attach(ca, cb))
        }
        #expect(cable.isAttached && a != nil && b != nil)
        cable.detach()
        #expect(!cable.isAttached && a == nil && b == nil)
    }

    @Test func destroyingTheCableReleasesItsCores() throws {
        weak var a: CoreBridge?
        do {
            let cable = try LinkCable()
            let ca = try Self.core(LinkTestROMs.romOnly(title: "A")), cb = try Self.core(LinkTestROMs.romOnly(title: "B"))
            a = ca
            #expect(cable.attach(ca, cb))
        }
        #expect(a == nil)
    }

    @Test func aCoreCannotBeInTwoCablesAndSelfAttachFails() throws {
        let first = try LinkCable(), second = try LinkCable()
        let a = try Self.core(LinkTestROMs.romOnly(title: "A"))
        let b = try Self.core(LinkTestROMs.romOnly(title: "B"))
        #expect(first.attach(a, b))
        weak var c: CoreBridge?
        do {
            let cc = try Self.core(LinkTestROMs.romOnly(title: "C"))
            c = cc
            #expect(!second.attach(a, cc))
        }
        #expect(!second.isAttached && c == nil)
        #expect(first.isAttached)

        weak var d: CoreBridge?
        do {
            let dd = try Self.core(LinkTestROMs.romOnly(title: "D"))
            d = dd
            #expect(!second.attach(dd, dd))
        }
        #expect(!second.isAttached && d == nil)
    }

    @Test func buttonsReachOnlyTheActiveSide() throws {
        let selector = LinkSideSelector()
        let rom = LinkTestROMs.joypadToSRAM(title: "BOTONES")
        let (pair, _) = try Self.pair(rom, rom, selector: selector)
        func a(_ side: Int) throws -> Bool { try pair.cores[side].sramSave()[0] & 1 == 0 }   // A pulsado = bit 0 a 0
        pair.setButtons(0x01)
        for _ in 0..<3 { pair.runFrame() }
        #expect(try a(0) && !a(1))
        selector.side = 1
        for _ in 0..<3 { pair.runFrame() }
        #expect(try !a(0) && a(1))
    }

    @Test func framesFollowTheActiveSideAndPeerFramesHoldTheOther() throws {
        let selector = LinkSideSelector()
        let (pair, peer) = try Self.pair(LinkTestROMs.palette(title: "BLANCO", bgp: 0x00),
                                         LinkTestROMs.palette(title: "NEGRO", bgp: 0xFF), selector: selector)
        func pixels() -> (active: UInt32, peer: UInt32) {
            var buffer = [UInt32](repeating: 0, count: ScreenSize.gameBoy.pixelCount)
            buffer.withUnsafeMutableBufferPointer { pair.copyFramebuffer(to: $0.baseAddress!) }
            return (buffer[0], peer.latest()[0])
        }
        for _ in 0..<5 { pair.runFrame() }
        let side0 = pixels()
        #expect(side0.active != side0.peer)
        selector.side = 1
        for _ in 0..<2 { pair.runFrame() }
        let side1 = pixels()
        #expect(side1.active == side0.peer && side1.peer == side0.active)
    }

    @Test func audioAfterSwitchingSidesIsNotTheStaleBacklog() throws {
        let selector = LinkSideSelector()
        let rom = LinkTestROMs.romOnly(title: "AUDIO")
        let (pair, _) = try Self.pair(rom, rom, selector: selector)
        let scratch = UnsafeMutablePointer<Int16>.allocate(capacity: 16_384)
        defer { scratch.deallocate() }
        for _ in 0..<30 { pair.runFrame() }       // 30 frames sin leer el lado 0: se llenaría el anillo
        selector.side = 1
        pair.runFrame()
        var total = 0
        while case let n = pair.readAudio(into: scratch, maxFrames: 4_096), n > 0 { total += n }
        #expect(total > 0 && total <= 1_000, "audio leído: \(total)")
    }

    @Test func linkedPairHasNoStatesNorSRAMAndShutdownDetaches() throws {
        let rom = LinkTestROMs.romOnly(title: "PAR")
        let (pair, _) = try Self.pair(rom, rom)
        #expect(pair.isAttached)
        #expect(throws: CoreError.linkUnsupported) { try pair.stateSave() }
        #expect(throws: CoreError.linkUnsupported) { try pair.stateLoad(Data()) }
        #expect(throws: CoreError.linkUnsupported) { try pair.sramSave() }
        #expect(pair.sramSaveSize == 0 && pair.sramFooterBytes == 0 && !pair.sramDirty)
        pair.shutdown()
        #expect(!pair.isAttached)
    }
}

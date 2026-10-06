import Foundation

/// Dos Game Boy unidas por el cable virtual, presentadas a `EmulatorSession` como un solo
/// `ConsoleCore`. Es el único dueño del cable y de los dos núcleos (docs/hitos/M9-ios-plan.md §1).
///
/// Todo el acceso ocurre en el hilo de emulación. El lado activo (el que se ve, se oye y recibe
/// los botones) lo fija `selector` y se lee una vez por frame. Nonisolated: `EmulatorSession`
/// lo usa fuera del hilo principal.
final class LinkedPair: ConsoleCore {
    let console = Console.gameBoy
    let cores: [CoreBridge]
    private let cable: LinkCable
    private let selector: LinkSideSelector
    private let peerFrames: FrameBuffers
    private var buttons: UInt16 = 0
    private var side = 0
    private let scratch: UnsafeMutablePointer<Int16>
    private static let scratchFrames = 1_024

    init(cores: [CoreBridge], selector: LinkSideSelector, peerFrames: FrameBuffers) throws {
        guard cores.count == 2 else { throw CoreError.linkAttachFailed }
        let cable = try LinkCable()
        guard cable.attach(cores[0], cores[1]) else { throw CoreError.linkAttachFailed }
        self.cores = cores
        self.cable = cable
        self.selector = selector
        self.peerFrames = peerFrames
        scratch = .allocate(capacity: Self.scratchFrames * 2)
    }

    deinit { scratch.deallocate() }

    var isAttached: Bool { cable.isAttached }

    func setButtons(_ mask: UInt16) { buttons = mask }

    func runFrame() {
        side = selector.side
        cores[side].setButtons(buttons)
        cores[1 - side].setButtons(0)
        cable.runFrame()
        // El lado inactivo no suena: se vacía su audio cada frame para que, al cambiar de
        // juego, no salga audio viejo (el anillo del APU descarta lo nuevo cuando está lleno).
        while cores[1 - side].readAudio(into: scratch, maxFrames: Self.scratchFrames) > 0 {}
        peerFrames.publish { cable.copyFramebuffer(side: 1 - side, to: $0) }
    }

    var cpuLocked: Bool { cores[side].cpuLocked }

    func copyFramebuffer(to dst: UnsafeMutablePointer<UInt32>) {
        cable.copyFramebuffer(side: side, to: dst)
    }

    func readAudio(into dst: UnsafeMutablePointer<Int16>, maxFrames: Int) -> Int {
        cores[side].readAudio(into: dst, maxFrames: maxFrames)
    }

    // Cada lado guarda su partida con su propia `SRAMPersistence`.
    var sramSaveSize: Int { 0 }
    var sramFooterBytes: Int { 0 }
    var sramDirty: Bool { false }
    func clearSRAMDirty() {}
    func sramLoad(_ data: Data) throws(CoreError) { throw .linkUnsupported }
    func sramSave() throws(CoreError) -> Data { throw .linkUnsupported }

    func setRTCTime(_ unixTime: Int64) { cores.forEach { $0.setRTCTime(unixTime) } }

    func stateSave() throws(CoreError) -> Data { throw .linkUnsupported }
    func stateLoad(_ data: Data) throws(CoreError) { throw .linkUnsupported }

    func shutdown() { cable.detach() }
}

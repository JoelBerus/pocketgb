import Foundation

/// Consola de un ROM. Decide qué núcleo se usa (docs/hitos/G-README.md §G7).
enum Console: String, Sendable, Codable, CaseIterable {
    case gameBoy = "gb"
    case gameBoyAdvance = "gba"

    /// Por la extensión del archivo: `.gba` es Game Boy Advance; el resto, Game Boy.
    init(fileName: String) {
        self = (fileName as NSString).pathExtension.lowercased() == "gba" ? .gameBoyAdvance : .gameBoy
    }

    var screen: ScreenSize { self == .gameBoyAdvance ? .gameBoyAdvance : .gameBoy }

    var shortName: String { self == .gameBoyAdvance ? "GBA" : "GB" }
}

/// Tamaño del framebuffer de una consola (RGBA8888).
struct ScreenSize: Sendable, Equatable {
    let width: Int
    let height: Int
    var pixelCount: Int { width * height }
    /// Ancho / alto: 10:9 en Game Boy, 3:2 en Game Boy Advance.
    var aspectRatio: Double { Double(width) / Double(height) }

    static let gameBoy = ScreenSize(width: 160, height: 144)
    static let gameBoyAdvance = ScreenSize(width: 240, height: 160)
    static let largest = gameBoyAdvance

    /// El tamaño de un frame por su número de píxeles (portadas y miniaturas de estados).
    init?(pixelCount: Int) {
        switch pixelCount {
        case Self.gameBoy.pixelCount: self = .gameBoy
        case Self.gameBoyAdvance.pixelCount: self = .gameBoyAdvance
        default: return nil
        }
    }

    init(width: Int, height: Int) {
        self.width = width
        self.height = height
    }
}

/// Lo que la sesión necesita de un núcleo. `CoreBridge` (Game Boy) y `GBACoreBridge`
/// (Game Boy Advance) lo implementan; ninguna instancia es thread-safe: tras
/// `EmulatorSession.start` solo se usa desde el hilo de emulación.
protocol ConsoleCore: AnyObject {
    var console: Console { get }

    /// Máscara con el orden de bits común a los dos núcleos: A, B, Select, Start,
    /// derecha, izquierda, arriba, abajo y, solo en GBA, R (bit 8) y L (bit 9). El núcleo
    /// Game Boy ignora los bits altos.
    func setButtons(_ mask: UInt16)
    func runFrame()
    var cpuLocked: Bool { get }
    /// Copia `console.screen.pixelCount` píxeles RGBA8888 a `dst`.
    func copyFramebuffer(to dst: UnsafeMutablePointer<UInt32>)
    func readAudio(into dst: UnsafeMutablePointer<Int16>, maxFrames: Int) -> Int

    /// La partida del cartucho como un único bloque (el `.sav` que se guarda y se refleja
    /// junto al ROM).
    var sramSaveSize: Int { get }
    var sramDirty: Bool { get }
    func clearSRAMDirty()
    func sramLoad(_ data: Data) throws(CoreError)
    func sramSave() throws(CoreError) -> Data
    func setRTCTime(_ unixTime: Int64)

    func stateSave() throws(CoreError) -> Data
    func stateLoad(_ data: Data) throws(CoreError)
}

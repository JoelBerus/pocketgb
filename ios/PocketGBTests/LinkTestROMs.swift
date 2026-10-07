import Foundation

/// ROMs sintéticos del cable link (M9, docs/hitos/M9-ios-plan.md §5.1). Cabecera MBC1 + RAM +
/// batería salvo `romOnly`. Nunca ROMs reales.
enum LinkTestROMs {
    /// Ensamblador mínimo con etiquetas: los `jr` se calculan como `etiqueta − (pc + 2)`.
    struct Program {
        private(set) var bytes: [UInt8] = []
        var here: Int { bytes.count }

        mutating func emit(_ b: UInt8...) { bytes += b }

        mutating func jr(_ opcode: UInt8, to label: Int) {
            let offset = label - (here + 2)
            precondition((-128...127).contains(offset), "salto fuera de rango")
            bytes += [opcode, UInt8(bitPattern: Int8(offset))]
        }
        mutating func jrNZ(_ label: Int) { jr(0x20, to: label) }
        mutating func jr(_ label: Int) { jr(0x18, to: label) }
    }

    /// Cabecera con título y checksum; el programa va en $0150 (tras `JP $0150`).
    static func rom(title: String, program: Program, cartType: UInt8, ramCode: UInt8, color: Bool = false) -> Data {
        var rom = LibraryScannerTests.rom(title: title, color: color)
        rom[0x100] = 0xC3; rom[0x101] = 0x50; rom[0x102] = 0x01   // JP $0150
        rom[0x147] = cartType
        rom[0x149] = ramCode
        rom.replaceSubrange(0x150..<(0x150 + program.bytes.count), with: program.bytes)
        var checksum: UInt8 = 0
        for i in 0x134...0x14C { checksum = checksum &- rom[i] &- 1 }
        rom[0x14D] = checksum
        return rom
    }

    private static func battery(title: String, program: Program, color: Bool = false) -> Data {
        rom(title: title, program: program, cartType: 0x03, ramCode: 0x02, color: color)
    }

    /// Intercambia 16 bytes (`i ^ key`) por la serie, guarda lo recibido en $A000+i y escribe 1 en
    /// $A100. `sc` = 0x81 (maestro) o 0x80 (esclavo). Copia de `build_exchange` (core/tests/unit_link.c)
    /// con la SRAM en lugar de la WRAM.
    static func exchange(title: String, key: UInt8, sc: UInt8, color: Bool = false) -> Data {
        var p = Program()
        p.emit(0x3E, 0x0A, 0xEA, 0x00, 0x00)              // habilitar RAM
        p.emit(0x21, 0x00, 0xA0, 0x06, 0x00)              // LD HL,$A000; LD B,0
        let loop = p.here
        p.emit(0x0E, (sc & 1) != 0 ? 0x40 : 0x01)         // LD C,espera
        let dly = p.here
        p.emit(0x0D)                                      // DEC C
        p.jrNZ(dly)
        p.emit(0x78, 0xEE, key, 0xE0, 0x01)               // LD A,B; XOR key; LDH (01),A
        p.emit(0x3E, sc, 0xE0, 0x02)                      // LD A,sc; LDH (02),A
        let wait = p.here
        p.emit(0xF0, 0x02, 0xCB, 0x7F)                    // LDH A,(02); BIT 7,A
        p.jrNZ(wait)
        p.emit(0xF0, 0x01, 0x22, 0x04, 0x78, 0xFE, 0x10)  // guardar; INC B; CP 16
        p.jrNZ(loop)
        p.emit(0x3E, 0x01, 0xEA, 0x00, 0xA1)              // ($A100) = 1
        let done = p.here
        p.jr(done)
        return battery(title: title, program: p, color: color)
    }

    /// Lee P1 con los botones seleccionados y guarda el resultado en $A000 (bit 0 = A, 0 si pulsado).
    static func joypadToSRAM(title: String) -> Data {
        var p = Program()
        p.emit(0x3E, 0x0A, 0xEA, 0x00, 0x00)              // habilitar RAM
        let loop = p.here
        p.emit(0x3E, 0x10, 0xE0, 0x00)                    // LD A,$10; LDH (00),A (botones)
        p.emit(0xF0, 0x00, 0xF0, 0x00)                    // LDH A,(00) ×2
        p.emit(0xEA, 0x00, 0xA0)                          // LD ($A000),A
        p.jr(loop)
        return battery(title: title, program: p)
    }

    /// Pantalla entera de un solo tono según `bgp` (DMG).
    static func palette(title: String, bgp: UInt8) -> Data {
        var p = Program()
        p.emit(0x3E, bgp, 0xE0, 0x47)                     // LD A,bgp; LDH (47),A
        let loop = p.here
        p.jr(loop)
        return battery(title: title, program: p)
    }

    /// Sin batería (mismo juego dos veces sin batería).
    static func romOnly(title: String) -> Data {
        var p = Program()
        let loop = p.here
        p.jr(loop)
        return rom(title: title, program: p, cartType: 0x00, ramCode: 0x00)
    }
}

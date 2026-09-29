/*
 * cpu_ops.inc — cuerpo del decodificador SM83, incluido dentro de cpu_step().
 *
 * Contexto: `g` (gb *), `c` (struct gb_cpu *), `op` (opcode ya leído).
 * Los opcodes irregulares van en su propio case; los bloques regulares
 * 0x40–0x7F (LD r,r') y 0x80–0xBF (ALU A,r) se decodifican por campos de bits
 * en `default`, y los 256 del prefijo CB en execute_cb(). Ciclos: tabla de gbdev.
 */
switch (op) {
/* ---- 0x00–0x3F ---- */
case 0x00: break;                                               /* NOP */
case 0x01: case 0x11: case 0x21: case 0x31:                     /* LD rr,d16 */
    set_rr(c, op >> 4, fetch16(g));
    break;
case 0x02: cpu_write(g, PAIR(b, c), c->a); break;               /* LD (BC),A */
case 0x12: cpu_write(g, PAIR(d, e), c->a); break;               /* LD (DE),A */
case 0x22: {                                                    /* LD (HL+),A */
    uint16_t hl = get_hl(c);
    cpu_write(g, hl, c->a);
    set_hl(c, (uint16_t)(hl + 1));
    break;
}
case 0x32: {                                                    /* LD (HL-),A */
    uint16_t hl = get_hl(c);
    cpu_write(g, hl, c->a);
    set_hl(c, (uint16_t)(hl - 1));
    break;
}
case 0x0A: c->a = cpu_read(g, PAIR(b, c)); break;               /* LD A,(BC) */
case 0x1A: c->a = cpu_read(g, PAIR(d, e)); break;               /* LD A,(DE) */
case 0x2A: {                                                    /* LD A,(HL+) */
    uint16_t hl = get_hl(c);
    c->a = cpu_read(g, hl);
    set_hl(c, (uint16_t)(hl + 1));
    break;
}
case 0x3A: {                                                    /* LD A,(HL-) */
    uint16_t hl = get_hl(c);
    c->a = cpu_read(g, hl);
    set_hl(c, (uint16_t)(hl - 1));
    break;
}
case 0x03: case 0x13: case 0x23: case 0x33:                     /* INC rr */
    cpu_idle(g);
    set_rr(c, op >> 4, (uint16_t)(get_rr(c, op >> 4) + 1));
    break;
case 0x0B: case 0x1B: case 0x2B: case 0x3B:                     /* DEC rr */
    cpu_idle(g);
    set_rr(c, op >> 4, (uint16_t)(get_rr(c, op >> 4) - 1));
    break;
case 0x04: case 0x0C: case 0x14: case 0x1C:                     /* INC r */
case 0x24: case 0x2C: case 0x34: case 0x3C:
    set_r8(g, op >> 3, inc8(c, get_r8(g, op >> 3)));
    break;
case 0x05: case 0x0D: case 0x15: case 0x1D:                     /* DEC r */
case 0x25: case 0x2D: case 0x35: case 0x3D:
    set_r8(g, op >> 3, dec8(c, get_r8(g, op >> 3)));
    break;
case 0x06: case 0x0E: case 0x16: case 0x1E:                     /* LD r,d8 */
case 0x26: case 0x2E: case 0x36: case 0x3E:
    set_r8(g, op >> 3, fetch8(g));
    break;
case 0x07: c->a = rot(c, 0, c->a); c->f &= FLAG_C; break;       /* RLCA */
case 0x0F: c->a = rot(c, 1, c->a); c->f &= FLAG_C; break;       /* RRCA */
case 0x17: c->a = rot(c, 2, c->a); c->f &= FLAG_C; break;       /* RLA */
case 0x1F: c->a = rot(c, 3, c->a); c->f &= FLAG_C; break;       /* RRA */
case 0x08: {                                                    /* LD (a16),SP */
    uint16_t addr = fetch16(g);
    cpu_write(g, addr, (uint8_t)c->sp);
    cpu_write(g, (uint16_t)(addr + 1), (uint8_t)(c->sp >> 8));
    break;
}
case 0x09: case 0x19: case 0x29: case 0x39:                     /* ADD HL,rr */
    cpu_idle(g);
    add_hl(c, get_rr(c, op >> 4));
    break;
case 0x10:                                                      /* STOP */
    /* DMG: se trata como HALT profundo hasta que se pulse un botón.
     * El doble de velocidad (CGB, KEY1) llega en M8. STOP reinicia DIV. */
    c->pc++;
    timer_write(g, 0xFF04, 0);
    if (!g->joy.buttons)
        c->stopped = true;
    break;
case 0x18: {                                                    /* JR e */
    int8_t e = (int8_t)fetch8(g);
    cpu_idle(g);
    c->pc = (uint16_t)(c->pc + e);
    break;
}
case 0x20: case 0x28: case 0x30: case 0x38: {                   /* JR cc,e */
    int8_t e = (int8_t)fetch8(g);
    if (cond(c, (op >> 3) & 3)) {
        cpu_idle(g);
        c->pc = (uint16_t)(c->pc + e);
    }
    break;
}
case 0x27: daa(c); break;                                       /* DAA */
case 0x2F: c->a = (uint8_t)~c->a; c->f |= FLAG_N | FLAG_H; break; /* CPL */
case 0x37: c->f = (uint8_t)((c->f & FLAG_Z) | FLAG_C); break;   /* SCF */
case 0x3F:                                                      /* CCF */
    c->f = (uint8_t)((c->f & (FLAG_Z | FLAG_C)) ^ FLAG_C);
    break;

/* ---- 0x40–0x7F: LD r,r' (con dos excepciones) ---- */
case 0x40:                                                      /* LD B,B */
    /* Sin efecto en la CPU; las pruebas Mooneye lo usan como "fin". */
    g->dbg.ld_b_b = true;
    g->dbg.regs[0] = c->b; g->dbg.regs[1] = c->c;
    g->dbg.regs[2] = c->d; g->dbg.regs[3] = c->e;
    g->dbg.regs[4] = c->h; g->dbg.regs[5] = c->l;
    break;
case 0x76:                                                      /* HALT */
    if (!c->ime && (g->mem.ie & g->mem.if_ & 0x1F))
        c->halt_bug = true;   /* no se detiene y el siguiente byte se lee 2 veces */
    else
        c->halted = true;
    break;

/* ---- 0xC0–0xFF ---- */
case 0xC0: case 0xC8: case 0xD0: case 0xD8:                     /* RET cc */
    cpu_idle(g);
    if (cond(c, (op >> 3) & 3)) {
        c->pc = pop16(g);
        cpu_idle(g);
    }
    break;
case 0xC1: case 0xD1: case 0xE1:                                /* POP rr */
    set_rr(c, (op >> 4) & 3, pop16(g));
    break;
case 0xF1: {                                                    /* POP AF */
    uint16_t v = pop16(g);
    c->a = (uint8_t)(v >> 8);
    c->f = (uint8_t)(v & 0xF0);
    break;
}
case 0xC2: case 0xCA: case 0xD2: case 0xDA: {                   /* JP cc,a16 */
    uint16_t addr = fetch16(g);
    if (cond(c, (op >> 3) & 3)) {
        cpu_idle(g);
        c->pc = addr;
    }
    break;
}
case 0xC3: {                                                    /* JP a16 */
    uint16_t addr = fetch16(g);
    cpu_idle(g);
    c->pc = addr;
    break;
}
case 0xC4: case 0xCC: case 0xD4: case 0xDC: {                   /* CALL cc,a16 */
    uint16_t addr = fetch16(g);
    if (cond(c, (op >> 3) & 3)) {
        cpu_idle(g);
        push16(g, c->pc);
        c->pc = addr;
    }
    break;
}
case 0xCD: {                                                    /* CALL a16 */
    uint16_t addr = fetch16(g);
    cpu_idle(g);
    push16(g, c->pc);
    c->pc = addr;
    break;
}
case 0xC5: case 0xD5: case 0xE5:                                /* PUSH rr */
    cpu_idle(g);
    push16(g, get_rr(c, (op >> 4) & 3));
    break;
case 0xF5:                                                      /* PUSH AF */
    cpu_idle(g);
    push16(g, (uint16_t)(c->a << 8 | c->f));
    break;
case 0xC6: case 0xCE: case 0xD6: case 0xDE:                     /* ALU A,d8 */
case 0xE6: case 0xEE: case 0xF6: case 0xFE:
    alu(c, (op >> 3) & 7, fetch8(g));
    break;
case 0xC7: case 0xCF: case 0xD7: case 0xDF:                     /* RST n */
case 0xE7: case 0xEF: case 0xF7: case 0xFF:
    cpu_idle(g);
    push16(g, c->pc);
    c->pc = (uint16_t)(op & 0x38);
    break;
case 0xC9:                                                      /* RET */
    c->pc = pop16(g);
    cpu_idle(g);
    break;
case 0xD9:                                                      /* RETI */
    c->pc = pop16(g);
    cpu_idle(g);
    c->ime = true;
    break;
case 0xCB: execute_cb(g); break;
case 0xE0: cpu_write(g, (uint16_t)(0xFF00 | fetch8(g)), c->a); break; /* LDH (a8),A */
case 0xF0: c->a = cpu_read(g, (uint16_t)(0xFF00 | fetch8(g))); break; /* LDH A,(a8) */
case 0xE2: cpu_write(g, (uint16_t)(0xFF00 | c->c), c->a); break;      /* LD (C),A */
case 0xF2: c->a = cpu_read(g, (uint16_t)(0xFF00 | c->c)); break;      /* LD A,(C) */
case 0xE8: {                                                    /* ADD SP,e */
    uint8_t e = fetch8(g);
    cpu_idle(g);
    cpu_idle(g);
    c->sp = sp_plus_e(c, e);
    break;
}
case 0xF8: {                                                    /* LD HL,SP+e */
    uint8_t e = fetch8(g);
    cpu_idle(g);
    set_hl(c, sp_plus_e(c, e));
    break;
}
case 0xE9: c->pc = get_hl(c); break;                            /* JP HL */
case 0xF9: cpu_idle(g); c->sp = get_hl(c); break;               /* LD SP,HL */
case 0xEA: cpu_write(g, fetch16(g), c->a); break;               /* LD (a16),A */
case 0xFA: c->a = cpu_read(g, fetch16(g)); break;               /* LD A,(a16) */
case 0xF3: c->ime = false; c->ei_pending = false; break;        /* DI */
case 0xFB: c->ei_pending = true; break;                         /* EI */
case 0xD3: case 0xDB: case 0xDD: case 0xE3: case 0xE4:          /* ilegales */
case 0xEB: case 0xEC: case 0xED: case 0xF4: case 0xFC: case 0xFD:
    c->locked = true;
    break;

default:
    if (op < 0x80)                                              /* LD r,r' */
        set_r8(g, op >> 3, get_r8(g, op));
    else                                                        /* ALU A,r */
        alu(c, (op >> 3) & 7, get_r8(g, op));
    break;
}

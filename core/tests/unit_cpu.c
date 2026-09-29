/* unit_cpu.c — opcodes ilegales, bug de HALT, EI diferido y estado post-boot. */
#include <stdlib.h>

#include "unit.h"

static gb *run_prog(const uint8_t *prog, size_t len, uint32_t cycles)
{
    uint8_t *rom = ut_make_rom(0x8000, 0x00, 0x00, 0x00, prog, len);
    gb *g = gb_create();
    for (int v = 0x40; rom && v <= 0x60; v += 8) {   /* vectores: JR -2 */
        rom[v] = 0x18;
        rom[v + 1] = 0xFE;
    }
    if (!rom || !g || gb_load_rom(g, rom, 0x8000, NULL) != GB_OK) {
        free(rom);
        gb_destroy(g);
        return NULL;
    }
    free(rom);
    gb_run_cycles(g, cycles);
    return g;
}

void unit_cpu(struct ut *t)
{
    /* Estado post-boot DMG ABC */
    {
        static const uint8_t prog[] = { 0x18, 0xFE };
        gb *g = run_prog(prog, sizeof prog, 0);
        CHECK(t, g != NULL);
        if (g) {
            CHECK(t, g->cpu.a == 0x01 && g->cpu.f == 0xB0);
            CHECK(t, g->cpu.b == 0x00 && g->cpu.c == 0x13);
            CHECK(t, g->cpu.d == 0x00 && g->cpu.e == 0xD8);
            CHECK(t, g->cpu.h == 0x01 && g->cpu.l == 0x4D);
            CHECK(t, g->cpu.sp == 0xFFFE && g->cpu.pc == 0x0100);
            CHECK(t, mmu_read(g, 0xFF40) == 0x91 && mmu_read(g, 0xFF0F) == 0xE1);
            CHECK(t, mmu_read(g, 0xFF47) == 0xFC && mmu_read(g, 0xFF26) == 0xF1);
            gb_destroy(g);
        }
    }
    /* Opcode ilegal: la CPU se bloquea y gb_run_frame sigue avanzando el tiempo. */
    {
        static const uint8_t prog[] = { 0xD3 };
        gb *g = run_prog(prog, sizeof prog, 100);
        CHECK(t, g != NULL);
        if (g) {
            CHECK(t, gb_cpu_locked(g));
            uint64_t before = gb_cycle_count(g);
            gb_run_frame(g);
            CHECK(t, gb_cycle_count(g) > before);
            CHECK(t, g->cpu.pc == 0x0151);
            gb_destroy(g);
        }
    }
    /* Bug de HALT: IME=0 con IRQ pendiente → INC A se ejecuta dos veces. */
    {
        static const uint8_t prog[] = {
            0xF3,             /* DI */
            0x3E, 0x04,       /* LD A,4 */
            0xE0, 0xFF,       /* LDH (IE),A */
            0xE0, 0x0F,       /* LDH (IF),A */
            0xAF,             /* XOR A */
            0x76,             /* HALT */
            0x3C,             /* INC A */
            0x18, 0xFE        /* JR -2 */
        };
        gb *g = run_prog(prog, sizeof prog, 400);
        CHECK(t, g != NULL);
        if (g) {
            CHECK(t, g->cpu.a == 2);
            CHECK(t, !g->cpu.halted);
            gb_destroy(g);
        }
    }
    /* EI surte efecto tras la instrucción siguiente; el despacho limpia IF y salta a 0x50. */
    {
        static const uint8_t prog[] = {
            0x3E, 0x04,       /* LD A,4 */
            0xE0, 0xFF,       /* LDH (IE),A */
            0xE0, 0x0F,       /* LDH (IF),A */
            0xAF,             /* XOR A */
            0xFB,             /* EI */
            0x3C,             /* INC A  (se ejecuta antes de la IRQ) */
            0x3C,             /* INC A  (no se llega a ejecutar) */
            0x18, 0xFE
        };
        gb *g = run_prog(prog, sizeof prog, 400);
        CHECK(t, g != NULL);
        if (g) {
            CHECK(t, g->cpu.a == 1);
            CHECK(t, !g->cpu.ime);
            CHECK(t, !(g->mem.if_ & IRQ_TIMER));
            CHECK(t, g->cpu.pc >= 0x0050 && g->cpu.pc < 0x0058);
            CHECK(t, g->cpu.sp == 0xFFFC);
            CHECK(t, g->mem.hram[0x7D] == 0x01 && g->mem.hram[0x7C] == 0x59); /* retorno 0x0159 */
            gb_destroy(g);
        }
    }
    /* EI; DI: la interrupción no llega a despacharse. */
    {
        static const uint8_t prog[] = {
            0x3E, 0x04, 0xE0, 0xFF, 0xE0, 0x0F, 0xAF,
            0xFB,             /* EI */
            0xF3,             /* DI */
            0x3C,             /* INC A */
            0x18, 0xFE
        };
        gb *g = run_prog(prog, sizeof prog, 400);
        CHECK(t, g != NULL);
        if (g) {
            CHECK(t, g->cpu.a == 1 && !g->cpu.ime);
            CHECK(t, g->mem.if_ & IRQ_TIMER);
            CHECK(t, g->cpu.pc >= 0x0150 && g->cpu.pc < 0x0200);
            gb_destroy(g);
        }
    }
}

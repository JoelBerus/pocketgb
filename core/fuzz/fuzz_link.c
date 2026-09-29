/*
 * fuzz_link.c — libFuzzer: dos instancias con código arbitrario en el cable
 * virtual (M9, core/src/link.c).
 *
 * Entrada: d[0] = opciones (bits 0–1 modelo del lado 0, bits 2–3 del lado 1:
 * DMG / CGB nativo / CGB en compatibilidad; bit 4 save state del lado 1 a mitad;
 * bit 5 recargar el ROM del lado 0; bit 6 desconectar un lado un rato; bit 7
 * ROM MBC5+RAM en vez de ROM-only), d[1] = reparto de los bytes entre los dos
 * programas, d[2] = SB inicial, d[3] = SC inicial (el lado 1 recibe SB = ~d[2]
 * y SC = d[3] ^ 1, así que uno es maestro y el otro esclavo si el bit 0 difiere).
 * El resto son los dos programas, a partir de 0x0150 tras un prólogo que
 * escribe SB y SC.
 * Invariantes: sin cortes de ASan/UBSan, sin colgarse, y mientras no se
 * realinea ningún reloj, |t_a - t_b| ≤ 44 tras cada frame (el lockstep).
 * (Las variables estáticas de este archivo son del fuzzer, no del núcleo.)
 */
#include <stdlib.h>
#include <string.h>

#include "pocketgb.h"

enum { ROM_SIZE = 0x8000, PROG_MAX = 0x3000 };

static gb *make(const uint8_t *prog, size_t len, uint8_t sb, uint8_t sc, unsigned model, bool mbc5)
{
    uint8_t *rom = calloc(1, ROM_SIZE);
    if (!rom)
        return NULL;
    static const uint8_t entry[4] = { 0x00, 0xC3, 0x50, 0x01 };
    memcpy(rom + 0x100, entry, sizeof entry);
    const uint8_t pro[8] = { 0x3E, sb, 0xE0, 0x01, 0x3E, sc, 0xE0, 0x02 };
    memcpy(rom + 0x150, pro, sizeof pro);
    if (len > PROG_MAX)
        len = PROG_MAX;
    memcpy(rom + 0x158, prog, len);
    rom[0x143] = model == 1 ? 0x80 : 0x00;
    rom[0x147] = mbc5 ? 0x1B : 0x00;
    rom[0x149] = mbc5 ? 0x02 : 0x00;
    gb_options o;
    gb_options_default(&o);
    o.model = model == 0 ? GB_MODEL_DMG : GB_MODEL_CGB;
    gb *g = gb_create();
    if (g && gb_load_rom(g, rom, ROM_SIZE, &o) != GB_OK) {
        gb_destroy(g);
        g = NULL;
    }
    free(rom);
    return g;
}

static void step(gb_link *l, gb *a, gb *b, bool check)
{
    gb_link_run_frame(l);
    if (check) {
        uint64_t ta = gb_cycle_count(a), tb = gb_cycle_count(b);
        if ((ta > tb ? ta - tb : tb - ta) > 44)
            abort();
    }
}

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size);

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size)
{
    if (size < 4)
        return 0;
    uint8_t flags = data[0];
    const uint8_t *code = data + 4;
    size_t n = size - 4;
    size_t split = n * data[1] / 256u;
    bool mbc5 = flags & 0x80;
    gb *a = make(code, split, data[2], data[3], (flags & 3u) % 3u, mbc5);
    gb *b = make(code + split, n - split, (uint8_t)~data[2], (uint8_t)(data[3] ^ 1u),
                 ((flags >> 2) & 3u) % 3u, mbc5);
    gb_link *l = gb_link_create();
    if (!a || !b || !l)
        goto out;
    gb_link_attach(l, a, b);
    for (int f = 0; f < 4; f++) {
        gb_set_buttons(a, n ? code[(size_t)f * 7u % n] : 0);
        gb_set_buttons(b, n ? code[(size_t)f * 13u % n] : 0);
        step(l, a, b, true);
    }
    if (flags & 0x10) {
        size_t st = gb_state_size(b);
        uint8_t *buf = malloc(st);
        if (buf && gb_state_save(b, buf, st) == GB_OK) {
            step(l, a, b, false);
            if (gb_state_load(b, buf, st) != GB_OK)
                abort();   /* un estado recién guardado siempre carga */
        }
        free(buf);
    }
    if (flags & 0x20) {
        gb_link_detach(l);       /* siempre antes de gb_destroy */
        gb_destroy(a);
        a = make(code, split, data[2], data[3], (flags & 3u) % 3u, mbc5);
        if (!a)
            goto out;
        gb_link_attach(l, a, b);
    }
    if (flags & 0x40) {
        gb_link_attach(l, NULL, b);
        step(l, a, b, false);
        gb_link_attach(l, a, b);
    }
    /* Tras un save state, una recarga o una reconexión los contadores de las
     * dos instancias ya no parten del mismo instante: solo se comprueba el
     * lockstep si no hubo ninguna de esas operaciones. */
    for (int f = 0; f < 4; f++)
        step(l, a, b, !(flags & 0x70));
    if (!gb_link_framebuffer(l, 0) || !gb_link_framebuffer(l, 1))
        abort();
out:
    gb_link_destroy(l);
    gb_destroy(a);
    gb_destroy(b);
    return 0;
}

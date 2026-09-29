/*
 * unit.h — mini framework de unit tests (sin dependencias). docs/06-testing.md §Unit tests.
 */
#ifndef POCKETGB_UNIT_H
#define POCKETGB_UNIT_H

#include <stdio.h>

#include "internal.h"

struct ut {
    int checks;
    int failed;
};

#define CHECK(t, expr)                                                          \
    do {                                                                        \
        (t)->checks++;                                                          \
        if (!(expr)) {                                                          \
            (t)->failed++;                                                      \
            fprintf(stderr, "  FALLO %s:%d: %s\n", __FILE__, __LINE__, #expr);  \
        }                                                                       \
    } while (0)

/* ROM sintético en memoria (nunca se escribe a disco): cabecera válida,
 * "NOP; JP 0x0150" en 0x100 y `prog` a partir de 0x150. Lo libera el llamador. */
uint8_t *ut_make_rom(size_t size, uint8_t type, uint8_t rom_code, uint8_t ram_code,
                     const uint8_t *prog, size_t prog_len);
void ut_fix_header_checksum(uint8_t *rom);

void unit_apu(struct ut *t);
void unit_cart(struct ut *t);
void unit_cgb(struct ut *t);
void unit_cpu(struct ut *t);
void unit_link(struct ut *t);
void unit_ppu(struct ut *t);
void unit_sha256(struct ut *t);
void unit_state(struct ut *t);
void unit_timer(struct ut *t);

#endif

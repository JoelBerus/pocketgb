/*
 * internal.h — estado interno del núcleo GBA (no es API pública).
 * Referencia de hardware: GBATEK (Martin Korth). Código propio; ver
 * docs/10-gba-spec.md para lo que se emula y lo que no.
 */
#ifndef POCKETGBA_INTERNAL_H
#define POCKETGBA_INTERNAL_H

#include "pocketgba.h"
#include <string.h>

/* ---- Modos y bits del CPSR ---- */
enum {
    ARM_MODE_USR = 0x10, ARM_MODE_FIQ = 0x11, ARM_MODE_IRQ = 0x12,
    ARM_MODE_SVC = 0x13, ARM_MODE_ABT = 0x17, ARM_MODE_UND = 0x1B,
    ARM_MODE_SYS = 0x1F
};
#define ARM_N (1u << 31)
#define ARM_Z (1u << 30)
#define ARM_C (1u << 29)
#define ARM_V (1u << 28)
#define ARM_I (1u << 7)
#define ARM_F (1u << 6)
#define ARM_T (1u << 5)

/* Bancos: 0 usuario/sistema, 1 FIQ, 2 IRQ, 3 SVC, 4 ABT, 5 UND. */
enum { ARM_BANK_USR, ARM_BANK_FIQ, ARM_BANK_IRQ, ARM_BANK_SVC, ARM_BANK_ABT, ARM_BANK_UND, ARM_BANKS };

/* Clases de instrucción (tablas por instancia: sin estado global). */
enum {
    ARM_OP_UNDEF = 0, ARM_OP_DP, ARM_OP_MRS, ARM_OP_MSR, ARM_OP_BX, ARM_OP_MUL,
    ARM_OP_MULL, ARM_OP_SWP, ARM_OP_HALF, ARM_OP_SDT, ARM_OP_LDM, ARM_OP_B,
    ARM_OP_SWI, ARM_OP_COUNT
};
enum {
    THUMB_OP_UNDEF = 0, THUMB_OP_SHIFT, THUMB_OP_ADDSUB, THUMB_OP_IMM, THUMB_OP_ALU,
    THUMB_OP_HIREG, THUMB_OP_LDRPC, THUMB_OP_LDSTREG, THUMB_OP_LDSTSX, THUMB_OP_LDSTIMM,
    THUMB_OP_LDSTH, THUMB_OP_LDSTSP, THUMB_OP_ADDR, THUMB_OP_ADDSP, THUMB_OP_PUSHPOP,
    THUMB_OP_LDMSTM, THUMB_OP_BCC, THUMB_OP_SWI, THUMB_OP_B, THUMB_OP_BL1, THUMB_OP_BL2,
    THUMB_OP_COUNT
};

typedef struct gba_arm {
    uint32_t r[16];
    uint32_t cpsr;
    uint32_t spsr;                 /* SPSR del modo actual (copia de trabajo) */
    uint32_t bank_fiq_r8[5];       /* r8..r12 de FIQ */
    uint32_t bank_usr_r8[5];       /* r8..r12 del resto de modos */
    uint32_t bank_r13[ARM_BANKS];
    uint32_t bank_r14[ARM_BANKS];
    uint32_t bank_spsr[ARM_BANKS];
    uint32_t pipe[2];              /* [0] se ejecuta ahora, [1] ya leída */
    uint8_t bank;                  /* banco cargado en r8..r14/spsr */
    bool flushed;                  /* la instrucción actual cambió el PC */
    bool halted;
} gba_arm;

/* Bus de prueba (solo con GBA_TEST_HOOKS): SingleStepTests ARM7TDMI. */
typedef struct gba_test_txn {
    uint32_t kind, size, addr, data;
} gba_test_txn;

#define GBA_TEST_MAX_TXN 64
typedef struct gba_test_bus {
    bool active;
    uint32_t base_addr, opcode;
    uint32_t ntxn;
    gba_test_txn txn[GBA_TEST_MAX_TXN];
    uint32_t nwrites;
    gba_test_txn writes[GBA_TEST_MAX_TXN];
    bool missing_read;            /* una lectura de datos sin transacción */
    uint32_t nreads;              /* lecturas de datos hechas */
} gba_test_bus;

struct gba {
    gba_arm cpu;
    uint8_t arm_lut[4096];         /* bits 27-20 y 7-4 */
    uint8_t thumb_lut[1024];       /* bits 15-6 */

    /* Memoria (G1: mapa plano; tiempos y E/S completas en G2) */
    uint8_t bios[GBA_BIOS_BYTES];
    uint8_t ewram[256 * 1024];
    uint8_t iwram[32 * 1024];
    uint8_t io[0x400];
    uint8_t pal[1024];
    uint8_t vram[96 * 1024];
    uint8_t oam[1024];
    uint8_t *rom;                  /* malloc en gba_load_rom */
    uint32_t rom_size;             /* tamaño real del archivo */
    uint32_t rom_mask;             /* potencia de 2 - 1 que cubre rom_size */
    uint8_t sram[64 * 1024];       /* G4: SRAM/Flash/EEPROM */
    bool bios_loaded;
    uint32_t bios_last;            /* última instrucción leída desde la BIOS */

    uint64_t cycles;               /* ciclos desde gba_load_rom */
    uint32_t line_cycles;          /* posición dentro de la línea (G1: solo VCOUNT/DISPSTAT) */
    uint16_t vcount;
    uint16_t keys;                 /* máscara GBA_BTN_* pulsados */
    bool frame_done;

    gba_options opts;
    gba_save_type save_type;
    uint8_t fingerprint[32];
    uint32_t framebuffer[GBA_SCREEN_W * GBA_SCREEN_H];

#ifdef GBA_TEST_HOOKS
    gba_test_bus test;
#endif
};

/* arm7.c */
void gba_arm_init_tables(gba *g);
void gba_arm_reset(gba *g, bool skip_bios);
void gba_arm_step(gba *g);
void gba_arm_set_cpsr(gba *g, uint32_t value);   /* cambia de banco si cambia el modo */
void gba_arm_flush(gba *g);                      /* rellena el pipeline desde r15 */
void gba_arm_irq(gba *g);

/* arm_mulcarry.c (zlib, zaydlang): bandera C de las multiplicaciones. */
enum { GBA_MUL_SHORT, GBA_MUL_LONG_SIGNED, GBA_MUL_LONG_UNSIGNED };
bool gba_arm_mul_carry(int flavor, uint32_t rm, uint32_t rs, uint64_t acc);

/* bus.c */
uint32_t gba_bus_read32(gba *g, uint32_t addr);
uint16_t gba_bus_read16(gba *g, uint32_t addr);
uint8_t gba_bus_read8(gba *g, uint32_t addr);
void gba_bus_write32(gba *g, uint32_t addr, uint32_t v);
void gba_bus_write16(gba *g, uint32_t addr, uint16_t v);
void gba_bus_write8(gba *g, uint32_t addr, uint8_t v);
uint32_t gba_bus_fetch32(gba *g, uint32_t addr);
uint16_t gba_bus_fetch16(gba *g, uint32_t addr);
void gba_bus_idle(gba *g, uint32_t n);
void gba_video_tick(gba *g, uint32_t n);

/* SHA-256 compartido con el núcleo GB (core/src/sha256.c, propio). */
void sha256(const uint8_t *data, size_t len, uint8_t out[32]);

static inline uint32_t gba_ror32(uint32_t v, unsigned s)
{
    s &= 31u;
    return s ? (v >> s) | (v << (32u - s)) : v;
}

#endif
